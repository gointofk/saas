package com.dailyactivity.common;

import com.dailyactivity.auth.api.AuthFilter;
import com.dailyactivity.auth.domain.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Logs every backend HTTP exchange while redacting credentials/PII and limiting request-body capture.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpTrafficLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(HttpTrafficLoggingFilter.class);
    public static final String REQUEST_ID_MDC_KEY = "requestId";
    private static final int MAX_REQUEST_BODY_BYTES = 64 * 1024;
    private static final int MAX_LOG_BODY_BYTES = 1024;
    private static final Set<String> SENSITIVE_PARTS = Set.of("password", "secret", "token", "authorization", "cookie", "signature", "sign", "key", "url", "email", "phone", "address", "recipient", "name", "note", "remark", "transaction", "account", "verificationcode", "registrationcode");
    private static final Set<String> SENSITIVE_EXACT = Set.of("q", "purpose");
    private final ObjectMapper objectMapper;

    public HttpTrafficLoggingFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        ContentCachingRequestWrapper cachedRequest = new ContentCachingRequestWrapper(request, MAX_REQUEST_BODY_BYTES);
        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(cachedRequest, cachedResponse);
        } catch (IOException | ServletException | RuntimeException exception) {
            log.error("Unhandled exception escaped servlet filter chain method={} path={}", request.getMethod(), request.getRequestURI(), exception);
            throw exception;
        } finally {
            try {
                captureUnreadJsonBody(cachedRequest);
                logRequest(cachedRequest);
                logResponse(cachedRequest, cachedResponse, startedAt);
            } finally {
                cachedResponse.copyBodyToResponse();
            }
        }
    }

    private void captureUnreadJsonBody(ContentCachingRequestWrapper request) throws IOException {
        if (isJson(request.getContentType()) && request.getContentAsByteArray().length == 0) {
            byte[] buffer = new byte[4096];
            while (request.getInputStream().read(buffer) != -1) {
                // ContentCachingRequestWrapper retains at most MAX_REQUEST_BODY_BYTES.
            }
        }
    }

    private void logRequest(ContentCachingRequestWrapper request) {
        byte[] body = request.getContentAsByteArray();
        log.debug("【Req】method: {} user: {} path: {} parameters: {} body: {}", request.getMethod(), userId(request), request.getRequestURI(), queryParameters(request), bodyForLog(body, request.getContentType()));
    }

    private void logResponse(HttpServletRequest request, ContentCachingResponseWrapper response, long startedAt) {
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.debug("【Rsp】method: {} user: {} path: {} status: {} durationMs: {} body: {}", request.getMethod(), userId(request), request.getRequestURI(), response.getStatus(), durationMs, bodyForLog(response.getContentAsByteArray(), response.getContentType()));
    }

    private String userId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
        return value instanceof Principal principal ? Long.toString(principal.userId()) : "-";
    }

    String bodyForLog(byte[] body, String contentType) {
        if (body.length == 0) return "<empty>";
        if (!isJson(contentType)) return "<not-json>";
        try {
            JsonNode parsed = objectMapper.readTree(body);
            String value = objectMapper.writeValueAsString(redact(parsed));
            return value.length() > MAX_LOG_BODY_BYTES ? value.substring(0, MAX_LOG_BODY_BYTES) : value;
        } catch (Exception ignored) {
            return "<unavailable>";
        }
    }

    private JsonNode redact(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.set(field.getKey(), isSensitive(field.getKey()) ? objectMapper.getNodeFactory().textNode("***") : redact(field.getValue()));
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(redact(item)));
            return result;
        }
        return value;
    }

    private String queryParameters(HttpServletRequest request) {
        if (request.getQueryString() == null || request.getQueryString().isBlank()) return "{}";
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (String part : request.getQueryString().split("&")) {
            String[] pair = part.split("=", 2);
            String name = decode(pair[0]);
            String value = pair.length == 2 ? decode(pair[1]) : "";
            values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(isSensitive(name) ? "***" : value);
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ignored) {
            return "<unavailable>";
        }
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return value;
        }
    }

    private boolean isJson(String contentType) {
        if (contentType == null) return false;
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.isCompatibleWith(mediaType) || mediaType.getSubtype().toLowerCase(Locale.ROOT).endsWith("+json");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isSensitive(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return SENSITIVE_EXACT.contains(normalized) || SENSITIVE_PARTS.stream().anyMatch(normalized::contains);
    }
}

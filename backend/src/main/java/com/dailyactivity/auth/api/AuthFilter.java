package com.dailyactivity.auth.api;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.auth.infrastructure.JwtTokenService;
import com.dailyactivity.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
public class AuthFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE = AuthFilter.class.getName() + ".principal";
    private final JwtTokenService tokens;
    private final AccountService accounts;
    private final com.dailyactivity.merchant.application.StorefrontService storefront;
    private final ObjectMapper objectMapper;

    public AuthFilter(JwtTokenService tokens, AccountService accounts, ObjectMapper objectMapper, com.dailyactivity.merchant.application.StorefrontService storefront) { this.storefront=storefront; this.tokens = tokens; this.accounts = accounts; this.objectMapper = objectMapper; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/api/v1/customer/storefront") || "OPTIONS".equalsIgnoreCase(request.getMethod()) || path.matches("/api/v1/(customer|admin|platform)/auth/login") || path.matches("/api/v1/files/[^/]+/content") || path.equals("/actuator/health");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) throw ApiException.unauthorized("Authentication is required.");
            Principal principal = tokens.parse(header.substring(7));
            accounts.assertValid(principal);
            String type = requestType(request);
            if (type != null && !type.equals(principal.userType())) throw ApiException.forbidden("Account type does not match this API.");
            if (principal.customer() && storefront.resolve(request).id() != principal.merchantId()) throw ApiException.forbidden("Account does not belong to this storefront.");
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            chain.doFilter(request, response);
        } catch (ApiException exception) {
            response.setStatus(exception.status().value()); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), Map.of("code", exception.code(), "message", exception.getMessage()));
        }
    }
    private String requestType(HttpServletRequest request) {
        try {
            // Match MVC's decoded segments and matrix-parameter handling; never decode slashes into extra segments.
            var segments = RequestPath.parse(request.getRequestURI(), request.getContextPath())
                    .pathWithinApplication().elements().stream()
                    .filter(PathContainer.PathSegment.class::isInstance)
                    .map(element -> ((PathContainer.PathSegment) element).valueToMatch())
                    .toList();
            if (segments.size() < 3 || !"api".equals(segments.get(0)) || !"v1".equals(segments.get(1))) return null;
            return switch (segments.get(2)) {
                case "platform" -> "platform";
                case "admin" -> "seller";
                case "customer" -> "customer";
                default -> null;
            };
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("INVALID_REQUEST_PATH", "Request path is invalid.");
        }
    }

}

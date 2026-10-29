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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
public class AuthFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE = AuthFilter.class.getName() + ".principal";
    private final JwtTokenService tokens;
    private final AccountService accounts;
    private final ObjectMapper objectMapper;

    public AuthFilter(JwtTokenService tokens, AccountService accounts, ObjectMapper objectMapper) { this.tokens = tokens; this.accounts = accounts; this.objectMapper = objectMapper; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod()) || path.matches("/api/v1/(customer|admin)/auth/login") || path.matches("/api/v1/files/[^/]+/content") || path.equals("/actuator/health");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) throw ApiException.unauthorized("Authentication is required.");
            Principal principal = tokens.parse(header.substring(7));
            accounts.assertValid(principal);
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            chain.doFilter(request, response);
        } catch (ApiException exception) {
            response.setStatus(exception.status().value()); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), Map.of("code", exception.code(), "message", exception.getMessage()));
        }
    }
}

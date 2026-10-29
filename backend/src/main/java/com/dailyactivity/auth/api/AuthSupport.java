package com.dailyactivity.auth.api;

import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;

public final class AuthSupport {
    private AuthSupport() {
    }

    public static Principal principal(HttpServletRequest request, String expectedType) {
        Principal principal = principal(request);
        if (expectedType != null && !expectedType.equals(principal.userType()))
            throw ApiException.forbidden("This account cannot access this area.");
        return principal;
    }

    public static Principal principal(HttpServletRequest request) {
        Principal principal = (Principal) request.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
        if (principal == null) throw ApiException.unauthorized("Authentication is required.");
        return principal;
    }
}

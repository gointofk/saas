package com.dailyactivity.auth.api;

import com.dailyactivity.auth.application.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Performs static menu authorization at the MVC boundary.
 *
 * <p>Application services still protect sensitive writes and dynamic-purpose
 * operations because those checks must also hold for non-HTTP callers and
 * after the request enters a transaction.</p>
 */
@Component
public class MenuAuthorizationInterceptor implements HandlerInterceptor {
    private final AccountService accounts;

    public MenuAuthorizationInterceptor(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) return true;

        RequireMenu requirement = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequireMenu.class);
        if (requirement == null) {
            requirement = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequireMenu.class);
        }
        if (requirement == null) return true;

        accounts.requireMenu(AuthSupport.principal(request, "seller"), requirement.value());
        return true;
    }
}

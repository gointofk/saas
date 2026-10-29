package com.dailyactivity.auth;

import com.dailyactivity.auth.api.AuthFilter;
import com.dailyactivity.auth.api.MenuAuthorizationInterceptor;
import com.dailyactivity.auth.api.RequireMenu;
import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MenuAuthorizationInterceptorTest {
    private final List<String> requiredMenus = new ArrayList<>();
    private final AccountService accounts = new AccountService(null, null, null, null) {
        @Override
        public void requireMenu(Principal principal, String menu) {
            requiredMenus.add(principal.userId() + ":" + principal.userType() + ":" + principal.authVersion() + ":" + menu);
        }
    };
    private final MenuAuthorizationInterceptor interceptor = new MenuAuthorizationInterceptor(accounts);

    @Test
    void methodAnnotationRequiresDeclaredMenu() throws Exception {
        MockHttpServletRequest request = authenticatedSellerRequest();
        HandlerMethod handler = handler("products");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler)).isTrue();

        assertThat(requiredMenus).containsExactly("7:seller:3:products");
    }

    @Test
    void classAnnotationIsSupported() throws Exception {
        MockHttpServletRequest request = authenticatedSellerRequest();
        HandlerMethod handler = new HandlerMethod(new MemberHandler(), MemberHandler.class.getMethod("page"));

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler)).isTrue();

        assertThat(requiredMenus).containsExactly("7:seller:3:members");
    }

    @Test
    void unannotatedHandlerDoesNotPerformMenuLookup() throws Exception {
        MockHttpServletRequest request = authenticatedSellerRequest();

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler("plain"))).isTrue();

        assertThat(requiredMenus).isEmpty();
    }

    private MockHttpServletRequest authenticatedSellerRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE, new Principal(7L, "seller", 3L, 1L));
        return request;
    }

    private HandlerMethod handler(String methodName) throws Exception {
        Method method = TestHandler.class.getMethod(methodName);
        return new HandlerMethod(new TestHandler(), method);
    }

    static class TestHandler {
        @RequireMenu("products")
        public void products() {
        }

        public void plain() {
        }
    }

    @RequireMenu("members")
    static class MemberHandler {
        public void page() {
        }
    }
}

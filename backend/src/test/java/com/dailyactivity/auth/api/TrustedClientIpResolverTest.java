package com.dailyactivity.auth.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedClientIpResolverTest {
    private final TrustedClientIpResolver resolver = new TrustedClientIpResolver();

    @Test
    void usesForwardedAddressFromLoopbackNginx() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.8");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.8");
    }

    @Test
    void ignoresForwardedAddressFromUntrustedPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.7");
        request.addHeader("X-Forwarded-For", "203.0.113.8");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.7");
    }
}

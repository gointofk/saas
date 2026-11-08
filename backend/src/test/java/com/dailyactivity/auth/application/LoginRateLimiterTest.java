package com.dailyactivity.auth.application;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.config.AuthProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {
    @Test
    void blocksAccountAfterConfiguredFailuresAndReturnsRetryAfter() {
        AuthProperties properties = new AuthProperties(
                "Xu", "customer", "admin", 30, 60,
                "01234567890123456789012345678901", 300, 2);
        LoginRateLimiter limiter = new LoginRateLimiter(
                properties,
                Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC));

        limiter.recordFailure("customer", "user@example.com", "192.0.2.10");

        assertThatThrownBy(() -> limiter.recordFailure("customer", "user@example.com", "192.0.2.10"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(429);
                    assertThat(exception.code()).isEqualTo("TOO_MANY_REQUESTS");
                    assertThat(exception.retryAfterSeconds()).isPositive();
                });
        assertThatThrownBy(() -> limiter.check("customer", "user@example.com", "192.0.2.10"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void boundsTrackedWindowsAndEvictsExpiredEntriesBeforeAdmittingNewOnes() {
        AuthProperties properties = new AuthProperties(
                "Xu", "customer", "admin", 30, 60,
                "01234567890123456789012345678901", 60, 10);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-22T00:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(properties, clock, 2);

        limiter.recordFailure("customer", "first@example.com", "192.0.2.1");
        limiter.recordFailure("customer", "second@example.com", "192.0.2.2");

        assertThat(limiter.trackedWindowCount()).isEqualTo(2);

        clock.set(Instant.parse("2026-09-22T00:01:01Z"));
        limiter.recordFailure("customer", "third@example.com", "192.0.2.3");

        assertThat(limiter.trackedWindowCount()).isEqualTo(2);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            this.instant = new AtomicReference<>(initial);
        }

        void set(Instant value) {
            instant.set(value);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}

package com.dailyactivity.auth.application;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.config.AuthProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small single-instance login failure limiter. Nginx should still provide the
 * edge/IP limit; this component protects account identifiers inside the app.
 */
@Component
public class LoginRateLimiter {
    private static final int DEFAULT_MAX_TRACKED_WINDOWS = 10_000;
    private final AuthProperties properties;
    private final Clock clock;
    private final int maxTrackedWindows;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public LoginRateLimiter(AuthProperties properties) {
        this(properties, Clock.systemUTC(), DEFAULT_MAX_TRACKED_WINDOWS);
    }

    LoginRateLimiter(AuthProperties properties, Clock clock) {
        this(properties, clock, DEFAULT_MAX_TRACKED_WINDOWS);
    }

    LoginRateLimiter(AuthProperties properties, Clock clock, int maxTrackedWindows) {
        this.properties = properties;
        this.clock = clock;
        this.maxTrackedWindows = maxTrackedWindows;
    }

    public void check(String area, String identifier, String clientIp) {
        checkKey(key(area, "account", identifier));
        if (clientIp != null && !clientIp.isBlank()) checkKey(key(area, "ip", clientIp));
    }

    public void recordFailure(String area, String identifier, String clientIp) {
        int accountAttempts = increment(key(area, "account", identifier));
        int ipAttempts = clientIp == null || clientIp.isBlank() ? 0 : increment(key(area, "ip", clientIp));
        if (accountAttempts >= properties.loginMaxAttempts() || ipAttempts >= properties.loginMaxAttempts()) {
            long accountRetry = accountAttempts >= properties.loginMaxAttempts() ? retryAfter(key(area, "account", identifier)) : 0;
            long ipRetry = ipAttempts >= properties.loginMaxAttempts() ? retryAfter(key(area, "ip", clientIp)) : 0;
            throw ApiException.tooManyRequests((int) Math.max(accountRetry, ipRetry));
        }
    }

    public void recordSuccess(String area, String identifier) {
        windows.remove(key(area, "account", identifier));
    }

    private void checkKey(String key) {
        Window window = windows.get(key);
        if (window == null) return;
        long now = clock.millis();
        if (window.expiresAtMillis() <= now) {
            windows.remove(key, window);
            return;
        }
        if (window.attempts() >= properties.loginMaxAttempts())
            throw ApiException.tooManyRequests((int) Math.max(1, (window.expiresAtMillis() - now + 999) / 1000));
    }

    private synchronized int increment(String key) {
        long now = clock.millis();
        Window current = windows.get(key);
        if (current != null && current.expiresAtMillis() > now) {
            Window updated = new Window(current.attempts() + 1, current.expiresAtMillis());
            windows.put(key, updated);
            return updated.attempts();
        }
        if (current != null) windows.remove(key, current);
        if (windows.size() >= maxTrackedWindows) cleanupExpired(now);
        if (windows.size() >= maxTrackedWindows) return 0;
        windows.put(key, new Window(1, now + properties.loginWindowSeconds() * 1000L));
        return 1;
    }

    private void cleanupExpired(long now) {
        windows.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void removeExpiredWindows() {
        cleanupExpired(clock.millis());
    }

    int trackedWindowCount() {
        return windows.size();
    }

    private long retryAfter(String key) {
        Window window = windows.get(key);
        if (window == null) return properties.loginWindowSeconds();
        return Math.max(1, (window.expiresAtMillis() - clock.millis() + 999) / 1000);
    }

    private String key(String area, String kind, String value) {
        String normalized = value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase();
        return area + ':' + kind + ':' + normalized;
    }

    private record Window(int attempts, long expiresAtMillis) {
    }
}

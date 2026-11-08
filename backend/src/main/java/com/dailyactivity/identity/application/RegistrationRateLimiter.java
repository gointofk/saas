package com.dailyactivity.identity.application;

import com.dailyactivity.common.ApiException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RegistrationRateLimiter {
    private static final int MAX_WINDOWS = 20_000;
    private final Clock clock = Clock.systemUTC();
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public synchronized void checkSend(long merchantId, String email, String ip) {
        record("send:email:" + merchantId + ':' + email, 5, 3_600);
        record("send:ip:" + safeIp(ip), 20, 3_600);
    }

    public synchronized void checkVerification(long merchantId, String email, String ip) {
        record("verify:email:" + merchantId + ':' + email, 10, 900);
        record("verify:ip:" + safeIp(ip), 30, 900);
    }

    private void record(String key, int maxAttempts, long windowSeconds) {
        long now = clock.millis();
        Window current = windows.get(key);
        if (current == null || current.expiresAtMillis() <= now) {
            if (windows.size() >= MAX_WINDOWS) windows.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
            if (windows.size() >= MAX_WINDOWS)
                throw ApiException.tooManyRequests(60, "Too many registration attempts. Please try again later.");
            windows.put(key, new Window(1, now + windowSeconds * 1_000));
            return;
        }
        if (current.attempts() >= maxAttempts)
            throw ApiException.tooManyRequests((int) Math.max(1, (current.expiresAtMillis() - now + 999) / 1_000),
                    "Too many registration attempts. Please try again later.");
        windows.put(key, new Window(current.attempts() + 1, current.expiresAtMillis()));
    }

    private String safeIp(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip.trim();
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void removeExpired() {
        long now = clock.millis();
        windows.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
    }

    private record Window(int attempts, long expiresAtMillis) {
    }
}

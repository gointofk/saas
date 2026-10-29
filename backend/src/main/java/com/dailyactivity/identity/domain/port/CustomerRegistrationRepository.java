package com.dailyactivity.identity.domain.port;

import java.time.Instant;
import java.util.Optional;

public interface CustomerRegistrationRepository {
    boolean issue(long merchantId, String email, String codeHash, Instant now, Instant expiresAt, Instant resendAfter);

    Optional<Challenge> lock(long merchantId, String email);

    void recordFailedAttempt(long merchantId, String email, int maxAttempts);

    void consume(long merchantId, String email);

    void revoke(long merchantId, String email, String codeHash);

    int deleteExpired(Instant before, int limit);

    record Challenge(String codeHash, Instant expiresAt, Instant resendAfter, int attempts, Instant consumedAt) {
    }
}

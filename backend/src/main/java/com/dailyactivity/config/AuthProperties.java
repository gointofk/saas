package com.dailyactivity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(String issuer, String audienceCustomer, String audienceAdmin, long tokenDays,
                             long clockSkewSeconds, String secret, long loginWindowSeconds,
                             int loginMaxAttempts) {
    public AuthProperties {
        if (tokenDays <= 0 || clockSkewSeconds < 0 || secret == null || secret.isBlank()
                || secret.getBytes(StandardCharsets.UTF_8).length < 32
                || loginWindowSeconds < 60 || loginMaxAttempts < 1)
            throw new IllegalArgumentException("Invalid auth configuration: app.auth.secret must be at least 32 bytes.");
    }
}

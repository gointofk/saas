package com.dailyactivity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.quote")
public record QuoteProperties(String secret, long ttlSeconds) {
    public QuoteProperties {
        if (secret == null || secret.isBlank())
            throw new IllegalArgumentException("app.quote.secret must be configured.");
        if (ttlSeconds < 60) throw new IllegalArgumentException("app.quote.ttl-seconds must be at least 60.");
    }
}

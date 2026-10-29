package com.dailyactivity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.registration")
public record RegistrationProperties(String secret, long codeTtlSeconds, long resendDelaySeconds,
                                     int maxCodeAttempts) {
    public RegistrationProperties {
        if (codeTtlSeconds < 60 || resendDelaySeconds < 1 || maxCodeAttempts < 1)
            throw new IllegalArgumentException("Invalid app.registration settings.");
    }
}

package com.dailyactivity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.file")
public record FileProperties(String root, long signedUrlSeconds, String signingSecret) {
    public FileProperties {
        if (root == null || root.isBlank() || signedUrlSeconds < 60 || signingSecret == null || signingSecret.isBlank())
            throw new IllegalArgumentException("app.file root, signed-url-seconds and signing-secret must be configured.");
    }
}

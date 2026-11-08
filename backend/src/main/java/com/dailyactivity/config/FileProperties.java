package com.dailyactivity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties(prefix = "app.file")
public record FileProperties(String root, long signedUrlSeconds, String signingSecret, String publicCoaBaseUrl) {
    public FileProperties {
        if (root == null || root.isBlank() || signedUrlSeconds < 60 || signingSecret == null || signingSecret.isBlank())
            throw new IllegalArgumentException("app.file root, signed-url-seconds and signing-secret must be configured.");
        publicCoaBaseUrl = publicCoaBaseUrl == null ? "" : publicCoaBaseUrl.trim().replaceAll("/+$", "");
        if (!publicCoaBaseUrl.isEmpty()) {
            URI uri = URI.create(publicCoaBaseUrl);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
                throw new IllegalArgumentException("app.file.public-coa-base-url must be an absolute HTTP(S) URL without query or fragment.");
        }
    }
}

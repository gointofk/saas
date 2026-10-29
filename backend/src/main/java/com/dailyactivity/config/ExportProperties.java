package com.dailyactivity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.export")
public record ExportProperties(int maxRows) {
    public ExportProperties {
        if (maxRows < 1 || maxRows > 10_000)
            throw new IllegalArgumentException("app.export.max-rows must be between 1 and 10000.");
    }
}

package com.dailyactivity.merchant.domain;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public record FreeShippingThresholds(BigDecimal v1, BigDecimal v2, BigDecimal v3,
                                     BigDecimal v4, BigDecimal v5, BigDecimal v6) {
    public BigDecimal forTier(String tier) {
        return switch (tier) {
            case "V1" -> v1;
            case "V2" -> v2;
            case "V3" -> v3;
            case "V4" -> v4;
            case "V5" -> v5;
            case "V6" -> v6;
            default -> throw new IllegalArgumentException("Unsupported customer price tier.");
        };
    }

    public List<BigDecimal> values() {
        return Arrays.asList(v1, v2, v3, v4, v5, v6);
    }

    public static FreeShippingThresholds disabled() {
        return new FreeShippingThresholds(null, null, null, null, null, null);
    }
}

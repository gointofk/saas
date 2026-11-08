package com.dailyactivity.merchant.domain;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public record ShippingFees(BigDecimal v1, BigDecimal v2, BigDecimal v3,
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

    public ShippingQuote quoteForTier(String tier, FreeShippingThresholds thresholds, BigDecimal subtotal) {
        return ShippingQuote.calculate(forTier(tier), thresholds.forTier(tier), subtotal);
    }

    public static ShippingFees zero() {
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        return new ShippingFees(zero, zero, zero, zero, zero, zero);
    }

    public static ShippingFees uniform(BigDecimal fee) {
        return new ShippingFees(fee, fee, fee, fee, fee, fee);
    }
}

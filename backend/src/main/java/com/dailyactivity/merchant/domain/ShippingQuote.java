package com.dailyactivity.merchant.domain;

import java.math.BigDecimal;

public record ShippingQuote(BigDecimal shippingFee, BigDecimal freeShippingThreshold,
                            boolean freeShippingApplied, BigDecimal amountToFreeShipping) {
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    public static ShippingQuote calculate(BigDecimal baseShippingFee, BigDecimal threshold, BigDecimal subtotal) {
        if (baseShippingFee == null || baseShippingFee.signum() < 0 || subtotal == null || subtotal.signum() < 0)
            throw new IllegalArgumentException("Shipping fee and subtotal must be non-negative amounts.");
        if (threshold != null && threshold.signum() <= 0)
            throw new IllegalArgumentException("Free shipping threshold must be greater than zero.");

        BigDecimal applicableThreshold = baseShippingFee.signum() > 0 ? threshold : null;
        boolean applied = applicableThreshold != null && subtotal.compareTo(applicableThreshold) >= 0;
        BigDecimal remaining = applicableThreshold == null ? null : applied ? ZERO : applicableThreshold.subtract(subtotal);
        return new ShippingQuote(applied ? ZERO : baseShippingFee, applicableThreshold, applied, remaining);
    }
}

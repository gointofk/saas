package com.dailyactivity.merchant;

import com.dailyactivity.merchant.domain.FreeShippingThresholds;
import com.dailyactivity.merchant.domain.ShippingFees;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShippingFeesTest {
    private final ShippingFees fees = new ShippingFees(
            new BigDecimal("10.00"), new BigDecimal("5.00"), new BigDecimal("4.00"),
            new BigDecimal("3.00"), new BigDecimal("2.00"), new BigDecimal("1.00"));

    @Test
    void shouldChargeTierFeeWhenThresholdIsDisabled() {
        var quote = fees.quoteForTier("V1", FreeShippingThresholds.disabled(), new BigDecimal("200.00"));

        assertThat(quote.shippingFee()).isEqualByComparingTo("10.00");
        assertThat(quote.freeShippingThreshold()).isNull();
        assertThat(quote.freeShippingApplied()).isFalse();
        assertThat(quote.amountToFreeShipping()).isNull();
    }

    @Test
    void shouldKeepShippingBelowThresholdAndExposeRemainingAmount() {
        var thresholds = new FreeShippingThresholds(new BigDecimal("100.00"), null, null, null, null, null);

        var quote = fees.quoteForTier("V1", thresholds, new BigDecimal("99.99"));

        assertThat(quote.shippingFee()).isEqualByComparingTo("10.00");
        assertThat(quote.freeShippingApplied()).isFalse();
        assertThat(quote.amountToFreeShipping()).isEqualByComparingTo("0.01");
    }

    @Test
    void shouldWaiveShippingAtInclusiveThresholdForTheMatchingTier() {
        var thresholds = new FreeShippingThresholds(new BigDecimal("100.00"), new BigDecimal("200.00"), null, null, null, null);

        var v1 = fees.quoteForTier("V1", thresholds, new BigDecimal("100.00"));
        var v2 = fees.quoteForTier("V2", thresholds, new BigDecimal("100.00"));

        assertThat(v1.shippingFee()).isEqualByComparingTo("0.00");
        assertThat(v1.freeShippingApplied()).isTrue();
        assertThat(v1.amountToFreeShipping()).isEqualByComparingTo("0.00");
        assertThat(v2.shippingFee()).isEqualByComparingTo("5.00");
        assertThat(v2.amountToFreeShipping()).isEqualByComparingTo("100.00");
    }

    @Test
    void shouldRejectNonPositiveThreshold() {
        assertThatThrownBy(() -> fees.quoteForTier("V1",
                new FreeShippingThresholds(BigDecimal.ZERO, null, null, null, null, null), BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    void shouldNotAdvertiseThresholdForTierWithNoShippingCharge() {
        var zeroFee = ShippingFees.uniform(new BigDecimal("0.00"));
        var thresholds = new FreeShippingThresholds(new BigDecimal("100.00"), null, null, null, null, null);

        var quote = zeroFee.quoteForTier("V1", thresholds, new BigDecimal("10.00"));

        assertThat(quote.shippingFee()).isEqualByComparingTo("0.00");
        assertThat(quote.freeShippingThreshold()).isNull();
        assertThat(quote.amountToFreeShipping()).isNull();
        assertThat(quote.freeShippingApplied()).isFalse();
    }
}

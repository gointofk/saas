package com.dailyactivity.catalog;

import com.dailyactivity.catalog.domain.QuantityPricing;
import com.dailyactivity.catalog.domain.QuantityPricing.Band;
import com.dailyactivity.catalog.domain.QuantityPricing.TierPrices;
import com.dailyactivity.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class QuantityPricingTest {
    private final List<Band> bands = List.of(band(1, 4, "95.00"), band(5, 29, "85.00"), band(30, null, "80.00"));

    @ParameterizedTest
    @CsvSource({"1,95", "4,95", "5,85", "29,85", "30,80", "2147483647,80"})
    void quantityBoundariesSelectTheWholeLineUnitPrice(int quantity, String price) {
        assertThat(QuantityPricing.price(bands, quantity, BigDecimal.ONE)).isEqualByComparingTo(price);
    }

    @Test
    void levelsHaveIndependentRangesAndLegacyProductsKeepTheirFixedPrice() {
        QuantityPricing.validate(List.of(new TierPrices("V1", List.of(band(1, null, "95.00"))), new TierPrices("V2", bands)));
        assertThat(QuantityPricing.price(List.of(), 30, new BigDecimal("90.00"))).isEqualByComparingTo("90.00");
        QuantityPricing.validate(null);
        QuantityPricing.validate(List.of());
    }

    @Test
    void invalidRangesAndPricesAreRejected() {
        for (var invalid : List.of(
                List.of(band(2, null, "95")),
                List.of(band(1, 4, "95"), band(6, null, "85")),
                List.of(band(1, 5, "95"), band(5, null, "85")),
                List.of(band(1, 4, "95")),
                List.of(band(1, null, "0")),
                List.of(band(1, null, "0.001")),
                List.of(band(1, null, "10000000000000000")))) {
            assertThatThrownBy(() -> QuantityPricing.validate(List.of(new TierPrices("V1", invalid))))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_QUANTITY_PRICES");
        }
        assertThatThrownBy(() -> QuantityPricing.validate(List.of(new TierPrices(null, bands)))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> QuantityPricing.validate(List.of(new TierPrices("V1", bands), new TierPrices("V1", bands)))).isInstanceOf(ApiException.class);
    }

    private static Band band(int min, Integer max, String price) { return new Band(min, max, new BigDecimal(price)); }
}

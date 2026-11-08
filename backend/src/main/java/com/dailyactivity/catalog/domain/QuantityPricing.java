package com.dailyactivity.catalog.domain;

import com.dailyactivity.common.ApiException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

/** A customer's applicable schedule contains no customer level information. */
public final class QuantityPricing {
    private QuantityPricing() {}

    public record Band(int minQuantity, Integer maxQuantity, BigDecimal unitPrice) {}
    public record TierPrices(String tier, List<Band> bands) {}

    /** Stored schedules contain only lower thresholds; API bands keep inclusive upper bounds. */
    public static List<Band> fromThresholds(List<Band> thresholds) {
        var sorted = thresholds.stream().sorted(java.util.Comparator.comparingInt(Band::minQuantity)).toList();
        var bands = new java.util.ArrayList<Band>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Band current = sorted.get(i);
            if ((i == 0 && current.minQuantity() != 1)
                    || (i > 0 && current.minQuantity() <= sorted.get(i - 1).minQuantity()))
                throw ApiException.conflict("QUANTITY_PRICE_UNAVAILABLE", "Stored quantity thresholds are invalid.");
            Integer max = i + 1 < sorted.size() ? sorted.get(i + 1).minQuantity() - 1 : null;
            bands.add(new Band(current.minQuantity(), max, current.unitPrice()));
        }
        return List.copyOf(bands);
    }

    public static BigDecimal price(List<Band> bands, int quantity, BigDecimal fallback) {
        if (bands.isEmpty()) return fallback;
        return bands.stream().filter(b -> quantity >= b.minQuantity()
                        && (b.maxQuantity() == null || quantity <= b.maxQuantity()))
                .findFirst().orElseThrow(() -> ApiException.conflict("QUANTITY_PRICE_UNAVAILABLE", "No price is available for this quantity.")).unitPrice();
    }

    public static void validate(List<TierPrices> tiers) {
        if (tiers == null) return; // Old clients omit the new configuration.
        var seen = new HashSet<String>();
        for (var tier : tiers) {
            if (tier == null || tier.tier() == null || !List.of("V1", "V2", "V3", "V4", "V5", "V6").contains(tier.tier())
                    || !seen.add(tier.tier()) || tier.bands() == null || tier.bands().isEmpty() || tier.bands().size() > 100)
                throw invalid();
            long next = 1;
            for (int i = 0; i < tier.bands().size(); i++) {
                var b = tier.bands().get(i);
                if (b == null || b.minQuantity() != next || b.unitPrice() == null || b.unitPrice().signum() <= 0
                        || b.unitPrice().scale() > 2 || b.unitPrice().compareTo(new BigDecimal("9999999999999999.99")) > 0
                        || (i == tier.bands().size() - 1 ? b.maxQuantity() != null
                        : b.maxQuantity() == null || b.maxQuantity() < b.minQuantity())) throw invalid();
                if (b.maxQuantity() != null) next = (long) b.maxQuantity() + 1;
            }
        }
    }

    private static ApiException invalid() {
        return ApiException.badRequest("INVALID_QUANTITY_PRICES", "Each level needs positive prices and continuous quantity ranges starting at 1, ending with an unlimited range.");
    }
}

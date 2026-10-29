package com.dailyactivity.catalog.domain.port;

import com.dailyactivity.catalog.domain.QuantityPricing.Band;
import com.dailyactivity.catalog.domain.QuantityPricing.TierPrices;
import java.util.List;
import java.util.Map;

public interface QuantityPriceRepository {
    Map<Long, List<Band>> prices(List<Long> productIds, String tier);
    List<Band> pricesForUpdate(long productId, String tier);
    List<TierPrices> allPrices(long productId);
    void replace(long productId, List<TierPrices> prices, long actorId);
}

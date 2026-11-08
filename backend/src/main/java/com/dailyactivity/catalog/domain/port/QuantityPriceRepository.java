package com.dailyactivity.catalog.domain.port;

import com.dailyactivity.catalog.domain.QuantityPricing.Band;
import com.dailyactivity.catalog.domain.QuantityPricing.TierPrices;
import java.util.List;
import java.util.Map;

public interface QuantityPriceRepository {
    Map<Long, List<Band>> prices(long merchantId, List<Long> productIds, String tier);
    List<Band> pricesForUpdate(long merchantId, long productId, String tier);
    List<TierPrices> allPrices(long merchantId, long productId);
    void replace(long merchantId, long productId, List<TierPrices> prices, long actorId);
}

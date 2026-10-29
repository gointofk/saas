package com.dailyactivity.merchant.domain.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface MerchantRepository {
    Set<String> PUBLIC_MENUS = Set.of("products", "certificates", "categories");
    Optional<Merchant> find(long id);
    Merchant lock(long id);
    void lockManagement();
    List<Merchant> page(int page);
    long count();
    long create(String code, String name, BigDecimal shippingFee, long actor);
    void update(long id, String name, BigDecimal shippingFee, boolean active, long actor);
    void replacePermissions(long id, Set<String> permissions, long actor);
    Set<String> permissions(long id);
    void protectManager(Long excludedUser, Long excludedMerchant);
    void audit(long merchantId, long actor, String action, String detail);
    record Merchant(long id, String code, String name, boolean active, BigDecimal shippingFee, Instant updatedAt) {}
}

package com.dailyactivity.merchant.domain.port;

import com.dailyactivity.auth.domain.Actor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface MerchantRepository {
    Set<String> PUBLIC_MENUS = Set.of("products", "certificates", "categories");
    Optional<Merchant> find(long id);
    Optional<Merchant> findByDomain(String domain);
    void branding(long id, String domain, String logoUrl, String theme);
    Merchant lock(long id);
    List<Merchant> page(int page);
    long count();
    long create(String code, String name, BigDecimal shippingFee, String domain, String logoUrl, String theme, long actor);
    void update(long id, String name, BigDecimal shippingFee, boolean active, long actor);
    void replacePermissions(long id, Set<String> permissions, long actor);
    Set<String> permissions(long id);
    void audit(long merchantId, Actor actor, String action, String detail);
    record Merchant(long id, String code, String name, boolean active, BigDecimal shippingFee, Instant updatedAt, String domain, String logoUrl, String theme) {
        public Merchant(long id,String code,String name,boolean active,BigDecimal fee,Instant time) {
            this(id,code,name,active,fee,time,null,"/ytpeps-logo.svg","classic");
        }
    }
}

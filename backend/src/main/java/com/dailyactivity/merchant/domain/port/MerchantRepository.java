package com.dailyactivity.merchant.domain.port;

import com.dailyactivity.auth.domain.Actor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MerchantRepository {
    Optional<Merchant> find(long id);
    Optional<Merchant> findByDomain(String domain);
    void branding(long id, String domain, String logoUrl, String theme);
    Merchant lock(long id);
    List<Merchant> page(int page);
    long count();
    long create(String code, String name, BigDecimal shippingFee, String domain, String logoUrl, String theme, long actor, boolean registrationEnabled);
    default long create(String code, String name, BigDecimal shippingFee, String domain, String logoUrl, String theme, long actor) {
        return create(code, name, shippingFee, domain, logoUrl, theme, actor, false);
    }
    void update(long id, String name, BigDecimal shippingFee, boolean active, long actor);
    void updateRegistrationEnabled(long id, boolean enabled, long actor);
    void audit(long merchantId, Actor actor, String action, String detail);
    record Merchant(long id, String code, String name, boolean active, BigDecimal shippingFee, Instant updatedAt, String domain, String logoUrl, String theme, boolean registrationEnabled) {
        public Merchant(long id,String code,String name,boolean active,BigDecimal fee,Instant time,String domain,String logoUrl,String theme) {
            this(id,code,name,active,fee,time,domain,logoUrl,theme,false);
        }
        public Merchant(long id,String code,String name,boolean active,BigDecimal fee,Instant time) {
            this(id,code,name,active,fee,time,null,"/ytpeps-logo.svg","classic",false);
        }
    }
}

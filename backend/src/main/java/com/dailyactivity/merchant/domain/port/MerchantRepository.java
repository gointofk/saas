package com.dailyactivity.merchant.domain.port;

import com.dailyactivity.auth.domain.Actor;
import com.dailyactivity.merchant.domain.ShippingFees;
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
    long create(String code, String name, ShippingFees shippingFees, String domain, String logoUrl, String theme, long actor, boolean registrationEnabled, boolean commissionEnabled, boolean cryptoEnabled, boolean onlinePaymentLinkEnabled);
    default long create(String code, String name, ShippingFees shippingFees, String domain, String logoUrl, String theme, long actor, boolean registrationEnabled) {
        return create(code, name, shippingFees, domain, logoUrl, theme, actor, registrationEnabled, true, true, false);
    }
    default long create(String code, String name, ShippingFees shippingFees, String domain, String logoUrl, String theme, long actor, boolean registrationEnabled, boolean commissionEnabled) {
        return create(code, name, shippingFees, domain, logoUrl, theme, actor, registrationEnabled, commissionEnabled, true, false);
    }
    default long create(String code, String name, ShippingFees shippingFees, String domain, String logoUrl, String theme, long actor) {
        return create(code, name, shippingFees, domain, logoUrl, theme, actor, false, true, true, false);
    }
    void update(long id, String name, ShippingFees shippingFees, boolean active, long actor);
    void updateRegistrationEnabled(long id, boolean enabled, long actor);
    void updateCommissionEnabled(long id, boolean enabled, long actor);
    void updatePaymentMethods(long id, boolean cryptoEnabled, boolean onlinePaymentLinkEnabled, long actor);
    void audit(long merchantId, Actor actor, String action, String detail);
    record Merchant(long id, String code, String name, boolean active, ShippingFees shippingFees, Instant updatedAt, String domain, String logoUrl, String theme, boolean registrationEnabled, boolean commissionEnabled, boolean cryptoEnabled, boolean onlinePaymentLinkEnabled) {
        public Merchant(long id,String code,String name,boolean active,ShippingFees fees,Instant time,String domain,String logoUrl,String theme,boolean registrationEnabled,boolean commissionEnabled) {
            this(id,code,name,active,fees,time,domain,logoUrl,theme,registrationEnabled,commissionEnabled,true,false);
        }
        public Merchant(long id,String code,String name,boolean active,ShippingFees fees,Instant time,String domain,String logoUrl,String theme,boolean registrationEnabled) {
            this(id,code,name,active,fees,time,domain,logoUrl,theme,registrationEnabled,true,true,false);
        }
        public Merchant(long id,String code,String name,boolean active,ShippingFees fees,Instant time,String domain,String logoUrl,String theme) {
            this(id,code,name,active,fees,time,domain,logoUrl,theme,false,true,true,false);
        }
        public Merchant(long id,String code,String name,boolean active,ShippingFees fees,Instant time) {
            this(id,code,name,active,fees,time,null,"/ytpeps-logo.svg","classic",false,true,true,false);
        }
    }
}

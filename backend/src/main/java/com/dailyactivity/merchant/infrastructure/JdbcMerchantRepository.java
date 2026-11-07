package com.dailyactivity.merchant.infrastructure;

import com.dailyactivity.auth.domain.Actor;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.merchant.domain.ShippingFees;
import com.dailyactivity.merchant.domain.port.MerchantRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcMerchantRepository implements MerchantRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcMerchantRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    private Merchant row(java.sql.ResultSet r) throws java.sql.SQLException {
        ShippingFees fees = new ShippingFees(r.getBigDecimal("shipping_fee_v1"), r.getBigDecimal("shipping_fee_v2"),
                r.getBigDecimal("shipping_fee_v3"), r.getBigDecimal("shipping_fee_v4"), r.getBigDecimal("shipping_fee_v5"),
                r.getBigDecimal("shipping_fee_v6"));
        return new Merchant(r.getLong("id"), r.getString("code"), r.getString("name"), r.getBoolean("active"), fees,
                r.getTimestamp("updated_at").toInstant(), r.getString("domain"), r.getString("logo_url"), r.getString("theme"),
                r.getBoolean("customer_registration_enabled"), r.getString("customer_registration_default_tier"), r.getBoolean("commission_enabled"),
                r.getBoolean("crypto_payment_enabled"), r.getBoolean("online_payment_link_enabled"));
    }
    public Optional<Merchant> find(long id) {
        return jdbc.query("SELECT * FROM merchant WHERE id=:id", new MapSqlParameterSource("id", id), r -> r.next() ? Optional.of(row(r)) : Optional.empty());
    }
    public Optional<Merchant> findByDomain(String domain) {
        return jdbc.query("SELECT * FROM merchant WHERE domain=:domain", new MapSqlParameterSource("domain",domain), r -> r.next() ? Optional.of(row(r)) : Optional.empty());
    }
    public void branding(long id, String domain, String logoUrl, String theme) {
        try {
            jdbc.update("UPDATE merchant SET domain=:domain,logo_url=:logo,theme=:theme WHERE id=:id", new MapSqlParameterSource("id",id).addValue("domain",domain).addValue("logo",logoUrl).addValue("theme",theme));
        } catch (DuplicateKeyException e) { throw ApiException.conflict("MERCHANT_DOMAIN_EXISTS","Domain is already in use."); }
    }
    public Merchant lock(long id) {
        return jdbc.query("SELECT * FROM merchant WHERE id=:id FOR UPDATE", new MapSqlParameterSource("id", id), r -> {
            if (!r.next()) throw ApiException.notFound("Merchant not found.");
            return row(r);
        });
    }
    public List<Merchant> page(int page) {
        return jdbc.query("SELECT * FROM merchant ORDER BY id LIMIT 10 OFFSET :offset", new MapSqlParameterSource("offset", (page-1)*10), (r,n) -> row(r));
    }
    public long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM merchant", new MapSqlParameterSource(), Long.class); }
    public long create(String code, String name, ShippingFees fees, String domain, String logoUrl, String theme, long actor, boolean registrationEnabled, String registrationDefaultTier, boolean commissionEnabled, boolean cryptoEnabled, boolean onlinePaymentLinkEnabled) {
        try {
            var key = new GeneratedKeyHolder();
            jdbc.update("INSERT INTO merchant(code,name,shipping_fee_v1,shipping_fee_v2,shipping_fee_v3,shipping_fee_v4,shipping_fee_v5,shipping_fee_v6,domain,logo_url,theme,customer_registration_enabled,customer_registration_default_tier,commission_enabled,crypto_payment_enabled,online_payment_link_enabled,created_by_user_id,updated_by_user_id) VALUES(:code,:name,:feeV1,:feeV2,:feeV3,:feeV4,:feeV5,:feeV6,:domain,:logo,:theme,:registrationEnabled,:registrationDefaultTier,:commissionEnabled,:cryptoEnabled,:onlinePaymentLinkEnabled,:actor,:actor)",
                    new MapSqlParameterSource("code",code).addValue("name",name).addValue("feeV1",fees.v1()).addValue("feeV2",fees.v2())
                            .addValue("feeV3",fees.v3()).addValue("feeV4",fees.v4()).addValue("feeV5",fees.v5()).addValue("feeV6",fees.v6())
                            .addValue("domain",domain).addValue("logo",logoUrl).addValue("theme",theme)
                            .addValue("registrationEnabled",registrationEnabled).addValue("registrationDefaultTier",registrationDefaultTier).addValue("commissionEnabled",commissionEnabled)
                            .addValue("cryptoEnabled",cryptoEnabled).addValue("onlinePaymentLinkEnabled",onlinePaymentLinkEnabled)
                            .addValue("actor",actor),key,new String[]{"id"});
            return key.getKey().longValue();
        } catch (DuplicateKeyException e) { throw ApiException.conflict("MERCHANT_IDENTIFIER_EXISTS", "Merchant code or domain is already in use."); }
    }
    public void update(long id, String name, ShippingFees fees, boolean active, long actor) {
        if (!active) {
            jdbc.update("UPDATE `user` SET auth_version=auth_version+1 WHERE merchant_id=:id",new MapSqlParameterSource("id",id));
            jdbc.update("UPDATE seller SET auth_version=auth_version+1 WHERE merchant_id=:id",new MapSqlParameterSource("id",id));
        }
        jdbc.update("UPDATE merchant SET name=:name,shipping_fee_v1=:feeV1,shipping_fee_v2=:feeV2,shipping_fee_v3=:feeV3,shipping_fee_v4=:feeV4,shipping_fee_v5=:feeV5,shipping_fee_v6=:feeV6,active=:active,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id",
                new MapSqlParameterSource("id",id).addValue("name",name).addValue("feeV1",fees.v1()).addValue("feeV2",fees.v2())
                        .addValue("feeV3",fees.v3()).addValue("feeV4",fees.v4()).addValue("feeV5",fees.v5()).addValue("feeV6",fees.v6())
                        .addValue("active",active).addValue("actor",actor));
    }
    public void updateRegistrationEnabled(long id, boolean enabled, long actor) {
        jdbc.update("UPDATE merchant SET customer_registration_enabled=:enabled,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id",new MapSqlParameterSource("id",id).addValue("enabled",enabled).addValue("actor",actor));
    }
    public void updateRegistrationDefaultTier(long id, String tier, long actor) {
        jdbc.update("UPDATE merchant SET customer_registration_default_tier=:tier,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor,updated_by_type='platform' WHERE id=:id",new MapSqlParameterSource("id",id).addValue("tier",tier).addValue("actor",actor));
    }
    public void updateCommissionEnabled(long id, boolean enabled, long actor) {
        jdbc.update("UPDATE merchant SET commission_enabled=:enabled,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id",new MapSqlParameterSource("id",id).addValue("enabled",enabled).addValue("actor",actor));
    }
    public void updatePaymentMethods(long id, boolean cryptoEnabled, boolean onlinePaymentLinkEnabled, long actor) {
        jdbc.update("UPDATE merchant SET crypto_payment_enabled=:cryptoEnabled,online_payment_link_enabled=:onlinePaymentLinkEnabled,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor,updated_by_type='platform' WHERE id=:id",new MapSqlParameterSource("id",id).addValue("cryptoEnabled",cryptoEnabled).addValue("onlinePaymentLinkEnabled",onlinePaymentLinkEnabled).addValue("actor",actor));
    }
    public void audit(long merchantId,Actor actor,String action,String detail) {
        jdbc.update("INSERT INTO merchant_event(merchant_id,actor_user_id,actor_type,action,detail) VALUES(:merchant,:actor,:actorType,:action,:detail)",new MapSqlParameterSource("merchant",merchantId).addValue("actor",actor.id()).addValue("actorType",actor.type()).addValue("action",action).addValue("detail",detail));
    }
}

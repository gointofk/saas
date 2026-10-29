package com.dailyactivity.merchant.infrastructure;

import com.dailyactivity.auth.domain.Actor;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.merchant.domain.port.MerchantRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.util.*;

@Repository
public class JdbcMerchantRepository implements MerchantRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcMerchantRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    private Merchant row(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Merchant(r.getLong("id"), r.getString("code"), r.getString("name"), r.getBoolean("active"), r.getBigDecimal("shipping_fee"), r.getTimestamp("updated_at").toInstant(), r.getString("domain"), r.getString("logo_url"), r.getString("theme"), r.getBoolean("customer_registration_enabled"));
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
    public long create(String code, String name, BigDecimal fee, String domain, String logoUrl, String theme, long actor, boolean registrationEnabled) {
        try {
            var key = new GeneratedKeyHolder();
            jdbc.update("INSERT INTO merchant(code,name,shipping_fee,domain,logo_url,theme,customer_registration_enabled,created_by_user_id,updated_by_user_id) VALUES(:code,:name,:fee,:domain,:logo,:theme,:registrationEnabled,:actor,:actor)", new MapSqlParameterSource("code",code).addValue("name",name).addValue("fee",fee).addValue("domain",domain).addValue("logo",logoUrl).addValue("theme",theme).addValue("registrationEnabled",registrationEnabled).addValue("actor",actor),key,new String[]{"id"});
            return key.getKey().longValue();
        } catch (DuplicateKeyException e) { throw ApiException.conflict("MERCHANT_IDENTIFIER_EXISTS", "Merchant code or domain is already in use."); }
    }
    public void update(long id, String name, BigDecimal fee, boolean active, long actor) {
        if (!active) {
            jdbc.update("UPDATE `user` SET auth_version=auth_version+1 WHERE merchant_id=:id",new MapSqlParameterSource("id",id));
            jdbc.update("UPDATE seller SET auth_version=auth_version+1 WHERE merchant_id=:id",new MapSqlParameterSource("id",id));
        }
        jdbc.update("UPDATE merchant SET name=:name,shipping_fee=:fee,active=:active,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id", new MapSqlParameterSource("id",id).addValue("name",name).addValue("fee",fee).addValue("active",active).addValue("actor",actor));
    }
    public void updateRegistrationEnabled(long id, boolean enabled, long actor) {
        jdbc.update("UPDATE merchant SET customer_registration_enabled=:enabled,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id",new MapSqlParameterSource("id",id).addValue("enabled",enabled).addValue("actor",actor));
    }
    public Set<String> permissions(long id) {
        return new HashSet<>(jdbc.query("SELECT menu_code FROM merchant_permission WHERE merchant_id=:id AND deleted=0",new MapSqlParameterSource("id",id),(r,n)->r.getString(1)));
    }
    public void replacePermissions(long id, Set<String> granted, long actor) {
        var existing = permissions(id);
        for (String menu : PUBLIC_MENUS) {
            var p = new MapSqlParameterSource("id",id).addValue("menu",menu).addValue("actor",actor);
            if (existing.contains(menu) && !granted.contains(menu)) {
                jdbc.update("UPDATE merchant_permission SET deleted=id WHERE merchant_id=:id AND menu_code=:menu AND deleted=0",p);
                jdbc.update("UPDATE seller_permission p JOIN seller u ON u.id=p.seller_id SET p.deleted=p.id,p.updated_at=UTC_TIMESTAMP(6),p.updated_by_user_id=:actor,p.updated_by_type='platform' WHERE u.merchant_id=:id AND p.menu_code=:menu AND p.deleted=0",p);
            } else if (!existing.contains(menu) && granted.contains(menu)) {
                jdbc.update("INSERT INTO merchant_permission(merchant_id,menu_code,created_by_user_id) VALUES(:id,:menu,:actor)",p);
            }
        }
    }
    public void audit(long merchantId,Actor actor,String action,String detail) {
        jdbc.update("INSERT INTO merchant_event(merchant_id,actor_user_id,actor_type,action,detail) VALUES(:merchant,:actor,:actorType,:action,:detail)",new MapSqlParameterSource("merchant",merchantId).addValue("actor",actor.id()).addValue("actorType",actor.type()).addValue("action",action).addValue("detail",detail));
    }
}

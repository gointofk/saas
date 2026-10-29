package com.dailyactivity.merchant.infrastructure;

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
        return new Merchant(r.getLong("id"), r.getString("code"), r.getString("name"), r.getBoolean("active"), r.getBigDecimal("shipping_fee"), r.getTimestamp("updated_at").toInstant());
    }
    public Optional<Merchant> find(long id) {
        return jdbc.query("SELECT * FROM merchant WHERE id=:id", new MapSqlParameterSource("id", id), r -> r.next() ? Optional.of(row(r)) : Optional.empty());
    }
    public Merchant lock(long id) {
        return jdbc.query("SELECT * FROM merchant WHERE id=:id FOR UPDATE", new MapSqlParameterSource("id", id), r -> {
            if (!r.next()) throw ApiException.notFound("Merchant not found.");
            return row(r);
        });
    }
    public void lockManagement() {
        jdbc.queryForObject("SELECT id FROM merchant_management_lock WHERE id=1 FOR UPDATE", new MapSqlParameterSource(), Long.class);
    }
    public List<Merchant> page(int page) {
        return jdbc.query("SELECT * FROM merchant ORDER BY id LIMIT 10 OFFSET :offset", new MapSqlParameterSource("offset", (page-1)*10), (r,n) -> row(r));
    }
    public long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM merchant", new MapSqlParameterSource(), Long.class); }
    public long create(String code, String name, BigDecimal fee, long actor) {
        try {
            var key = new GeneratedKeyHolder();
            jdbc.update("INSERT INTO merchant(code,name,shipping_fee,created_by_user_id,updated_by_user_id) VALUES(:code,:name,:fee,:actor,:actor)", new MapSqlParameterSource("code",code).addValue("name",name).addValue("fee",fee).addValue("actor",actor),key,new String[]{"id"});
            return key.getKey().longValue();
        } catch (DuplicateKeyException e) { throw ApiException.conflict("MERCHANT_CODE_EXISTS", "Merchant code is already in use."); }
    }
    public void update(long id, String name, BigDecimal fee, boolean active, long actor) {
        if (!active) jdbc.update("UPDATE `user` SET auth_version=auth_version+1 WHERE merchant_id=:id",new MapSqlParameterSource("id",id));
        jdbc.update("UPDATE merchant SET name=:name,shipping_fee=:fee,active=:active,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id", new MapSqlParameterSource("id",id).addValue("name",name).addValue("fee",fee).addValue("active",active).addValue("actor",actor));
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
                jdbc.update("UPDATE user_permission p JOIN `user` u ON u.id=p.user_id SET p.deleted=p.id,p.updated_at=UTC_TIMESTAMP(6),p.updated_by_user_id=:actor WHERE u.merchant_id=:id AND p.menu_code=:menu AND p.deleted=0",p);
            } else if (!existing.contains(menu) && granted.contains(menu)) {
                jdbc.update("INSERT INTO merchant_permission(merchant_id,menu_code,created_by_user_id) VALUES(:id,:menu,:actor)",p);
            }
        }
    }
    public void protectManager(Long user, Long merchant) {
        Long n=jdbc.queryForObject("SELECT COUNT(*) FROM `user` u JOIN merchant m ON m.id=u.merchant_id WHERE u.merchant_management=1 AND u.user_type='seller' AND u.active=1 AND u.deleted=0 AND m.active=1 AND (:userId IS NULL OR u.id<>:userId) AND (:merchantId IS NULL OR m.id<>:merchantId)",new MapSqlParameterSource("userId",user).addValue("merchantId",merchant),Long.class);
        if (n==null || n<1) throw ApiException.conflict("LAST_MERCHANT_MANAGER", "At least one active merchant manager is required.");
    }
    public void audit(long merchantId,long actor,String action,String detail) {
        jdbc.update("INSERT INTO merchant_event(merchant_id,actor_user_id,action,detail) VALUES(:merchant,:actor,:action,:detail)",new MapSqlParameterSource("merchant",merchantId).addValue("actor",actor).addValue("action",action).addValue("detail",detail));
    }
}

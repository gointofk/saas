package com.dailyactivity.auth.infrastructure;

import com.dailyactivity.auth.domain.Account;
import com.dailyactivity.auth.domain.port.AccountRepository;
import com.dailyactivity.common.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

@Repository
public class JdbcAccountRepository implements AccountRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcAccountRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    private String table(String type) {
        return switch (type) {
            case "customer" -> "`user`";
            case "seller" -> "seller";
            case "platform" -> "admin";
            default -> throw ApiException.unauthorized("Unknown account type.");
        };
    }
    private String select(String type) {
        String fields="customer".equals(type)
                ? "merchant_id,NULL account,email,code,customer_type,price_tier,referrer_id,commission_rate"
                : ("platform".equals(type) ? "0" : "merchant_id")+" merchant_id,account,NULL email,NULL code,NULL customer_type,NULL price_tier,NULL referrer_id,NULL commission_rate";
        return "SELECT id,name,password_hash,active,auth_version,last_login_at,"+fields+" FROM "+table(type)+" WHERE deleted=0";
    }
    public Optional<Account> findCustomerByEmail(long merchantId,String email) {
        return one("customer",select("customer")+" AND merchant_id=:merchant AND email=:email",new MapSqlParameterSource("merchant",merchantId).addValue("email",email));
    }
    public Optional<Account> findSellerByAccount(String account) { return byAccount("seller",account); }
    public Optional<Account> findPlatformByAccount(String account) { return byAccount("platform",account); }
    private Optional<Account> byAccount(String type,String account) {
        return one(type,select(type)+" AND account=:account",new MapSqlParameterSource("account",account));
    }
    public Optional<Account> findActiveById(String type,long id) {
        return one(type,select(type)+" AND id=:id AND active=1",new MapSqlParameterSource("id",id));
    }
    public Optional<Account> findCustomerById(long id) {
        return one("customer",select("customer")+" AND id=:id",new MapSqlParameterSource("id",id));
    }
    public Optional<Account> findByIdForUpdate(String type,long id) {
        return one(type,select(type)+" AND id=:id FOR UPDATE",new MapSqlParameterSource("id",id));
    }
    public Set<String> findPermissions(long id) {
        return new LinkedHashSet<>(jdbc.query("SELECT menu_code FROM seller_permission WHERE seller_id=:id AND deleted=0 ORDER BY menu_code",new MapSqlParameterSource("id",id),(rs,n)->rs.getString(1)));
    }
    public void touchLastLogin(String type,long id,Instant now) {
        jdbc.update("UPDATE "+table(type)+" SET last_login_at=:time,updated_at=UTC_TIMESTAMP(6) WHERE id=:id AND deleted=0",new MapSqlParameterSource("id",id).addValue("time",Timestamp.from(now)));
    }
    public boolean incrementAuthVersion(String type,long id,long version) {
        return jdbc.update("UPDATE "+table(type)+" SET auth_version=auth_version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=:id AND auth_version=:version AND deleted=0",new MapSqlParameterSource("id",id).addValue("version",version))==1;
    }
    public boolean updateCustomerName(long id,String name,long actorId) {
        return jdbc.update("UPDATE `user` SET name=:name,updated_at=UTC_TIMESTAMP(6),updated_by_type='customer',updated_by_user_id=:actor WHERE id=:id AND deleted=0",new MapSqlParameterSource("id",id).addValue("name",name).addValue("actor",actorId))==1;
    }
    public boolean updateCustomerPassword(long id,String hash,long actorId) {
        return jdbc.update("UPDATE `user` SET password_hash=:hash,auth_version=auth_version+1,updated_at=UTC_TIMESTAMP(6),updated_by_type='customer',updated_by_user_id=:actor WHERE id=:id AND deleted=0",new MapSqlParameterSource("id",id).addValue("hash",hash).addValue("actor",actorId))==1;
    }
    private Optional<Account> one(String type,String sql,MapSqlParameterSource params) {
        return jdbc.query(sql,params,rs -> {
            if(!rs.next()) return Optional.empty();
            Timestamp login=rs.getTimestamp("last_login_at");
            Number referrer=(Number)rs.getObject("referrer_id");
            return Optional.of(new Account(rs.getLong("id"),type,rs.getString("account"),rs.getString("email"),rs.getString("name"),rs.getString("password_hash"),rs.getString("code"),rs.getString("customer_type"),rs.getString("price_tier"),rs.getBoolean("active"),referrer==null?null:referrer.longValue(),rs.getBigDecimal("commission_rate"),rs.getLong("auth_version"),login==null?null:login.toInstant(),rs.getLong("merchant_id")));
        });
    }
}

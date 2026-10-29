package com.dailyactivity.auth.infrastructure;

import com.dailyactivity.auth.domain.Account;
import com.dailyactivity.auth.domain.port.UserRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

@Repository
public class JdbcUserRepository implements UserRepository {
    private static final String SELECT = "SELECT merchant_id,merchant_management,id,user_type,account,email,name,password_hash,code,customer_type,price_tier,active,referrer_id,commission_rate,auth_version,last_login_at FROM `user` WHERE deleted=0 ";
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcUserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Account> findCustomerByEmail(String email) {
        return one(SELECT + "AND user_type='customer' AND email=:email", new MapSqlParameterSource("email", email));
    }

    @Override
    public Optional<Account> findSellerByAccount(String account) {
        return one(SELECT + "AND user_type='seller' AND account=:account", new MapSqlParameterSource("account", account));
    }

    @Override
    public Optional<Account> findActiveById(long id) {
        return one(SELECT + "AND id=:id AND active=1", new MapSqlParameterSource("id", id));
    }

    @Override
    public Optional<Account> findCustomerById(long id) {
        return one(SELECT + "AND id=:id AND user_type='customer'", new MapSqlParameterSource("id", id));
    }

    @Override
    public Optional<Account> findByIdForUpdate(long id) {
        return one(SELECT + "AND id=:id FOR UPDATE", new MapSqlParameterSource("id", id));
    }

    @Override
    public Set<String> findPermissions(long sellerId) {
        return new LinkedHashSet<>(jdbc.query("SELECT menu_code FROM user_permission WHERE user_id=:id AND deleted=0 ORDER BY menu_code", new MapSqlParameterSource("id", sellerId), (rs, rowNum) -> rs.getString(1)));
    }

    @Override
    public void touchLastLogin(long id, Instant now) {
        jdbc.update("UPDATE `user` SET last_login_at=:time,updated_at=UTC_TIMESTAMP(6) WHERE id=:id AND deleted=0", new MapSqlParameterSource().addValue("id", id).addValue("time", Timestamp.from(now)));
    }

    @Override
    public boolean incrementAuthVersion(long id, long expectedVersion) {
        return jdbc.update("UPDATE `user` SET auth_version=auth_version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=:id AND auth_version=:version AND deleted=0", new MapSqlParameterSource().addValue("id", id).addValue("version", expectedVersion)) == 1;
    }

    @Override
    public boolean updateName(long id, String name, long actorId) {
        return jdbc.update("UPDATE `user` SET name=:name,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id AND deleted=0", new MapSqlParameterSource().addValue("id", id).addValue("name", name).addValue("actor", actorId)) == 1;
    }

    @Override
    public boolean updatePassword(long id, String passwordHash, long actorId) {
        return jdbc.update("UPDATE `user` SET password_hash=:passwordHash,auth_version=auth_version+1,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id AND deleted=0", new MapSqlParameterSource().addValue("id", id).addValue("passwordHash", passwordHash).addValue("actor", actorId)) == 1;
    }

    @Override
    public boolean updateActive(long id, boolean active, long actorId) {
        return jdbc.update("UPDATE `user` SET active=:active,auth_version=auth_version+1,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id AND deleted=0", new MapSqlParameterSource().addValue("id", id).addValue("active", active).addValue("actor", actorId)) == 1;
    }

    private Optional<Account> one(String sql, MapSqlParameterSource params) {
        return jdbc.query(sql, params, rows -> rows.next() ? Optional.of(account(rows)) : Optional.empty());
    }

    private Account account(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp lastLogin = rs.getTimestamp("last_login_at");
        return new Account(rs.getLong("id"), rs.getString("user_type"), rs.getString("account"), rs.getString("email"), rs.getString("name"), rs.getString("password_hash"), rs.getString("code"), rs.getString("customer_type"), rs.getString("price_tier"), rs.getBoolean("active"), (Long) rs.getObject("referrer_id"), rs.getBigDecimal("commission_rate"), rs.getLong("auth_version"), lastLogin == null ? null : lastLogin.toInstant(), rs.getLong("merchant_id"), rs.getBoolean("merchant_management"));
    }
}

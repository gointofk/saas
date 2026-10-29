package com.dailyactivity.identity.infrastructure;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.identity.domain.port.CustomerRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcCustomerRepository implements CustomerRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCustomerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private String select() {
        return "SELECT " + customerColumns() + " FROM `user` u WHERE u.deleted=0 AND u.merchant_id=:merchantId";
    }

    private String customerColumns() {
        return "u.id,u.code,u.name,u.email,u.customer_type,u.price_tier,u.active,u.referrer_id,u.commission_rate,"
                + "(SELECT COUNT(*) FROM sales_order o WHERE o.customer_id=u.id) order_count,"
                + "(SELECT COUNT(*) FROM `user` r WHERE r.r.referrer_id=u.id AND r.deleted=0) referral_count,"
                + "(SELECT COALESCE(SUM(o.total_amount),0) FROM sales_order o WHERE o.customer_id=u.id AND o.order_status<>'cancelled') total_amount,"
                + "(SELECT COUNT(*) FROM sales_order o WHERE o.customer_id=u.id AND o.order_status<>'cancelled' AND o.payment_status IN ('unpaid','pending_verification','partially_paid')) unpaid_count,"
                + "(SELECT COALESCE(SUM(o.total_amount-COALESCE((SELECT SUM(pr.received_amount) FROM payment_receipt pr WHERE pr.order_id=o.id),0)),0) FROM sales_order o WHERE o.customer_id=u.id AND o.order_status<>'cancelled' AND o.payment_status IN ('unpaid','pending_verification','partially_paid')) unpaid_amount,"
                + "u.last_login_at,u.created_at";
    }

    @Override
    public List<Customer> page(long merchantId, String q, String customerType, boolean unpaidOnly, int page) {
        return jdbc.query(select() + filter(q, customerType, unpaidOnly) + " ORDER BY u.created_at DESC,u.id DESC LIMIT 10 OFFSET :offset", params(q, customerType).addValue("merchantId", merchantId).addValue("offset", (page - 1) * 10), (rs, row) -> customer(rs));
    }

    @Override
    public long count(long merchantId, String q, String customerType, boolean unpaidOnly) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `user` u WHERE u.deleted=0 AND u.merchant_id=:merchantId" + filter(q, customerType, unpaidOnly), params(q, customerType).addValue("merchantId", merchantId), Long.class);
    }

    @Override
    public Optional<Customer> find(long merchantId, long id) {
        return jdbc.query(select() + " AND u.id=:id", new MapSqlParameterSource("id", id).addValue("merchantId", merchantId), rows -> rows.next() ? Optional.of(customer(rows)) : Optional.empty());
    }

    @Override
    public long create(long merchantId, CustomerInput i, String code, long actor) {
        try {
            KeyHolder h = new GeneratedKeyHolder();
            jdbc.update("INSERT INTO `user`(merchant_id,email,name,password_hash,code,customer_type,price_tier,active,referrer_id,commission_rate,created_by_user_id,updated_by_user_id) VALUES(:merchantId,:email,:name,:passwordHash,:code,:customerType,:priceTier,1,:referrerId,:commissionRate,:actor,:actor)", params(i, code, actor).addValue("merchantId", merchantId), h, new String[]{"id"});
            return h.getKey().longValue();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CUSTOMER_IDENTIFIER_EXISTS", "Email or customer code is already in use.");
        }
    }

    @Override
    public void update(long merchantId, long id, CustomerInput i, long actor) {
        try {
            int n = jdbc.update("UPDATE `user` SET email=:email,name=:name,customer_type=:customerType,price_tier=:priceTier,referrer_id=:referrerId,commission_rate=:commissionRate,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor,updated_by_type='seller' WHERE merchant_id=:merchantId AND id=:id AND deleted=0", params(i, null, actor).addValue("merchantId", merchantId).addValue("id", id));
            if (n != 1) throw ApiException.notFound("Customer not found.");
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CUSTOMER_EMAIL_EXISTS", "Email is already in use.");
        }
    }

    @Override
    public void setActive(long merchantId, long id, boolean active, long actor) {
        int n = jdbc.update("UPDATE `user` SET active=:active,auth_version=auth_version+1,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor,updated_by_type='seller' WHERE merchant_id=:merchantId AND id=:id AND deleted=0", new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", id).addValue("active", active).addValue("actor", actor));
        if (n != 1) throw ApiException.notFound("Customer not found.");
    }

    @Override
    public void setPassword(long merchantId, long id, String hash, long actor) {
        int n = jdbc.update("UPDATE `user` SET password_hash=:hash,auth_version=auth_version+1,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor,updated_by_type='seller' WHERE merchant_id=:merchantId AND id=:id AND deleted=0", new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", id).addValue("hash", hash).addValue("actor", actor));
        if (n != 1) throw ApiException.notFound("Customer not found.");
    }

    @Override
    public List<Customer> directReferrals(long merchantId, long referrerId) {
        return jdbc.query(select() + " AND u.referrer_id=:referrerId ORDER BY u.created_at DESC,u.id DESC", new MapSqlParameterSource("referrerId", referrerId).addValue("merchantId", merchantId), (rs, row) -> customer(rs));
    }

    @Override
    public List<Customer> directReferrals(long merchantId, long referrerId, int page) {
        return jdbc.query(select() + " AND u.referrer_id=:referrerId ORDER BY u.created_at DESC,u.id DESC LIMIT 10 OFFSET :offset", new MapSqlParameterSource("referrerId", referrerId).addValue("merchantId", merchantId).addValue("offset", (page - 1) * 10), (rs, row) -> customer(rs));
    }

    @Override
    public List<ReferralCustomer> customerReferrals(long merchantId, long referrerId) {
        String sql = "SELECT " + customerColumns() + ","
                + "(u.referrer_id=:referrerId) current_relation,"
                + "(SELECT COUNT(DISTINCT a.rate) FROM commission_accrual a WHERE a.referrer_id=:referrerId AND a.referred_customer_id=u.id) historical_rate_count,"
                + "(SELECT MAX(a.rate) FROM commission_accrual a WHERE a.referrer_id=:referrerId AND a.referred_customer_id=u.id) historical_rate,"
                + "(SELECT COALESCE(SUM(a.base_amount),0) FROM commission_accrual a WHERE a.referrer_id=:referrerId AND a.referred_customer_id=u.id AND a.status IN ('estimated','payable')) pending_base,"
                + "(SELECT COALESCE(SUM(a.commission_amount),0) FROM commission_accrual a WHERE a.referrer_id=:referrerId AND a.referred_customer_id=u.id AND a.status IN ('estimated','payable')) pending_commission,"
                + "(SELECT COALESCE(SUM(s.commission_amount),0) FROM commission_settlement s WHERE s.referrer_id=:referrerId AND s.referred_customer_id=u.id) settled_commission_amount "
                + "FROM `user` u WHERE u.deleted=0 AND u.merchant_id=:merchantId AND ((u.referrer_id=:referrerId AND u.commission_rate>0) "
                + "OR EXISTS (SELECT 1 FROM commission_accrual a JOIN sales_order o ON o.id=a.order_id WHERE a.referrer_id=:referrerId AND a.referred_customer_id=u.id AND a.rate>0 AND o.audit_status='approved' AND o.order_status<>'cancelled')) "
                + "ORDER BY current_relation DESC,u.created_at DESC,u.id DESC";
        return jdbc.query(sql, new MapSqlParameterSource("referrerId", referrerId).addValue("merchantId", merchantId), (rs, row) ->
                new ReferralCustomer(customer(rs), rs.getBoolean("current_relation"), rs.getLong("historical_rate_count"),
                        rs.getBigDecimal("historical_rate"), rs.getBigDecimal("pending_base"), rs.getBigDecimal("pending_commission"),
                        rs.getBigDecimal("settled_commission_amount")));
    }

    @Override
    public long directReferralCount(long merchantId, long referrerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE merchant_id=:merchantId AND referrer_id=:id AND deleted=0", new MapSqlParameterSource("id", referrerId).addValue("merchantId", merchantId), Long.class);
    }

    @Override
    public ReferralSummary referralSummary(long merchantId, long referrerId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE merchant_id=:merchantId AND referrer_id=:id AND deleted=0", new MapSqlParameterSource("id", referrerId).addValue("merchantId", merchantId), Long.class);
        var row = jdbc.queryForMap("SELECT "
                        + "COUNT(*) order_count,"
                        + "COALESCE(SUM(a.status='estimated'),0) pending_verification_order_count,"
                        + "COALESCE(SUM(a.status='payable'),0) pending_payment_order_count,"
                        + "COALESCE(SUM(a.status='settled'),0) paid_order_count,"
                        + "COALESCE(SUM(CASE WHEN a.status IN ('estimated','payable') THEN a.base_amount ELSE 0 END),0) pending_base,"
                        + "COALESCE(SUM(CASE WHEN a.status IN ('estimated','payable') THEN a.commission_amount ELSE 0 END),0) pending_commission,"
                        + "COALESCE(SUM(a.commission_amount),0) commission_amount,"
                        + "COALESCE(SUM(CASE WHEN a.status='estimated' THEN a.commission_amount ELSE 0 END),0) estimated_commission_amount,"
                        + "COALESCE(SUM(CASE WHEN a.status='payable' THEN a.commission_amount ELSE 0 END),0) payable_commission_amount,"
                        + "COALESCE(SUM(CASE WHEN a.status='settled' THEN a.commission_amount ELSE 0 END),0) settled_commission_amount "
                        + "FROM commission_accrual a JOIN sales_order o ON o.id=a.order_id "
                        + "WHERE a.merchant_id=:merchantId AND a.referrer_id=:id AND a.rate>0 AND o.audit_status='approved' AND o.order_status<>'cancelled'",
                new MapSqlParameterSource("id", referrerId).addValue("merchantId", merchantId));
        var settled = jdbc.queryForObject("SELECT COALESCE(SUM(commission_amount),0) FROM commission_settlement WHERE merchant_id=:merchantId AND referrer_id=:id", new MapSqlParameterSource("id", referrerId).addValue("merchantId", merchantId), java.math.BigDecimal.class);
        return new ReferralSummary(count, (java.math.BigDecimal) row.get("pending_base"), (java.math.BigDecimal) row.get("pending_commission"), settled,
                ((Number) row.get("order_count")).longValue(), ((Number) row.get("pending_verification_order_count")).longValue(),
                ((Number) row.get("pending_payment_order_count")).longValue(), ((Number) row.get("paid_order_count")).longValue(),
                (java.math.BigDecimal) row.get("commission_amount"), (java.math.BigDecimal) row.get("estimated_commission_amount"),
                (java.math.BigDecimal) row.get("payable_commission_amount"), (java.math.BigDecimal) row.get("settled_commission_amount"));
    }

    private String filter(String q, String customerType, boolean unpaidOnly) {
        StringBuilder filter = new StringBuilder();
        if (q != null && !q.isBlank()) filter.append(" AND (u.name LIKE :query OR u.email LIKE :query OR u.code LIKE :query)");
        if (customerType != null) filter.append(" AND u.customer_type=:customerType");
        if (unpaidOnly) filter.append(" AND EXISTS (SELECT 1 FROM sales_order o WHERE o.customer_id=u.id AND o.order_status<>'cancelled' AND o.payment_status IN ('unpaid','pending_verification','partially_paid'))");
        return filter.toString();
    }

    private MapSqlParameterSource params(String q, String customerType) {
        return new MapSqlParameterSource()
                .addValue("query", q == null || q.isBlank() ? null : "%" + q.trim() + "%")
                .addValue("customerType", customerType);
    }

    private MapSqlParameterSource params(CustomerInput i, String code, long actor) {
        return new MapSqlParameterSource().addValue("email", i.email()).addValue("name", i.name()).addValue("passwordHash", i.passwordHash()).addValue("code", code).addValue("customerType", i.customerType()).addValue("priceTier", i.priceTier()).addValue("referrerId", i.referrerId()).addValue("commissionRate", i.commissionRate()).addValue("actor", actor);
    }

    private Customer customer(java.sql.ResultSet r) throws java.sql.SQLException {
        Timestamp lastLogin = r.getTimestamp("last_login_at");
        Timestamp created = r.getTimestamp("created_at");
        return new Customer(r.getLong("id"), r.getString("code"), r.getString("name"), r.getString("email"),
                r.getString("customer_type"), r.getString("price_tier"), r.getBoolean("active"),
                (Long) r.getObject("referrer_id"), r.getBigDecimal("commission_rate"), r.getLong("order_count"),
                r.getLong("referral_count"), r.getBigDecimal("total_amount"), r.getLong("unpaid_count"),
                r.getBigDecimal("unpaid_amount"), lastLogin == null ? null : lastLogin.toInstant(),
                created == null ? null : created.toInstant());
    }
}

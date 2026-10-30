package com.dailyactivity.commission.infrastructure;

import com.dailyactivity.commission.domain.port.CommissionRepository;
import com.dailyactivity.common.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcCommissionRepository implements CommissionRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCommissionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Accrual> page(long merchantId, Long referrerId, String status, int page) {
        String f = (referrerId == null ? "" : " AND a.referrer_id=:referrerId") + (status == null || status.isBlank() ? "" : " AND a.status=:status");
        return jdbc.query("SELECT a.id,a.order_id,o.order_no,a.referrer_id,a.referrer_name_snapshot,a.referred_name_snapshot,a.status,a.rate,a.base_amount,a.commission_amount,o.order_status,o.payment_status,o.created_at FROM commission_accrual a JOIN sales_order o ON o.id=a.order_id WHERE o.merchant_id=:merchantId AND o.audit_status='approved' AND o.order_status<>'cancelled'" + f + " ORDER BY o.created_at DESC,a.id DESC LIMIT 10 OFFSET :offset", params(referrerId, status).addValue("merchantId", merchantId).addValue("offset", (page - 1) * 10), (rs, row) -> accrual(rs));
    }

    @Override
    public List<Accrual> adminPage(long merchantId, AccrualFilter filter, int page) {
        String sql = "SELECT a.id,a.order_id,o.order_no,a.referrer_id,a.referrer_name_snapshot,a.referred_name_snapshot,a.status,a.rate,a.base_amount,a.commission_amount,o.order_status,o.payment_status,o.created_at "
                + "FROM commission_accrual a JOIN sales_order o ON o.id=a.order_id WHERE o.merchant_id=:merchantId AND o.audit_status='approved' AND o.order_status<>'cancelled'" + accrualFilters(filter)
                + " ORDER BY o.created_at DESC,a.id DESC LIMIT 10 OFFSET :offset";
        return jdbc.query(sql, accrualParams(filter).addValue("merchantId",merchantId).addValue("offset", (page - 1) * 10), (rs, row) -> accrual(rs));
    }

    @Override
    public long count(long merchantId, Long referrerId, String status) {
        String f = (referrerId == null ? "" : " AND referrer_id=:referrerId") + (status == null || status.isBlank() ? "" : " AND status=:status");
        return jdbc.queryForObject("SELECT COUNT(*) FROM commission_accrual a JOIN sales_order o ON o.id=a.order_id WHERE o.merchant_id=:merchantId AND o.audit_status='approved' AND o.order_status<>'cancelled'" + f, params(referrerId, status).addValue("merchantId", merchantId), Long.class);
    }

    @Override
    public long adminCount(long merchantId, AccrualFilter filter) {
        String sql = "SELECT COUNT(*) FROM commission_accrual a JOIN sales_order o ON o.id=a.order_id WHERE o.merchant_id=:merchantId AND o.audit_status='approved' AND o.order_status<>'cancelled'" + accrualFilters(filter);
        Long count = jdbc.queryForObject(sql, accrualParams(filter).addValue("merchantId",merchantId), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<Accrual> findAccrual(long merchantId, long id) {
        return jdbc.query("SELECT a.id,a.order_id,o.order_no,a.referrer_id,a.referrer_name_snapshot,a.referred_name_snapshot,a.status,a.rate,a.base_amount,a.commission_amount,o.order_status,o.payment_status,o.created_at FROM commission_accrual a JOIN sales_order o ON o.id=a.order_id WHERE o.merchant_id=:merchantId AND a.id=:id AND o.audit_status='approved'", new MapSqlParameterSource("id", id).addValue("merchantId", merchantId), rows -> rows.next() ? Optional.of(accrual(rows)) : Optional.empty());
    }

    @Override
    public Summary summary(long merchantId) {
        return jdbc.queryForObject("SELECT COUNT(*) total_count,COALESCE(SUM(a.status='estimated'),0) estimated_count,COALESCE(SUM(a.status='payable'),0) payable_count,COALESCE(SUM(a.status='settled'),0) settled_count,COALESCE(SUM(a.commission_amount),0) commission_amount,COALESCE(SUM(CASE WHEN a.status='settled' THEN a.commission_amount ELSE 0 END),0) settled_amount FROM commission_accrual a JOIN sales_order o ON o.id=a.order_id WHERE o.merchant_id=:merchantId AND o.audit_status='approved' AND o.order_status<>'cancelled'",
                new MapSqlParameterSource().addValue("merchantId", merchantId), (rs, row) -> new Summary(rs.getLong("total_count"), rs.getLong("estimated_count"), rs.getLong("payable_count"), rs.getLong("settled_count"), rs.getBigDecimal("commission_amount"), rs.getBigDecimal("settled_amount")));
    }

    @Override
    public List<Accrual> pageForReferred(long merchantId, long referrerId, long referredCustomerId, int page) {
        return jdbc.query("SELECT a.id,a.order_id,o.order_no,a.referrer_id,a.referrer_name_snapshot,a.referred_name_snapshot,a.status,a.rate,a.base_amount,a.commission_amount,o.order_status,o.payment_status,o.created_at FROM commission_accrual a JOIN sales_order o ON o.id=a.order_id WHERE o.merchant_id=:merchantId AND o.audit_status='approved' AND o.order_status<>'cancelled' AND a.rate>0 AND a.referrer_id=:referrerId AND a.referred_customer_id=:referredCustomerId ORDER BY o.created_at DESC,a.id DESC LIMIT 10 OFFSET :offset", new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("referrerId", referrerId).addValue("referredCustomerId", referredCustomerId).addValue("offset", (page - 1) * 10), (rs, row) -> accrual(rs));
    }

    @Override
    public long countForReferred(long merchantId, long referrerId, long referredCustomerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM commission_accrual a JOIN sales_order o ON o.id=a.order_id WHERE o.merchant_id=:merchantId AND o.audit_status='approved' AND o.order_status<>'cancelled' AND a.rate>0 AND a.referrer_id=:referrerId AND a.referred_customer_id=:referredCustomerId", new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("referrerId", referrerId).addValue("referredCustomerId", referredCustomerId), Long.class);
    }

    @Override
    public List<Settlement> settlements(long merchantId, Long referrerId, Long referred, int page) {
        String f = (referrerId == null ? "" : " AND s.referrer_id=:referrerId") + (referred == null ? "" : " AND s.referred_customer_id=:referred");
        return jdbc.query("SELECT s.id,s.order_id,s.order_no_snapshot,s.referred_customer_id,s.referred_name_snapshot,s.rate,s.base_amount,s.commission_amount,b.settled_at,b.remark FROM commission_settlement s JOIN commission_settlement_batch b ON b.id=s.batch_id AND b.merchant_id=s.merchant_id WHERE s.merchant_id=:merchantId" + f + " ORDER BY b.settled_at DESC,s.id DESC LIMIT 10 OFFSET :offset", params(referrerId, null).addValue("merchantId", merchantId).addValue("referred", referred).addValue("offset", (page - 1) * 10), (rs, row) -> new Settlement(rs.getLong("id"), rs.getLong("order_id"), rs.getString("order_no_snapshot"), rs.getLong("referred_customer_id"), rs.getString("referred_name_snapshot"), rs.getBigDecimal("rate"), rs.getBigDecimal("base_amount"), rs.getBigDecimal("commission_amount"), rs.getTimestamp("settled_at").toInstant(), rs.getString("remark")));
    }

    @Override
    public long settlementCount(long merchantId, Long referrerId, Long referred) {
        String f = (referrerId == null ? "" : " AND referrer_id=:referrerId") + (referred == null ? "" : " AND referred_customer_id=:referred");
        return jdbc.queryForObject("SELECT COUNT(*) FROM commission_settlement WHERE merchant_id=:merchantId" + f, params(referrerId, null).addValue("merchantId", merchantId).addValue("referred", referred), Long.class);
    }

    @Override
    public Optional<SettlementDetail> settlement(long merchantId, long id) {
        return settlementDetail("s.merchant_id=:merchantId AND s.id=:id", new MapSqlParameterSource("id", id).addValue("merchantId", merchantId));
    }

    @Override
    public Optional<SettlementDetail> settlementByAccrual(long merchantId, long accrualId) {
        return settlementDetail("s.merchant_id=:merchantId AND s.accrual_id=:accrualId", new MapSqlParameterSource("accrualId", accrualId).addValue("merchantId", merchantId));
    }

    private Optional<SettlementDetail> settlementDetail(String predicate, MapSqlParameterSource parameters) {
        return jdbc.query("SELECT s.id,s.batch_id,b.batch_no,s.accrual_id,s.order_id,s.order_no_snapshot,s.referrer_id,s.referrer_name_snapshot,s.referred_customer_id,s.referred_name_snapshot,s.base_amount,s.rate,s.commission_amount,b.settled_at,b.settled_by_user_id,b.remark FROM commission_settlement s JOIN commission_settlement_batch b ON b.id=s.batch_id AND b.merchant_id=s.merchant_id WHERE " + predicate, parameters, rows -> {
            if (!rows.next()) return Optional.empty();
            return Optional.of(new SettlementDetail(rows.getLong("id"), rows.getLong("batch_id"), rows.getString("batch_no"), rows.getLong("accrual_id"), rows.getLong("order_id"), rows.getString("order_no_snapshot"), rows.getLong("referrer_id"), rows.getString("referrer_name_snapshot"), rows.getLong("referred_customer_id"), rows.getString("referred_name_snapshot"), rows.getBigDecimal("base_amount"), rows.getBigDecimal("rate"), rows.getBigDecimal("commission_amount"), rows.getTimestamp("settled_at").toInstant(), rows.getLong("settled_by_user_id"), rows.getString("remark")));
        });
    }

    @Override
    public long settle(long merchantId, List<SettlementRequest> items, String remark, long actor) {
        if (items == null || items.isEmpty())
            throw ApiException.badRequest("EMPTY_SETTLEMENT", "At least one accrual is required.");
        String batchNo = "SET-" + Instant.now().toEpochMilli() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        BigDecimal totalBase = BigDecimal.ZERO, totalCommission = BigDecimal.ZERO;
        List<Accrual> locked = new java.util.ArrayList<>();
        List<SettlementCandidate> candidates = new java.util.ArrayList<>();
        for (var item : items) {
            Long orderId = jdbc.query("SELECT order_id FROM commission_accrual WHERE merchant_id=:merchantId AND id=:id", new MapSqlParameterSource("id", item.accrualId()).addValue("merchantId", merchantId),
                    rows -> rows.next() ? rows.getLong("order_id") : null);
            if (orderId == null)
                throw ApiException.conflict("COMMISSION_NOT_PAYABLE", "Commission is not payable.");
            candidates.add(new SettlementCandidate(item, orderId));
        }
        candidates.sort(java.util.Comparator.comparingLong(SettlementCandidate::orderId)
                .thenComparingLong(candidate -> candidate.request().accrualId()));
        for (SettlementCandidate candidate : candidates) {
            List<Long> eligibleOrderIds = jdbc.query("SELECT id FROM sales_order WHERE merchant_id=:merchantId AND id=:orderId AND audit_status='approved' AND order_status<>'cancelled' FOR UPDATE",
                    new MapSqlParameterSource("orderId", candidate.orderId()).addValue("merchantId", merchantId), (rows, rowNum) -> rows.getLong("id"));
            if (eligibleOrderIds.isEmpty())
                throw ApiException.conflict("COMMISSION_NOT_PAYABLE", "Commission is not payable.");
            var i = candidate.request();
            Accrual a = jdbc.query("SELECT a.id,a.order_id,o.order_no,a.referrer_id,a.referrer_name_snapshot,a.referred_name_snapshot,a.status,a.rate,a.base_amount,a.commission_amount,o.order_status,o.payment_status,o.created_at FROM commission_accrual a JOIN sales_order o ON o.id=a.order_id WHERE o.merchant_id=:merchantId AND a.id=:id AND a.order_id=:orderId AND o.audit_status='approved' AND o.order_status<>'cancelled' FOR UPDATE",
                    new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", i.accrualId()).addValue("orderId", candidate.orderId()), rows -> rows.next() ? accrual(rows) : null);
            if (a == null || !"payable".equals(a.status()))
                throw ApiException.conflict("COMMISSION_NOT_PAYABLE", "Commission is not payable.");
            if (i.expectedAmount() == null || i.expectedAmount().compareTo(a.commissionAmount()) != 0)
                throw ApiException.conflict("COMMISSION_AMOUNT_CHANGED", "Commission amount changed.");
            locked.add(a);
            totalBase = totalBase.add(a.baseAmount());
            totalCommission = totalCommission.add(a.commissionAmount());
        }
        KeyHolder h = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO commission_settlement_batch(merchant_id,batch_no,order_count,total_base,total_commission,settled_by_user_id,settled_at,remark) VALUES(:merchantId,:batchNo,:count,:base,:commission,:actor,UTC_TIMESTAMP(6),:remark)", new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("batchNo", batchNo).addValue("count", locked.size()).addValue("base", totalBase).addValue("commission", totalCommission).addValue("actor", actor).addValue("remark", remark), h, new String[]{"id"});
        long batchId = h.getKey().longValue();
        for (Accrual a : locked) {
            jdbc.update("INSERT INTO commission_settlement(merchant_id,batch_id,accrual_id,order_id,order_no_snapshot,referrer_id,referred_customer_id,referrer_name_snapshot,referred_name_snapshot,base_amount,rate,commission_amount) VALUES(:merchantId,:batchId,:accrualId,:orderId,:orderNo,:referrerId,(SELECT customer_id FROM sales_order WHERE merchant_id=:merchantId AND id=:orderId),:referrerName,:referredName,:base,:rate,:commission)", new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("batchId", batchId).addValue("accrualId", a.id()).addValue("orderId", a.orderId()).addValue("orderNo", a.orderNo()).addValue("referrerId", a.referrerId()).addValue("referrerName", a.referrer()).addValue("referredName", a.referred()).addValue("base", a.baseAmount()).addValue("rate", a.rate()).addValue("commission", a.commissionAmount()).addValue("actor", actor).addValue("remark", remark));
            jdbc.update("UPDATE commission_accrual SET status='settled',updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor,updated_by_type='seller' WHERE merchant_id=:merchantId AND id=:id", new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("id", a.id()).addValue("actor", actor));
            jdbc.update("UPDATE billing_entry SET status='settled',settled_amount=amount,settled_at=(SELECT settled_at FROM commission_settlement_batch WHERE merchant_id=:merchantId AND id=:batchId),updated_at=UTC_TIMESTAMP(6) WHERE merchant_id=:merchantId AND order_id=:orderId AND bill_type='commission_payable'", new MapSqlParameterSource("orderId", a.orderId()).addValue("merchantId", merchantId).addValue("batchId", batchId));
        }
        return batchId;
    }

    private record SettlementCandidate(SettlementRequest request, long orderId) {}

    @Override
    public List<Bill> bills(long merchantId, String type, String status, int page) {
        return bills(merchantId, new BillFilter(type, status, null, null, null), page);
    }

    @Override
    public List<Bill> bills(long merchantId, BillFilter filter, int page) {
        String f = billFilters(filter);
        return jdbc.query("SELECT b.id,b.bill_no,b.bill_type,CASE b.bill_type WHEN 'order_receivable' THEN 'receivable' WHEN 'commission_payable' THEN 'payable' END direction,b.order_id,b.order_no_snapshot,b.customer_id,b.customer_name_snapshot,b.customer_email_snapshot,b.amount,b.settled_amount,b.status,b.order_created_at,b.settled_at,o.order_status,o.payment_status FROM billing_entry b JOIN sales_order o ON o.id=b.order_id AND o.merchant_id=b.merchant_id WHERE b.merchant_id=:merchantId AND o.audit_status='approved' AND o.order_status<>'cancelled'" + f + " ORDER BY b.order_created_at DESC,b.id DESC LIMIT 10 OFFSET :offset", billParams(filter).addValue("merchantId",merchantId).addValue("offset", (page - 1) * 10), (rs, row) -> {
            Timestamp settled = rs.getTimestamp("settled_at");
            return new Bill(rs.getLong("id"), rs.getString("bill_no"), rs.getString("bill_type"), rs.getString("direction"), rs.getLong("order_id"), rs.getString("order_no_snapshot"), rs.getLong("customer_id"), rs.getString("customer_name_snapshot"), rs.getString("customer_email_snapshot"), rs.getBigDecimal("amount"), rs.getBigDecimal("settled_amount"), rs.getString("status"), rs.getTimestamp("order_created_at").toInstant(), settled == null ? null : settled.toInstant(), rs.getString("order_status"), rs.getString("payment_status"));
        });
    }

    @Override
    public long billCount(long merchantId, String type, String status) {
        return billCount(merchantId, new BillFilter(type, status, null, null, null));
    }

    @Override
    public long billCount(long merchantId, BillFilter filter) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM billing_entry b JOIN sales_order o ON o.id=b.order_id AND o.merchant_id=b.merchant_id WHERE b.merchant_id=:merchantId AND o.audit_status='approved' AND o.order_status<>'cancelled'" + billFilters(filter), billParams(filter).addValue("merchantId",merchantId), Long.class);
    }

    @Override
    public BillSummary billSummary(long merchantId) {
        String sql = "SELECT COALESCE(SUM(o.order_status<>'cancelled'),0) AS total_bills," +
                "COALESCE(SUM(b.bill_type='order_receivable' AND o.order_status<>'cancelled'),0) AS order_bills," +
                "COALESCE(SUM(b.bill_type='order_receivable' AND o.order_status<>'cancelled' AND o.payment_status IN ('unpaid','pending_verification','partially_paid')),0) AS pending_orders," +
                "COALESCE(SUM(CASE WHEN b.bill_type='order_receivable' AND o.order_status<>'cancelled' THEN o.total_amount ELSE 0 END),0) AS order_amount," +
                "COALESCE(SUM(CASE WHEN b.bill_type='order_receivable' AND o.order_status<>'cancelled' THEN b.settled_amount ELSE 0 END),0) AS received_amount," +
                "COALESCE(SUM(CASE WHEN b.bill_type='order_receivable' AND o.order_status<>'cancelled' AND o.payment_status IN ('unpaid','pending_verification','partially_paid') THEN o.total_amount-b.settled_amount ELSE 0 END),0) AS pending_receivable_amount," +
                "COALESCE(SUM(b.bill_type='commission_payable' AND b.status<>'void'),0) AS commission_bills," +
                "COALESCE(SUM(b.bill_type='commission_payable' AND b.status='payable'),0) AS payable_commission_bills," +
                "COALESCE(SUM(CASE WHEN b.bill_type='commission_payable' AND b.status<>'void' THEN b.amount ELSE 0 END),0) AS commission_amount," +
                "COALESCE(SUM(CASE WHEN b.bill_type='commission_payable' AND b.status='settled' THEN b.settled_amount ELSE 0 END),0) AS settled_commission_amount," +
                "COALESCE(SUM(CASE WHEN b.bill_type='commission_payable' AND b.status='payable' THEN b.amount ELSE 0 END),0) AS payable_commission_amount," +
                "COALESCE(SUM(CASE WHEN b.bill_type='commission_payable' AND b.status='estimated' THEN b.amount ELSE 0 END),0) AS estimated_commission_amount " +
                "FROM billing_entry b JOIN sales_order o ON o.id=b.order_id AND o.merchant_id=b.merchant_id WHERE b.merchant_id=:merchantId AND o.audit_status='approved'";
        return jdbc.queryForObject(sql, new MapSqlParameterSource().addValue("merchantId", merchantId), (rs, row) -> new BillSummary(
                rs.getLong("total_bills"), rs.getLong("order_bills"), rs.getLong("pending_orders"),
                rs.getBigDecimal("order_amount"), rs.getBigDecimal("received_amount"), rs.getBigDecimal("pending_receivable_amount"),
                rs.getLong("commission_bills"), rs.getLong("payable_commission_bills"), rs.getBigDecimal("commission_amount"),
                rs.getBigDecimal("settled_commission_amount"), rs.getBigDecimal("payable_commission_amount"), rs.getBigDecimal("estimated_commission_amount")));
    }

    @Override
    public Optional<Bill> bill(long merchantId, long id) {
        return jdbc.query("SELECT b.id,b.bill_no,b.bill_type,CASE b.bill_type WHEN 'order_receivable' THEN 'receivable' WHEN 'commission_payable' THEN 'payable' END direction,b.order_id,b.order_no_snapshot,b.customer_id,b.customer_name_snapshot,b.customer_email_snapshot,b.amount,b.settled_amount,b.status,b.order_created_at,b.settled_at,o.order_status,o.payment_status FROM billing_entry b JOIN sales_order o ON o.id=b.order_id AND o.merchant_id=b.merchant_id WHERE b.merchant_id=:merchantId AND b.id=:id AND o.audit_status='approved'", new MapSqlParameterSource("id", id).addValue("merchantId", merchantId), rows -> {
            if (!rows.next()) return Optional.empty();
            Timestamp settled = rows.getTimestamp("settled_at");
            return Optional.of(new Bill(rows.getLong("id"), rows.getString("bill_no"), rows.getString("bill_type"), rows.getString("direction"), rows.getLong("order_id"), rows.getString("order_no_snapshot"), rows.getLong("customer_id"), rows.getString("customer_name_snapshot"), rows.getString("customer_email_snapshot"), rows.getBigDecimal("amount"), rows.getBigDecimal("settled_amount"), rows.getString("status"), rows.getTimestamp("order_created_at").toInstant(), settled == null ? null : settled.toInstant(), rows.getString("order_status"), rows.getString("payment_status")));
        });
    }

    private String billFilters(BillFilter filter) {
        if (filter == null) return "";
        return (filter.type() == null || filter.type().isBlank() ? "" : " AND b.bill_type=:type")
                + (filter.status() == null || filter.status().isBlank() ? ""
                    : "order_receivable".equals(filter.type()) ? " AND o.payment_status=:status" : " AND b.status=:status")
                + (filter.from() == null ? "" : " AND b.order_created_at>=:from")
                + (filter.to() == null ? "" : " AND b.order_created_at<:to")
                + (filter.query() == null || filter.query().isBlank() ? "" : " AND (b.bill_no LIKE :query OR b.order_no_snapshot LIKE :query OR b.customer_name_snapshot LIKE :query OR b.customer_email_snapshot LIKE :query)");
    }

    private MapSqlParameterSource billParams(BillFilter filter) {
        BillFilter value = filter == null ? new BillFilter(null, null, null, null, null) : filter;
        return new MapSqlParameterSource().addValue("type", value.type()).addValue("status", value.status())
                .addValue("from", value.from() == null ? null : Timestamp.from(value.from()))
                .addValue("to", value.to() == null ? null : Timestamp.from(value.to()))
                .addValue("query", value.query() == null ? null : "%" + value.query().trim() + "%");
    }

    private MapSqlParameterSource params(Long referrerId, String status) {
        return new MapSqlParameterSource().addValue("referrerId", referrerId).addValue("status", status);
    }

    private String accrualFilters(AccrualFilter filter) {
        if (filter == null) return "";
        return (filter.status() == null || filter.status().isBlank() ? "" : " AND a.status=:status")
                + (filter.from() == null ? "" : " AND o.created_at>=:from")
                + (filter.to() == null ? "" : " AND o.created_at<:to")
                + (filter.query() == null || filter.query().isBlank() ? "" : " AND (o.order_no LIKE :query OR a.referrer_name_snapshot LIKE :query OR a.referred_name_snapshot LIKE :query)");
    }

    private MapSqlParameterSource accrualParams(AccrualFilter filter) {
        AccrualFilter value = filter == null ? new AccrualFilter(null, null, null, null) : filter;
        return new MapSqlParameterSource()
                .addValue("status", value.status())
                .addValue("from", value.from() == null ? null : Timestamp.from(value.from()))
                .addValue("to", value.to() == null ? null : Timestamp.from(value.to()))
                .addValue("query", value.query() == null ? null : "%" + value.query().trim() + "%");
    }

    private Accrual accrual(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Accrual(rs.getLong("id"), rs.getLong("order_id"), rs.getString("order_no"), rs.getLong("referrer_id"), rs.getString("referrer_name_snapshot"), rs.getString("referred_name_snapshot"), rs.getString("status"), rs.getBigDecimal("rate"), rs.getBigDecimal("base_amount"), rs.getBigDecimal("commission_amount"), rs.getString("order_status"), rs.getString("payment_status"), rs.getTimestamp("created_at").toInstant());
    }
}

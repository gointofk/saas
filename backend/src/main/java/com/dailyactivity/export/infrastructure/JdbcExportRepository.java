package com.dailyactivity.export.infrastructure;

import com.dailyactivity.export.domain.port.ExportRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcExportRepository implements ExportRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcExportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Long> matchingIds(long merchantId, String type, Filter filter, int limit) {
        return switch (type) {
            case "orders" -> orderIds(merchantId, filter, limit);
            case "commissions" -> commissionIds(merchantId, filter, limit);
            case "bills" -> billIds(merchantId, filter, limit);
            default -> throw new IllegalArgumentException("Unsupported export type: " + type);
        };
    }

    @Override
    public long matchingRecordCount(long merchantId, String type, Filter filter) {
        String sql = switch (type) {
            case "orders" -> "SELECT COUNT(*) FROM sales_order o WHERE o.merchant_id=:merchantId AND 1=1" + orderFilters(filter);
            case "commissions" -> "SELECT COUNT(*) FROM commission_accrual a JOIN sales_order o ON o.id=a.order_id WHERE o.merchant_id=:merchantId AND o.audit_status='approved' AND o.order_status<>'cancelled'" + commissionFilters(filter);
            case "bills" -> "SELECT COUNT(*) FROM billing_entry b JOIN sales_order o ON o.id=b.order_id AND o.merchant_id=b.merchant_id WHERE b.merchant_id=:merchantId AND o.audit_status='approved'" + billFilters(filter);
            default -> throw new IllegalArgumentException("Unsupported export type: " + type);
        };
        Long count = jdbc.queryForObject(sql, countParams(merchantId, type, filter), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public long outputRowCount(long merchantId, String type, Filter filter) {
        String sql = switch (type) {
            case "orders" -> "SELECT COUNT(*) FROM sales_order o WHERE o.merchant_id=:merchantId AND 1=1" + orderFilters(filter);
            case "commissions" -> "SELECT COUNT(*) FROM commission_accrual a JOIN sales_order o ON o.id=a.order_id WHERE o.merchant_id=:merchantId AND o.audit_status='approved' AND o.order_status<>'cancelled'" + commissionFilters(filter);
            case "bills" -> "SELECT COUNT(*) FROM billing_entry b JOIN sales_order o ON o.id=b.order_id AND o.merchant_id=b.merchant_id WHERE b.merchant_id=:merchantId AND o.audit_status='approved'" + billFilters(filter);
            default -> throw new IllegalArgumentException("Unsupported export type: " + type);
        };
        Long count = jdbc.queryForObject(sql, countParams(merchantId, type, filter), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<Map<String, Object>> rows(long merchantId, String type, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return switch (type) {
            case "orders" -> orderRows(merchantId, ids);
            case "commissions" -> commissionRows(merchantId, ids);
            case "bills" -> billRows(merchantId, ids);
            default -> throw new IllegalArgumentException("Unsupported export type: " + type);
        };
    }

    @Override
    public int outputRowCount(long merchantId, String type, List<Long> ids) {
        if (ids.isEmpty()) return 0;
        String sql = switch (type) {
            case "orders" -> "SELECT COUNT(*) FROM sales_order WHERE merchant_id=:merchantId AND id IN (:ids)";
            case "commissions" -> "SELECT COUNT(*) FROM commission_accrual WHERE merchant_id=:merchantId AND id IN (:ids)";
            case "bills" -> "SELECT COUNT(*) FROM billing_entry WHERE merchant_id=:merchantId AND id IN (:ids)";
            default -> throw new IllegalArgumentException("Unsupported export type: " + type);
        };
        Integer count = jdbc.queryForObject(sql, new MapSqlParameterSource("ids", ids).addValue("merchantId", merchantId), Integer.class);
        return count == null ? 0 : count;
    }

    private List<Long> orderIds(long merchantId, Filter filter, int limit) {
        String sql = "SELECT o.id FROM sales_order o WHERE o.merchant_id=:merchantId AND 1=1" + orderFilters(filter)
                + " ORDER BY o.created_at DESC,o.id DESC LIMIT :limit";
        return jdbc.query(sql, orderParams(merchantId, filter).addValue("limit", limit), (rs, row) -> rs.getLong("id"));
    }

    private List<Long> commissionIds(long merchantId, Filter filter, int limit) {
        String sql = "SELECT a.id FROM commission_accrual a JOIN sales_order o ON o.id=a.order_id WHERE o.merchant_id=:merchantId AND o.audit_status='approved' AND o.order_status<>'cancelled'"
                + commissionFilters(filter) + " ORDER BY o.created_at DESC,a.id DESC LIMIT :limit";
        return jdbc.query(sql, commissionParams(merchantId, filter).addValue("limit", limit), (rs, row) -> rs.getLong("id"));
    }

    private List<Long> billIds(long merchantId, Filter filter, int limit) {
        String sql = "SELECT b.id FROM billing_entry b JOIN sales_order o ON o.id=b.order_id AND o.merchant_id=b.merchant_id WHERE b.merchant_id=:merchantId AND o.audit_status='approved'"
                + billFilters(filter) + " ORDER BY b.order_created_at DESC,b.id DESC LIMIT :limit";
        return jdbc.query(sql, billParams(merchantId, filter).addValue("limit", limit), (rs, row) -> rs.getLong("id"));
    }

    private List<Map<String, Object>> orderRows(long merchantId, List<Long> ids) {
        String sql = "SELECT o.id,o.order_no,o.created_at,o.customer_name_snapshot,o.customer_email_snapshot,o.subtotal,"
                + "o.shipping_fee,o.discount_amount,o.total_amount,o.payment_status,o.order_status,o.buyer_note,COALESCE((SELECT SUM(pr.received_amount) FROM payment_receipt pr WHERE pr.order_id=o.id),0) paid_amount "
                + "FROM sales_order o WHERE o.merchant_id=:merchantId AND o.id IN (:ids) ORDER BY o.created_at DESC,o.id DESC";
        var rows = jdbc.query(sql, new MapSqlParameterSource("ids", ids).addValue("merchantId", merchantId), (rs, row) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("orderId", rs.getLong("id"));
            value.put("orderNo", rs.getString("order_no"));
            value.put("createdAt", rs.getTimestamp("created_at").toInstant());
            value.put("customerName", rs.getString("customer_name_snapshot"));
            value.put("email", rs.getString("customer_email_snapshot"));
            value.put("subtotal", rs.getBigDecimal("subtotal"));
            value.put("shippingFee", rs.getBigDecimal("shipping_fee"));
            value.put("discountAmount", rs.getBigDecimal("discount_amount"));
            value.put("totalAmount", rs.getBigDecimal("total_amount"));
            value.put("paidAmount", rs.getBigDecimal("paid_amount"));
            value.put("remainingAmount", "cancelled".equals(rs.getString("order_status")) ? BigDecimal.ZERO : rs.getBigDecimal("total_amount").subtract(rs.getBigDecimal("paid_amount")));
            value.put("paymentStatus", rs.getString("payment_status"));
            value.put("orderStatus", rs.getString("order_status"));
            value.put("buyerNote", rs.getString("buyer_note"));
            return value;
        });
        var parcelRows = jdbc.query("SELECT order_id,tracking_no FROM order_parcel WHERE order_id IN (SELECT id FROM sales_order WHERE merchant_id=:merchantId) AND order_id IN (:ids) AND status<>'repacked' ORDER BY order_id,parcel_no,id",
                new MapSqlParameterSource("ids", ids).addValue("merchantId", merchantId), (rs, n) -> new Object[]{rs.getLong("order_id"), rs.getString("tracking_no")});
        Map<Long, java.util.List<String>> tracking = new java.util.HashMap<>();
        Map<Long, Integer> counts = new java.util.HashMap<>();
        for (var parcel : parcelRows) {
            long orderId = (Long) parcel[0];
            counts.merge(orderId, 1, Integer::sum);
            if (parcel[1] != null) tracking.computeIfAbsent(orderId, ignored -> new java.util.ArrayList<>()).add((String) parcel[1]);
        }
        for (var row : rows) {
            long orderId = (Long) row.remove("orderId");
            row.put("trackingNo", String.join("\n", tracking.getOrDefault(orderId, java.util.List.of())));
            row.put("parcelCount", counts.getOrDefault(orderId, 0));
        }
        return rows;
    }

    private List<Map<String, Object>> commissionRows(long merchantId, List<Long> ids) {
        String sql = "SELECT a.id,a.order_id,o.order_no,a.base_amount,o.order_status,o.created_at,"
                + "a.referrer_name_snapshot,a.referred_name_snapshot,a.rate,a.commission_amount,a.status "
                + "FROM commission_accrual a JOIN sales_order o ON o.id=a.order_id "
                + "WHERE a.id IN (:ids) ORDER BY o.created_at DESC,a.id DESC";
        return jdbc.query(sql, new MapSqlParameterSource("ids", ids).addValue("merchantId", merchantId), (rs, row) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("orderNo", rs.getString("order_no"));
            value.put("referrer", rs.getString("referrer_name_snapshot"));
            value.put("customer", rs.getString("referred_name_snapshot"));
            value.put("baseAmount", rs.getBigDecimal("base_amount"));
            value.put("rate", rs.getBigDecimal("rate"));
            value.put("commissionAmount", rs.getBigDecimal("commission_amount"));
            value.put("orderStatus", rs.getString("order_status"));
            value.put("commissionStatus", rs.getString("status"));
            value.put("orderCreatedAt", rs.getTimestamp("created_at").toInstant());
            return value;
        });
    }

    private List<Map<String, Object>> billRows(long merchantId, List<Long> ids) {
        String sql = "SELECT b.bill_no,b.bill_type,CASE b.bill_type WHEN 'order_receivable' THEN 'receivable' WHEN 'commission_payable' THEN 'payable' END direction,b.order_created_at,b.order_no_snapshot,"
                + "b.customer_name_snapshot,b.customer_email_snapshot,b.amount,b.settled_amount,b.status,o.order_status "
                + "FROM billing_entry b JOIN sales_order o ON o.id=b.order_id AND o.merchant_id=b.merchant_id "
                + "WHERE b.merchant_id=:merchantId AND b.id IN (:ids) ORDER BY b.order_created_at DESC,b.id DESC";
        return jdbc.query(sql, new MapSqlParameterSource("ids", ids).addValue("merchantId", merchantId), (rs, row) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("billNo", rs.getString("bill_no"));
            value.put("billType", rs.getString("bill_type"));
            value.put("direction", rs.getString("direction"));
            value.put("orderCreatedAt", rs.getTimestamp("order_created_at").toInstant());
            value.put("orderNo", rs.getString("order_no_snapshot"));
            value.put("customer", rs.getString("customer_name_snapshot"));
            value.put("email", rs.getString("customer_email_snapshot"));
            value.put("amount", rs.getBigDecimal("amount"));
            value.put("settledAmount", rs.getBigDecimal("settled_amount"));
            value.put("remainingAmount", "void".equals(rs.getString("status")) ? BigDecimal.ZERO : rs.getBigDecimal("amount").subtract(rs.getBigDecimal("settled_amount")));
            value.put("status", rs.getString("status"));
            value.put("orderStatus", rs.getString("order_status"));
            return value;
        });
    }

    private String orderFilters(Filter filter) {
        return " AND o.audit_status='approved'" + statusFilter("o.order_status", filter.status())
                + equalsFilter("o.payment_status", "paymentStatus", filter.paymentStatus())
                + dateFilters("o.created_at", filter)
                + queryFilter("o.order_no LIKE :query OR o.customer_name_snapshot LIKE :query OR o.customer_email_snapshot LIKE :query OR EXISTS (SELECT 1 FROM order_parcel op WHERE op.order_id=o.id AND op.status<>'repacked' AND op.tracking_no LIKE :query) OR EXISTS (SELECT 1 FROM order_item oi WHERE oi.order_id=o.id AND oi.deleted=0 AND (oi.sku_snapshot LIKE :query OR oi.name_snapshot LIKE :query))", filter.query());
    }

    private String commissionFilters(Filter filter) {
        return equalsFilter("a.status", "status", filter.status())
                + dateFilters("o.created_at", filter)
                + queryFilter("o.order_no LIKE :query OR a.referrer_name_snapshot LIKE :query OR a.referred_name_snapshot LIKE :query", filter.query());
    }

    private String billFilters(Filter filter) {
        return equalsFilter("b.bill_type", "billType", filter.billType())
                + equalsFilter("b.status", "status", filter.status())
                + dateFilters("b.order_created_at", filter)
                + queryFilter("b.bill_no LIKE :query OR b.order_no_snapshot LIKE :query OR b.customer_name_snapshot LIKE :query OR b.customer_email_snapshot LIKE :query", filter.query());
    }

    private MapSqlParameterSource orderParams(long merchantId, Filter filter) {
        return baseParams(merchantId, filter).addValue("paymentStatus", filter.paymentStatus());
    }

    private MapSqlParameterSource countParams(long merchantId, String type, Filter filter) {
        return switch (type) {
            case "orders" -> orderParams(merchantId, filter);
            case "commissions" -> commissionParams(merchantId, filter);
            case "bills" -> billParams(merchantId, filter);
            default -> throw new IllegalArgumentException("Unsupported export type: " + type);
        };
    }

    private MapSqlParameterSource commissionParams(long merchantId, Filter filter) {
        return baseParams(merchantId, filter);
    }

    private MapSqlParameterSource billParams(long merchantId, Filter filter) {
        return baseParams(merchantId, filter).addValue("billType", filter.billType());
    }

    private MapSqlParameterSource baseParams(long merchantId, Filter filter) {
        return new MapSqlParameterSource().addValue("merchantId", merchantId)
                .addValue("status", filter.status())
                .addValue("query", blankToNull(filter.query()) == null ? null : "%" + filter.query().trim() + "%")
                .addValue("from", filter.from() == null ? null : Timestamp.from(filter.from()))
                .addValue("to", filter.to() == null ? null : Timestamp.from(filter.to()));
    }

    private String equalsFilter(String column, String parameter, String value) {
        return value == null || value.isBlank() ? "" : " AND " + column + "=:" + parameter;
    }

    private String statusFilter(String column, String status) {
        if (status == null || status.isBlank()) return "";
        if ("not".equals(status)) return " AND " + column + " IN ('pending','packed')";
        if ("partial".equals(status) && "o.order_status".equals(column)) return " AND o.order_status IN ('pending','packed') AND EXISTS (SELECT 1 FROM order_parcel op WHERE op.order_id=o.id AND op.status IN ('shipped','received'))";
        if ("shipped".equals(status)) return " AND " + column + " IN ('shipped','received','completed')";
        return " AND " + column + "=:status";
    }

    private String dateFilters(String column, Filter filter) {
        return (filter.from() == null ? "" : " AND " + column + ">=:from")
                + (filter.to() == null ? "" : " AND " + column + "<:to");
    }

    private String queryFilter(String expression, String query) {
        return query == null || query.isBlank() ? "" : " AND (" + expression + ")";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

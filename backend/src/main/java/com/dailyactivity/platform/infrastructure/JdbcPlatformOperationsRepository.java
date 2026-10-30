package com.dailyactivity.platform.infrastructure;

import com.dailyactivity.platform.domain.port.PlatformOperationsRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcPlatformOperationsRepository implements PlatformOperationsRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPlatformOperationsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Object> overview(Instant from, Instant to) {
        Map<String, Object> result = jdbc.queryForMap("SELECT "
                + "(SELECT COUNT(*) FROM merchant) merchant_count,"
                + "(SELECT COUNT(*) FROM merchant WHERE active=1) active_merchant_count,"
                + "(SELECT COUNT(*) FROM `user` WHERE deleted=0) buyer_count,"
                + "(SELECT COUNT(*) FROM `user` WHERE deleted=0 AND active=1) active_buyer_count,"
                + "(SELECT COUNT(*) FROM sales_order) order_count,"
                + "(SELECT COALESCE(SUM(total_amount),0) FROM sales_order WHERE order_status<>'cancelled') gross_order_amount,"
                + "(SELECT COALESCE(SUM(pr.received_amount),0) FROM payment_receipt pr JOIN sales_order o ON o.id=pr.order_id WHERE o.order_status<>'cancelled') received_amount,"
                + "(SELECT COUNT(*) FROM sales_order WHERE audit_status='pending' AND order_status<>'cancelled') pending_audit_count,"
                + "(SELECT COUNT(*) FROM sales_order WHERE payment_status='pending_verification' AND order_status<>'cancelled') pending_verification_count,"
                + "(SELECT COUNT(*) FROM sales_order WHERE audit_status='approved' AND order_status IN ('pending','packed')) pending_fulfillment_count",
                new MapSqlParameterSource());
        result.put("trend", jdbc.query("SELECT DATE_FORMAT(created_at,'%Y-%m-%d') day,COUNT(*) order_count,"
                        + "COALESCE(SUM(CASE WHEN order_status<>'cancelled' THEN total_amount ELSE 0 END),0) order_amount "
                + "FROM sales_order WHERE created_at>=:from AND created_at<:to GROUP BY DATE_FORMAT(created_at,'%Y-%m-%d') ORDER BY day",
                new MapSqlParameterSource("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)), this::mapRow));
        return result;
    }

    @Override
    public List<Map<String, Object>> orderPage(OrderFilter filter, int page) {
        return jdbc.query(orderSelect() + orderWhere(filter) + " ORDER BY o.created_at DESC,o.id DESC LIMIT 10 OFFSET :offset",
                orderParams(filter).addValue("offset", (page - 1) * 10), this::orderRow);
    }

    @Override
    public long orderCount(OrderFilter filter) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM sales_order o JOIN merchant m ON m.id=o.merchant_id " + orderWhere(filter), orderParams(filter), Long.class);
        return count == null ? 0 : count;
    }

    private String orderSelect() {
        return "SELECT o.id,o.merchant_id,m.name merchant_name,m.code merchant_code,o.order_no,o.customer_id,"
                + "o.customer_code_snapshot,o.customer_name_snapshot,o.customer_email_snapshot,o.order_status,o.payment_status,o.audit_status,"
                + "o.subtotal,o.shipping_fee,o.discount_amount,o.total_amount,COALESCE((SELECT SUM(pr.received_amount) FROM payment_receipt pr WHERE pr.order_id=o.id),0) paid_amount,"
                + "o.created_at,o.updated_at,o.city,o.recipient_name "
                + "FROM sales_order o JOIN merchant m ON m.id=o.merchant_id ";
    }

    private String orderWhere(OrderFilter f) {
        StringBuilder where = new StringBuilder("WHERE 1=1");
        if (f.merchantId() != null) where.append(" AND o.merchant_id=:merchantId");
        if (f.orderStatus() != null) where.append(" AND o.order_status=:orderStatus");
        if (f.paymentStatus() != null) where.append(" AND o.payment_status=:paymentStatus");
        if (f.auditStatus() != null) where.append(" AND o.audit_status=:auditStatus");
        if (f.from() != null) where.append(" AND o.created_at>=:from");
        if (f.to() != null) where.append(" AND o.created_at<:to");
        if (f.query() != null) where.append(" AND (o.order_no LIKE :query OR o.customer_name_snapshot LIKE :query OR o.customer_email_snapshot LIKE :query OR m.name LIKE :query OR m.code LIKE :query)");
        return where.toString();
    }

    private MapSqlParameterSource orderParams(OrderFilter f) {
        return new MapSqlParameterSource().addValue("merchantId", f.merchantId()).addValue("orderStatus", f.orderStatus())
                .addValue("paymentStatus", f.paymentStatus()).addValue("auditStatus", f.auditStatus())
                .addValue("from", f.from() == null ? null : Timestamp.from(f.from()))
                .addValue("to", f.to() == null ? null : Timestamp.from(f.to()))
                .addValue("query", f.query() == null ? null : "%" + f.query() + "%");
    }

    @Override
    public Optional<Map<String, Object>> orderDetail(long merchantId, long orderId) {
        List<Map<String, Object>> rows = jdbc.query(orderSelect() + "WHERE o.merchant_id=:merchantId AND o.id=:orderId",
                new MapSqlParameterSource("merchantId", merchantId).addValue("orderId", orderId), this::orderRow);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> detail = rows.get(0);
        detail.put("items", jdbc.query("SELECT i.sku_snapshot sku,i.name_snapshot name,i.type_snapshot product_type,i.quantity,i.unit_price,i.line_amount "
                        + "FROM order_item i JOIN sales_order scoped_order ON scoped_order.id=i.order_id AND scoped_order.merchant_id=:merchantId "
                        + "WHERE i.order_id=:orderId AND i.deleted=0 ORDER BY i.sort_no,i.id",
                new MapSqlParameterSource("merchantId", merchantId).addValue("orderId", orderId), this::mapRow));
        detail.put("payments", jdbc.query("SELECT kind,channel_snapshot,transaction_no,amount,status,occurred_at FROM ("
                        + "SELECT 'submission' kind,ps.channel_snapshot,ps.original_transaction_no transaction_no,ps.declared_amount amount,ps.status,ps.submitted_at occurred_at "
                        + "FROM payment_submission ps JOIN sales_order scoped_order ON scoped_order.id=ps.order_id AND scoped_order.merchant_id=:merchantId WHERE ps.order_id=:orderId "
                        + "UNION ALL SELECT 'receipt' kind,pr.channel_snapshot,pr.verified_transaction_no transaction_no,pr.received_amount amount,'confirmed' status,pr.verified_at occurred_at "
                        + "FROM payment_receipt pr JOIN sales_order scoped_order ON scoped_order.id=pr.order_id AND scoped_order.merchant_id=:merchantId WHERE pr.order_id=:orderId) p ORDER BY occurred_at",
                new MapSqlParameterSource("merchantId", merchantId).addValue("orderId", orderId), this::mapRow));
        detail.put("events", jdbc.query("SELECT e.action,e.from_status,e.to_status,e.note,e.occurred_at,e.actor_type,"
                        + "COALESCE(a.name,s.name,u.name,CONCAT(e.actor_type,' #',e.actor_user_id)) actor_name "
                        + "FROM order_event e JOIN sales_order scoped_order ON scoped_order.id=e.order_id AND scoped_order.merchant_id=:merchantId "
                        + "LEFT JOIN admin a ON e.actor_type='platform' AND a.id=e.actor_user_id "
                        + "LEFT JOIN seller s ON e.actor_type='seller' AND s.id=e.actor_user_id "
                        + "LEFT JOIN `user` u ON e.actor_type='customer' AND u.id=e.actor_user_id "
                        + "WHERE e.order_id=:orderId ORDER BY e.occurred_at,e.id",
                new MapSqlParameterSource("merchantId", merchantId).addValue("orderId", orderId), this::mapRow));
        return Optional.of(detail);
    }

    @Override
    public Optional<Map<String, Object>> orderContact(long merchantId, long orderId) {
        List<Map<String, Object>> rows = jdbc.query("SELECT merchant_id,order_no,customer_name_snapshot customer_name,customer_email_snapshot email,"
                        + "recipient_name,city,address_snapshot FROM sales_order WHERE merchant_id=:merchantId AND id=:orderId",
                new MapSqlParameterSource("merchantId", merchantId).addValue("orderId", orderId), this::mapRow);
        return rows.stream().findFirst();
    }

    @Override
    public List<Map<String, Object>> customerPage(CustomerFilter filter, int page) {
        return jdbc.query(customerSelect() + customerWhere(filter) + " ORDER BY u.created_at DESC,u.id DESC LIMIT 10 OFFSET :offset",
                customerParams(filter).addValue("offset", (page - 1) * 10), this::mapRow);
    }

    @Override
    public long customerCount(CustomerFilter filter) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM `user` u JOIN merchant m ON m.id=u.merchant_id " + customerWhere(filter), customerParams(filter), Long.class);
        return count == null ? 0 : count;
    }

    private String customerSelect() {
        return "SELECT u.id,u.merchant_id,m.name merchant_name,m.code merchant_code,u.code customer_code,u.name customer_name,u.email,"
                + "u.customer_type,u.price_tier,u.active,u.created_at,u.last_login_at,"
                + "(SELECT COUNT(*) FROM sales_order o WHERE o.merchant_id=u.merchant_id AND o.customer_id=u.id) order_count,"
                + "(SELECT COALESCE(SUM(o.total_amount),0) FROM sales_order o WHERE o.merchant_id=u.merchant_id AND o.customer_id=u.id AND o.order_status<>'cancelled') order_amount,"
                + "(SELECT COUNT(*) FROM sales_order o WHERE o.merchant_id=u.merchant_id AND o.customer_id=u.id AND o.order_status<>'cancelled' AND o.payment_status IN ('unpaid','pending_verification','partially_paid')) open_payment_count "
                + "FROM `user` u JOIN merchant m ON m.id=u.merchant_id ";
    }

    private String customerWhere(CustomerFilter f) {
        StringBuilder where = new StringBuilder("WHERE u.deleted=0");
        if (f.merchantId() != null) where.append(" AND u.merchant_id=:merchantId");
        if (f.active() != null) where.append(" AND u.active=:active");
        if (f.from() != null) where.append(" AND u.created_at>=:from");
        if (f.to() != null) where.append(" AND u.created_at<:to");
        if (f.query() != null) where.append(" AND (u.name LIKE :query OR u.email LIKE :query OR u.code LIKE :query OR m.name LIKE :query OR m.code LIKE :query)");
        return where.toString();
    }

    private MapSqlParameterSource customerParams(CustomerFilter f) {
        return new MapSqlParameterSource().addValue("merchantId", f.merchantId()).addValue("active", f.active())
                .addValue("from", f.from() == null ? null : Timestamp.from(f.from()))
                .addValue("to", f.to() == null ? null : Timestamp.from(f.to()))
                .addValue("query", f.query() == null ? null : "%" + f.query() + "%");
    }

    @Override
    public Optional<Map<String, Object>> customerDetail(long merchantId, long customerId) {
        List<Map<String, Object>> rows = jdbc.query(customerSelect() + "WHERE u.deleted=0 AND u.merchant_id=:merchantId AND u.id=:customerId",
                new MapSqlParameterSource("merchantId", merchantId).addValue("customerId", customerId), this::mapRow);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> detail = rows.get(0);
        detail.put("recentOrders", jdbc.query("SELECT order_no,order_status,payment_status,audit_status,total_amount,created_at "
                        + "FROM sales_order WHERE merchant_id=:merchantId AND customer_id=:customerId ORDER BY created_at DESC,id DESC LIMIT 10",
                new MapSqlParameterSource("merchantId", merchantId).addValue("customerId", customerId), this::mapRow));
        return Optional.of(detail);
    }

    @Override
    public Optional<Map<String, Object>> customerContact(long merchantId, long customerId) {
        List<Map<String, Object>> rows = jdbc.query("SELECT u.merchant_id,u.id,u.code customer_code,u.name customer_name,u.email,u.active,u.email_verified_at,u.last_login_at,u.created_at "
                        + "FROM `user` u WHERE u.deleted=0 AND u.merchant_id=:merchantId AND u.id=:customerId",
                new MapSqlParameterSource("merchantId", merchantId).addValue("customerId", customerId), this::mapRow);
        return rows.stream().findFirst();
    }

    @Override
    public List<Map<String, Object>> merchantPerformance(Instant from, Instant to, int page, int limit) {
        return jdbc.query(performanceSql() + " ORDER BY gross_amount DESC,m.id LIMIT :limit OFFSET :offset",
                performanceParams(from, to).addValue("limit", limit).addValue("offset", (page - 1) * limit), this::mapRow);
    }

    @Override
    public long merchantCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM merchant", new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    private String performanceSql() {
        return "SELECT m.id merchant_id,m.code merchant_code,m.name merchant_name,m.active,m.domain,"
                + "COALESCE(o.order_count,0) order_count,COALESCE(o.gross_amount,0) gross_amount,COALESCE(r.received_amount,0) received_amount,"
                + "COALESCE(o.open_order_count,0) open_order_count,COALESCE(c.buyer_count,0) buyer_count,COALESCE(c.new_buyer_count,0) new_buyer_count,"
                + "o.last_order_at FROM merchant m LEFT JOIN (SELECT so.merchant_id,COUNT(*) order_count,"
                + "SUM(CASE WHEN so.order_status<>'cancelled' THEN so.total_amount ELSE 0 END) gross_amount,"
                + "SUM(CASE WHEN so.audit_status='approved' AND so.order_status IN ('pending','packed') THEN 1 ELSE 0 END) open_order_count,MAX(so.created_at) last_order_at "
                + "FROM sales_order so WHERE so.created_at>=:from AND so.created_at<:to GROUP BY so.merchant_id) o ON o.merchant_id=m.id "
                + "LEFT JOIN (SELECT so.merchant_id,SUM(pr.received_amount) received_amount FROM payment_receipt pr "
                + "JOIN sales_order so ON so.id=pr.order_id WHERE pr.verified_at>=:from AND pr.verified_at<:to "
                + "AND so.order_status<>'cancelled' GROUP BY so.merchant_id) r ON r.merchant_id=m.id "
                + "LEFT JOIN (SELECT u.merchant_id,COUNT(*) buyer_count,SUM(CASE WHEN u.created_at>=:from AND u.created_at<:to THEN 1 ELSE 0 END) new_buyer_count "
                + "FROM `user` u WHERE u.deleted=0 GROUP BY u.merchant_id) c ON c.merchant_id=m.id";
    }

    private MapSqlParameterSource performanceParams(Instant from, Instant to) {
        return new MapSqlParameterSource("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
    }

    @Override
    public List<Map<String, Object>> alertPage(String type, Long merchantId, int page) {
        return jdbc.query(orderSelect() + alertWhere(type, merchantId) + " ORDER BY o.created_at ASC,o.id ASC LIMIT 10 OFFSET :offset",
                new MapSqlParameterSource("merchantId", merchantId).addValue("offset", (page - 1) * 10), this::orderRow);
    }

    @Override
    public long alertCount(String type, Long merchantId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM sales_order o JOIN merchant m ON m.id=o.merchant_id " + alertWhere(type, merchantId),
                new MapSqlParameterSource("merchantId", merchantId), Long.class);
        return count == null ? 0 : count;
    }

    private String alertWhere(String type, Long merchantId) {
        String state = switch (type) {
            case "audit" -> "o.audit_status='pending' AND o.order_status<>'cancelled'";
            case "payment" -> "o.payment_status='pending_verification' AND o.order_status<>'cancelled'";
            case "fulfillment" -> "o.audit_status='approved' AND o.order_status IN ('pending','packed')";
            default -> throw new IllegalArgumentException("Unknown platform task type");
        };
        return "WHERE " + state + (merchantId == null ? "" : " AND o.merchant_id=:merchantId");
    }

    @Override
    public List<Map<String, Object>> auditPage(int page) {
        String union = "SELECT x.source,x.audit_id,x.actor_name,x.merchant_id,x.merchant_name,x.resource_type,x.resource_id,x.action,x.purpose,x.created_at FROM ("
                + "SELECT 'data_access' source,e.id audit_id,COALESCE(a.name,CONCAT('platform #',e.actor_user_id)) actor_name,e.merchant_id,m.name merchant_name,e.resource_type,e.resource_id,e.access_type action,e.purpose,e.created_at "
                + "FROM platform_data_access_event e LEFT JOIN admin a ON a.id=e.actor_user_id LEFT JOIN merchant m ON m.id=e.merchant_id "
                + "UNION ALL SELECT 'merchant_change' source,e.id audit_id,COALESCE(a.name,s.name,CONCAT(e.actor_type,' #',e.actor_user_id)) actor_name,e.merchant_id,m.name merchant_name,'merchant' resource_type,e.merchant_id resource_id,e.action,e.detail purpose,e.created_at "
                + "FROM merchant_event e LEFT JOIN admin a ON e.actor_type='platform' AND a.id=e.actor_user_id LEFT JOIN seller s ON e.actor_type='seller' AND s.id=e.actor_user_id LEFT JOIN merchant m ON m.id=e.merchant_id) x";
        return jdbc.query(union + " ORDER BY created_at DESC,source,audit_id DESC LIMIT 10 OFFSET :offset", new MapSqlParameterSource("offset", (page - 1) * 10), this::mapRow);
    }

    @Override
    public long auditCount() {
        Long count = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM platform_data_access_event)+(SELECT COUNT(*) FROM merchant_event)", new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public void recordAccess(long platformAdminId, long merchantId, String resourceType, long resourceId, String accessType, String purpose) {
        jdbc.update("INSERT INTO platform_data_access_event(actor_user_id,merchant_id,resource_type,resource_id,access_type,purpose) "
                        + "VALUES(:actor,:merchant,:resourceType,:resourceId,:accessType,:purpose)",
                new MapSqlParameterSource("actor", platformAdminId).addValue("merchant", merchantId).addValue("resourceType", resourceType)
                        .addValue("resourceId", resourceId).addValue("accessType", accessType).addValue("purpose", purpose));
    }

    @Override
    public List<Map<String, Object>> exportOrders(OrderFilter filter, int limit) {
        return jdbc.query(orderSelect() + orderWhere(filter) + " ORDER BY o.created_at DESC,o.id DESC LIMIT :limit",
                orderParams(filter).addValue("limit", limit), this::orderRow);
    }

    @Override
    public List<Map<String, Object>> exportCustomers(CustomerFilter filter, int limit) {
        return jdbc.query(customerSelect() + customerWhere(filter) + " ORDER BY u.created_at DESC,u.id DESC LIMIT :limit",
                customerParams(filter).addValue("limit", limit), this::mapRow);
    }

    @Override
    public List<Map<String, Object>> exportMerchantPerformance(Instant from, Instant to, int limit) {
        return jdbc.query(performanceSql() + " ORDER BY gross_amount DESC,m.id LIMIT :limit",
                performanceParams(from, to).addValue("limit", limit), this::mapRow);
    }

    private Map<String, Object> orderRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Map<String, Object> value = mapRow(rs, row);
        value.put("buyerName", rs.getString("customer_name_snapshot"));
        value.put("email", rs.getString("customer_email_snapshot"));
        value.put("orderStatus", rs.getString("order_status"));
        value.put("paymentStatus", rs.getString("payment_status"));
        value.put("auditStatus", rs.getString("audit_status"));
        value.put("totalAmount", rs.getBigDecimal("total_amount"));
        value.put("paidAmount", rs.getBigDecimal("paid_amount"));
        value.put("createdAt", instant(rs, "created_at"));
        value.put("updatedAt", instant(rs, "updated_at"));
        return value;
    }

    private Map<String, Object> mapRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        var metadata = rs.getMetaData();
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            String label = camel(metadata.getColumnLabel(i));
            Object item = rs.getObject(i);
            if (item instanceof java.sql.Timestamp timestamp) item = timestamp.toInstant();
            if ("active".equals(label)) item = rs.getBoolean(i);
            value.put(label, item);
        }
        return value;
    }

    private String camel(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean upper = false;
        for (char c : value.toCharArray()) {
            if (c == '_') upper = true;
            else {
                result.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return result.toString();
    }

    private Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}

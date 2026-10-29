package com.dailyactivity.order.infrastructure;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.order.domain.OrderNumberCollisionException;
import com.dailyactivity.order.domain.port.OrderRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcOrderRepository implements OrderRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insertOrder(long merchantId, OrderCreate o) {
        KeyHolder h = new GeneratedKeyHolder();
        try {
            jdbc.update("INSERT INTO sales_order(merchant_id,order_no,quote_token_hash,customer_id,customer_code_snapshot,customer_name_snapshot,customer_email_snapshot,order_status,payment_status,currency,subtotal,shipping_fee,total_amount,address_snapshot,recipient_name,city,buyer_note,referrer_id_snapshot,referrer_name_snapshot,commission_rate_snapshot,status_changed_at,created_at,created_by_user_id,updated_by_user_id) VALUES(:merchantId,:orderNo,:quoteTokenHash,:customerId,:code,:name,:email,'pending','unpaid','USD',:subtotal,:shippingFee,:total,:addressSnapshot,:recipientName,:city,:buyerNote,:referrerId,:referrerName,:commissionRate,:now,:now,:actor,:actor)", new MapSqlParameterSource().addValue("merchantId", merchantId).addValue("orderNo", o.orderNo()).addValue("quoteTokenHash", o.quoteTokenHash()).addValue("customerId", o.customerId()).addValue("code", o.customerCode()).addValue("name", o.customerName()).addValue("email", o.customerEmail()).addValue("subtotal", o.subtotal()).addValue("shippingFee", o.shippingFee()).addValue("total", o.total()).addValue("addressSnapshot", o.addressSnapshot()).addValue("recipientName", o.recipientName()).addValue("city", o.city()).addValue("buyerNote", o.buyerNote()).addValue("referrerId", o.referrerId()).addValue("referrerName", o.referrerName()).addValue("commissionRate", o.commissionRate()).addValue("now", Timestamp.from(o.now())).addValue("actor", o.customerId()), h, new String[]{"id"});
        } catch (DuplicateKeyException e) {
            if (quoteAlreadyUsedForUpdate(o.quoteTokenHash()))
                throw ApiException.conflict("QUOTE_ALREADY_USED", "This checkout quote has already created an order.");
            if (orderNumberAlreadyUsedForUpdate(o.orderNo()))
                throw new OrderNumberCollisionException();
            throw e;
        }
        return h.getKey().longValue();
    }

    @Override
    public boolean quoteAlreadyUsed(String quoteTokenHash) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM sales_order WHERE quote_token_hash=:hash", new MapSqlParameterSource("hash", quoteTokenHash), Long.class);
        return count != null && count > 0;
    }

    private boolean quoteAlreadyUsedForUpdate(String quoteTokenHash) {
        return jdbc.query("SELECT id FROM sales_order WHERE quote_token_hash=:hash FOR UPDATE", new MapSqlParameterSource("hash", quoteTokenHash), rows -> {
            return rows.next();
        });
    }

    private boolean orderNumberAlreadyUsedForUpdate(String orderNo) {
        return jdbc.query("SELECT id FROM sales_order WHERE order_no=:orderNo FOR UPDATE", new MapSqlParameterSource("orderNo", orderNo), rows -> {
            return rows.next();
        });
    }

    @Override
    public void insertItem(long orderId, ItemCreate i, long actorId) {
        jdbc.update("INSERT INTO order_item(order_id,product_id,sku_snapshot,name_snapshot,type_snapshot,quantity,unit_price,line_amount,unit_cost_snapshot,price_source,price_tier_snapshot,sort_no,created_by_user_id,updated_by_user_id) VALUES(:orderId,:productId,:sku,:name,:type,:quantity,:price,:lineAmount,:cost,:source,:tier,:sortNo,:actor,:actor)", new MapSqlParameterSource().addValue("orderId", orderId).addValue("productId", i.productId()).addValue("sku", i.sku()).addValue("name", i.name()).addValue("type", i.productType()).addValue("quantity", i.quantity()).addValue("price", i.unitPrice()).addValue("lineAmount", i.unitPrice().multiply(BigDecimal.valueOf(i.quantity()))).addValue("cost", i.unitCost()).addValue("source", i.priceSource()).addValue("tier", i.tier()).addValue("sortNo", 0).addValue("actor", actorId));
    }

    @Override
    public void saveQuantityPrices(long orderId, long productId, List<com.dailyactivity.catalog.domain.QuantityPricing.Band> bands) {
        for (var band : bands) {
            jdbc.update("INSERT INTO order_item_quantity_price(order_item_id,min_quantity,max_quantity,unit_price) SELECT id,:min,:max,:price FROM order_item WHERE order_id=:orderId AND product_id=:productId AND deleted=0",
                    new MapSqlParameterSource("orderId", orderId).addValue("productId", productId)
                            .addValue("min", band.minQuantity()).addValue("max", band.maxQuantity()).addValue("price", band.unitPrice()));
        }
        if (!bands.isEmpty()) jdbc.update("UPDATE order_item SET price_source='quantity_price' WHERE order_id=:orderId AND product_id=:productId AND deleted=0 AND price_source<>'manual'",
                new MapSqlParameterSource("orderId", orderId).addValue("productId", productId));
    }

    @Override
    public List<com.dailyactivity.catalog.domain.QuantityPricing.Band> quantityPrices(long itemId) {
        return jdbc.query("SELECT qp.min_quantity,qp.max_quantity,qp.unit_price FROM order_item_quantity_price qp JOIN order_item i ON i.id=qp.order_item_id WHERE i.id=:id AND i.deleted=0 AND i.price_source<>'manual' ORDER BY qp.min_quantity",
                new MapSqlParameterSource("id", itemId), (rs, row) -> new com.dailyactivity.catalog.domain.QuantityPricing.Band(
                        rs.getInt("min_quantity"), (Integer) rs.getObject("max_quantity"), rs.getBigDecimal("unit_price")));
    }

    @Override
    public void deleteCart(long customerId, List<Long> products) {
        if (products.isEmpty()) return;
        jdbc.update("DELETE FROM cart_item WHERE customer_id=:customerId AND product_id IN (:products)", new MapSqlParameterSource().addValue("customerId", customerId).addValue("products", products));
    }

    @Override
    public long addEvent(long orderId, String operationId, String action, String from, String to, String before, String after, String note, long actorId) {
        KeyHolder h = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO order_event(order_id,operation_id,action,from_status,to_status,before_data,after_data,note,occurred_at,actor_user_id) VALUES(:orderId,:operationId,:action,:from,:to,:before,:after,:note,UTC_TIMESTAMP(6),:actor)", new MapSqlParameterSource().addValue("orderId", orderId).addValue("operationId", operationId).addValue("action", action).addValue("from", from).addValue("to", to).addValue("before", before).addValue("after", after).addValue("note", note).addValue("actor", actorId), h, new String[]{"id"});
        return h.getKey().longValue();
    }

    @Override
    public void attachFiles(long orderId, long eventId, List<String> fileUuids, String stage) {
        int sort = 0;
        for (String fileUuid : fileUuids) {
            jdbc.update("INSERT INTO order_event_file(order_id,event_id,file_uuid,stage,sort_no) VALUES(:orderId,:eventId,:fileUuid,:stage,:sortNo)", new MapSqlParameterSource().addValue("orderId", orderId).addValue("eventId", eventId).addValue("fileUuid", fileUuid).addValue("stage", stage).addValue("sortNo", sort++));
        }
    }

    @Override
    public void createReceivable(OrderRow o, long actorId) {
        requireApproved(o);
        jdbc.update("INSERT INTO billing_entry(merchant_id,bill_no,bill_type,direction,order_id,order_no_snapshot,customer_id,customer_name_snapshot,customer_email_snapshot,currency,amount,settled_amount,status,order_created_at) VALUES((SELECT merchant_id FROM sales_order WHERE id=:orderId),:billNo,'order_receivable','receivable',:orderId,:orderNo,:customerId,:name,:email,'USD',:amount,0,:paymentStatus,:createdAt)", new MapSqlParameterSource().addValue("paymentStatus", o.paymentStatus()).addValue("billNo", "BILL-" + o.orderNo()).addValue("orderId", o.id()).addValue("orderNo", o.orderNo()).addValue("customerId", o.customerId()).addValue("name", o.customerName()).addValue("email", o.customerEmail()).addValue("amount", o.total()).addValue("createdAt", Timestamp.from(o.createdAt())));
    }

    @Override
    public void createAccrual(OrderRow o, long actorId) {
        requireApproved(o);
        if (o.referrerId() == null || o.commissionRate() == null || o.commissionRate().signum() <= 0) return;
        BigDecimal commissionBase = o.subtotal().subtract(o.discountAmount());
        BigDecimal amount = commissionBase.multiply(o.commissionRate()).movePointLeft(2).setScale(2, java.math.RoundingMode.HALF_UP);
        jdbc.update("INSERT INTO commission_accrual(merchant_id,order_id,referrer_id,referred_customer_id,referrer_name_snapshot,referred_name_snapshot,rate,base_amount,commission_amount,status,created_by_user_id,updated_by_user_id) VALUES((SELECT merchant_id FROM sales_order WHERE id=:orderId),:orderId,:referrerId,:customerId,:referrerName,:customerName,:rate,:base,:amount,'estimated',:actor,:actor)", new MapSqlParameterSource().addValue("orderId", o.id()).addValue("referrerId", o.referrerId()).addValue("customerId", o.customerId()).addValue("referrerName", o.referrerName()).addValue("customerName", o.customerName()).addValue("rate", o.commissionRate()).addValue("base", commissionBase).addValue("amount", amount).addValue("actor", actorId));
        jdbc.update("INSERT INTO billing_entry(merchant_id,bill_no,bill_type,direction,order_id,order_no_snapshot,customer_id,customer_name_snapshot,customer_email_snapshot,currency,amount,settled_amount,status,order_created_at) VALUES((SELECT merchant_id FROM sales_order WHERE id=:orderId),:billNo,'commission_payable','payable',:orderId,:orderNo,:referrerId,:referrerName,'', 'USD',:amount,0,'estimated',:createdAt)", new MapSqlParameterSource().addValue("billNo", "COMM-" + o.orderNo()).addValue("orderId", o.id()).addValue("orderNo", o.orderNo()).addValue("referrerId", o.referrerId()).addValue("referrerName", o.referrerName()).addValue("amount", amount).addValue("createdAt", Timestamp.from(o.createdAt())));
    }

    @Override
    public Optional<OrderRow> findForUpdate(long merchantId, long id, Long customerId) {
        return one(orderSql() + " AND o.id=:id" + (customerId == null ? " AND o.audit_status='approved'" : " AND o.customer_id=:customerId") + " FOR UPDATE", params(customerId, id).addValue("merchantId", merchantId));
    }

    @Override
    public Optional<OrderRow> find(long merchantId, long id, Long customerId) {
        return one(orderSql() + " AND o.id=:id" + (customerId == null ? " AND o.audit_status='approved'" : " AND o.customer_id=:customerId"), params(customerId, id).addValue("merchantId", merchantId));
    }

    @Override
    public Optional<OrderRow> findAuditOrder(long merchantId, long id) {
        return one(orderSql() + " AND o.id=:id AND o.audit_status IN ('pending','approved','rejected')", params(null, id).addValue("merchantId", merchantId));
    }

    @Override
    public Optional<OrderRow> findAuditOrderForUpdate(long merchantId, long id) {
        return one(orderSql() + " AND o.id=:id AND o.audit_status='pending' AND o.order_status<>'cancelled' FOR UPDATE", params(null, id).addValue("merchantId", merchantId));
    }

    @Override
    public List<OrderRow> findAuditPage(long merchantId, String result, String query, int page) {
        return jdbc.query(orderSql() + auditFilters(result, query) + " ORDER BY o.created_at DESC,o.id DESC LIMIT 10 OFFSET :offset",
                auditParams(query).addValue("merchantId", merchantId).addValue("offset", (page - 1) * 10), (rs, row) -> order(rs));
    }

    @Override
    public long countAudit(long merchantId, String result, String query) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales_order o WHERE o.merchant_id=:merchantId" + auditFilters(result, query),
                auditParams(query).addValue("merchantId", merchantId), Long.class);
    }

    private String auditFilters(String result, String query) {
        String resultFilter = switch (result) {
            case "pending" -> " AND o.audit_status='pending' AND o.order_status<>'cancelled'";
            case "approved" -> " AND o.audit_status='approved'";
            case "rejected" -> " AND o.audit_status='rejected'";
            default -> " AND o.audit_status IN ('pending','approved','rejected')";
        };
        boolean hasQuery = query != null && !query.isBlank();
        return resultFilter + (!hasQuery ? "" : " AND (o.order_no LIKE :query OR o.customer_name_snapshot LIKE :query OR o.customer_email_snapshot LIKE :query OR EXISTS (SELECT 1 FROM order_parcel op WHERE op.order_id=o.id AND op.status<>'repacked' AND op.tracking_no LIKE :query) OR EXISTS (SELECT 1 FROM order_item oi WHERE oi.order_id=o.id AND oi.deleted=0 AND (oi.sku_snapshot LIKE :query OR oi.name_snapshot LIKE :query)))");
    }

    private MapSqlParameterSource auditParams(String query) {
        String normalized = query == null ? null : query.trim();
        if (normalized != null && normalized.length() > 100) normalized = normalized.substring(0, 100);
        return new MapSqlParameterSource("query", normalized == null || normalized.isBlank() ? null : "%" + normalized + "%");
    }

    @Override
    public void decideAudit(long orderId, String result, String remark, long actorId) {
        int changed = jdbc.update("UPDATE sales_order SET audit_status=:result,audited_by_user_id=:actor,audited_at=UTC_TIMESTAMP(6),audit_remark=:remark,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id AND audit_status='pending' AND order_status<>'cancelled'",
                new MapSqlParameterSource().addValue("id", orderId).addValue("result", result).addValue("actor", actorId).addValue("remark", remark));
        if (changed != 1)
            throw ApiException.conflict("ORDER_AUDIT_ALREADY_DECIDED", "This order is no longer waiting for review.");
    }

    @Override
    public void withdrawPendingAudit(long orderId, long actorId) {
        jdbc.update("UPDATE sales_order SET audit_status='withdrawn',updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id AND audit_status='pending'",
                new MapSqlParameterSource().addValue("id", orderId).addValue("actor", actorId));
    }

    @Override
    public List<OrderRow> findPage(long merchantId, Long customerId, String status, String paymentStatus, String query, java.time.Instant from, java.time.Instant to, int page) {
        String filter = filters(customerId, status, paymentStatus, query, from, to);
        return jdbc.query(orderSql() + filter + " ORDER BY o.created_at DESC,o.id DESC LIMIT 10 OFFSET :offset", pageParams(customerId, status, paymentStatus, query, from, to).addValue("merchantId", merchantId).addValue("offset", (page - 1) * 10), (rs, row) -> order(rs));
    }

    @Override
    public long count(long merchantId, Long customerId, String status, String paymentStatus, String query, java.time.Instant from, java.time.Instant to) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales_order o WHERE o.merchant_id=:merchantId" + filters(customerId, status, paymentStatus, query, from, to), pageParams(customerId, status, paymentStatus, query, from, to).addValue("merchantId", merchantId), Long.class);
    }

    @Override
    public CustomerOrderFilterSummary customerOrderFilterSummary(long merchantId, long customerId, String status, String paymentStatus, String query, java.time.Instant from, java.time.Instant to) {
        String sql = "SELECT COUNT(*) AS order_count,"
                + "COUNT(CASE WHEN o.payment_status IN ('unpaid','pending_verification','partially_paid') AND o.order_status<>'cancelled' THEN 1 END) AS pending_payment_orders,"
                + "COALESCE(SUM(CASE WHEN o.order_status<>'cancelled' THEN o.total_amount ELSE 0 END),0) AS total_amount,"
                + "COALESCE(SUM(CASE WHEN o.payment_status IN ('unpaid','pending_verification','partially_paid') AND o.order_status<>'cancelled' THEN o.total_amount-COALESCE((SELECT SUM(pr.received_amount) FROM payment_receipt pr WHERE pr.order_id=o.id),0) ELSE 0 END),0) AS pending_payment_amount "
                + "FROM sales_order o WHERE o.merchant_id=:merchantId" + filters(customerId, status, paymentStatus, query, from, to);
        return jdbc.queryForObject(sql, pageParams(customerId, status, paymentStatus, query, from, to).addValue("merchantId", merchantId), (rs, row) ->
                new CustomerOrderFilterSummary(rs.getLong("order_count"), rs.getLong("pending_payment_orders"),
                        rs.getBigDecimal("total_amount"), rs.getBigDecimal("pending_payment_amount")));
    }

    @Override
    public CustomerOrderSummary customerOrderSummary(long merchantId, long customerId) {
        String sql = "SELECT COUNT(*) AS all_orders,"
                + "COUNT(CASE WHEN order_status='pending' THEN 1 END) AS pending_orders,"
                + "COUNT(CASE WHEN order_status='completed' THEN 1 END) AS completed_orders,"
                + "COUNT(CASE WHEN order_status='cancelled' THEN 1 END) AS cancelled_orders,"
                + "COUNT(CASE WHEN order_status IN ('received','completed') THEN 1 END) AS received_orders,"
                + "COUNT(CASE WHEN EXISTS (SELECT 1 FROM order_parcel op WHERE op.order_id=sales_order.id AND op.status='shipped') THEN 1 END) AS transit_orders,"
                + "COUNT(CASE WHEN audit_status='approved' AND order_status IN ('pending','packed') THEN 1 END) AS pending_shipment_orders,"
                + "COUNT(CASE WHEN payment_status IN ('unpaid','pending_verification','partially_paid') AND order_status<>'cancelled' THEN 1 END) AS payable_orders,"
                + "COALESCE(SUM(CASE WHEN payment_status IN ('unpaid','pending_verification','partially_paid') AND order_status<>'cancelled' THEN total_amount-COALESCE((SELECT SUM(pr.received_amount) FROM payment_receipt pr WHERE pr.order_id=sales_order.id),0) ELSE 0 END),0) AS payable_total "
                + "FROM sales_order WHERE merchant_id=:merchantId AND customer_id=:customerId";
        return jdbc.queryForObject(sql, new MapSqlParameterSource("customerId", customerId).addValue("merchantId", merchantId), (rs, row) ->
                new CustomerOrderSummary(rs.getLong("all_orders"), rs.getLong("pending_orders"),
                        rs.getLong("completed_orders"), rs.getLong("cancelled_orders"),
                        rs.getLong("received_orders"), rs.getLong("transit_orders"),
                        rs.getLong("pending_shipment_orders"), rs.getLong("payable_orders"),
                        rs.getBigDecimal("payable_total")));
    }

    @Override
    public AdminOrderSummary adminOrderSummary(long merchantId) {
        String sql = "SELECT COUNT(*) AS all_orders,"
                + "COUNT(CASE WHEN payment_status IN ('unpaid','pending_verification','partially_paid') AND order_status<>'cancelled' THEN 1 END) AS pending_payment_orders,"
                + "COUNT(CASE WHEN order_status IN ('pending','packed') THEN 1 END) AS not_shipped_orders,"
                + "COUNT(CASE WHEN order_status='completed' THEN 1 END) AS completed_orders,"
                + "COALESCE(SUM(CASE WHEN order_status<>'cancelled' THEN total_amount ELSE 0 END),0) AS gross_order_value,"
                + "COALESCE(SUM(CASE WHEN payment_status IN ('unpaid','pending_verification','partially_paid') AND order_status<>'cancelled' THEN total_amount-COALESCE((SELECT SUM(pr.received_amount) FROM payment_receipt pr WHERE pr.order_id=sales_order.id),0) ELSE 0 END),0) AS pending_receivable_amount,"
                + "COALESCE((SELECT SUM(pr.received_amount) FROM payment_receipt pr JOIN sales_order paid_order ON paid_order.id=pr.order_id WHERE paid_order.merchant_id=:merchantId AND paid_order.audit_status='approved' AND paid_order.order_status<>'cancelled'),0) AS paid_amount "
                + "FROM sales_order WHERE merchant_id=:merchantId AND audit_status='approved'";
        return jdbc.queryForObject(sql, new MapSqlParameterSource().addValue("merchantId", merchantId), (rs, row) ->
                new AdminOrderSummary(rs.getLong("all_orders"), rs.getLong("pending_payment_orders"),
                        rs.getLong("not_shipped_orders"), rs.getLong("completed_orders"),
                        rs.getBigDecimal("gross_order_value"), rs.getBigDecimal("pending_receivable_amount"),
                        rs.getBigDecimal("paid_amount")));
    }

    @Override
    public List<ItemRow> items(long orderId) {
        return jdbc.query("SELECT id,product_id,sku_snapshot,name_snapshot,type_snapshot,quantity,unit_price,line_amount FROM order_item WHERE order_id=:id AND deleted=0 ORDER BY sort_no,id", new MapSqlParameterSource("id", orderId), (rs, row) -> new ItemRow(rs.getLong("id"), rs.getLong("product_id"), rs.getString("sku_snapshot"), rs.getString("name_snapshot"), rs.getString("type_snapshot"), rs.getInt("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("line_amount")));
    }

    @Override
    public List<EventRow> events(long orderId) {
        return jdbc.query("SELECT e.id,e.parcel_id,e.action,e.from_status,e.to_status,e.note,e.occurred_at,e.actor_user_id,COALESCE(NULLIF(u.name,''),CONCAT('用户 #',e.actor_user_id)) actor_name FROM order_event e LEFT JOIN user u ON u.id=e.actor_user_id WHERE e.order_id=:id ORDER BY e.occurred_at,e.id", new MapSqlParameterSource("id", orderId), (rs, row) -> new EventRow(rs.getLong("id"), rs.getString("action"), rs.getString("from_status"), rs.getString("to_status"), rs.getString("note"), rs.getTimestamp("occurred_at").toInstant(), rs.getLong("actor_user_id"), rs.getString("actor_name"), (Long) rs.getObject("parcel_id")));
    }

    @Override
    public List<String> eventFiles(long orderId, long eventId) {
        return jdbc.query("SELECT file_uuid FROM order_event_file WHERE order_id=:orderId AND event_id=:eventId ORDER BY sort_no,id",
                new MapSqlParameterSource().addValue("orderId", orderId).addValue("eventId", eventId),
                (rs, row) -> rs.getString("file_uuid"));
    }

    @Override
    public Optional<PaymentSubmissionRow> paymentSubmission(long orderId) {
        return submission(orderId, false);
    }

    @Override
    public Optional<PaymentSubmissionRow> pendingPaymentSubmission(long orderId) {
        return submission(orderId, true);
    }

    private Optional<PaymentSubmissionRow> submission(long orderId, boolean pendingOnly) {
        return jdbc.query("SELECT id,channel_id,channel_snapshot,original_transaction_no,declared_order_total,submitted_at,declared_amount,status FROM payment_submission WHERE order_id=:id"
                + (pendingOnly ? " AND status='pending'" : "") + " ORDER BY id DESC LIMIT 1", new MapSqlParameterSource("id", orderId),
                rows -> rows.next() ? Optional.of(new PaymentSubmissionRow(rows.getLong("id"), rows.getLong("channel_id"), rows.getString("channel_snapshot"), rows.getString("original_transaction_no"), rows.getBigDecimal("declared_order_total"), rows.getTimestamp("submitted_at").toInstant(), rows.getBigDecimal("declared_amount"), rows.getString("status"))) : Optional.empty());
    }

    @Override
    public Optional<String> paymentReceiptTransactionNo(long orderId) {
        return jdbc.query("SELECT verified_transaction_no FROM payment_receipt WHERE order_id=:id ORDER BY id DESC LIMIT 1", new MapSqlParameterSource("id", orderId), rows -> rows.next() ? Optional.ofNullable(rows.getString("verified_transaction_no")) : Optional.empty());
    }

    @Override
    public BigDecimal receivedAmount(long orderId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(received_amount),0) FROM payment_receipt WHERE order_id=:id", new MapSqlParameterSource("id", orderId), BigDecimal.class);
    }

    @Override
    public void savePaymentSubmission(PaymentSubmissionInput i, String snapshot, long actorId) {
        jdbc.update("INSERT INTO payment_submission(order_id,customer_id,channel_id,channel_snapshot,original_transaction_no,declared_order_total,declared_amount,status,submitted_at,created_by_user_id,updated_by_user_id) VALUES(:orderId,:customerId,:channelId,:snapshot,:transactionNo,:expectedTotal,:amount,'pending',UTC_TIMESTAMP(6),:actor,:actor)", new MapSqlParameterSource().addValue("orderId", i.orderId()).addValue("customerId", i.customerId()).addValue("channelId", i.channelId()).addValue("snapshot", snapshot).addValue("transactionNo", i.transactionNo()).addValue("expectedTotal", i.expectedTotal()).addValue("amount", i.declaredAmount()).addValue("actor", actorId));
    }

    @Override
    public void savePaymentReceipt(PaymentReceiptInput i, String snapshot, long actorId) {
        jdbc.update("INSERT INTO payment_receipt(order_id,submission_id,channel_id,channel_snapshot,verified_transaction_no,received_amount,verified_by_user_id,verified_at,internal_note) VALUES(:orderId,:submissionId,:channelId,:snapshot,:transactionNo,:amount,:actor,UTC_TIMESTAMP(6),:note)", new MapSqlParameterSource().addValue("orderId", i.orderId()).addValue("submissionId", i.submissionId()).addValue("channelId", i.channelId()).addValue("snapshot", snapshot).addValue("transactionNo", i.transactionNo()).addValue("amount", i.receivedAmount()).addValue("actor", actorId).addValue("note", i.internalNote()));
    }

    @Override
    public List<PaymentReceiptNoteRow> paymentReceiptNotes(long orderId) {
        return jdbc.query("SELECT verified_by_user_id,verified_at,internal_note FROM payment_receipt WHERE order_id=:id ORDER BY id",
                new MapSqlParameterSource("id", orderId),
                (rs, row) -> new PaymentReceiptNoteRow(rs.getLong("verified_by_user_id"), rs.getTimestamp("verified_at").toInstant(), rs.getString("internal_note")));
    }

    @Override
    public void confirmSubmission(long submissionId, long actorId) {
        int changed = jdbc.update("UPDATE payment_submission SET status='confirmed',updated_by_user_id=:actor,updated_at=UTC_TIMESTAMP(6) WHERE id=:id AND status='pending'", new MapSqlParameterSource().addValue("id", submissionId).addValue("actor", actorId));
        if (changed != 1) throw ApiException.conflict("PAYMENT_ALREADY_CONFIRMED", "This submission has already been confirmed.");
    }

    @Override
    public void updatePaymentTotals(long orderId, String status, BigDecimal paidAmount, long actorId) {
        jdbc.update("UPDATE sales_order SET payment_status=:status,updated_at=GREATEST(UTC_TIMESTAMP(6),updated_at+INTERVAL 1 MICROSECOND),updated_by_user_id=:actor,status_changed_at=UTC_TIMESTAMP(6) WHERE id=:id", new MapSqlParameterSource().addValue("id", orderId).addValue("status", status).addValue("actor", actorId));
        jdbc.update("UPDATE billing_entry SET settled_amount=:amount,status=:status,settled_at=CASE WHEN :status='paid' THEN UTC_TIMESTAMP(6) ELSE NULL END,updated_at=UTC_TIMESTAMP(6) WHERE order_id=:id AND bill_type='order_receivable'", new MapSqlParameterSource().addValue("id", orderId).addValue("amount", paidAmount).addValue("status", status));
    }

    private String paymentRecordSql() {
        return "SELECT s.id submission_id,r.id receipt_id,s.channel_id,s.channel_snapshot,s.original_transaction_no,r.verified_transaction_no,s.declared_order_total,s.declared_amount,r.received_amount,s.status,s.submitted_at,r.verified_at,u.name verified_by_name,r.internal_note,COALESCE(r.verified_at,s.submitted_at) record_at,s.id record_id,0 record_kind,r.channel_id receipt_channel_id,r.channel_snapshot receipt_channel_snapshot FROM payment_submission s LEFT JOIN payment_receipt r ON r.submission_id=s.id LEFT JOIN user u ON u.id=r.verified_by_user_id WHERE s.order_id=:id"
                + " UNION ALL SELECT NULL,r.id,r.channel_id,r.channel_snapshot,NULL,r.verified_transaction_no,NULL,NULL,r.received_amount,'confirmed',NULL,r.verified_at,u.name,r.internal_note,r.verified_at,r.id,1,r.channel_id,r.channel_snapshot FROM payment_receipt r LEFT JOIN user u ON u.id=r.verified_by_user_id WHERE r.order_id=:id AND r.submission_id IS NULL";
    }

    @Override
    public List<PaymentRecordRow> paymentRecords(long orderId, int page) {
        return jdbc.query("SELECT * FROM (" + paymentRecordSql() + ") records ORDER BY record_at DESC,record_kind DESC,record_id DESC LIMIT 10 OFFSET :offset",
                new MapSqlParameterSource().addValue("id", orderId).addValue("offset", (page - 1) * 10), (rs, row) -> {
                    Timestamp submitted = rs.getTimestamp("submitted_at"), verified = rs.getTimestamp("verified_at");
                    return new PaymentRecordRow((Long) rs.getObject("submission_id"), (Long) rs.getObject("receipt_id"), rs.getLong("channel_id"), rs.getString("channel_snapshot"), rs.getString("original_transaction_no"), rs.getString("verified_transaction_no"), rs.getBigDecimal("declared_order_total"), rs.getBigDecimal("declared_amount"), rs.getBigDecimal("received_amount"), rs.getString("status"), submitted == null ? null : submitted.toInstant(), verified == null ? null : verified.toInstant(), rs.getString("verified_by_name"), rs.getString("internal_note"), (Long) rs.getObject("receipt_channel_id"), rs.getString("receipt_channel_snapshot"));
                });
    }

    @Override
    public long paymentRecordCount(long orderId) {
        return jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM payment_submission WHERE order_id=:id)+(SELECT COUNT(*) FROM payment_receipt WHERE order_id=:id AND submission_id IS NULL)", new MapSqlParameterSource("id", orderId), Long.class);
    }

    @Override
    public void updateStatus(long orderId, String status, String buyerNote, String internalNote, long actorId) {
        jdbc.update("UPDATE sales_order SET status_changed_at=IF(order_status<>:status,UTC_TIMESTAMP(6),status_changed_at),order_status=:status,buyer_note=COALESCE(:buyerNote,buyer_note),internal_note=COALESCE(:internalNote,internal_note),updated_at=GREATEST(UTC_TIMESTAMP(6),updated_at+INTERVAL 1 MICROSECOND),updated_by_user_id=:actor,completed_at=IF(:status='completed',UTC_TIMESTAMP(6),completed_at),cancelled_at=IF(:status='cancelled',UTC_TIMESTAMP(6),cancelled_at) WHERE id=:id", new MapSqlParameterSource().addValue("id", orderId).addValue("status", status).addValue("buyerNote", buyerNote).addValue("internalNote", internalNote).addValue("actor", actorId));
    }

    @Override
    public void updateCustomerDetails(long orderId, String addressSnapshot, String recipientName, String city, String buyerNote, long actorId) {
        jdbc.update("UPDATE sales_order SET address_snapshot=:addressSnapshot,recipient_name=:recipientName,city=:city,buyer_note=:buyerNote,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id", new MapSqlParameterSource().addValue("id", orderId).addValue("addressSnapshot", addressSnapshot).addValue("recipientName", recipientName).addValue("city", city).addValue("buyerNote", buyerNote).addValue("actor", actorId));
    }

    @Override
    public void updateSellerDetails(long orderId, String addressSnapshot, String recipientName, String city, String buyerNote, String internalNote, long actorId) {
        jdbc.update("UPDATE sales_order SET address_snapshot=COALESCE(:addressSnapshot,address_snapshot),recipient_name=COALESCE(:recipientName,recipient_name),city=COALESCE(:city,city),buyer_note=COALESCE(:buyerNote,buyer_note),internal_note=COALESCE(:internalNote,internal_note),updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id", new MapSqlParameterSource().addValue("id", orderId).addValue("addressSnapshot", addressSnapshot).addValue("recipientName", recipientName).addValue("city", city).addValue("buyerNote", buyerNote).addValue("internalNote", internalNote).addValue("actor", actorId));
    }

    @Override
    public void updateItem(long itemId, int quantity, BigDecimal unitPrice, long actorId) {
        jdbc.update("UPDATE order_item SET quantity=:quantity,unit_price=:price,line_amount=:amount,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id AND deleted=0", new MapSqlParameterSource().addValue("id", itemId).addValue("quantity", quantity).addValue("price", unitPrice).addValue("amount", unitPrice.multiply(BigDecimal.valueOf(quantity))).addValue("actor", actorId));
    }

    @Override
    public void updateOrderAmounts(long orderId, BigDecimal subtotal, BigDecimal discountAmount, BigDecimal total, long actorId) {
        jdbc.update("UPDATE sales_order SET subtotal=:subtotal,discount_amount=:discount,total_amount=:total,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id", new MapSqlParameterSource().addValue("id", orderId).addValue("subtotal", subtotal).addValue("discount", discountAmount).addValue("total", total).addValue("actor", actorId));
        jdbc.update("UPDATE billing_entry SET amount=:total,updated_at=UTC_TIMESTAMP(6) WHERE order_id=:id AND bill_type='order_receivable' AND status='unpaid'", new MapSqlParameterSource().addValue("id", orderId).addValue("total", total));
        BigDecimal commissionBase = subtotal.subtract(discountAmount);
        jdbc.update("UPDATE commission_accrual SET base_amount=:base,commission_amount=ROUND(:base*rate/100,2),updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE order_id=:id AND status IN ('estimated','payable')", new MapSqlParameterSource().addValue("id", orderId).addValue("base", commissionBase).addValue("actor", actorId));
        jdbc.update("UPDATE billing_entry b JOIN commission_accrual a ON a.order_id=b.order_id SET b.amount=a.commission_amount,b.updated_at=UTC_TIMESTAMP(6) WHERE b.order_id=:id AND b.bill_type='commission_payable' AND b.status IN ('estimated','payable') AND a.status IN ('estimated','payable')", new MapSqlParameterSource("id", orderId));
    }

    @Override
    public void removeItem(long itemId, long actorId) {
        jdbc.update("UPDATE order_item SET deleted=id,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id AND deleted=0",
                new MapSqlParameterSource("id", itemId).addValue("actor", actorId));
    }

    @Override
    public void markItemManualPrice(long itemId, long actorId) {
        jdbc.update("UPDATE order_item SET price_source='manual',price_tier_snapshot=NULL,updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:id AND deleted=0",
                new MapSqlParameterSource("id", itemId).addValue("actor", actorId));
    }

    @Override
    public void updateBillingVoid(long orderId, long actorId) {
        jdbc.update("UPDATE billing_entry SET status='void',updated_at=UTC_TIMESTAMP(6) WHERE order_id=:id", new MapSqlParameterSource("id", orderId));
    }

    @Override
    public void updateCommissionVoid(long orderId, long actorId) {
        jdbc.update("UPDATE commission_accrual SET status='void',finalized_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE order_id=:id AND status IN ('estimated','payable')", new MapSqlParameterSource().addValue("id", orderId).addValue("actor", actorId));
    }

    @Override
    public boolean hasSettledCommission(long orderId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM commission_accrual WHERE order_id=:id AND status='settled'", new MapSqlParameterSource("id", orderId), Integer.class);
        return count != null && count > 0;
    }

    @Override
    public void updateBillingPayable(long orderId, long actorId) {
        jdbc.update("UPDATE commission_accrual a JOIN sales_order o ON o.id=a.order_id SET a.base_amount=o.subtotal-o.discount_amount,a.commission_amount=ROUND((o.subtotal-o.discount_amount)*a.rate/100,2),a.status='payable',a.finalized_at=UTC_TIMESTAMP(6),a.updated_at=UTC_TIMESTAMP(6),a.updated_by_user_id=:actor WHERE a.order_id=:id AND a.status='estimated'", new MapSqlParameterSource().addValue("id", orderId).addValue("actor", actorId));
        jdbc.update("UPDATE billing_entry b JOIN commission_accrual a ON a.order_id=b.order_id SET b.amount=a.commission_amount,b.status='payable',b.updated_at=UTC_TIMESTAMP(6) WHERE b.order_id=:id AND b.bill_type='commission_payable' AND b.status='estimated' AND a.status='payable'", new MapSqlParameterSource("id", orderId));
    }

    @Override
    public void captureCoaSnapshots(long orderId, long actorId) {
        jdbc.update("INSERT INTO order_item_coa_snapshot(order_id,order_item_id,product_id,coa_id,source_link_id,name_snapshot,lot_snapshot,purity_snapshot,fill_snapshot,test_date_snapshot,file_uuid,linked_by_user_id_snapshot,linked_at_snapshot,captured_at) SELECT oi.order_id,oi.id,oi.product_id,c.id,pcl.id,c.name,c.lot,c.purity,c.fill,c.test_date,c.current_file_uuid,pcl.linked_by_user_id,pcl.linked_at,UTC_TIMESTAMP(6) FROM order_item oi JOIN product_coa_link pcl ON pcl.product_id=oi.product_id AND pcl.deleted=0 JOIN coa_certificate c ON c.id=pcl.coa_id AND c.deleted=0 WHERE oi.order_id=:orderId AND oi.deleted=0", new MapSqlParameterSource("orderId", orderId));
        jdbc.update("UPDATE sales_order SET coa_captured_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6),updated_by_user_id=:actor WHERE id=:orderId", new MapSqlParameterSource().addValue("orderId", orderId).addValue("actor", actorId));
    }

    @Override
    public Dashboard dashboard(long merchantId) {
        Long open = jdbc.queryForObject("SELECT COUNT(*) FROM sales_order WHERE merchant_id=:merchantId AND audit_status='approved' AND order_status IN ('pending','packed','shipped','received')", new MapSqlParameterSource().addValue("merchantId", merchantId), Long.class);
        Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM sales_order WHERE merchant_id=:merchantId AND audit_status='approved' AND payment_status<>'paid' AND order_status<>'cancelled'", new MapSqlParameterSource().addValue("merchantId", merchantId), Long.class);
        BigDecimal gross = jdbc.queryForObject("SELECT COALESCE(SUM(total_amount),0) FROM sales_order WHERE merchant_id=:merchantId AND audit_status='approved' AND order_status<>'cancelled'", new MapSqlParameterSource().addValue("merchantId", merchantId), BigDecimal.class);
        Long customers = jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE merchant_id=:merchantId AND user_type='customer' AND active=1 AND deleted=0", new MapSqlParameterSource().addValue("merchantId", merchantId), Long.class);
        return new Dashboard(open, pending, gross, customers);
    }

    private String orderSql() {
        return "SELECT o.id,o.order_no,o.customer_id,o.customer_code_snapshot,o.customer_name_snapshot,o.customer_email_snapshot,o.order_status,o.payment_status,o.address_snapshot,o.recipient_name,o.city,o.buyer_note,o.internal_note,o.subtotal,o.shipping_fee,o.discount_amount,o.total_amount,o.referrer_id_snapshot,o.referrer_name_snapshot,o.commission_rate_snapshot,o.created_at,o.updated_at,o.completed_at,o.cancelled_at,o.audit_status,o.audited_by_user_id,(SELECT u.name FROM `user` u WHERE u.id=o.audited_by_user_id) audited_by_name,o.audited_at,o.audit_remark,COALESCE((SELECT SUM(pr.received_amount) FROM payment_receipt pr WHERE pr.order_id=o.id),0) paid_amount FROM sales_order o WHERE o.merchant_id=:merchantId";
    }

    private String filters(Long customerId, String status, String paymentStatus, String query, java.time.Instant from, java.time.Instant to) {
        boolean hasQuery = query != null && !query.isBlank();
        return (customerId == null ? " AND o.audit_status='approved'" : " AND o.customer_id=:customerId")
                + statusFilter(status)
                + paymentStatusFilter(paymentStatus)
                + (from == null ? "" : " AND o.created_at>=:from")
                + (to == null ? "" : " AND o.created_at<:to")
                + (!hasQuery ? "" : customerId == null
                    ? " AND (o.order_no LIKE :query OR o.customer_name_snapshot LIKE :query OR o.customer_email_snapshot LIKE :query OR EXISTS (SELECT 1 FROM order_parcel op WHERE op.order_id=o.id AND op.status<>'repacked' AND op.tracking_no LIKE :query) OR EXISTS (SELECT 1 FROM order_item oi WHERE oi.order_id=o.id AND oi.deleted=0 AND (oi.sku_snapshot LIKE :query OR oi.name_snapshot LIKE :query)))"
                    : " AND (o.order_no LIKE :query OR o.customer_code_snapshot LIKE :query OR o.customer_name_snapshot LIKE :query OR o.customer_email_snapshot LIKE :query OR o.recipient_name LIKE :query OR EXISTS (SELECT 1 FROM order_parcel op WHERE op.order_id=o.id AND op.status<>'repacked' AND op.tracking_no LIKE :query) OR EXISTS (SELECT 1 FROM order_item oi WHERE oi.order_id=o.id AND oi.deleted=0 AND (oi.sku_snapshot LIKE :query OR oi.name_snapshot LIKE :query)))");
    }

    private String paymentStatusFilter(String paymentStatus) {
        if (paymentStatus == null || paymentStatus.isBlank()) return "";
        if ("payable".equals(paymentStatus)) return " AND o.payment_status IN ('unpaid','pending_verification','partially_paid') AND o.order_status<>'cancelled'";
        return " AND o.payment_status=:paymentStatus";
    }

    private MapSqlParameterSource pageParams(Long customerId, String status, String paymentStatus, String query, java.time.Instant from, java.time.Instant to) {
        String normalized = query == null ? null : query.trim();
        if (normalized != null && normalized.length() > 100) normalized = normalized.substring(0, 100);
        return params(customerId, null).addValue("status", status).addValue("paymentStatus", paymentStatus)
                .addValue("query", normalized == null || normalized.isBlank() ? null : "%" + normalized + "%")
                .addValue("from", from == null ? null : Timestamp.from(from)).addValue("to", to == null ? null : Timestamp.from(to));
    }

    private String statusFilter(String status) {
        if (status == null || status.isBlank()) return "";
        if ("not".equals(status)) return " AND o.order_status IN ('pending','packed')";
        if ("partial".equals(status)) return " AND o.order_status IN ('pending','packed') AND EXISTS (SELECT 1 FROM order_parcel op WHERE op.order_id=o.id AND op.status IN ('shipped','received'))";
        if ("shipped".equals(status)) return " AND o.order_status IN ('shipped','received','completed')";
        return " AND o.order_status=:status";
    }

    private MapSqlParameterSource params(Long customerId, Long id) {
        return new MapSqlParameterSource().addValue("customerId", customerId).addValue("id", id);
    }

    private Optional<OrderRow> one(String sql, MapSqlParameterSource p) {
        return jdbc.query(sql, p, rows -> rows.next() ? Optional.of(order(rows)) : Optional.empty());
    }

    private OrderRow order(java.sql.ResultSet r) throws java.sql.SQLException {
        Timestamp c = r.getTimestamp("created_at"), updated = r.getTimestamp("updated_at"), done = r.getTimestamp("completed_at"), cancel = r.getTimestamp("cancelled_at"), audited = r.getTimestamp("audited_at");
        long auditor = r.getLong("audited_by_user_id");
        Long auditorId = r.wasNull() ? null : auditor;
        return new OrderRow(r.getLong("id"), r.getString("order_no"), r.getLong("customer_id"), r.getString("customer_code_snapshot"), r.getString("customer_name_snapshot"), r.getString("customer_email_snapshot"), r.getString("order_status"), r.getString("payment_status"), r.getString("address_snapshot"), r.getString("recipient_name"), r.getString("city"), r.getString("buyer_note"), r.getString("internal_note"), r.getBigDecimal("subtotal"), r.getBigDecimal("shipping_fee"), r.getBigDecimal("discount_amount"), r.getBigDecimal("total_amount"), (Long) r.getObject("referrer_id_snapshot"), r.getString("referrer_name_snapshot"), r.getBigDecimal("commission_rate_snapshot"), c.toInstant(), done == null ? null : done.toInstant(), updated.toInstant(), cancel == null ? null : cancel.toInstant(), r.getString("audit_status"), auditorId, r.getString("audited_by_name"), audited == null ? null : audited.toInstant(), r.getString("audit_remark"), r.getBigDecimal("paid_amount"));
    }

    private void requireApproved(OrderRow order) {
        if (!"approved".equals(order.auditStatus()))
            throw ApiException.conflict("ORDER_NOT_APPROVED", "Order must pass review before financial records are created.");
    }
}

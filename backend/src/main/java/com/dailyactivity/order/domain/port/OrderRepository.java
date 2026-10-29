package com.dailyactivity.order.domain.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    long insertOrder(long merchantId, OrderCreate order);

    boolean quoteAlreadyUsed(String quoteTokenHash);

    void insertItem(long orderId, ItemCreate item, long actorId);

    void saveQuantityPrices(long orderId, long productId, List<com.dailyactivity.catalog.domain.QuantityPricing.Band> bands);

    List<com.dailyactivity.catalog.domain.QuantityPricing.Band> quantityPrices(long itemId);

    void deleteCart(long customerId, List<Long> productIds);

    long addEvent(long orderId, String operationId, String action, String fromStatus, String toStatus, String beforeData, String afterData, String note, long actorId);

    void attachFiles(long orderId, long eventId, List<String> fileUuids, String stage);

    void createReceivable(OrderRow order, long actorId);

    void createAccrual(OrderRow order, long actorId);

    Optional<OrderRow> findForUpdate(long merchantId, long orderId, Long customerId);

    Optional<OrderRow> find(long merchantId, long orderId, Long customerId);

    Optional<OrderRow> findAuditOrder(long merchantId, long orderId);

    Optional<OrderRow> findAuditOrderForUpdate(long merchantId, long orderId);

    List<OrderRow> findAuditPage(long merchantId, String result, String query, int page);

    long countAudit(long merchantId, String result, String query);

    void decideAudit(long orderId, String result, String remark, long actorId);

    void withdrawPendingAudit(long orderId, long actorId);

    List<OrderRow> findPage(long merchantId, Long customerId, String status, String paymentStatus, String query, Instant from, Instant to, int page);

    long count(long merchantId, Long customerId, String status, String paymentStatus, String query, Instant from, Instant to);

    CustomerOrderFilterSummary customerOrderFilterSummary(long merchantId, long customerId, String status, String paymentStatus, String query, Instant from, Instant to);

    CustomerOrderSummary customerOrderSummary(long merchantId, long customerId);

    AdminOrderSummary adminOrderSummary(long merchantId);

    List<ItemRow> items(long orderId);

    List<EventRow> events(long orderId);

    List<String> eventFiles(long orderId, long eventId);

    Optional<PaymentSubmissionRow> paymentSubmission(long orderId);

    Optional<String> paymentReceiptTransactionNo(long orderId);

    List<PaymentReceiptNoteRow> paymentReceiptNotes(long orderId);

    void savePaymentSubmission(PaymentSubmissionInput input, String snapshot, long actorId);

    void savePaymentReceipt(PaymentReceiptInput input, String snapshot, long actorId);

    Optional<PaymentSubmissionRow> pendingPaymentSubmission(long orderId);

    BigDecimal receivedAmount(long orderId);

    List<PaymentRecordRow> paymentRecords(long orderId, int page);

    long paymentRecordCount(long orderId);

    void confirmSubmission(long submissionId, long actorId);

    void updatePaymentTotals(long orderId, String status, BigDecimal paidAmount, long actorId);

    void updateStatus(long orderId, String status, String buyerNote, String internalNote, long actorId);

    void updateCustomerDetails(long orderId, String addressSnapshot, String recipientName, String city, String buyerNote, long actorId);

    void updateSellerDetails(long orderId, String addressSnapshot, String recipientName, String city, String buyerNote, String internalNote, long actorId);

    void updateItem(long itemId, int quantity, BigDecimal unitPrice, long actorId);

    void removeItem(long itemId, long actorId);

    void markItemManualPrice(long itemId, long actorId);

    void updateOrderAmounts(long orderId, BigDecimal subtotal, BigDecimal discountAmount, BigDecimal total, long actorId);

    void updateBillingVoid(long orderId, long actorId);

    void updateCommissionVoid(long orderId, long actorId);

    boolean hasSettledCommission(long orderId);

    void updateBillingPayable(long orderId, long actorId);

    void captureCoaSnapshots(long orderId, long actorId);

    Dashboard dashboard(long merchantId);

    record OrderCreate(String orderNo, String quoteTokenHash, long customerId, String customerCode, String customerName, String customerEmail,
                       String addressSnapshot, String recipientName, String city, String buyerNote, BigDecimal subtotal,
                       BigDecimal shippingFee, BigDecimal total, Long referrerId, String referrerName,
                       BigDecimal commissionRate, Instant now) {
    }

    record ItemCreate(long productId, String sku, String name, String productType, int quantity, BigDecimal unitPrice,
                      BigDecimal unitCost, String priceSource, String tier) {
    }

    record OrderRow(long id, String orderNo, long customerId, String customerCode, String customerName,
                    String customerEmail, String status, String paymentStatus, String addressSnapshot,
                    String recipientName, String city, String buyerNote, String internalNote, BigDecimal subtotal, BigDecimal shippingFee, BigDecimal discountAmount, BigDecimal total, Long referrerId,
                    String referrerName, BigDecimal commissionRate, Instant createdAt, Instant completedAt,
                    Instant updatedAt, Instant cancelledAt, String auditStatus, Long auditedByUserId,
                    String auditedByName, Instant auditedAt, String auditRemark, BigDecimal paidAmount) {
    }

    record ItemRow(long id, long productId, String sku, String name, String productType, int quantity,
                   BigDecimal unitPrice, BigDecimal lineAmount) {
    }

    record EventRow(long id, String action, String fromStatus, String toStatus, String note,
                    Instant occurredAt, long actorUserId, String actorName, Long parcelId) {
    }

    record PaymentReceiptNoteRow(long verifiedByUserId, Instant verifiedAt, String internalNote) {
    }

    record PaymentSubmissionRow(long id, long channelId, String channelSnapshot, String transactionNo,
                                BigDecimal declaredTotal, Instant submittedAt, BigDecimal declaredAmount, String status) {
    }

    record PaymentSubmissionInput(long orderId, long customerId, long channelId, String transactionNo,
                                  BigDecimal expectedTotal, BigDecimal declaredAmount) {
    }

    record PaymentReceiptInput(long orderId, Long submissionId, long channelId, String transactionNo,
                               BigDecimal receivedAmount, String internalNote) {
    }

    record PaymentRecordRow(Long submissionId, Long receiptId, long channelId, String channelSnapshot,
                            String originalTransactionNo, String verifiedTransactionNo, BigDecimal orderTotalSnapshot,
                            BigDecimal declaredAmount, BigDecimal receivedAmount, String status, Instant submittedAt,
                            Instant verifiedAt, String verifiedByName, String internalNote,
                            Long receiptChannelId, String receiptChannelSnapshot) {
    }

    record Dashboard(long openOrders, long pendingPaymentOrders, BigDecimal grossOrderValue, long customers) {
    }

    record CustomerOrderSummary(long allOrders, long pendingOrders, long completedOrders, long cancelledOrders,
                                long receivedOrders, long transitOrders, long pendingShipmentOrders,
                                long payableOrders, BigDecimal payableTotal) {
    }

    record CustomerOrderFilterSummary(long orderCount, long pendingPaymentOrders, BigDecimal totalAmount,
                                      BigDecimal pendingPaymentAmount) {
    }

    record AdminOrderSummary(long allOrders, long pendingPaymentOrders, long notShippedOrders,
                             long completedOrders, BigDecimal grossOrderValue,
                             BigDecimal pendingReceivableAmount, BigDecimal paidAmount) {
    }
}

package com.dailyactivity.order;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.support.PrdDatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

    @DisplayName("PRD S-02A 订单审核及财务门禁")
class PrdOrderAuditTest extends PrdDatabaseTest {

    @Test
    @DisplayName("新订单待审核时可以申报付款，但不进入订单管理与财务记录")
    void newOrderCanDeclarePaymentBeforeReviewWithoutEnteringFinance() {
        int stockBefore = jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?", Integer.class, data.productId());
        Map<String, Object> order = createPendingOrder("audit-pending", 2);
        long id = orderId(order);

        assertThat(order).doesNotContainKey("auditStatus");
        assertThat(orders.auditOrders(seller(), "pending", null, 1).total()).isEqualTo(1);
        assertThat(orders.adminOrders(seller(), null, null, null, null, null, 1).total()).isZero();
        assertThat(jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?", Integer.class, data.productId()))
                .isEqualTo(stockBefore - 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_entry WHERE order_id=?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commission_accrual WHERE order_id=?", Long.class, id)).isZero();
        Map<String, Object> payment = submitPayment(customer(), id,
                new OrderService.PaymentRequest(data.channelId(), "pending-audit-tx", new BigDecimal("200.00")));
        assertThat(payment).containsEntry("paymentStatus", "pending_verification").doesNotContainKey("auditStatus");
        assertThat(orders.adminOrders(seller(), null, null, null, null, null, 1).total()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_entry WHERE order_id=?", Long.class, id)).isZero();
    }

    @Test
    @DisplayName("审核通过记录审核人并生成应收和佣金")
    void approvalCreatesFinancialRecordsAndPaymentCanBeSubmitted() {
        Map<String, Object> pending = createPendingOrder("audit-approved", 1);
        long id = orderId(pending);

        Map<String, Object> approved = orders.decideAudit(seller(), id,
                new OrderService.AuditDecision("approved", "资料已核对", null));

        assertThat(approved)
                .containsEntry("auditStatus", "approved")
                .containsEntry("auditedByName", "Test Admin")
                .containsEntry("auditRemark", "资料已核对");
        assertThat(orders.auditOrders(seller(), "pending", null, 1).total()).isZero();
        assertThat(orders.adminOrders(seller(), null, null, null, null, null, 1).total()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_entry WHERE order_id=?", Long.class, id)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_entry WHERE order_id=? AND bill_type='order_receivable'", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commission_accrual WHERE order_id=?", Long.class, id)).isEqualTo(1L);

        Map<String, Object> payment = submitPayment(customer(), id,
                new OrderService.PaymentRequest(data.channelId(), "approved-audit-tx", new BigDecimal("110.00")));
        assertThat(payment).containsEntry("paymentStatus", "pending_verification").doesNotContainKey("auditStatus");
        Map<String, Object> auditDetail = orders.auditDetail(seller(), id);
        assertThat(((Map<?, ?>) auditDetail.get("paymentSubmission")).get("transactionNo")).isEqualTo("approved-audit-tx");
        assertThat(orders.auditOrders(seller(), "approved", null, 1).total()).isEqualTo(1);
    }

    @Test
    @DisplayName("审核拒绝只恢复一次库存且不生成应收或佣金")
    void rejectionRestoresStockAndDoesNotCreateFinance() {
        int stockBefore = jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?", Integer.class, data.productId());
        Map<String, Object> pending = createPendingOrder("audit-rejected", 2);
        long id = orderId(pending);

        Map<String, Object> payment = submitPayment(customer(), id,
                new OrderService.PaymentRequest(data.channelId(), "rejected-audit-tx", new BigDecimal("200.00")));
        assertThat(payment).containsEntry("paymentStatus", "pending_verification").doesNotContainKey("auditStatus");

        Map<String, Object> rejected = orders.decideAudit(seller(), id,
                new OrderService.AuditDecision("rejected", "信息不完整", null));

        assertThat(rejected).containsEntry("auditStatus", "rejected").containsEntry("auditRemark", "信息不完整")
                .containsEntry("status", "cancelled").containsEntry("paymentStatus", "pending_verification");
        Map<String, Object> customerDetail = orders.detail(customer(), id);
        assertThat(customerDetail).doesNotContainKey("auditStatus").doesNotContainKey("auditRemark");
        assertThat(((java.util.List<?>) customerDetail.get("events")).stream()
                .map(event -> ((Map<?, ?>) event).get("type"))
                .noneMatch("audit_rejected"::equals)).isTrue();
        assertThat(jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?", Integer.class, data.productId()))
                .isEqualTo(stockBefore);
        assertThat(orders.auditOrders(seller(), "pending", null, 1).total()).isZero();
        String orderNo = String.valueOf(pending.get("orderNo"));
        assertThat(orders.auditOrders(seller(), "rejected", orderNo, 1).total()).isEqualTo(1);
        assertThat(orders.auditOrders(seller(), "rejected", "does-not-match", 1).total()).isZero();
        assertThat(orders.adminOrders(seller(), null, null, null, null, null, 1).total()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_entry WHERE order_id=?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commission_accrual WHERE order_id=?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_event WHERE order_id=? AND action='cancelled'", Long.class, id)).isEqualTo(1L);
        assertThatThrownBy(() -> submitPayment(customer(), id,
                new OrderService.PaymentRequest(data.channelId(), "rejected-audit-tx", new BigDecimal("200.00"))))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("PAYMENT_NOT_ALLOWED");
    }
}

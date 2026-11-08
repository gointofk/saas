package com.dailyactivity.order;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.support.PrdDatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PRD C-05/C-06 订单创建与付款状态")
class PrdOrderPaymentTest extends PrdDatabaseTest {
    @Autowired
    private JdbcTemplate database;

    @Test
    @DisplayName("同一报价只能创建一单，重放被拒绝且库存只扣一次")
    void quoteCanCreateOnlyOneOrderAndStartsUnpaid() {
        // Arrange
        carts.set(customer(), data.productId(), 2);
        Map<String, Object> quote = orders.quote(
                customer(),
                new OrderService.QuoteRequest(data.addressId(), java.util.List.of(new OrderService.CartRequest(data.productId(), 2))));
        String quotePayload = new String(Base64.getUrlDecoder().decode(((String) quote.get("quoteToken")).split("\\.")[0]), StandardCharsets.UTF_8);
        assertThat(quotePayload).doesNotContain("priceTier", "customerType", "unitCost");
        OrderService.CreateRequest request = new OrderService.CreateRequest(
                (String) quote.get("quoteToken"), "single-use quote", java.util.List.of(new OrderService.CartRequest(data.productId(), 2)), data.addressId());

        // Act
        Map<String, Object> first = orders.create(customer(), request);

        // Assert
        assertThat(first)
                .containsEntry("status", "pending")
                .containsEntry("paymentStatus", "unpaid")
                .containsEntry("subtotal", new BigDecimal("180.00"))
                .containsEntry("shippingFee", new BigDecimal("20.00"))
                .containsEntry("total", new BigDecimal("200.00"));
        assertThatThrownBy(() -> orders.create(customer(), request))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("QUOTE_ALREADY_USED");
        assertThat((String) first.get("orderNo")).matches("[0-9]{13}");
        assertThat(database.queryForObject("SELECT COUNT(*) FROM sales_order WHERE customer_id=?", Long.class, data.customerId()))
                .isEqualTo(1L);
        assertThat(database.queryForObject("SELECT stock_available FROM product WHERE id=?", Integer.class, data.productId()))
                .isEqualTo(8);
    }

    @Test
    @DisplayName("客户提交付款后进入待验证，商家核验后才变为已支付且不推进履约")
    void paymentSubmissionAndVerificationAreIndependentFromFulfillment() {
        // Arrange
        Map<String, Object> order = createOrder("payment-order-1", 1);
        long id = orderId(order);
        OrderService.PaymentRequest submission = new OrderService.PaymentRequest(data.channelId(), "shared-tx-001", new BigDecimal("110.00"));

        // Act
        Map<String, Object> pending = submitPayment(customer(), id, submission);
        Map<String, Object> paid = sellerAction(
                seller(), id, "confirm-payment",
                new OrderService.SellerAction(new BigDecimal("110.00"), "verified-tx-001", data.channelId(), "verified offline", null, null, null, null, false));

        // Assert
        assertThat(pending)
                .containsEntry("status", "pending")
                .containsEntry("paymentStatus", "pending_verification");
        assertThat(paid)
                .containsEntry("status", "pending")
                .containsEntry("paymentStatus", "paid");
        assertThat(database.queryForObject("SELECT COUNT(*) FROM payment_submission WHERE order_id=?", Long.class, id))
                .isEqualTo(1L);
        assertThat(database.queryForObject("SELECT verified_transaction_no FROM payment_receipt WHERE order_id=?", String.class, id))
                .isEqualTo("verified-tx-001");
        assertThat(database.queryForObject("SELECT settled_amount FROM billing_entry WHERE order_id=? AND bill_type='order_receivable'", BigDecimal.class, id))
                .isEqualByComparingTo("110.00");
    }

    @Test
    @DisplayName("空交易号被拒绝，订单仍保持待支付")
    void emptyPaymentTransactionDoesNotChangeOrder() {
        // Arrange
        Map<String, Object> order = createOrder("payment-invalid-1", 1);
        long id = orderId(order);

        // Act / Assert
        assertThatThrownBy(() -> submitPayment(customer(), id,
                new OrderService.PaymentRequest(data.channelId(), " ", new BigDecimal("110.00"))))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("TRANSACTION_NO_REQUIRED");
        assertThat(orders.detail(customer(), id))
                .containsEntry("status", "pending")
                .containsEntry("paymentStatus", "unpaid");
    }

    @Test
    @DisplayName("错误申报金额被拒绝，订单仍保持待支付")
    void mismatchedPaymentTotalDoesNotChangeOrder() {
        Map<String, Object> order = createOrder("payment-invalid-total-1", 1);
        long id = orderId(order);

        assertThatThrownBy(() -> submitPayment(customer(), id,
                new OrderService.PaymentRequest(data.channelId(), "tx-002", new BigDecimal("999.00"))))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("TOTAL_MISMATCH");
        assertThat(orders.detail(customer(), id))
                .containsEntry("status", "pending")
                .containsEntry("paymentStatus", "unpaid");
    }

    @Test
    @DisplayName("只有待处理且待支付订单可取消，库存按当前数量只回补一次")
    void cancellationRestoresStockOnceAndVoidsReceivable() {
        // Arrange
        Map<String, Object> order = createOrder("cancel-order-1", 2);
        long id = orderId(order);

        // Act
        Map<String, Object> cancelled = orders.cancel(customer(), id, "Customer changed mind", null);

        // Assert
        assertThat(cancelled)
                .containsEntry("status", "cancelled")
                .containsEntry("paymentStatus", "unpaid");
        assertThat(database.queryForObject("SELECT stock_available FROM product WHERE id=?", Integer.class, data.productId()))
                .isEqualTo(10);
        assertThat(database.queryForObject("SELECT status FROM billing_entry WHERE order_id=? AND bill_type='order_receivable'", String.class, id))
                .isEqualTo("void");
        assertThatThrownBy(() -> orders.cancel(customer(), id, "retry", null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("ORDER_NOT_CANCELLABLE");
        assertThat(database.queryForObject("SELECT COUNT(*) FROM stock_movement WHERE order_id=? AND movement_type='order_cancel'", Long.class, id))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("待审核未支付订单可按数量差额编辑，进入待验证后禁止客户编辑")
    void editUsesStockDeltaAndLocksAfterPaymentSubmission() {
        // Arrange
        Map<String, Object> order = createPendingOrder("edit-order-1", 1);
        long id = orderId(order);

        // Act
        Map<String, Object> edited = orders.updateCustomer(
                customer(), id,
                new OrderService.CustomerUpdate(data.addressId(), "edited note",
                        java.util.List.of(new OrderService.OrderEditItem(data.productId(), 3, null))));
        submitPayment(customer(), id,
                new OrderService.PaymentRequest(data.channelId(), "edit-payment-1", new BigDecimal("290.00")));

        // Assert
        assertThat(edited)
                .containsEntry("subtotal", new BigDecimal("270.00"))
                .containsEntry("total", new BigDecimal("290.00"));
        assertThat(database.queryForObject("SELECT stock_available FROM product WHERE id=?", Integer.class, data.productId()))
                .isEqualTo(7);
        assertThatThrownBy(() -> orders.updateCustomer(
                customer(), id,
                new OrderService.CustomerUpdate(data.addressId(), "not allowed",
                        java.util.List.of(new OrderService.OrderEditItem(data.productId(), 2, null)))))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("ORDER_NOT_EDITABLE");
    }
}

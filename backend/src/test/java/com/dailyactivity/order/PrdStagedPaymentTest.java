package com.dailyactivity.order;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.billing.application.BillingService;
import com.dailyactivity.identity.domain.port.CustomerRepository;
import com.dailyactivity.payment.application.PaymentService;
import com.dailyactivity.support.PrdDatabaseTest;
import com.dailyactivity.support.PrdTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
@DisplayName("分阶段付款：逐笔收款、余额与申报快照")
class PrdStagedPaymentTest extends PrdDatabaseTest {
    @Autowired private BillingService bills;
    @Autowired private CustomerRepository customers;
    @Autowired private PaymentService payments;
    @Autowired private MockMvc http;
    @Autowired private AccountService accounts;

    @Test
    @DisplayName("三笔付款累计付清，部分实收立即进入订单、客户和账单汇总")
    void receivesThreePaymentsWithoutDuplicatingOrderAmounts() {
        // Arrange
        var initialBills = bills.summary(seller());
        var order = createOrder("staged-three", 1);
        long id = orderId(order);
        // Act
        var first = confirm(id, order, "30.00", null, null);
        // Assert
        assertThat(first).containsEntry("paymentStatus", "partially_paid").containsEntry("status", "pending");
        assertAmount(first, "paidAmount", "30.00");
        assertAmount(first, "remainingAmount", "80.00");
        assertAmount(orders.summary(customer()), "payableTotal", "80.00");
        assertThat(customers.find(1L, data.customerId()).orElseThrow().unpaidAmount()).isEqualByComparingTo("80.00");
        assertThat((BigDecimal) bills.summary(seller()).get("receivedAmount"))
                .isEqualByComparingTo(((BigDecimal) initialBills.get("receivedAmount")).add(new BigDecimal("30.00")));
        assertThat((BigDecimal) bills.summary(seller()).get("pendingReceivableAmount"))
                .isEqualByComparingTo(((BigDecimal) initialBills.get("pendingReceivableAmount")).add(new BigDecimal("80.00")));
        var receivable = bills.page(seller(), "order_receivable", "partially_paid", 1).items().stream()
                .filter(bill -> ((Number) bill.get("orderId")).longValue() == id).findFirst().orElseThrow();
        assertAmount(receivable, "settledAmount", "30.00");
        assertAmount(receivable, "remainingAmount", "80.00");
        var submitted = declare(id, first, "40.00");
        assertAmount(submitted, "paidAmount", "30.00");
        assertAmount(submitted, "remainingAmount", "80.00");
        var second = confirm(id, submitted, "40.00", pendingId(submitted), null);
        var last = confirm(id, second, "40.00", null, null);
        assertThat(last).containsEntry("paymentStatus", "paid").containsEntry("status", "pending");
        assertAmount(last, "paidAmount", "110.00");
        assertAmount(last, "remainingAmount", "0.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_receipt WHERE order_id=?", Long.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT settled_amount FROM billing_entry WHERE order_id=? AND bill_type='order_receivable'", BigDecimal.class, id)).isEqualByComparingTo("110.00");
        assertThat(orders.paymentRecords(customer(), id, 1).items()).hasSize(3);
    }

    @Test
    @DisplayName("线上支付链接申请只记录一次，站外收款使用固定虚拟渠道快照")
    void requestsOnlinePaymentLinkAndRecordsExternalReceipt() throws Exception {
        var order = createOrder("online-link", 1);
        long id = orderId(order);
        assertThatThrownBy(() -> orders.requestPaymentLink(customer(), id))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PAYMENT_METHOD_NOT_AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_event WHERE order_id=? AND action='payment_link_requested'", Long.class, id)).isZero();
        payments.updatePaymentMethods(seller(), new PaymentService.PaymentMethodsRequest(true, true));

        String token = (String) accounts.customerLogin(data.customer().merchantId(), data.customerEmail(), PrdTestFixture.CUSTOMER_PASSWORD).get("accessToken");
        String domain = jdbc.queryForObject("SELECT domain FROM merchant WHERE id=?", String.class, data.customer().merchantId());
        var response = http.perform(post("/api/v1/customer/orders/{id}/payment-link-request", id)
                .header(HttpHeaders.HOST, domain)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"paymentLinkRequested\":true");

        var requested = orders.detail(customer(), id);
        assertThat(requested).containsEntry("paymentStatus", "unpaid").containsEntry("paymentLinkRequested", true);
        assertThat(orders.sellerPaymentChannels(seller(), id))
                .anySatisfy(channel -> assertThat(channel).containsEntry("id", 0L).containsEntry("name", "Online payment link"));

        var repeated = orders.requestPaymentLink(customer(), id);
        assertThat(repeated).containsEntry("paymentStatus", "unpaid").containsEntry("paymentLinkRequested", true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_event WHERE order_id=? AND action='payment_link_requested'", Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_submission WHERE order_id=?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_receipt WHERE order_id=?", Long.class, id)).isZero();

        var paid = orders.sellerAction(seller(), id, "confirm-payment", new OrderService.SellerAction(
                (BigDecimal) repeated.get("total"), "external-reference", 0L, null, null, null, null, null,
                false, version(repeated), new BigDecimal("110.00"), null));

        assertThat(paid).containsEntry("paymentStatus", "paid").containsEntry("paymentLinkRequested", true);
        assertThat(jdbc.queryForObject("SELECT channel_id FROM payment_receipt WHERE order_id=?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT channel_snapshot FROM payment_receipt WHERE order_id=?", String.class, id))
                .contains("Online payment link");
        assertThat(orders.paymentRecords(seller(), id, 1).items()).singleElement()
                .satisfies(record -> assertThat(record.get("transactionNo")).isEqualTo("external-reference"));
    }

    @Test
    @DisplayName("停用加密货币后拒绝新的加密货币付款申报")
    void disabledCryptoPaymentCannotBeSubmitted() {
        var order = createOrder("crypto-disabled", 1);
        payments.updatePaymentMethods(seller(), new PaymentService.PaymentMethodsRequest(false, true));

        assertThatThrownBy(() -> orders.submitPayment(customer(), orderId(order), new OrderService.PaymentRequest(
                data.channelId(), "crypto-disabled-reference", (BigDecimal) order.get("total"), version(order), new BigDecimal("30.00"))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PAYMENT_METHOD_NOT_AVAILABLE");
        assertThat(orders.sellerPaymentChannels(seller(), orderId(order)))
                .noneMatch(channel -> ((Number) channel.get("id")).longValue() == data.channelId());
        assertThatThrownBy(() -> orders.sellerAction(seller(), orderId(order), "confirm-payment", new OrderService.SellerAction(
                (BigDecimal) order.get("total"), "crypto-disabled-reference", data.channelId(), null, null, null, null,
                null, false, version(order), new BigDecimal("30.00"), null)))
                .isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_submission WHERE order_id=?", Long.class, orderId(order))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_receipt WHERE order_id=?", Long.class, orderId(order))).isZero();
    }

    @Test
    @DisplayName("核实金额与申报不同必须写原因，原始值保留且客户看不到内部原因")
    void recordsVerifiedDifferenceWithoutExposingInternalNotes() {
        // Arrange
        var order = createOrder("staged-difference", 1);
        long id = orderId(order);
        var pending = declare(id, order, "30.00");
        // Act / Assert
        assertThatThrownBy(() -> confirm(id, pending, "28.00", pendingId(pending), null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PAYMENT_DIFFERENCE_REASON_REQUIRED");
        var received = confirm(id, pending, "28.00", pendingId(pending), "核实抵扣金额为 28");
        assertAmount(received, "paidAmount", "28.00");
        assertAmount(received, "remainingAmount", "82.00");
        var customerRecord = orders.paymentRecords(customer(), id, 1).items().get(0);
        assertAmount(customerRecord, "declaredAmount", "30.00");
        assertAmount(customerRecord, "receivedAmount", "28.00");
        assertThat(customerRecord).doesNotContainKeys("internalNote", "verifiedByName");
        assertThat(orders.paymentRecords(seller(), id, 1).items().get(0)).containsEntry("internalNote", "核实抵扣金额为 28");
        assertThat(orders.detail(customer(), id).get("events").toString()).doesNotContain("核实抵扣金额为 28");
        assertThat(declare(id, received, "20.00")).containsEntry("paymentStatus", "pending_verification");
    }

    @Test
    @DisplayName("申报后审核改正品和优惠，按最新应付收款且保留申报快照")
    void auditCanReduceAmountBelowTheOriginalDeclaration() {
        // Arrange
        var order = createPendingOrder("staged-audit-edit", 1);
        long id = orderId(order);
        var declared = declare(id, order, "110.00");
        // Act
        var edited = orders.updateAudit(seller(), id, new OrderService.AuditUpdate(
                java.util.List.of(new OrderService.AuditEditItem(data.productId(), 1, new BigDecimal("70.00"))),
                "协商改价", version(declared), new BigDecimal("10.00")));
        var approved = orders.decideAudit(seller(), id, new OrderService.AuditDecision("approved", null, version(edited)));
        var paid = confirm(id, approved, "80.00", pendingId(approved), "订单审核后应付改为 80，超额线下处理");
        // Assert
        assertAmount(paid, "paidAmount", "80.00");
        assertThat(paid).containsEntry("paymentStatus", "paid");
        var history = orders.paymentRecords(seller(), id, 1).items().get(0);
        assertAmount(history, "orderTotalSnapshot", "110.00");
        assertAmount(history, "declaredAmount", "110.00");
        assertAmount(history, "receivedAmount", "80.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.001", "111.00"})
    @DisplayName("非法收款金额不写收款、账单和事件")
    void rejectsInvalidReceiptAmounts(String amount) {
        // Arrange
        var order = createOrder("staged-invalid", 1);
        long id = orderId(order);
        // Act / Assert
        assertThatThrownBy(() -> confirm(id, order, amount, null, null)).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_receipt WHERE order_id=?", Long.class, id)).isZero();
        assertAmount(orders.detail(customer(), id), "paidAmount", "0.00");
    }

    @Test
    @DisplayName("重复确认、过期版本和重复申报不能增加实收或申报")
    void rejectsDuplicateAndStaleCommands() {
        // Arrange
        var order = createOrder("staged-replay", 1);
        long id = orderId(order);
        var pending = declare(id, order, "30.00");
        assertThatThrownBy(() -> declare(id, pending, "30.00")).isInstanceOf(ApiException.class);
        var received = confirm(id, pending, "30.00", pendingId(pending), null);
        // Act / Assert
        assertThatThrownBy(() -> confirm(id, pending, "30.00", pendingId(pending), null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_CHANGED");
        assertThatThrownBy(() -> confirm(id, received, "30.00", pendingId(pending), null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PAYMENT_SUBMISSION_CHANGED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_receipt WHERE order_id=?", Long.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("逐笔保存渠道快照，旧渠道修改停用后仍按原申报核验，下一笔可换渠道")
    void preservesSubmissionChannelSnapshot() {
        // Arrange
        var order = createOrder("staged-channel", 1);
        long id = orderId(order);
        var pending = declare(id, order, "30.00");
        String snapshot = jdbc.queryForObject("SELECT channel_snapshot FROM payment_submission WHERE order_id=?", String.class, id);
        jdbc.update("UPDATE payment_channel SET name=CONCAT(name,' changed'),active=0 WHERE id=?", data.channelId());
        // Act
        var received = confirm(id, pending, "30.00", pendingId(pending), null);
        // Assert
        assertThat(jdbc.queryForObject("SELECT channel_snapshot FROM payment_receipt WHERE order_id=?", String.class, id)).isEqualTo(snapshot);
        assertAmount(received, "remainingAmount", "80.00");
        jdbc.update("INSERT INTO payment_channel(merchant_id,name,currency_code,network,receiving_address,instructions,qr_file_uuid,active,sort_no,created_by_user_id,updated_by_user_id) "
                + "SELECT merchant_id,CONCAT(name,' next'),currency_code,network,receiving_address,instructions,qr_file_uuid,1,sort_no,created_by_user_id,updated_by_user_id FROM payment_channel WHERE id=?", data.channelId());
        long replacementId = jdbc.queryForObject("SELECT MAX(id) FROM payment_channel WHERE created_by_user_id=?", Long.class, data.sellerId());
        var next = orders.submitPayment(customer(), id, new OrderService.PaymentRequest(replacementId, "next-channel",
                (BigDecimal) received.get("total"), version(received), new BigDecimal("20.00")));
        assertThat(((Map<?, ?>) next.get("pendingPaymentSubmission")).get("channelId")).isEqualTo(replacementId);
    }

    @Test
    @DisplayName("收款历史固定十条分页，客户归属和后台菜单均校验")
    void paginatesHistoryAndChecksPermissions() {
        // Arrange
        var order = createOrder("staged-history", 1);
        long id = orderId(order);
        for (int index = 0; index < 11; index++) order = confirm(id, order, "1.00", null, null);
        // Act / Assert
        assertThat(orders.paymentRecords(customer(), id, 1).items()).hasSize(10);
        assertThat(orders.paymentRecords(customer(), id, 2).items()).hasSize(1);
        var other = new com.dailyactivity.auth.domain.Principal(data.referrerId(), "customer", 0, data.seller().merchantId());
        assertThatThrownBy(() -> orders.paymentRecords(other, id, 1)).isInstanceOf(ApiException.class);
        var member = PrdTestFixture.seedRestrictedSeller(jdbc, data.sellerId());
        jdbc.update("UPDATE seller_permission SET menu_code='products' WHERE seller_id=?", member.userId());
        assertThatThrownBy(() -> orders.paymentRecords(member, id, 1))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("HTTP 逐笔申报强制金额和版本，旧请求不会默认全额付款")
    void httpSubmissionRequiresExplicitAmountAndVersion() throws Exception {
        // Arrange
        var order = createOrder("staged-http", 1);
        long id = orderId(order);
        String token = (String) accounts.customerLogin(data.customer().merchantId(), data.customerEmail(), PrdTestFixture.CUSTOMER_PASSWORD).get("accessToken");
        String domain = jdbc.queryForObject("SELECT domain FROM merchant WHERE id=?", String.class, data.customer().merchantId());
        String fields = "\"channelId\":" + data.channelId() + ",\"transactionNo\":\"http-tx\",\"expectedTotalAmount\":110";
        // Act
        var missingVersion = http.perform(post("/api/v1/customer/orders/{id}/payment-submissions", id)
                .header(HttpHeaders.HOST, domain)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{" + fields + ",\"declaredAmount\":30}")).andReturn().getResponse();
        var missingAmount = http.perform(post("/api/v1/customer/orders/{id}/payment-submissions", id)
                .header(HttpHeaders.HOST, domain)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{" + fields + ",\"expectedUpdatedAt\":\"" + version(order) + "\"}")).andReturn().getResponse();
        var success = http.perform(post("/api/v1/customer/orders/{id}/payment-submissions", id)
                .header(HttpHeaders.HOST, domain)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{" + fields + ",\"expectedUpdatedAt\":\"" + version(order) + "\",\"declaredAmount\":30}")).andReturn().getResponse();
        // Assert
        assertThat(missingVersion.getStatus()).isEqualTo(400);
        assertThat(missingVersion.getContentAsString()).contains("ORDER_VERSION_REQUIRED");
        assertThat(missingAmount.getStatus()).isEqualTo(400);
        assertThat(missingAmount.getContentAsString()).contains("INVALID_PAYMENT_AMOUNT");
        assertThat(success.getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_submission WHERE order_id=?", Long.class, id)).isEqualTo(1);
        assertAmount(orders.detail(customer(), id), "paidAmount", "0.00");
        var history = http.perform(get("/api/v1/customer/orders/{id}/payments", id)
                .header(HttpHeaders.HOST, domain)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse();
        assertThat(history.getStatus()).isEqualTo(200);
        assertThat(history.getContentAsString()).doesNotContain("internalNote", "verifiedByName");
    }

    private Map<String, Object> declare(long id, Map<String, Object> order, String amount) {
        return orders.submitPayment(customer(), id, new OrderService.PaymentRequest(data.channelId(), "same-transaction-allowed",
                (BigDecimal) order.get("total"), version(order), new BigDecimal(amount)));
    }

    private Map<String, Object> confirm(long id, Map<String, Object> order, String amount, Long submissionId, String note) {
        return orders.sellerAction(seller(), id, "confirm-payment", new OrderService.SellerAction((BigDecimal) order.get("total"),
                "same-transaction-allowed", submissionId == null ? data.channelId() : null, note, null, null, null, null,
                false, version(order), new BigDecimal(amount), submissionId));
    }

    private Long pendingId(Map<String, Object> order) {
        return ((Number) ((Map<?, ?>) order.get("pendingPaymentSubmission")).get("id")).longValue();
    }

    private Instant version(Map<String, Object> order) { return (Instant) order.get("updatedAt"); }

    private void assertAmount(Map<String, Object> values, String key, String amount) {
        assertThat((BigDecimal) values.get(key)).isEqualByComparingTo(amount);
    }
}

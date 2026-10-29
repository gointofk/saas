package com.dailyactivity.order;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Account;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.inventory.domain.port.InventoryRepository;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.order.domain.port.OrderRepository;
import com.dailyactivity.payment.domain.port.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("分阶段付款服务：核实金额、状态、快照与重放保护")
class StagedPaymentServiceTest {
    private static final Instant VERSION = Instant.parse("2026-10-07T00:00:00Z");
    private static final Principal SELLER = new Principal(3, "seller", 0, 1L);
    private static final Principal CUSTOMER = new Principal(2, "customer", 0, 1L);
    private static final String SNAPSHOT = "{\"name\":\"Original channel\",\"additionalRate\":5}";
    private OrderRepository orders;
    private PaymentRepository payments;
    private AccountService accounts;
    private InventoryRepository inventory;
    private OrderService service;

    @BeforeEach
    void setUp() {
        // Arrange
        orders = mock(OrderRepository.class);
        payments = mock(PaymentRepository.class);
        accounts = mock(AccountService.class);
        inventory = mock(InventoryRepository.class);
        service = new OrderService(accounts, null, null, null, orders, null, payments, inventory, null, null, null, new ObjectMapper(), mock(com.dailyactivity.order.application.OrderParcelService.class), mock(com.dailyactivity.catalog.domain.port.QuantityPriceRepository.class));
        var before = row("pending_verification", "30.00");
        when(orders.findForUpdate(1L, 1, null)).thenReturn(Optional.of(before));
        when(orders.find(1L, 1, null)).thenReturn(Optional.of(row("partially_paid", "58.00")));
        when(orders.receivedAmount(1)).thenReturn(new BigDecimal("30.00"));
        when(orders.pendingPaymentSubmission(1)).thenReturn(Optional.of(new OrderRepository.PaymentSubmissionRow(
                8, 5, SNAPSHOT, "original-tx", new BigDecimal("100.00"), VERSION, new BigDecimal("30.00"), "pending")));
        var customer = mock(Account.class);
        when(customer.id()).thenReturn(2L);
        when(accounts.customer(CUSTOMER)).thenReturn(customer);
    }

    @Test
    @DisplayName("按核实金额增加实收并保留原申报渠道，不推进履约")
    void confirmsVerifiedAmountUsingTheOriginalSnapshot() {
        // Act
        var result = service.sellerAction(SELLER, 1, "confirm-payment", action("28.00", "差异原因", VERSION, 8L));
        // Assert
        assertThat(result).containsEntry("paymentStatus", "partially_paid").containsEntry("status", "pending");
        verify(orders).savePaymentReceipt(new OrderRepository.PaymentReceiptInput(1, 8L, 5, "verified-tx", new BigDecimal("28.00"), "差异原因"), SNAPSHOT, new com.dailyactivity.auth.domain.Actor("seller",3));
        verify(orders).confirmSubmission(8, new com.dailyactivity.auth.domain.Actor("seller",3));
        verify(orders).updatePaymentTotals(1, "partially_paid", new BigDecimal("58.00"), new com.dailyactivity.auth.domain.Actor("seller",3));
        verify(orders, never()).updateStatus(anyLong(), anyString(), any(), any(), any(com.dailyactivity.auth.domain.Actor.class));
        verifyNoInteractions(payments, inventory);
    }

    @Test
    @DisplayName("最后一笔才将订单和账单标记付清")
    void marksPaidOnlyWhenTheBalanceReachesZero() {
        // Arrange
        when(orders.find(1L, 1, null)).thenReturn(Optional.of(row("paid", "100.00")));
        // Act
        service.sellerAction(SELLER, 1, "confirm-payment", action("70.00", "核实本次抵扣 70", VERSION, 8L));
        // Assert
        verify(orders).updatePaymentTotals(1, "paid", new BigDecimal("100.00"), new com.dailyactivity.auth.domain.Actor("seller",3));
    }

    @Test
    @DisplayName("金额差异未写原因时所有资金写操作均拒绝")
    void requiresAReasonForDeclaredAmountDifferences() {
        // Act / Assert
        assertFailure(action("28.00", null, VERSION, 8L), "PAYMENT_DIFFERENCE_REASON_REQUIRED");
        verifyNoFinancialWrites();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "0.001", "71.00"})
    @DisplayName("拒绝缺失、非正、超精度和超余额的收款金额")
    void rejectsInvalidAmounts(String amount) {
        // Act / Assert
        assertFailure(action(amount, "note", VERSION, 8L), "71.00".equals(amount) ? "PAYMENT_EXCEEDS_REMAINING" : "INVALID_PAYMENT_AMOUNT");
        verifyNoFinancialWrites();
    }

    @Test
    @DisplayName("缺少版本、过期版本和申报ID变化不写资金")
    void requiresCurrentVersionAndSubmission() {
        // Act / Assert
        assertFailure(action("30.00", null, null, 8L), "ORDER_VERSION_REQUIRED");
        assertFailure(action("30.00", null, VERSION.minusSeconds(1), 8L), "ORDER_CHANGED");
        assertFailure(action("30.00", null, VERSION, null), "PAYMENT_SUBMISSION_CHANGED");
        verifyNoFinancialWrites();
    }

    @Test
    @DisplayName("部分付款后的新申报使用剩余余额，申报不会增加实收")
    void declaresAnotherPaymentAgainstTheRemainingBalance() {
        // Arrange
        var before = row("partially_paid", "30.00");
        when(orders.findForUpdate(1L, 1, 2L)).thenReturn(Optional.of(before));
        when(orders.find(1L, 1, 2L)).thenReturn(Optional.of(row("pending_verification", "30.00")));
        when(orders.pendingPaymentSubmission(1)).thenReturn(Optional.empty());
        var channel = new PaymentRepository.Channel(5, "New channel", "USD", "TEST", "address", null, null, "qr", true, 1);
        when(payments.channel(1L, 5, true)).thenReturn(Optional.of(channel));
        // Act
        service.submitPayment(CUSTOMER, 1, new OrderService.PaymentRequest(5, "next-tx", new BigDecimal("100.00"), VERSION, new BigDecimal("20.00")));
        // Assert
        verify(orders).savePaymentSubmission(new OrderRepository.PaymentSubmissionInput(1, 2, 5, "next-tx", new BigDecimal("100.00"), new BigDecimal("20.00")), channel.snapshot(), new com.dailyactivity.auth.domain.Actor("customer",2));
        verify(orders).updatePaymentTotals(1, "pending_verification", new BigDecimal("30.00"), new com.dailyactivity.auth.domain.Actor("customer",2));
        verify(orders, never()).savePaymentReceipt(any(), anyString(), any(com.dailyactivity.auth.domain.Actor.class));
    }

    @Test
    @DisplayName("付款历史隐藏客户不可见的内部原因和核验人")
    void excludesInternalNotesFromCustomerHistory() {
        // Arrange
        when(orders.find(1L, 1, 2L)).thenReturn(Optional.of(row("partially_paid", "58.00")));
        when(orders.paymentRecords(1, 1)).thenReturn(List.of(new OrderRepository.PaymentRecordRow(
                8L, 9L, 5, SNAPSHOT, "original", "verified", new BigDecimal("100.00"), new BigDecimal("30.00"),
                new BigDecimal("28.00"), "confirmed", VERSION, VERSION, "Seller", "private reason", 5L, SNAPSHOT)));
        // Act
        var record = service.paymentRecords(CUSTOMER, 1, 1).items().get(0);
        // Assert
        assertThat(record).doesNotContainKeys("internalNote", "verifiedByName");
        assertThat(record).containsEntry("originalTransactionNo", "original").containsEntry("transactionNo", "verified");
    }

    private OrderService.SellerAction action(String amount, String note, Instant version, Long submissionId) {
        return new OrderService.SellerAction(new BigDecimal("100.00"), "verified-tx", null, note, null, null, null, null, false,
                version, amount == null ? null : new BigDecimal(amount), submissionId);
    }

    private void assertFailure(OrderService.SellerAction action, String code) {
        assertThatThrownBy(() -> service.sellerAction(SELLER, 1, "confirm-payment", action))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);
    }

    private void verifyNoFinancialWrites() {
        verify(orders, never()).savePaymentReceipt(any(), anyString(), any(com.dailyactivity.auth.domain.Actor.class));
        verify(orders, never()).confirmSubmission(anyLong(), any(com.dailyactivity.auth.domain.Actor.class));
        verify(orders, never()).updatePaymentTotals(anyLong(), anyString(), any(), any(com.dailyactivity.auth.domain.Actor.class));
    }

    private OrderRepository.OrderRow row(String paymentStatus, String paidAmount) {
        return new OrderRepository.OrderRow(1, "TST-20261007-001", 2, "TST", "Test customer", "customer@example.test",
                "pending", paymentStatus, "{}", "Recipient", "City", null, null,
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"),
                null, null, null, VERSION, null, VERSION, null, "approved", null, null, null, null, new BigDecimal(paidAmount));
    }
}

package com.dailyactivity.order;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.identity.domain.port.CustomerRepository;
import com.dailyactivity.inventory.domain.port.InventoryRepository;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.order.domain.port.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("订单审核优惠的金额与付款门禁")
class OrderDiscountTest {
    private static final Instant VERSION = Instant.parse("2026-10-02T00:00:00Z");
    private static final Principal SELLER = new Principal(3, "seller", 0, 1L);
    private OrderRepository repository;
    private CustomerRepository customers;
    private InventoryRepository inventory;
    private OrderService service;

    @BeforeEach
    void setUp() {
        repository = mock(OrderRepository.class);
        when(repository.receivedAmount(anyLong())).thenReturn(BigDecimal.ZERO);
        customers = mock(CustomerRepository.class);
        inventory = mock(InventoryRepository.class);
        service = new OrderService(mock(AccountService.class), null, null, null, repository,
                customers, null, inventory, null, null, null, new ObjectMapper(), mock(com.dailyactivity.order.application.OrderParcelService.class), mock(com.dailyactivity.catalog.domain.port.QuantityPriceRepository.class));
    }

    @Test
    @DisplayName("编辑保存优惠 20 立即更新应付 80，保持待审核并记录金额快照")
    void editSavesDiscountBeforeApproval() {
        // Arrange
        prepareEdit("0.00");
        when(repository.findAuditOrder(1L, 1)).thenReturn(Optional.of(row("unpaid", "pending", "20.00")));

        // Act
        var result = service.updateAudit(SELLER, 1, edit("20.00", VERSION));

        // Assert
        assertThat(result).containsEntry("auditStatus", "pending");
        assertThat((BigDecimal) result.get("discountAmount")).isEqualByComparingTo("20.00");
        assertThat((BigDecimal) result.get("total")).isEqualByComparingTo("80.00");
        verify(repository).updateOrderAmounts(1, new BigDecimal("100.00"), new BigDecimal("20.00"), new BigDecimal("80.00"), new com.dailyactivity.auth.domain.Actor("seller",3));
        verify(repository).addEvent(eq(1L), anyString(), eq("audit_updated"), eq("pending"), eq("pending"),
                contains("\"discountAmount\":0.00"), contains("\"discountAmount\":20.00"), isNull(), eq(new com.dailyactivity.auth.domain.Actor("seller",3L)));
        verify(repository, never()).decideAudit(anyLong(), anyString(), any(), any(com.dailyactivity.auth.domain.Actor.class));
        verify(repository, never()).createReceivable(any(), any(com.dailyactivity.auth.domain.Actor.class));
        verify(repository, never()).createAccrual(any(), any(com.dailyactivity.auth.domain.Actor.class));
        verifyNoInteractions(inventory);
    }

    @Test
    @DisplayName("确认收款后不能修改审核优惠")
    void paymentLocksDiscountEditing() {
        when(repository.findAuditOrderForUpdate(1L, 1)).thenReturn(Optional.of(row("paid", "pending", "20.00")));
        when(repository.receivedAmount(1)).thenReturn(new BigDecimal("80.00"));
        for (String discount : List.of("0.00", "10.00", "30.00")) {
            assertThatThrownBy(() -> service.updateAudit(SELLER, 1, edit(discount, VERSION)))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_AMOUNT_LOCKED");
        }
        verifyNoWrites();
    }

    @Test
    @DisplayName("付款已申报仍可修改审核优惠，原始申报不被覆盖")
    void submissionFactLocksDiscountEditing() {
        prepareEdit("0.00");
        when(repository.findAuditOrderForUpdate(1L, 1)).thenReturn(Optional.of(row("pending_verification", "pending", "0.00")));
        when(repository.findAuditOrder(1L, 1)).thenReturn(Optional.of(row("pending_verification", "pending", "20.00")));
        service.updateAudit(SELLER, 1, edit("20.00", VERSION));
        verify(repository).updateOrderAmounts(1, new BigDecimal("100.00"), new BigDecimal("20.00"), new BigDecimal("80.00"), new com.dailyactivity.auth.domain.Actor("seller",3));
        verify(repository, never()).savePaymentSubmission(any(), anyString(), any(com.dailyactivity.auth.domain.Actor.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "0.001", "100.01", "100.00"})
    @DisplayName("编辑优惠校验金额边界，非法输入不改商品、库存和订单")
    void invalidEditDiscountDoesNotWrite(String discount) {
        prepareEdit("0.00");

        assertThatThrownBy(() -> service.updateAudit(SELLER, 1, edit(discount, VERSION)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_DISCOUNT_AMOUNT");
        verifyNoWrites();
        verify(repository, never()).updateItem(anyLong(), anyInt(), any(), any(com.dailyactivity.auth.domain.Actor.class));
        verifyNoInteractions(inventory);
    }

    @Test
    @DisplayName("编辑优惠必须携带当前订单版本")
    void editDiscountRejectsMissingAndStaleVersions() {
        when(repository.findAuditOrderForUpdate(1L, 1)).thenReturn(Optional.of(row("unpaid", "pending", "0.00")));

        assertThatThrownBy(() -> service.updateAudit(SELLER, 1, edit("20.00", null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_VERSION_REQUIRED");
        assertThatThrownBy(() -> service.updateAudit(SELLER, 1, edit("20.00", VERSION.minusSeconds(1))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_CHANGED");
        verifyNoWrites();
    }

    @Test
    @DisplayName("编辑已保存优惠后买家申报，审核通过沿用优惠金额并创建净额应收")
    void approvalPreservesSavedDiscountAfterPaymentSubmission() {
        var pending = row("pending_verification", "pending", "20.00");
        var approved = row("pending_verification", "approved", "20.00");
        when(repository.findAuditOrderForUpdate(1L, 1)).thenReturn(Optional.of(pending));
        when(repository.findForUpdate(1L, 1, 2L)).thenReturn(Optional.of(approved));
        when(repository.find(1L, 1, 2L)).thenReturn(Optional.of(approved));
        when(repository.paymentSubmission(1)).thenReturn(Optional.of(new OrderRepository.PaymentSubmissionRow(
                4, 5, "{}", "discount-paid", new BigDecimal("80.00"), VERSION, new BigDecimal("80.00"), "pending")));

        var result = service.decideAudit(SELLER, 1, new OrderService.AuditDecision("approved", null, VERSION));

        assertThat(result).containsEntry("auditStatus", "approved").containsEntry("paymentStatus", "pending_verification");
        assertThat((BigDecimal) result.get("total")).isEqualByComparingTo("80.00");
        verify(repository, never()).updateOrderAmounts(anyLong(), any(), any(), any(), any(com.dailyactivity.auth.domain.Actor.class));
        verify(repository).createReceivable(approved, new com.dailyactivity.auth.domain.Actor("seller",3));
        verify(repository).createAccrual(approved, new com.dailyactivity.auth.domain.Actor("seller",3));
    }

    @Test
    @DisplayName("已有优惠的待审核订单仍可拒绝，并保留优惠金额快照")
    void rejectionPreservesSavedDiscount() {
        when(repository.findAuditOrderForUpdate(1L, 1)).thenReturn(Optional.of(row("unpaid", "pending", "20.00")));
        when(repository.find(1L, 1, 2L)).thenReturn(Optional.of(row("unpaid", "rejected", "20.00")));

        var result = service.decideAudit(SELLER, 1, new OrderService.AuditDecision("rejected", null, VERSION));

        assertThat((BigDecimal) result.get("discountAmount")).isEqualByComparingTo("20.00");
        verify(repository).decideAudit(1, "rejected", null, new com.dailyactivity.auth.domain.Actor("seller",3));
        verify(repository).updateStatus(1, "cancelled", null, null, new com.dailyactivity.auth.domain.Actor("seller",3));
        verify(repository, never()).updateOrderAmounts(anyLong(), any(), any(), any(), any(com.dailyactivity.auth.domain.Actor.class));
        verify(repository, never()).createReceivable(any(), any(com.dailyactivity.auth.domain.Actor.class));
    }

    @Test
    @DisplayName("未传优惠字段的旧编辑请求沿用已保存优惠")
    void omittedEditDiscountPreservesSavedAmount() {
        prepareEdit("20.00");
        when(repository.findAuditOrder(1L, 1)).thenReturn(Optional.of(row("unpaid", "pending", "20.00")));

        service.updateAudit(SELLER, 1, edit(null, VERSION));

        verify(repository).updateOrderAmounts(1, new BigDecimal("100.00"), new BigDecimal("20.00"), new BigDecimal("80.00"), new com.dailyactivity.auth.domain.Actor("seller",3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "10.00", "30.00"})
    @DisplayName("尚未申报付款时编辑可清除、减少或增加已有优惠")
    void unpaidEditCanChangeSavedDiscount(String discount) {
        prepareEdit("20.00");
        when(repository.findAuditOrder(1L, 1)).thenReturn(Optional.of(row("unpaid", "pending", discount)));

        service.updateAudit(SELLER, 1, edit(discount, VERSION));

        var amount = new BigDecimal(discount);
        verify(repository).updateOrderAmounts(1, new BigDecimal("100.00"), amount, new BigDecimal("100.00").subtract(amount), new com.dailyactivity.auth.domain.Actor("seller",3));
    }

    private void prepareEdit(String discount) {
        when(repository.findAuditOrderForUpdate(1L, 1)).thenReturn(Optional.of(row("unpaid", "pending", discount)));
        when(customers.find(1L, 2)).thenReturn(Optional.of(new CustomerRepository.Customer(2, "TST", "Test customer",
                "customer@example.test", "regular", "V1", true, null, BigDecimal.ZERO, 0, 0,
                BigDecimal.ZERO, 0, BigDecimal.ZERO, null, VERSION)));
        when(repository.items(1)).thenReturn(List.of(new OrderRepository.ItemRow(11, 3, "TEST-SKU", "Test product", "regular", 1,
                new BigDecimal("100.00"), new BigDecimal("100.00"))));
    }

    private OrderService.AuditUpdate edit(String discount, Instant version) {
        return new OrderService.AuditUpdate(List.of(new OrderService.AuditEditItem(3, 1, new BigDecimal("100.00"))),
                null, version, discount == null ? null : new BigDecimal(discount));
    }

    @Test
    @DisplayName("优惠 20 后保留原价 100，按应付 80 创建财务记录并保存审计快照")
    void approvalRetainsGrossAmountAndCreatesNetReceivable() {
        var pending = row("unpaid", "pending", "0.00");
        var approved = row("unpaid", "approved", "20.00");
        when(repository.findAuditOrderForUpdate(1L, 1)).thenReturn(Optional.of(pending));
        when(repository.findForUpdate(1L, 1, 2L)).thenReturn(Optional.of(approved));
        when(repository.find(1L, 1, 2L)).thenReturn(Optional.of(approved));

        var result = service.decideAudit(SELLER, 1, decision("20.00"));

        assertThat((BigDecimal) result.get("subtotal")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) result.get("grossTotal")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) result.get("discountAmount")).isEqualByComparingTo("20.00");
        assertThat((BigDecimal) result.get("total")).isEqualByComparingTo("80.00");
        var writes = inOrder(repository);
        writes.verify(repository).updateOrderAmounts(1, new BigDecimal("100.00"), new BigDecimal("20.00"), new BigDecimal("80.00"), new com.dailyactivity.auth.domain.Actor("seller",3));
        writes.verify(repository).decideAudit(1, "approved", null, new com.dailyactivity.auth.domain.Actor("seller",3));
        writes.verify(repository).createReceivable(approved, new com.dailyactivity.auth.domain.Actor("seller",3));
        writes.verify(repository).createAccrual(approved, new com.dailyactivity.auth.domain.Actor("seller",3));
        verify(repository).addEvent(eq(1L), anyString(), eq("audit_discounted"), eq("pending"), eq("pending"),
                contains("\"total\":100.00"), contains("\"discountAmount\":20.00"), contains("80.00"), eq(new com.dailyactivity.auth.domain.Actor("seller",3L)));
    }

    @Test
    @DisplayName("确认收款事实阻止审核改金额")
    void declaredOrPaidOrderCannotReceiveDiscount() {
        when(repository.findAuditOrderForUpdate(1L, 1)).thenReturn(Optional.of(row("paid", "pending", "0.00")));
        when(repository.receivedAmount(1)).thenReturn(new BigDecimal("100.00"));
        assertFailure(decision("20.00"), "ORDER_AMOUNT_LOCKED");
        verifyNoWrites();
    }

    @Test
    @DisplayName("申报后的审核通过可使用最新优惠金额")
    void paymentSubmissionFactPreventsDiscount() {
        when(repository.findAuditOrderForUpdate(1L, 1)).thenReturn(Optional.of(row("pending_verification", "pending", "0.00")));
        var approved = row("pending_verification", "approved", "20.00");
        when(repository.findForUpdate(1L, 1, 2L)).thenReturn(Optional.of(approved));
        when(repository.find(1L, 1, 2L)).thenReturn(Optional.of(approved));
        service.decideAudit(SELLER, 1, decision("20.00"));
        verify(repository).createReceivable(approved, new com.dailyactivity.auth.domain.Actor("seller",3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "0.001", "100.01", "100.00", "9999999999999999.99"})
    @DisplayName("负数、超精度、超商品金额和零应付均拒绝且不落审核")
    void invalidDiscountDoesNotWrite(String discount) {
        when(repository.findAuditOrderForUpdate(1L, 1)).thenReturn(Optional.of(row("unpaid", "pending", "0.00")));

        assertFailure(decision(discount), "INVALID_DISCOUNT_AMOUNT");
        verifyNoWrites();
    }

    @Test
    @DisplayName("过期版本拒绝优惠审核，避免覆盖买家或其他审核人的变更")
    void staleVersionDoesNotWrite() {
        when(repository.findAuditOrderForUpdate(1L, 1)).thenReturn(Optional.of(row("unpaid", "pending", "0.00")));

        assertFailure(new OrderService.AuditDecision("approved", null, VERSION.minusSeconds(1), new BigDecimal("20.00")), "ORDER_CHANGED");
        verifyNoWrites();
    }

    @Test
    @DisplayName("设置优惠需要提供已读取的订单版本")
    void discountRequiresVersion() {
        when(repository.findAuditOrderForUpdate(1L, 1)).thenReturn(Optional.of(row("unpaid", "pending", "0.00")));

        assertFailure(new OrderService.AuditDecision("approved", null, null, new BigDecimal("20.00")), "ORDER_VERSION_REQUIRED");
        verifyNoWrites();
    }

    @Test
    @DisplayName("拒绝审核不能附带应用优惠")
    void rejectionCannotApplyDiscount() {
        when(repository.findAuditOrderForUpdate(1L, 1)).thenReturn(Optional.of(row("unpaid", "pending", "0.00")));

        assertFailure(new OrderService.AuditDecision("rejected", null, VERSION, new BigDecimal("20.00")), "DISCOUNT_REQUIRES_APPROVAL");
        verifyNoWrites();
    }

    private OrderService.AuditDecision decision(String discount) {
        return new OrderService.AuditDecision("approved", null, VERSION, new BigDecimal(discount));
    }

    private void assertFailure(OrderService.AuditDecision decision, String code) {
        assertThatThrownBy(() -> service.decideAudit(SELLER, 1, decision))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);
    }

    private void verifyNoWrites() {
        verify(repository, never()).updateOrderAmounts(anyLong(), any(), any(), any(), any(com.dailyactivity.auth.domain.Actor.class));
        verify(repository, never()).decideAudit(anyLong(), anyString(), any(), any(com.dailyactivity.auth.domain.Actor.class));
        verify(repository, never()).createReceivable(any(), any(com.dailyactivity.auth.domain.Actor.class));
        verify(repository, never()).createAccrual(any(), any(com.dailyactivity.auth.domain.Actor.class));
        verify(repository, never()).addEvent(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any(), any(com.dailyactivity.auth.domain.Actor.class));
    }

    private OrderRepository.OrderRow row(String paymentStatus, String auditStatus, String discount) {
        BigDecimal amount = new BigDecimal(discount);
        return new OrderRepository.OrderRow(1, "TST-20261002-001", 2, "TST", "Test customer", "customer@example.test",
                "rejected".equals(auditStatus) ? "cancelled" : "pending", paymentStatus, "{}", "Recipient", "City", null, null,
                new BigDecimal("100.00"), new BigDecimal("0.00"), amount, new BigDecimal("100.00").subtract(amount),
                null, null, null, VERSION, null, VERSION, null, auditStatus, null, null, null, null, "paid".equals(paymentStatus) ? new BigDecimal("100.00") : BigDecimal.ZERO, false);
    }
}

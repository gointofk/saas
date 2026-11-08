package com.dailyactivity.order;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.commission.application.CommissionService;
import com.dailyactivity.commission.domain.port.CommissionRepository;
import com.dailyactivity.export.application.ExportService;
import com.dailyactivity.identity.application.CustomerService;
import com.dailyactivity.order.domain.port.OrderRepository;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.support.PrdDatabaseTest;
import com.dailyactivity.support.TestFileServiceConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("订单优惠、客户展示及资金事实一致性")
@Import(TestFileServiceConfiguration.class)
class PrdOrderDiscountTest extends PrdDatabaseTest {
    @Autowired
    private ExportService exports;

    @Autowired
    private CommissionService commissions;

    @Autowired
    private CustomerService customers;

    @Autowired
    private OrderRepository repository;

    @Test
    @DisplayName("总价 100 优惠 20 后买家显示优惠，应收、申报和实收均为 80")
    void discountFlowsThroughCustomerViewBillingPaymentAndExport() {
        var pending = createPendingOrder("discount-payment", 1);
        long id = orderId(pending);
        var edited = orders.updateAudit(seller(), id, new OrderService.AuditUpdate(
                List.of(new OrderService.AuditEditItem(data.productId(), 1, new BigDecimal("80.00"))), "协商商品价格", version(pending), new BigDecimal("20.00")));
        assertThat(edited).containsEntry("auditStatus", "pending");
        assertThat((BigDecimal) orders.detail(customer(), id).get("discountAmount")).isEqualByComparingTo("20.00");
        assertAmount("SELECT total_amount FROM sales_order WHERE id=?", id, "80.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_entry WHERE order_id=?", Long.class, id)).isZero();
        var approved = approve(edited);
        assertThat(orders.auditOrders(seller(), "approved", (String) approved.get("orderNo"), 1).items())
                .singleElement().satisfies(item -> assertThat(item.get("createdAt")).isEqualTo(pending.get("createdAt")));

        var detail = orders.detail(customer(), id);
        assertThat((BigDecimal) detail.get("subtotal")).isEqualByComparingTo("80.00");
        assertThat((BigDecimal) detail.get("grossTotal")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) detail.get("discountAmount")).isEqualByComparingTo("20.00");
        assertThat((BigDecimal) detail.get("total")).isEqualByComparingTo("80.00");
        assertThat(detail).doesNotContainKeys("auditRemark", "auditedByUserId");
        assertThat((List<?>) detail.get("events"))
                .noneMatch(event -> String.valueOf(((Map<?, ?>) event).get("type")).startsWith("audit_"));
        assertAmount("SELECT amount FROM billing_entry WHERE order_id=? AND bill_type='order_receivable'", id, "80.00");
        assertAmount("SELECT base_amount FROM commission_accrual WHERE order_id=?", id, "60.00");
        assertAmount("SELECT commission_amount FROM commission_accrual WHERE order_id=?", id, "3.00");
        assertAmount("SELECT amount FROM billing_entry WHERE order_id=? AND bill_type='commission_payable'", id, "3.00");
        String commissionCsv = new String(exports.export(seller(), "commissions", new ExportService.ExportRequest(
                Map.of("q", (String) detail.get("orderNo")))).content(), StandardCharsets.UTF_8);
        assertThat(commissionCsv).contains("\"佣金基数\",\"锁定佣金率\",\"佣金额\"", "\"60.00\",\"5.00\",\"3.00\"");
        String csv = new String(exports.export(seller(), "orders", new ExportService.ExportRequest(
                Map.of("q", (String) detail.get("orderNo")))).content(), StandardCharsets.UTF_8);
        assertThat(csv).contains("\"运费\",\"优惠额\",\"订单总额\"", "\"80.00\",\"20.00\",\"20.00\",\"80.00\"");

        assertThatThrownBy(() -> submitPayment(customer(), id, new OrderService.PaymentRequest(
                data.channelId(), "old-total", new BigDecimal("100.00"), version(approved))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("TOTAL_MISMATCH");
        var declared = submitPayment(customer(), id, new OrderService.PaymentRequest(
                data.channelId(), "discount-paid", new BigDecimal("80.00"), version(approved)));
        assertAmount("SELECT declared_order_total FROM payment_submission WHERE order_id=?", id, "80.00");
        sellerAction(seller(), id, "confirm-payment", new OrderService.SellerAction(
                new BigDecimal("80.00"), "discount-verified", null, null, null, null, null, null, false, version(declared)));
        assertAmount("SELECT received_amount FROM payment_receipt WHERE order_id=?", id, "80.00");
        assertAmount("SELECT settled_amount FROM billing_entry WHERE order_id=? AND bill_type='order_receivable'", id, "80.00");
    }

    @Test
    @DisplayName("申报后可修改优惠，审核按新金额入账，旧申报金额保留")
    void preAuditSubmissionLocksDiscountWithoutBlockingApproval() {
        var pending = createPendingOrder("discount-declared", 1);
        long id = orderId(pending);
        var declared = submitPayment(customer(), id, new OrderService.PaymentRequest(
                data.channelId(), "already-declared", new BigDecimal("110.00"), version(pending)));
        assertThat((List<?>) orders.auditDetail(seller(), id).get("allowedActions")).anyMatch("audit_discount"::equals);
        var edited = saveDiscount(declared, "20.00");
        var approved = approve(edited);
        assertThat(approved).containsEntry("paymentStatus", "pending_verification");
        assertAmount("SELECT amount FROM billing_entry WHERE order_id=? AND bill_type='order_receivable'", id, "90.00");
        assertAmount("SELECT declared_order_total FROM payment_submission WHERE order_id=?", id, "110.00");
        assertAmount("SELECT declared_amount FROM payment_submission WHERE order_id=?", id, "110.00");
    }

    @Test
    @DisplayName("已保存优惠申报后仍可调整优惠，申报保留旧金额")
    void savedDiscountCanBeDeclaredBeforeApproval() {
        var pending = createPendingOrder("discount-edit-declare", 1);
        long id = orderId(pending);
        var edited = saveDiscount(pending, "20.00");
        var declared = submitPayment(customer(), id, new OrderService.PaymentRequest(
                data.channelId(), "declared-after-edit", new BigDecimal("90.00"), version(edited)));
        var changed = saveDiscount(declared, "30.00");
        var approved = approve(changed);
        assertThat(approved).containsEntry("auditStatus", "approved").containsEntry("paymentStatus", "pending_verification");
        assertAmount("SELECT discount_amount FROM sales_order WHERE id=?", id, "30.00");
        assertAmount("SELECT declared_order_total FROM payment_submission WHERE order_id=?", id, "90.00");
        assertAmount("SELECT amount FROM billing_entry WHERE order_id=? AND bill_type='order_receivable'", id, "80.00");
    }

    @Test
    @DisplayName("客户修改数量保留优惠额并同步应收和佣金，避免优惠被覆盖")
    void customerQuantityEditRetainsDiscount() {
        var approved = saveDiscount(createPendingOrder("discount-edit", 1), "20.00");
        long id = orderId(approved);

        var edited = orders.updateCustomer(customer(), id, new OrderService.CustomerUpdate(data.addressId(), null,
                List.of(new OrderService.OrderEditItem(data.productId(), 2, null)), version(approved)));

        approve(edited);
        assertThat((BigDecimal) edited.get("discountAmount")).isEqualByComparingTo("20.00");
        assertThat((BigDecimal) edited.get("total")).isEqualByComparingTo("180.00");
        assertAmount("SELECT amount FROM billing_entry WHERE order_id=? AND bill_type='order_receivable'", id, "180.00");
        assertAmount("SELECT base_amount FROM commission_accrual WHERE order_id=?", id, "160.00");
        assertAmount("SELECT commission_amount FROM commission_accrual WHERE order_id=?", id, "8.00");
        assertAmount("SELECT amount FROM billing_entry WHERE order_id=? AND bill_type='commission_payable'", id, "8.00");
    }

    @Test
    @DisplayName("审核通过后可结佣，结算前编辑更新佣金，结算后禁止改金额或取消")
    void discountedBaseFlowsThroughReferralCompletionSettlementAndExport() {
        // Arrange
        var pending = createPendingOrder("discount-commission-settlement", 1);
        long id = orderId(pending);
        var edited = orders.updateAudit(seller(), id, new OrderService.AuditUpdate(
                List.of(new OrderService.AuditEditItem(data.productId(), 1, new BigDecimal("100.00"))),
                "协商价格和优惠", version(pending), new BigDecimal("20.00")));
        var referrer = new Principal(data.referrerId(), "customer", 0, data.seller().merchantId());

        // Act
        var approved = approve(edited);
        assertAmount("SELECT base_amount FROM commission_accrual WHERE order_id=?", id, "80.00");
        assertAmount("SELECT commission_amount FROM commission_accrual WHERE order_id=?", id, "4.00");
        assertThat((BigDecimal) customers.referralSummary(referrer).get("estimatedCommissionAmount")).isZero();
        assertThat((BigDecimal) customers.referralSummary(referrer).get("payableCommissionAmount")).isEqualByComparingTo("4.00");
        long accrualId = jdbc.queryForObject("SELECT id FROM commission_accrual WHERE order_id=?", Long.class, id);
        commissions.settle(seller(), new CommissionService.SettlementRequest(
                List.of(new CommissionRepository.SettlementRequest(accrualId, new BigDecimal("4.00"))), "Paid offline", true));

        assertThatThrownBy(() -> orders.updateCustomer(customer(), id, new OrderService.CustomerUpdate(data.addressId(), null,
                List.of(new OrderService.OrderEditItem(data.productId(), 2, null)), version(approved))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_ITEMS_LOCKED");
        assertThatThrownBy(() -> orders.cancel(customer(), id, "佣金已结算", version(approved)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_COMMISSION_SETTLED");
        completeOrder(id);

        // Assert
        assertAmount("SELECT base_amount FROM commission_settlement WHERE order_id=?", id, "80.00");
        assertAmount("SELECT commission_amount FROM commission_settlement WHERE order_id=?", id, "4.00");
        assertAmount("SELECT amount FROM billing_entry WHERE order_id=? AND bill_type='commission_payable'", id, "4.00");
        assertAmount("SELECT settled_amount FROM billing_entry WHERE order_id=? AND bill_type='commission_payable'", id, "4.00");
        assertThat(customers.referrals(referrer)).singleElement().satisfies(row ->
                assertThat((BigDecimal) row.get("settledCommissionAmount")).isEqualByComparingTo("4.00"));
        assertThat((BigDecimal) customers.referralSummary(referrer).get("settledCommissionAmount")).isEqualByComparingTo("4.00");
        // Updating a current order or customer cannot recalculate the settled commission fact.
        jdbc.update("UPDATE `user` SET commission_rate=10 WHERE id=?", data.customerId());
        repository.updateOrderAmounts(id, new BigDecimal("200.00"), new BigDecimal("20.00"), new BigDecimal("200.00"), data.seller().actor());
        repository.updateBillingPayable(id, data.seller().actor());
        assertAmount("SELECT base_amount FROM commission_accrual WHERE order_id=?", id, "80.00");
        assertAmount("SELECT commission_amount FROM commission_accrual WHERE order_id=?", id, "4.00");
        assertAmount("SELECT commission_amount FROM commission_settlement WHERE order_id=?", id, "4.00");
        assertThat(jdbc.queryForObject("SELECT status FROM billing_entry WHERE order_id=? AND bill_type='commission_payable'", String.class, id))
                .isEqualTo("settled");
        String csv = new String(exports.export(seller(), "commissions", new ExportService.ExportRequest(
                Map.of("q", (String) pending.get("orderNo")))).content(), StandardCharsets.UTF_8);
        assertThat(csv).contains("\"80.00\",\"5.00\",\"4.00\"");
    }

    @Test
    @DisplayName("审核通过后商品和佣金金额冻结，完成不再重算佣金")
    void approvedCommissionTracksEditsUntilSettlementAndCompletionDoesNotRecalculate() {
        // Arrange
        var approved = approveWithDiscount(createPendingOrder("discount-commission-finalization", 1), "20.00");
        long id = orderId(approved);
        assertAmount("SELECT base_amount FROM commission_accrual WHERE order_id=?", id, "70.00");
        assertAmount("SELECT commission_amount FROM commission_accrual WHERE order_id=?", id, "3.50");

        assertThatThrownBy(() -> orders.updateCustomer(customer(), id, new OrderService.CustomerUpdate(data.addressId(), null,
                List.of(new OrderService.OrderEditItem(data.productId(), 2, null)), version(approved))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_ITEMS_LOCKED");
        assertAmount("SELECT base_amount FROM commission_accrual WHERE order_id=?", id, "70.00");
        assertAmount("SELECT commission_amount FROM commission_accrual WHERE order_id=?", id, "3.50");
        assertAmount("SELECT amount FROM billing_entry WHERE order_id=? AND bill_type='commission_payable'", id, "3.50");

        completeOrder(id);

        // Assert
        assertAmount("SELECT base_amount FROM commission_accrual WHERE order_id=?", id, "70.00");
        assertAmount("SELECT commission_amount FROM commission_accrual WHERE order_id=?", id, "3.50");
        assertAmount("SELECT amount FROM billing_entry WHERE order_id=? AND bill_type='commission_payable'", id, "3.50");
        assertThat(jdbc.queryForObject("SELECT status FROM commission_accrual WHERE order_id=?", String.class, id)).isEqualTo("payable");
    }

    @Test
    @DisplayName("商品金额全部优惠后佣金为零，运费仍应付且不得计佣")
    void fullyDiscountedProductsHaveZeroCommission() {
        var approved = approveWithDiscount(createPendingOrder("discount-commission-zero", 1), "90.00");
        long id = orderId(approved);

        assertAmount("SELECT total_amount FROM sales_order WHERE id=?", id, "20.00");
        assertAmount("SELECT base_amount FROM commission_accrual WHERE order_id=?", id, "0.00");
        assertAmount("SELECT commission_amount FROM commission_accrual WHERE order_id=?", id, "0.00");
        completeOrder(id);
        assertAmount("SELECT amount FROM billing_entry WHERE order_id=? AND bill_type='commission_payable'", id, "0.00");
    }

    @Test
    @DisplayName("待审核数量修改后，审核按优惠后商品小计 HALF_UP 计佣")
    void discountedCommissionRoundsHalfUpOnCreationAndEditing() {
        var pending = createPendingOrder("discount-commission-rounding", 1);
        long id = orderId(pending);
        var edited = orders.updateAudit(seller(), id, new OrderService.AuditUpdate(
                List.of(new OrderService.AuditEditItem(data.productId(), 1, new BigDecimal("0.20"))),
                "验证小额优惠佣金", version(pending), new BigDecimal("0.10")));
        var changed = orders.updateCustomer(customer(), id, new OrderService.CustomerUpdate(data.addressId(), null,
                List.of(new OrderService.OrderEditItem(data.productId(), 2, null)), version(edited)));
        approve(changed);

        assertAmount("SELECT base_amount FROM commission_accrual WHERE order_id=?", id, "0.30");
        assertAmount("SELECT commission_amount FROM commission_accrual WHERE order_id=?", id, "0.02");
        assertAmount("SELECT amount FROM billing_entry WHERE order_id=? AND bill_type='commission_payable'", id, "0.02");
    }

    private void completeOrder(long id) {
        orders.sellerAction(seller(), id, "pack", new OrderService.SellerAction(
                null, null, null, null, null, null, null, List.of(new OrderService.FileReference("packed", "token")), false));
        orders.sellerAction(seller(), id, "ship", new OrderService.SellerAction(
                null, null, null, null, null, "TRACK-" + id, "Test Carrier", List.of(new OrderService.FileReference("shipped", "token")), false));
        orders.sellerAction(seller(), id, "receive", new OrderService.SellerAction(null, null, null, null, null, null, null, null, true));
        orders.sellerAction(seller(), id, "complete", new OrderService.SellerAction(null, null, null, null, null, null, null, null, true));
    }

    @Test
    @DisplayName("改数量后商品金额不足抵扣优惠时，商品、库存和账单均不变")
    void quantityReductionCannotExceedDiscountCoverage() {
        var approved = saveDiscount(createPendingOrder("discount-invalid-edit", 2), "150.00");
        long id = orderId(approved);

        assertThatThrownBy(() -> orders.updateCustomer(customer(), id, new OrderService.CustomerUpdate(data.addressId(), null,
                List.of(new OrderService.OrderEditItem(data.productId(), 1, null)), version(approved))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_DISCOUNT_AMOUNT");

        assertThat(jdbc.queryForObject("SELECT quantity FROM order_item WHERE order_id=? AND deleted=0", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?", Integer.class, data.productId())).isEqualTo(8);
        assertAmount("SELECT total_amount FROM sales_order WHERE id=?", id, "50.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_entry WHERE order_id=?", Long.class, id)).isZero();
    }

    private Map<String, Object> approveWithDiscount(Map<String, Object> order, String discount) {
        return approve(saveDiscount(order, discount));
    }

    private Map<String, Object> saveDiscount(Map<String, Object> order, String discount) {
        var items = ((List<?>) order.get("items")).stream().map(item -> {
            var value = (Map<?, ?>) item;
            return new OrderService.AuditEditItem(((Number) value.get("productId")).longValue(),
                    ((Number) value.get("quantity")).intValue(), new BigDecimal(String.valueOf(value.get("unitPrice"))));
        }).toList();
        return orders.updateAudit(seller(), orderId(order), new OrderService.AuditUpdate(items, null,
                version(order), new BigDecimal(discount)));
    }

    private Map<String, Object> approve(Map<String, Object> order) {
        return orders.decideAudit(seller(), orderId(order), new OrderService.AuditDecision(
                "approved", null, version(order)));
    }

    private Instant version(Map<String, Object> order) {
        return (Instant) order.get("updatedAt");
    }

    private void assertAmount(String sql, long id, String amount) {
        assertThat(jdbc.queryForObject(sql, BigDecimal.class, id)).isEqualByComparingTo(amount);
    }
}

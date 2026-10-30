package com.dailyactivity.billing;

import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.billing.application.BillingService;
import com.dailyactivity.commission.application.CommissionService;
import com.dailyactivity.commission.domain.port.CommissionRepository;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.export.application.ExportService;
import com.dailyactivity.identity.application.CustomerService;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.support.PrdDatabaseTest;
import com.dailyactivity.support.TestFileServiceConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestFileServiceConfiguration.class)
@DisplayName("PRD C-08/C-09 账单、佣金与报表")
class PrdBillingAndReportingTest extends PrdDatabaseTest {
    @Autowired
    private BillingService billing;

    @Autowired
    private CommissionService commissions;

    @Autowired
    private CustomerService customers;

    @Autowired
    private ExportService exports;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("订单创建后生成应收账单，账单列表可按类型与状态筛选")
    void orderCreatesReceivableBill() {
        long id = orderId(createOrder("billing-page-1", 1));

        assertThat(billing.page(seller(), "order_receivable", "unpaid", 1).items())
                .anySatisfy(bill -> assertThat(bill)
                        .containsEntry("orderId", id)
                        .containsEntry("billType", "order_receivable")
                        .containsEntry("status", "unpaid")
                .containsEntry("amount", new BigDecimal("110.00")));
        assertThat(billing.page(seller(), "commission_payable", "payable", 1).items())
                .anySatisfy(bill -> assertThat(bill).containsEntry("orderId", id).containsEntry("status", "payable"));
    }

    @Test
    @DisplayName("取消订单不计入账单列表和下单账单数")
    void cancelledOrdersAreExcludedFromBillListAndOrderBillCounts() {
        Map<String, Object> summaryBefore = billing.summary(seller());
        long billCountBefore = billing.page(seller(), null, null, null, null, null, 1).total();
        long orderBillCountBefore = ((Number) summaryBefore.get("orderBills")).longValue();
        Map<String, Object> activeOrder = createOrder("billing-active-order", 1);
        Map<String, Object> cancelledOrder = createOrder("billing-cancelled-order", 1);
        orders.cancel(customer(), orderId(cancelledOrder), "Changed my mind", null);

        PageResponse<Map<String, Object>> page = billing.page(seller(), null, null, null, null, null, 1);
        assertThat(page.total()).isEqualTo(billCountBefore + 2);
        assertThat(page.items()).allSatisfy(bill -> assertThat(bill.get("orderStatus")).isNotEqualTo("cancelled"));
        assertThat(billing.page(seller(), "order_receivable", null, 1).total()).isEqualTo(orderBillCountBefore + 1);
        assertThat(billing.page(seller(), null, null, (String) cancelledOrder.get("orderNo"), null, null, 1).total()).isZero();
        assertThat(page.items()).anySatisfy(bill -> assertThat(bill.get("orderId")).isEqualTo(activeOrder.get("id")));
    }

    @Test
    @DisplayName("导出以当前筛选条件单次生成 CSV")
    void exportUsesCurrentFilterWithoutSelectionToken() {
        Map<String, Object> order = createOrder("billing-export-1", 1);

        ExportService.ExportFile file = exports.export(seller(), "orders",
                new ExportService.ExportRequest(Map.of("q", (String) order.get("orderNo"))));

        assertThat(file.filename()).startsWith("order-details-").endsWith(".csv");
        assertThat(new String(file.content(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("订单号", (String) order.get("orderNo"));
    }

    @Test
    @DisplayName("推荐人汇总锁定订单基数和佣金金额")
    void referralSummaryUsesAccrualSnapshot() {
        long orderId = orderId(createOrder("billing-summary-1", 1));

        Principal referrer = new Principal(data.referrerId(), "customer", 0, data.seller().merchantId());
        Map<String, Object> summary = customers.referralSummary(referrer);

        assertThat(summary.get("currentCount")).isEqualTo(1L);
        assertThat((BigDecimal) summary.get("pendingBase")).isEqualByComparingTo("90.00");
        assertThat((BigDecimal) summary.get("pendingCommission")).isEqualByComparingTo("4.50");
        assertThat(summary)
                .containsEntry("orderCount", 1L)
                .containsEntry("pendingVerificationOrderCount", 0L)
                .containsEntry("pendingPaymentOrderCount", 1L)
                .containsEntry("paidOrderCount", 0L);
        assertThat((BigDecimal) summary.get("commissionAmount")).isEqualByComparingTo("4.50");
        assertThat((BigDecimal) summary.get("estimatedCommissionAmount")).isZero();
        assertThat((BigDecimal) summary.get("payableCommissionAmount")).isEqualByComparingTo("4.50");
        assertThat((BigDecimal) summary.get("settledCommissionAmount")).isZero();

        jdbc.update("UPDATE sales_order SET payment_status='pending_verification' WHERE id=?", orderId);
        assertThat(customers.referralSummary(referrer))
                .containsEntry("pendingVerificationOrderCount", 0L)
                .containsEntry("pendingPaymentOrderCount", 1L)
                .containsEntry("paidOrderCount", 0L);
        jdbc.update("UPDATE sales_order SET payment_status='paid' WHERE id=?", orderId);
        assertThat(customers.referralSummary(referrer))
                .containsEntry("pendingVerificationOrderCount", 0L)
                .containsEntry("pendingPaymentOrderCount", 1L)
                .containsEntry("paidOrderCount", 0L);

        assertThat(customers.referrals(referrer)).singleElement().satisfies(row -> {
            assertThat(row)
                    .containsEntry("id", data.customerId())
                    .containsEntry("currentRelation", true)
                    .containsKey("pendingBase")
                    .containsKey("pendingCommission")
                    .doesNotContainKeys("email", "orderCount", "customerType", "priceTier");
            assertThat((BigDecimal) row.get("pendingBase")).isEqualByComparingTo("90.00");
            assertThat((BigDecimal) row.get("pendingCommission")).isEqualByComparingTo("4.50");
        });
    }

    @Test
    @DisplayName("客户推荐列表不展示零费率且无历史佣金的客户")
    void referralListOmitsZeroRateCustomerWithoutCommissionHistory() {
        jdbc.update("UPDATE `user` SET commission_rate=0 WHERE id=?", data.customerId());

        assertThat(customers.referrals(new Principal(data.referrerId(), "customer", 0, data.seller().merchantId()))).isEmpty();
    }

    @Test
    @DisplayName("客户推荐历史保留旧关系佣金明细且结算历史包含备注")
    void customerReferralHistoryKeepsOrderSnapshotsAndSettlementRemark() {
        Map<String, Object> created = createOrder("billing-customer-referral-history-1", 1);
        long orderId = orderId(created);
        String orderNo = (String) created.get("orderNo");
        long accrualId = jdbc.queryForObject("SELECT id FROM commission_accrual WHERE order_id=?", Long.class, orderId);
        Principal referrer = new Principal(data.referrerId(), "customer", 0, data.seller().merchantId());

        customers.update(seller(), data.customerId(), new CustomerService.CustomerRequest(
                data.customerEmail(), "Test Customer", null, null, "regular", "V1", null, null));

        assertThat(customers.referralSummary(referrer))
                .containsEntry("currentCount", 0L)
                .containsEntry("pendingBase", new BigDecimal("90.00"));
        assertThat(customers.referrals(referrer)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("id", data.customerId()).containsEntry("currentRelation", false);
            assertThat((BigDecimal) row.get("pendingCommission")).isEqualByComparingTo("4.50");
        });
        assertThat(commissions.customerCommissions(referrer, data.customerId(), 1).items())
                .singleElement()
                .satisfies(item -> assertThat(item)
                        .containsEntry("id", accrualId)
                        .containsEntry("orderNo", orderNo)
                        .containsEntry("status", "payable"));

        commissions.settle(seller(), new CommissionService.SettlementRequest(
                List.of(new CommissionRepository.SettlementRequest(accrualId, new BigDecimal("4.50"))),
                "Recorded offline payment", true));
        assertThat(customers.referralSummary(referrer))
                .containsEntry("payableCommissionAmount", new BigDecimal("0.00"))
                .containsEntry("settledCommissionAmount", new BigDecimal("4.50"));

        assertThat(commissions.customerSettlements(referrer, data.customerId(), 1).items())
                .singleElement()
                .satisfies(item -> assertThat(item)
                        .containsEntry("orderNo", orderNo)
                        .containsEntry("remark", "Recorded offline payment")
                        .containsEntry("commissionAmount", new BigDecimal("4.50")));
        assertThat(customers.referrals(referrer)).singleElement().satisfies(row -> {
            assertThat((BigDecimal) row.get("pendingBase")).isEqualByComparingTo("0.00");
            assertThat((BigDecimal) row.get("pendingCommission")).isEqualByComparingTo("0.00");
        });
    }

    @Test
    @DisplayName("审核通过后的未完成订单佣金已进入可结算列表")
    void approvedOrderCommissionIsPayableBeforeCompletion() {
        long id = orderId(createOrder("billing-payable-1", 1));
        long accrualId = jdbc.queryForObject("SELECT id FROM commission_accrual WHERE order_id=?", Long.class, id);

        assertThat(jdbc.queryForObject("SELECT order_status FROM sales_order WHERE id=?", String.class, id)).isEqualTo("pending");
        assertThat(commissions.admin(seller(), "payable", 1).items())
                .anySatisfy(item -> assertThat(item)
                        .containsEntry("id", accrualId)
                        .containsEntry("status", "payable")
                .containsEntry("commissionAmount", new BigDecimal("4.50")));
        assertThat(customers.referralSummary(new Principal(data.referrerId(), "customer", 0, data.seller().merchantId())).get("payableCommissionAmount"))
                .isEqualTo(new BigDecimal("4.50"));
    }

    @Test
    @DisplayName("结算详情和佣金/账单关联订单详情返回同一订单快照")
    void financeDetailsExposeSettlementAndOrder() {
        long id = orderId(createOrder("billing-detail-1", 1));
        completeOrder(id);
        long accrualId = jdbc.queryForObject("SELECT id FROM commission_accrual WHERE order_id=?", Long.class, id);
        commissions.settle(seller(), new CommissionService.SettlementRequest(
                List.of(new CommissionRepository.SettlementRequest(accrualId, new BigDecimal("4.50"))), "paid on site", true));
        long settlementId = jdbc.queryForObject("SELECT id FROM commission_settlement WHERE accrual_id=?", Long.class, accrualId);
        long billId = jdbc.queryForObject("SELECT id FROM billing_entry WHERE order_id=? AND bill_type='commission_payable'", Long.class, id);

        assertThat(commissions.settlementDetail(seller(), settlementId))
                .containsEntry("accrualId", accrualId)
                .containsEntry("orderId", id)
                .containsEntry("remark", "paid on site");
        assertThat(commissions.admin(seller(), "settled", 1).items())
                .anySatisfy(item -> assertThat(item)
                        .containsEntry("id", accrualId)
                        .containsEntry("orderStatus", "completed")
                        .containsEntry("paymentStatus", "unpaid"));
        assertThat(commissions.accrualOrder(seller(), accrualId)).containsEntry("id", id).containsKey("orderNo");
        assertThat(billing.billOrder(seller(), billId)).containsEntry("id", id).containsEntry("total", new BigDecimal("110.00"));
    }

    @Test
    @DisplayName("佣金实际结算金额变化时拒绝结算")
    void commissionSettlementRejectsChangedAmount() {
        long id = orderId(createOrder("billing-amount-1", 1));
        completeOrder(id);
        long accrualId = jdbc.queryForObject("SELECT id FROM commission_accrual WHERE order_id=?", Long.class, id);

        assertThatThrownBy(() -> commissions.settle(seller(), new CommissionService.SettlementRequest(
                List.of(new CommissionRepository.SettlementRequest(accrualId, new BigDecimal("1.00"))), "wrong amount", true)))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("COMMISSION_AMOUNT_CHANGED");
    }

    @Test
    @DisplayName("同一佣金结算完成后不能重复结算")
    void settledCommissionCannotBeSettledAgain() {
        long id = orderId(createOrder("billing-double-settle-1", 1));
        completeOrder(id);
        long accrualId = jdbc.queryForObject("SELECT id FROM commission_accrual WHERE order_id=?", Long.class, id);
        CommissionRepository.SettlementRequest item = new CommissionRepository.SettlementRequest(accrualId, new BigDecimal("4.50"));
        commissions.settle(seller(), new CommissionService.SettlementRequest(List.of(item), "first", true));

        assertThatThrownBy(() -> commissions.settle(seller(), new CommissionService.SettlementRequest(List.of(item), "second", true)))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("COMMISSION_NOT_PAYABLE");
    }

    private void completeOrder(long id) {
        orders.sellerAction(seller(), id, "pack",
                new OrderService.SellerAction(null, null, null, null, null, null, null, List.of(new OrderService.FileReference("packed", "token")), false));
        orders.sellerAction(seller(), id, "ship",
                new OrderService.SellerAction(null, null, null, null, null, "TRACK-" + id, "Carrier", List.of(new OrderService.FileReference("shipped", "token")), false));
        orders.sellerAction(seller(), id, "receive",
                new OrderService.SellerAction(null, null, null, null, null, null, null, null, true));
        orders.sellerAction(seller(), id, "complete",
                new OrderService.SellerAction(null, null, null, null, null, null, null, null, true));
    }
}

package com.dailyactivity.commission;

import com.dailyactivity.commission.application.CommissionService;
import com.dailyactivity.commission.domain.port.CommissionRepository;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.support.PrdDatabaseTest;
import com.dailyactivity.support.TestFileServiceConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PRD 5.4/S-07 佣金锁定与结算")
@Import(TestFileServiceConfiguration.class)
class PrdCommissionTest extends PrdDatabaseTest {
    @Autowired
    private CommissionService commissions;

    @Autowired
    private JdbcTemplate database;

    @Test
    @DisplayName("佣金列表按佣金状态、下单日期和订单/客户搜索筛选")
    void commissionListFiltersByStatusDateAndQuery() {
        // Arrange
        Map<String, Object> created = createOrder("commission-filter-order", 1);
        long orderId = orderId(created);
        completeOrder(orderId);
        String orderNo = database.queryForObject("SELECT order_no FROM sales_order WHERE id=?", String.class, orderId);
        Instant createdAt = database.queryForObject("SELECT created_at FROM sales_order WHERE id=?", Timestamp.class, orderId).toInstant();

        // Act
        PageResponse<Map<String, Object>> matching = commissions.admin(
                seller(), "payable", orderNo, createdAt.minusSeconds(1), createdAt.plusSeconds(1), 1);

        // Assert
        assertThat(matching.total()).isEqualTo(1);
        assertThat(matching.items()).singleElement().satisfies(item -> {
            assertThat(item.get("orderNo")).isEqualTo(orderNo);
            assertThat(item.get("status")).isEqualTo("payable");
        });
        assertThat(commissions.admin(seller(), "estimated", orderNo, null, null, 1).total()).isZero();
        assertThat(commissions.admin(seller(), "payable", "no-such-order", null, null, 1).total()).isZero();
        assertThat(commissions.admin(seller(), "payable", orderNo, createdAt.plusSeconds(2), createdAt.plusSeconds(3), 1).total()).isZero();
    }

    @Test
    @DisplayName("佣金列表拒绝订单付款状态作为佣金状态筛选")
    void commissionListRejectsPaymentStateAsCommissionStatus() {
        assertThatThrownBy(() -> commissions.admin(seller(), "paid", null, null, null, 1))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_COMMISSION_STATUS_FILTER");
    }

    @Test
    @DisplayName("佣金按下单推荐关系和费率锁定，审核通过的未完成订单也可结算一次")
    void commissionUsesOrderSnapshotAndSettlesBeforeCompletion() {
        // Arrange
        Map<String, Object> created = createOrder("commission-order-1", 1);
        long orderId = orderId(created);
        long accrualId = database.queryForObject("SELECT id FROM commission_accrual WHERE order_id=?", Long.class, orderId);

        // Act
        Map<String, Object> settled = commissions.settle(
                seller(),
                new CommissionService.SettlementRequest(
                        List.of(new CommissionRepository.SettlementRequest(accrualId, new BigDecimal("4.50"))),
                        "paid offline",
                        true));

        // Assert
        assertThat(settled.get("batchId")).isNotNull();
        assertThat(database.queryForObject("SELECT status FROM commission_accrual WHERE id=?", String.class, accrualId))
                .isEqualTo("settled");
        assertThat(database.queryForObject("SELECT payment_status FROM sales_order WHERE id=?", String.class, orderId))
                .isEqualTo("unpaid");
        assertThat(database.queryForObject("SELECT order_status FROM sales_order WHERE id=?", String.class, orderId))
                .isEqualTo("pending");
        assertThat(database.queryForObject("SELECT COUNT(*) FROM commission_settlement WHERE order_id=?", Long.class, orderId))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("审核后锁定商品明细，结佣后撤销取消权限并保留资料编辑")
    void settledCommissionLocksItemsButAllowsDetailsUpdate() {
        var created = createOrder("commission-actions", 1);
        long id = orderId(created);
        assertThat((List<String>) orders.detail(customer(), id).get("allowedActions"))
                .containsAll(List.of("edit", "cancel", "pay")).doesNotContain("edit_items");
        long accrualId = database.queryForObject("SELECT id FROM commission_accrual WHERE order_id=?", Long.class, id);

        commissions.settle(seller(), new CommissionService.SettlementRequest(
                List.of(new CommissionRepository.SettlementRequest(accrualId, new BigDecimal("4.50"))), "Paid offline", true));

        var detail = orders.detail(customer(), id);
        assertThat((List<String>) detail.get("allowedActions")).containsExactly("edit", "pay");
        assertThat(detail.get("amountLocked")).isEqualTo(true);
        assertThat(orders.customerOrders(customer(), null, null, (String) detail.get("orderNo"), null, null, 1).items())
                .singleElement().satisfies(row -> assertThat(row.get("allowedActions")).isEqualTo(detail.get("allowedActions")));
        var updated = orders.updateCustomer(customer(), id, new OrderService.CustomerUpdate(data.addressId(), "Updated delivery note",
                List.of(new OrderService.OrderEditItem(data.productId(), 1, null)), (Instant) detail.get("updatedAt")));
        assertThat(updated).containsEntry("buyerNote", "Updated delivery note").containsEntry("total", detail.get("total"));
        assertThat((List<String>) updated.get("allowedActions")).containsExactly("edit", "pay");
        assertThat(database.queryForObject("SELECT commission_amount FROM commission_settlement WHERE order_id=?", BigDecimal.class, id))
                .isEqualByComparingTo("4.50");
    }

    @Test
    @DisplayName("未确认线下付款时不允许结佣")
    void settlementRequiresOfflinePaymentConfirmation() {
        // Arrange
        Map<String, Object> created = createOrder("commission-confirmation-1", 1);
        long orderId = orderId(created);
        completeOrder(orderId);
        long accrualId = database.queryForObject("SELECT id FROM commission_accrual WHERE order_id=?", Long.class, orderId);

        // Act / Assert
        assertThatThrownBy(() -> commissions.settle(
                seller(),
                new CommissionService.SettlementRequest(
                        List.of(new CommissionRepository.SettlementRequest(accrualId, new BigDecimal("4.50"))),
                        "not confirmed",
                        false)))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("OFFLINE_PAYMENT_CONFIRMATION_REQUIRED");
    }

    private void completeOrder(long orderId) {
        orders.sellerAction(seller(), orderId, "pack",
                new OrderService.SellerAction(null, null, null, null, null, null, null, List.of(new OrderService.FileReference("packed", "token")), false));
        orders.sellerAction(seller(), orderId, "ship",
                new OrderService.SellerAction(null, null, null, null, null, "TRACK-" + orderId, "Carrier", List.of(new OrderService.FileReference("shipped", "token")), false));
        orders.sellerAction(seller(), orderId, "receive",
                new OrderService.SellerAction(null, null, null, null, null, null, null, null, true));
        orders.sellerAction(seller(), orderId, "complete",
                new OrderService.SellerAction(null, null, null, null, null, null, null, null, true));
    }
}

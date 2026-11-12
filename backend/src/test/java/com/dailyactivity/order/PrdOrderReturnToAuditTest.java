package com.dailyactivity.order;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.support.PrdDatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PRD 已审核待处理订单打回重审")
class PrdOrderReturnToAuditTest extends PrdDatabaseTest {

    @Test
    @DisplayName("打回后重新审核复用应收和佣金记录，不产生重复财务事实")
    void reapprovalReusesFinancialFacts() {
        var approved = createOrder("return-reapprove", 1);
        long id = orderId(approved);

        var returned = orders.returnToAudit(seller(), id, new OrderService.ReturnToAudit(version(approved)));

        assertThat(returned).containsEntry("auditStatus", "pending").containsEntry("status", "pending")
                .containsEntry("auditedByName", null).containsEntry("auditedAt", null).containsEntry("auditRemark", null);
        assertThat(orders.adminOrders(seller(), null, null, null, null, null, 1).total()).isZero();
        assertThat(orders.auditOrders(seller(), "pending", null, 1).total()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM commission_accrual WHERE order_id=?", String.class, id))
                .isEqualTo("estimated");
        assertThat(jdbc.queryForObject("SELECT status FROM billing_entry WHERE order_id=? AND bill_type='commission_payable'", String.class, id))
                .isEqualTo("estimated");
        assertThat(jdbc.queryForObject("SELECT from_status FROM order_event WHERE order_id=? AND action='audit_returned'", String.class, id))
                .isEqualTo("approved");
        assertThat(jdbc.queryForObject("SELECT to_status FROM order_event WHERE order_id=? AND action='audit_returned'", String.class, id))
                .isEqualTo("pending");

        var edited = orders.updateAudit(seller(), id, new OrderService.AuditUpdate(
                List.of(new OrderService.AuditEditItem(data.productId(), 2, new BigDecimal("90.00"))),
                null, version(returned)));
        orders.decideAudit(seller(), id, new OrderService.AuditDecision("approved", "重新审核通过", version(edited)));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_entry WHERE order_id=?", Long.class, id)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commission_accrual WHERE order_id=?", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_entry WHERE order_id=? AND bill_type='order_receivable'", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT amount FROM billing_entry WHERE order_id=? AND bill_type='order_receivable'", BigDecimal.class, id))
                .isEqualByComparingTo("200.00");
        assertThat(jdbc.queryForObject("SELECT base_amount FROM commission_accrual WHERE order_id=?", BigDecimal.class, id))
                .isEqualByComparingTo("180.00");
        assertThat(jdbc.queryForObject("SELECT commission_amount FROM commission_accrual WHERE order_id=?", BigDecimal.class, id))
                .isEqualByComparingTo("9.00");
        assertThat(jdbc.queryForObject("SELECT status FROM commission_accrual WHERE order_id=?", String.class, id))
                .isEqualTo("payable");
        assertThat(orders.adminOrders(seller(), null, null, null, null, null, 1).total()).isEqualTo(1);
    }

    @Test
    @DisplayName("打回后拒绝审核会取消订单、恢复库存并作废原财务记录")
    void rejectionAfterReturnRestoresStockAndVoidsFinance() {
        int stockBefore = jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?", Integer.class, data.productId());
        var approved = createOrder("return-reject", 1);
        long id = orderId(approved);

        var returned = orders.returnToAudit(seller(), id, new OrderService.ReturnToAudit(version(approved)));
        var rejected = orders.decideAudit(seller(), id, new OrderService.AuditDecision("rejected", "重新审核不通过", version(returned)));

        assertThat(rejected).containsEntry("auditStatus", "rejected").containsEntry("status", "cancelled");
        assertThat(jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?", Integer.class, data.productId()))
                .isEqualTo(stockBefore);
        assertThat(jdbc.queryForList("SELECT status FROM billing_entry WHERE order_id=?", String.class, id))
                .containsOnly("void");
        assertThat(jdbc.queryForObject("SELECT status FROM commission_accrual WHERE order_id=?", String.class, id))
                .isEqualTo("void");
    }

    @Test
    @DisplayName("已打包、已开始分包或已结佣的订单不能打回审核")
    void rejectsOrdersThatHaveStartedFulfillmentOrSettlement() {
        var packed = createOrder("return-packed", 1);
        long packedId = orderId(packed);
        jdbc.update("UPDATE sales_order SET order_status='packed' WHERE id=?", packedId);
        var packedCurrent = orders.auditDetail(seller(), packedId);
        assertThatThrownBy(() -> orders.returnToAudit(seller(), packedId,
                new OrderService.ReturnToAudit(version(packedCurrent))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_NOT_RETURNABLE");

        var packedPartially = createOrder("return-partial-parcel", 1);
        long parcelOrderId = orderId(packedPartially);
        jdbc.update("INSERT INTO order_parcel(order_id,parcel_uuid,parcel_no,status,created_by_user_id,updated_by_user_id) VALUES(?,?,1,'packed',?,?)",
                parcelOrderId, UUID.randomUUID().toString(), data.sellerId(), data.sellerId());
        assertThatThrownBy(() -> orders.returnToAudit(seller(), parcelOrderId,
                new OrderService.ReturnToAudit(version(packedPartially))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_PARCELS_STARTED");

        var settled = createOrder("return-settled", 1);
        long settledId = orderId(settled);
        jdbc.update("UPDATE commission_accrual SET status='settled' WHERE order_id=?", settledId);
        assertThatThrownBy(() -> orders.returnToAudit(seller(), settledId,
                new OrderService.ReturnToAudit(version(settled))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_COMMISSION_SETTLED");
    }

    private Instant version(Map<String, Object> order) {
        return (Instant) order.get("updatedAt");
    }
}

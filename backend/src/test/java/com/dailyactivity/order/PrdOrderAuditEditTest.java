package com.dailyactivity.order;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.support.PrdDatabaseTest;
import com.dailyactivity.support.PrdTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PRD S-02A 审核前商家编辑订单")
class PrdOrderAuditEditTest extends PrdDatabaseTest {
    @Test
    @DisplayName("审核编辑增改商品调整库存，审核通过按最新金额创建财务记录")
    void editsStockAndAmountsBeforeCreatingFinance() {
        var order = createPendingOrder("audit-edit", 2);
        long id = orderId(order);

        var edited = orders.updateAudit(seller(), id, update(order, "协商价格",
                item(data.productId(), 3, "80.00"), item(data.giftProductId(), 2, "0.00")));

        assertThat(edited).containsEntry("auditStatus", "pending").containsEntry("paymentStatus", "unpaid");
        assertThat((BigDecimal) edited.get("subtotal")).isEqualByComparingTo("240.00");
        assertThat((BigDecimal) edited.get("total")).isEqualByComparingTo("260.00");
        assertThat(stock(data.productId())).isEqualTo(7);
        assertThat(stock(data.giftProductId())).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_entry WHERE order_id=?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT before_data FROM order_event WHERE order_id=? AND action='audit_updated'", String.class, id))
                .contains("90.00", "Test Regular Product");
        assertThat(jdbc.queryForObject("SELECT after_data FROM order_event WHERE order_id=? AND action='audit_updated'", String.class, id))
                .contains("80.00", "Test Gift Product");
        assertThat((List<?>) orders.detail(customer(), id).get("events"))
                .noneMatch(event -> "audit_updated".equals(((Map<?, ?>) event).get("type")));

        orders.decideAudit(seller(), id, new OrderService.AuditDecision("approved", null, version(edited)));

        assertThat(jdbc.queryForObject("SELECT amount FROM billing_entry WHERE order_id=? AND bill_type='order_receivable'", BigDecimal.class, id))
                .isEqualByComparingTo("260.00");
        assertThat(jdbc.queryForObject("SELECT base_amount FROM commission_accrual WHERE order_id=?", BigDecimal.class, id))
                .isEqualByComparingTo("240.00");
    }

    @Test
    @DisplayName("移除商品软删除并回补库存；重新添加按当前等级取价，拒绝回补最新数量")
    void removesAndReaddsWithCurrentTierPrice() {
        var order = createPendingOrder("audit-replace", 2);
        long id = orderId(order);
        var replaced = orders.updateAudit(seller(), id, update(order, null, item(data.giftProductId(), 1, "0.00")));
        assertThat(stock(data.productId())).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT deleted FROM order_item WHERE order_id=? AND product_id=?", Long.class, id, data.productId())).isPositive();
        jdbc.update("UPDATE `user` SET price_tier='V5' WHERE id=?", data.customerId());
        jdbc.update("INSERT INTO product_tier_price(product_id,tier,price) VALUES(?,'V5',70.00)", data.productId());

        var readded = orders.updateAudit(seller(), id, update(replaced, null,
                item(data.productId(), 3, null), item(data.giftProductId(), 1, "0.00")));
        assertThat((BigDecimal) readded.get("subtotal")).isEqualByComparingTo("210.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item WHERE order_id=? AND product_id=? AND deleted=0", Long.class, id, data.productId())).isEqualTo(1);
        orders.decideAudit(seller(), id, new OrderService.AuditDecision("rejected", null, version(readded)));
        assertThat(stock(data.productId())).isEqualTo(10);
        assertThat(stock(data.giftProductId())).isEqualTo(10);
    }

    @Test
    @DisplayName("付款申报不阻止审核改正品、金额和赠品，原始申报保留")
    void protectsPaymentLinesWhileAllowingGifts() {
        // Arrange
        var order = createPendingOrder("audit-declared", 2);
        long id = orderId(order);
        var payment = submitPayment(customer(), id,
                new OrderService.PaymentRequest(data.channelId(), "audit-edit-payment", new BigDecimal("200.00")));
        // Act
        var edited = orders.updateAudit(seller(), id, update(payment, "协商改价",
                item(data.productId(), 3, "60.00"), item(data.giftProductId(), 2, "0.00")));
        // Assert
        assertThat(edited).containsEntry("paymentStatus", "pending_verification");
        assertThat((BigDecimal) edited.get("total")).isEqualByComparingTo("200.00");
        assertThat(stock(data.productId())).isEqualTo(7);
        assertThat(stock(data.giftProductId())).isEqualTo(8);
        var reduced = orders.updateAudit(seller(), id, update(edited, "减少采购数量", item(data.productId(), 1, "60.00")));
        assertThat((BigDecimal) reduced.get("total")).isEqualByComparingTo("80.00");
        assertThat(stock(data.productId())).isEqualTo(9);
        assertThat(stock(data.giftProductId())).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT declared_order_total FROM payment_submission WHERE order_id=?", BigDecimal.class, id)).isEqualByComparingTo("200.00");
        assertThat(jdbc.queryForObject("SELECT declared_amount FROM payment_submission WHERE order_id=?", BigDecimal.class, id)).isEqualByComparingTo("200.00");
        assertThat(orders.auditEditProducts(seller(), id, "Test Regular Product", 1).items()).isNotEmpty();
        orders.decideAudit(seller(), id, new OrderService.AuditDecision("approved", null, version(reduced)));
        assertThat(jdbc.queryForObject("SELECT amount FROM billing_entry WHERE order_id=? AND bill_type='order_receivable'", BigDecimal.class, id)).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("客户或只有订单管理权限的成员无法审核编辑；仅审核权限即可搜索并保存")
    void requiresAuditMenuRatherThanProductMenu() {
        var order = createPendingOrder("audit-permission", 1);
        long id = orderId(order);
        var request = update(order, null, item(data.productId(), 2, "90.00"));
        var restricted = PrdTestFixture.seedRestrictedSeller(jdbc, data.sellerId());
        assertThatThrownBy(() -> orders.updateAudit(restricted, id, request))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("FORBIDDEN");
        assertThatThrownBy(() -> orders.auditEditProducts(restricted, id, null, 1))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("FORBIDDEN");
        assertThatThrownBy(() -> orders.updateAudit(customer(), id, request)).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE user_permission SET menu_code='order_audit' WHERE user_id=?", restricted.userId());
        assertThat(orders.auditEditProducts(restricted, id, "Test Regular Product", 1).items()).isNotEmpty();
        assertThat(orders.updateAudit(restricted, id, request)).containsEntry("auditStatus", "pending");
    }

    @Test
    @DisplayName("库存不足、商品下架或类目禁用时拒绝新增；原订单下架商品仍可编辑")
    void validatesNewProductsAndPreservesExistingEligibility() {
        var order = createPendingOrder("audit-availability", 1);
        long id = orderId(order);
        assertThatThrownBy(() -> orders.updateAudit(seller(), id, update(order, null,
                item(data.productId(), 2, "90.00"), item(data.giftProductId(), 11, "0.00"))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INSUFFICIENT_STOCK");
        assertThat(stock(data.productId())).isEqualTo(9);
        assertThat(stock(data.giftProductId())).isEqualTo(10);
        jdbc.update("UPDATE product SET active=0 WHERE id=?", data.giftProductId());
        assertThatThrownBy(() -> orders.updateAudit(seller(), id, update(order, null,
                item(data.productId(), 1, "90.00"), item(data.giftProductId(), 1, "0.00"))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PRODUCT_NOT_AVAILABLE");
        jdbc.update("UPDATE product SET active=0 WHERE id=?", data.productId());
        var edited = orders.updateAudit(seller(), id, update(order, null, item(data.productId(), 2, "90.00")));
        assertThat((BigDecimal) edited.get("subtotal")).isEqualByComparingTo("180.00");
        assertThat(stock(data.productId())).isEqualTo(8);
    }

    @Test
    @DisplayName("已审核或过期订单保存拒绝，不能删除最后一行")
    void rejectsStaleOrDecidedOrdersAndEmptyItems() {
        var order = createPendingOrder("audit-stale", 1);
        long id = orderId(order);
        var request = update(order, null, item(data.productId(), 2, "90.00"));
        assertThatThrownBy(() -> orders.updateAudit(seller(), id, update(order, null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_ITEMS_REQUIRED");
        orders.updateAudit(seller(), id, request);
        assertThatThrownBy(() -> orders.updateAudit(seller(), id, request))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_CHANGED");
        orders.decideAudit(seller(), id, new OrderService.AuditDecision("approved", null, null));
        assertThatThrownBy(() -> orders.updateAudit(seller(), id, request))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_NOT_EDITABLE");
    }

    @Test
    @DisplayName("数量不得为小数或超出INT范围，单价必须有效且改价必填原因")
    void validatesQuantityAndPriceWithoutTruncation() {
        var order = createPendingOrder("audit-input", 1);
        long id = orderId(order);
        for (String quantity : List.of("0", "-1", "1.5", "2147483648")) {
            assertThatThrownBy(() -> orders.updateAudit(seller(), id,
                    update(order, null, new OrderService.AuditEditItem(data.productId(), new BigDecimal(quantity), new BigDecimal("90.00")))))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_ORDER_ITEMS");
        }
        for (String price : List.of("0", "-1", "0.001", "10000000000000000.00")) {
            assertThatThrownBy(() -> orders.updateAudit(seller(), id, update(order, "价格调整", item(data.productId(), 1, price))))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_ORDER_PRICE");
        }
        assertThatThrownBy(() -> orders.updateAudit(seller(), id, update(order, null, item(data.productId(), 1, "80.00"))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PRICE_CHANGE_REASON_REQUIRED");
        assertThat(stock(data.productId())).isEqualTo(9);
    }

    private OrderService.AuditEditItem item(long productId, int quantity, String price) {
        return new OrderService.AuditEditItem(productId, quantity, price == null ? null : new BigDecimal(price));
    }

    private OrderService.AuditUpdate update(Map<String, Object> order, String reason, OrderService.AuditEditItem... items) {
        return new OrderService.AuditUpdate(List.of(items), reason, version(order));
    }

    private Instant version(Map<String, Object> order) { return (Instant) order.get("updatedAt"); }

    private int stock(long productId) {
        return jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?", Integer.class, productId);
    }
}

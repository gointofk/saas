package com.dailyactivity.order;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.support.PrdDatabaseTest;
import com.dailyactivity.support.TestFileServiceConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestFileServiceConfiguration.class)
@DisplayName("PRD C-05/C-06 订单快照、归属与状态边界")
class PrdOrderBoundaryTest extends PrdDatabaseTest {
    @Test
    @DisplayName("空购物车不能报价")
    void quoteRejectsEmptyCart() {
        assertThatThrownBy(() -> orders.quote(customer(), new OrderService.QuoteRequest(data.addressId(), List.of())))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("EMPTY_CART");
    }

    @Test
    @DisplayName("订单保存商品和地址快照，不受后续资料修改影响")
    void orderSnapshotsRemainStableAfterSourceChanges() {
        Map<String, Object> created = createOrder("snapshot-order-1", 1);
        long id = orderId(created);
        jdbc.update("UPDATE product SET name=? WHERE id=?", "Changed Product Name", data.productId());
        jdbc.update("UPDATE customer_address SET street=? WHERE id=?", "Changed Street", data.addressId());

        Map<String, Object> detail = orders.detail(customer(), id);

        assertThat(detail.get("items")).asList().singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("name", "Test Regular Product")
                .containsEntry("unitPrice", new BigDecimal("90.00"));
        assertThat(detail.get("address")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("street", "1 Test Street");
    }

    @Test
    @DisplayName("客户不能查看其他客户的订单")
    void orderDetailIsRestrictedToOwner() {
        long id = orderId(createOrder("ownership-order-1", 1));
        com.dailyactivity.auth.domain.Principal otherCustomer = new com.dailyactivity.auth.domain.Principal(data.referrerId(), "customer", 0, data.seller().merchantId());

        assertThatThrownBy(() -> orders.detail(otherCustomer, id))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("取消订单后号码仍唯一且符合新格式")
    void orderNumberRemainsUniqueAfterCancellation() {
        Map<String, Object> first = createOrder("random-order-1", 1);
        orders.cancel(customer(), orderId(first), "cancel after order creation", null);
        Map<String, Object> second = createOrder("random-order-2", 1);

        assertThat(first.get("orderNo")).asString().matches("[0-9]{13}");
        assertThat(first.get("orderNo")).asString().startsWith(java.time.format.DateTimeFormatter
                .ofPattern("uuuuMMdd").withZone(java.time.ZoneOffset.UTC).format((java.time.Instant) first.get("createdAt")));
        assertThat(second.get("orderNo")).asString().matches("[0-9]{13}");
        assertThat(second.get("orderNo")).isNotEqualTo(first.get("orderNo"));
    }

    @Test
    @DisplayName("未打包订单不能直接发货")
    void shippingRequiresPackedState() {
        long id = orderId(createOrder("transition-order-1", 1));

        assertThatThrownBy(() -> orders.sellerAction(seller(), id, "ship",
                action("TRACK-001", List.of(new OrderService.FileReference("ship-file", "ship-token")), false)))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_ORDER_TRANSITION");
    }

    @Test
    @DisplayName("打包必须提供履约文件")
    void packingRequiresFiles() {
        long id = orderId(createOrder("fulfillment-validation-1", 1));
        assertThatThrownBy(() -> orders.sellerAction(seller(), id, "pack",
                action(null, null, false)))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("FULFILLMENT_FILES_REQUIRED");
    }

    @Test
    @DisplayName("发货必须提供运单号")
    void shippingRequiresTrackingNumber() {
        long id = orderId(createOrder("fulfillment-validation-2", 1));
        orders.sellerAction(seller(), id, "pack",
                action(null, List.of(new OrderService.FileReference("packed-file", "packed-token")), false));
        assertThatThrownBy(() -> orders.sellerAction(seller(), id, "ship",
                action(null, List.of(new OrderService.FileReference("shipped-file", "shipped-token")), false)))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("TRACKING_REQUIRED");
    }

    @Test
    @DisplayName("商家核验沿用付款申报的原始支付渠道快照")
    void paymentReceiptPreservesSubmissionChannelSnapshot() {
        long id = orderId(createOrder("payment-snapshot-1", 1));
        submitPayment(customer(), id,
                new OrderService.PaymentRequest(data.channelId(), "original-tx", new BigDecimal("110.00")));
        jdbc.update("UPDATE payment_channel SET receiving_address=? WHERE id=?", "changed-address", data.channelId());

        sellerAction(seller(), id, "confirm-payment",
                new OrderService.SellerAction(new BigDecimal("110.00"), "verified-tx", data.channelId(), null, null, null, null, null, false));

        assertThat(jdbc.queryForObject("SELECT channel_snapshot FROM payment_submission WHERE order_id=?", String.class, id))
                .contains("test-receiving-address")
                .doesNotContain("changed-address");
        assertThat(jdbc.queryForObject("SELECT channel_snapshot FROM payment_receipt WHERE order_id=?", String.class, id))
                .contains("test-receiving-address")
                .doesNotContain("changed-address")
                .isEqualTo(jdbc.queryForObject("SELECT channel_snapshot FROM payment_submission WHERE order_id=?", String.class, id));
    }

    private OrderService.SellerAction action(String trackingNo, List<OrderService.FileReference> files, boolean confirmed) {
        return new OrderService.SellerAction(null, null, null, null, null, trackingNo, "Carrier", files, confirmed);
    }

}

package com.dailyactivity.order;

import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.export.domain.port.ExportRepository;
import com.dailyactivity.order.application.OrderParcelService;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.support.PrdDatabaseTest;
import com.dailyactivity.support.TestFileServiceConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PRD 多包履约与 JDBC 数据一致性")
@Import(TestFileServiceConfiguration.class)
class PrdOrderParcelTest extends PrdDatabaseTest {
    @Autowired
    private ExportRepository exports;

    @Test
    @DisplayName("同 SKU 分批装箱、发货和收货，订单金额和库存不重复变化")
    void partialFulfillmentAcrossParcels() {
        var created = createOrder("multiple-parcels", 5);
        long id = orderId(created);
        long itemId = itemId(created);
        int stock = stock();
        int movements = movementCount(id);

        var firstPacked = pack(created, draft(itemId, 2));
        var first = parcelRows(firstPacked).get(0);
        assertThat(firstPacked).containsEntry("status", "pending");
        assertThat(summary(firstPacked).unpackedQuantity()).isEqualTo(3);
        assertThat((List<String>) orders.detail(customer(), id).get("allowedActions")).doesNotContain("edit", "cancel");
        var firstShipped = ship(firstPacked, first, "000-FIRST");
        assertThat(firstShipped).containsEntry("status", "pending");
        assertThat(summary(firstShipped).shippingStatus()).isEqualTo("partial");
        assertThat(orders.customerOrders(customer(), "partial", null, null, null, null, 1).items())
                .extracting(o -> o.get("id")).contains(id);
        assertThat(((Number) orders.summary(customer()).get("transit")).longValue()).isEqualTo(1);

        var bothPacked = pack(firstShipped, draft(itemId, 3));
        assertThat(bothPacked).containsEntry("status", "packed");
        var second = parcelRows(bothPacked).get(1);
        var bothShipped = ship(bothPacked, second, "000-SECOND");
        assertThat(bothShipped).containsEntry("status", "shipped");
        assertThat(orders.adminOrders(seller(), null, null, "000-SECOND", null, null, 1).items())
                .extracting(o -> o.get("id")).containsExactly(id);
        assertThat(exports.rows(data.seller().merchantId(), "orders", List.of(id))).singleElement().satisfies(row ->
                assertThat(row).containsEntry("trackingNo", "000-FIRST\n000-SECOND").containsEntry("parcelCount", 2));
        var firstReceived = receive(bothShipped, parcelId(first));
        assertThat(firstReceived).containsEntry("status", "shipped");
        assertThat(summary(firstReceived).receiptStatus()).isEqualTo("partial");
        var allReceived = receive(firstReceived, parcelId(second));
        assertThat(allReceived).containsEntry("status", "received");
        assertThat(summary(allReceived).receivedQuantity()).isEqualTo(5);
        var completed = orders.sellerAction(seller(), id, "complete",
                new OrderService.SellerAction(null, null, null, null, null, null, null, null, true, version(allReceived)));
        assertThat(completed).containsEntry("status", "completed").containsEntry("paymentStatus", "unpaid")
                .containsEntry("total", created.get("total")).doesNotContainKeys("trackingNo", "carrier");
        assertThat(stock()).isEqualTo(stock);
        assertThat(movementCount(id)).isEqualTo(movements);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commission_accrual WHERE order_id=?", Integer.class, id)).isEqualTo(1);
        var customerDetail = orders.detail(customer(), id);
        assertThat(parcelRows(customerDetail)).extracting(p -> p.get("trackingNo")).containsExactly("000-FIRST", "000-SECOND");
        assertThat((List<Map<String, Object>>) customerDetail.get("events")).allSatisfy(event -> assertThat(event).doesNotContainKey("actorName"));
        assertThatThrownBy(() -> orders.detail(new Principal(data.referrerId(), "customer", 0, data.seller().merchantId()), id))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("部分打包锁定编辑与取消，不提供重新分包操作")
    void partialPackingLocksOrder() {
        var created = createOrder("packing-lock", 5);
        long id = orderId(created);
        var partial = pack(created, draft(itemId(created), 2));
        Instant version = version(partial);
        assertThatThrownBy(() -> orders.cancel(customer(), id, "cancel", version))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_NOT_CANCELLABLE");
        assertThatThrownBy(() -> orders.updateCustomer(customer(), id,
                new OrderService.CustomerUpdate(data.addressId(), null, List.of(new OrderService.OrderEditItem(data.productId(), 5, null)), version)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_NOT_EDITABLE");
        assertThatThrownBy(() -> orders.updateSeller(seller(), id,
                new OrderService.SellerUpdate(null, null, null, null, null, List.of(), version)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_NOT_EDITABLE");

        assertThat((List<String>) partial.get("allowedActions")).contains("pack_parcel", "ship_parcel").doesNotContain("repack");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_event_file WHERE order_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("批量发货和收货先校验全部包裹，失败不留下部分副作用")
    void invalidBatchDoesNotChangeAnyParcel() {
        var created = createOrder("batch-validation", 4);
        long item = itemId(created);
        var packed = pack(created, draft(item, 2), draft(item, 2));
        var first = parcelRows(packed).get(0);
        var second = parcelRows(packed).get(1);
        assertThatThrownBy(() -> orders.shipParcels(seller(), orderId(packed), new OrderParcelService.ShipRequest(List.of(
                shipment(first, "VALID"), shipment(second, " ")), version(packed))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("TRACKING_REQUIRED");
        assertThat(parcelRows(orders.detail(customer(), orderId(packed)))).extracting(p -> p.get("status")).containsExactly("packed", "packed");
        var shipped = ship(packed, first, "VALID");
        assertThatThrownBy(() -> receive(shipped, parcelId(first), parcelId(second)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_PARCEL_TRANSITION");
        assertThat(parcelRows(orders.detail(customer(), orderId(packed)))).extracting(p -> p.get("status")).containsExactly("shipped", "packed");
    }

    private Map<String, Object> pack(Map<String, Object> current, OrderParcelService.PackParcel... drafts) {
        return orders.packParcels(seller(), orderId(current), new OrderParcelService.PackRequest(List.of(drafts), version(current)));
    }

    private OrderParcelService.PackParcel draft(long item, int quantity) {
        return new OrderParcelService.PackParcel(UUID.randomUUID().toString(), List.of(new OrderParcelService.ParcelItem(item, quantity)), photo(), "Buyer visible");
    }

    private Map<String, Object> ship(Map<String, Object> current, Map<String, Object> parcel, String tracking) {
        return orders.shipParcels(seller(), orderId(current), new OrderParcelService.ShipRequest(List.of(shipment(parcel, tracking)), version(current)));
    }

    private OrderParcelService.ShipParcel shipment(Map<String, Object> parcel, String tracking) {
        return new OrderParcelService.ShipParcel(parcelId(parcel), tracking, "Carrier", photo(), null);
    }

    private Map<String, Object> receive(Map<String, Object> current, Long... ids) {
        return orders.receiveParcels(seller(), orderId(current), new OrderParcelService.ReceiveRequest(List.of(ids), true, null, version(current)));
    }

    private List<OrderService.FileReference> photo() { return List.of(new OrderService.FileReference(UUID.randomUUID().toString(), "test-token")); }
    private long parcelId(Map<String, Object> parcel) { return ((Number) parcel.get("id")).longValue(); }
    private Instant version(Map<String, Object> order) { return (Instant) order.get("updatedAt"); }
    private long itemId(Map<String, Object> order) { return ((Number) ((List<Map<String, Object>>) order.get("items")).get(0).get("orderItemId")).longValue(); }
    private OrderParcelService.FulfillmentSummary summary(Map<String, Object> order) { return (OrderParcelService.FulfillmentSummary) order.get("fulfillmentSummary"); }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parcelRows(Map<String, Object> order) { return (List<Map<String, Object>>) order.get("parcels"); }
    private int stock() { return jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?", Integer.class, data.productId()); }
    private int movementCount(long id) { return jdbc.queryForObject("SELECT COUNT(*) FROM stock_movement WHERE order_id=?", Integer.class, id); }
}

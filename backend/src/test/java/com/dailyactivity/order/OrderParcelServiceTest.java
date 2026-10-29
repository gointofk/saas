package com.dailyactivity.order;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.file.application.FileService;
import com.dailyactivity.order.application.OrderParcelService;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.order.domain.port.OrderRepository;
import com.dailyactivity.order.domain.port.ParcelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("订单多包履约规则")
class OrderParcelServiceTest {
    private static final Principal SELLER = new Principal(3, "seller", 0, 1L);
    private static final Instant VERSION = Instant.parse("2026-10-08T00:00:00Z");
    private static final List<OrderRepository.ItemRow> ITEMS = List.of(
            new OrderRepository.ItemRow(10, 20, "SKU-A", "A", "regular", 5, BigDecimal.TEN, new BigDecimal("50")),
            new OrderRepository.ItemRow(11, 21, "SKU-G", "Gift", "gift", 2, BigDecimal.ZERO, BigDecimal.ZERO));
    private OrderParcelService service;
    private OrderRepository orders;
    private FileService files;
    private MemoryParcels parcels;
    private String orderStatus;

    @BeforeEach
    void setUp() {
        // Arrange: actual parcel state and quantity aggregation with mocked external ports.
        orders = mock(OrderRepository.class);
        files = mock(FileService.class);
        parcels = new MemoryParcels();
        orderStatus = "pending";
        var order = mock(OrderRepository.OrderRow.class);
        when(order.id()).thenReturn(1L);
        when(order.auditStatus()).thenReturn("approved");
        when(order.status()).thenAnswer(call -> orderStatus);
        when(order.updatedAt()).thenReturn(VERSION);
        when(orders.findForUpdate(1L, 1, null)).thenReturn(Optional.of(order));
        when(orders.items(1)).thenReturn(ITEMS);
        doAnswer(call -> { orderStatus = call.getArgument(1); return null; }).when(orders).updateStatus(eq(1L), anyString(), isNull(), isNull(), eq(new com.dailyactivity.auth.domain.Actor("seller",3L)));
        service = new OrderParcelService(orders, parcels, mock(AccountService.class), files);
    }

    @Test
    @DisplayName("同一商品跨包并分批打包、发货、收货，全部商品收货后才可完成")
    void splitQuantitiesAndPartialFulfillment() {
        // Act: first package ships before the remaining items are packed.
        service.pack(SELLER, 1, request(pack(10, 3)));
        service.ship(SELLER, 1, shipment(1, "00001"));
        service.receive(SELLER, 1, new OrderParcelService.ReceiveRequest(List.of(1L), true, null, VERSION));

        // Assert: receiving the only current package cannot complete the order.
        var partial = service.summary(1, ITEMS);
        assertThat(partial.unpackedQuantity()).isEqualTo(4);
        assertThat(partial.shippingStatus()).isEqualTo("partial");
        assertThat(partial.receiptStatus()).isEqualTo("partial");
        assertThatThrownBy(() -> service.requireAllReceived(1)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_NOT_FULLY_RECEIVED");
        assertThat(orderStatus).isEqualTo("pending");

        service.pack(SELLER, 1, request(new OrderParcelService.PackParcel(UUID.randomUUID().toString(), List.of(new OrderParcelService.ParcelItem(10, 2), new OrderParcelService.ParcelItem(11, 2)), photo(), null)));
        assertThat(orderStatus).isEqualTo("packed");
        service.ship(SELLER, 1, new OrderParcelService.ShipRequest(List.of(
                new OrderParcelService.ShipParcel(2, "00002", null, null, null)), VERSION));
        assertThat(orderStatus).isEqualTo("shipped");
        assertThat(parcels.parcels(1).get(1).carrier()).isNull();
        service.receive(SELLER, 1, new OrderParcelService.ReceiveRequest(List.of(2L), true, null, VERSION));
        assertThat(orderStatus).isEqualTo("received");
        assertThatCode(() -> service.requireAllReceived(1)).doesNotThrowAnyException();
        assertThat(service.summary(1, ITEMS).receivedQuantity()).isEqualTo(7);
        assertThat(parcels.parcels(1)).extracting(ParcelRepository.ParcelRow::trackingNo).containsExactly("00001", "00002");
    }

    @Test
    @DisplayName("跨包总量超限、重复商品行及其他订单商品被拒绝")
    void invalidAllocationsDoNotCreateParcels() {
        assertThatThrownBy(() -> service.pack(SELLER, 1, request(pack(10, 3), pack(10, 3)))).isInstanceOf(ApiException.class).extracting("code").isEqualTo("PARCEL_QUANTITY_EXCEEDED");
        assertThatThrownBy(() -> service.pack(SELLER, 1, request(pack(999, 1)))).isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_PARCEL_ITEMS");
        var duplicate = new OrderParcelService.PackParcel(UUID.randomUUID().toString(), List.of(new OrderParcelService.ParcelItem(10, 1), new OrderParcelService.ParcelItem(10, 1)), photo(), null);
        assertThatThrownBy(() -> service.pack(SELLER, 1, request(duplicate))).isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_PARCEL_ITEMS");
        assertThat(parcels.parcels(1)).isEmpty();
    }

    @Test
    @DisplayName("过期版本和重复打包标识不会重复创建包裹")
    void staleAndRepeatedRequestsAreRejected() {
        var draft = pack(10, 1);
        assertThatThrownBy(() -> service.pack(SELLER, 1, new OrderParcelService.PackRequest(List.of(draft), VERSION.minusSeconds(1)))).isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_CHANGED");
        service.pack(SELLER, 1, request(draft));
        assertThatThrownBy(() -> service.pack(SELLER, 1, request(draft))).isInstanceOf(ApiException.class).extracting("code").isEqualTo("PARCEL_ALREADY_EXISTS");
        assertThat(parcels.parcels(1)).hasSize(1);
    }

    @Test
    @DisplayName("历史归档包裹不计入剩余量，继续打包保留原记录和编号")
    void archivedParcelsRemainReadable() {
        parcels.rows.put(1L, new ParcelRepository.ParcelRow(1, 1, UUID.randomUUID().toString(), 1, "repacked", null, null, VERSION, null, null));
        parcels.allocations.add(new ParcelRepository.ParcelItemRow(1, 10, 5));
        parcels.allocations.add(new ParcelRepository.ParcelItemRow(1, 11, 2));
        service.pack(SELLER, 1, request(pack(10, 2)));
        assertThat(parcels.parcels(1)).extracting(ParcelRepository.ParcelRow::status).containsExactly("repacked", "packed");
        assertThat(parcels.parcels(1)).extracting(ParcelRepository.ParcelRow::number).containsExactly(1, 2);
        assertThat(service.summary(1, ITEMS).parcelCount()).isEqualTo(1);
        assertThat(service.summary(1, ITEMS).packedQuantity()).isEqualTo(2);
        assertThat(service.summary(1, ITEMS).unpackedQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("发货校验归属、运单与包裹阶段凭证，重复收货被拒绝")
    void parcelTransitionsAndUploadScope() {
        var draft = pack(10, 1);
        service.pack(SELLER, 1, request(draft));
        verify(files).validateParcelUploadToken(anyString(), eq("order_fulfillment"), eq(3L), eq(1L), eq("packed"), eq(draft.parcelUuid()), anyString());
        assertThatThrownBy(() -> service.ship(SELLER, 1, shipment(99, "X"))).isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_FOUND");
        assertThatThrownBy(() -> service.ship(SELLER, 1, shipment(1, " "))).isInstanceOf(ApiException.class).extracting("code").isEqualTo("TRACKING_REQUIRED");
        service.ship(SELLER, 1, shipment(1, "X"));
        verify(files).validateParcelUploadToken(anyString(), eq("order_shipping_label"), eq(3L), eq(1L), eq("shipped"), eq(draft.parcelUuid()), anyString());
        service.receive(SELLER, 1, new OrderParcelService.ReceiveRequest(List.of(1L), true, null, VERSION));
        assertThatThrownBy(() -> service.receive(SELLER, 1, new OrderParcelService.ReceiveRequest(List.of(1L), true, null, VERSION))).isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_PARCEL_TRANSITION");
    }

    @Test
    @DisplayName("单包历史缺少图片和阶段时间仍能查询进度并确认完整收货")
    void migratedParcelUsesTheSameModel() {
        parcels.rows.put(1L, new ParcelRepository.ParcelRow(1, 1, UUID.randomUUID().toString(), 1, "received", "OLD", null, null, null, null));
        parcels.allocations.add(new ParcelRepository.ParcelItemRow(1, 10, 5));
        parcels.allocations.add(new ParcelRepository.ParcelItemRow(1, 11, 2));
        assertThat(service.summary(1, ITEMS).receiptStatus()).isEqualTo("all");
        assertThatCode(() -> service.requireAllReceived(1)).doesNotThrowAnyException();
    }

    private OrderParcelService.PackParcel pack(long item, int quantity) {
        return new OrderParcelService.PackParcel(UUID.randomUUID().toString(), List.of(new OrderParcelService.ParcelItem(item, quantity)), photo(), null);
    }
    private List<OrderService.FileReference> photo() { return List.of(new OrderService.FileReference(UUID.randomUUID().toString(), "test-token")); }
    private OrderParcelService.PackRequest request(OrderParcelService.PackParcel... drafts) { return new OrderParcelService.PackRequest(List.of(drafts), VERSION); }
    private OrderParcelService.ShipRequest shipment(long id, String tracking) { return new OrderParcelService.ShipRequest(List.of(new OrderParcelService.ShipParcel(id, tracking, "Carrier", photo(), null)), VERSION); }

    private static class MemoryParcels implements ParcelRepository {
        final Map<Long, ParcelRow> rows = new LinkedHashMap<>();
        final List<ParcelItemRow> allocations = new ArrayList<>();
        public List<ParcelRow> parcels(long orderId) { return rows.values().stream().filter(p -> p.orderId() == orderId).toList(); }
        public List<ParcelItemRow> items(long orderId) { return allocations.stream().filter(i -> rows.get(i.parcelId()).orderId() == orderId).toList(); }
        public long insert(long orderId, String uuid, int number, long actor) { long id = rows.size() + 1L; rows.put(id, new ParcelRow(id, orderId, uuid, number, "packed", null, null, VERSION, null, null)); return id; }
        public void insertItem(long parcelId, long orderItemId, int quantity) { allocations.add(new ParcelItemRow(parcelId, orderItemId, quantity)); }
        public void ship(long id, String tracking, String carrier, long actor) { var p = rows.get(id); rows.put(id, new ParcelRow(id, p.orderId(), p.uuid(), p.number(), "shipped", tracking, carrier, p.packedAt(), VERSION, null)); }
        public void receive(long id, long actor) { var p = rows.get(id); rows.put(id, new ParcelRow(id, p.orderId(), p.uuid(), p.number(), "received", p.trackingNo(), p.carrier(), p.packedAt(), p.shippedAt(), VERSION)); }
        public void associateEvent(long eventId, long parcelId) {}
    }
}

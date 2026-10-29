package com.dailyactivity.order.application;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.file.application.FileService;
import com.dailyactivity.order.domain.port.OrderRepository;
import com.dailyactivity.order.domain.port.ParcelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderParcelService {
    private final OrderRepository orders;
    private final ParcelRepository parcels;
    private final AccountService accounts;
    private final FileService files;

    public OrderParcelService(OrderRepository orders, ParcelRepository parcels, AccountService accounts, FileService files) {
        this.orders = orders;
        this.parcels = parcels;
        this.accounts = accounts;
        this.files = files;
    }

    @Transactional(readOnly = true)
    public boolean hasParcels(long orderId) { return !parcels.parcels(orderId).isEmpty(); }

    @Transactional(readOnly = true)
    public List<ParcelRepository.ParcelRow> rows(long orderId) { return parcels.parcels(orderId); }

    @Transactional(readOnly = true)
    public List<ParcelRepository.ParcelItemRow> items(long orderId) { return parcels.items(orderId); }

    @Transactional(readOnly = true)
    public FulfillmentSummary summary(long orderId, List<OrderRepository.ItemRow> orderItems) {
        var active = parcels.parcels(orderId).stream().filter(p -> !"repacked".equals(p.status())).toList();
        var byId = active.stream().collect(Collectors.toMap(ParcelRepository.ParcelRow::id, Function.identity()));
        long packed = 0, shipped = 0, received = 0;
        for (var item : parcels.items(orderId)) {
            var parcel = byId.get(item.parcelId());
            if (parcel == null) continue;
            packed += item.quantity();
            if (!"packed".equals(parcel.status())) shipped += item.quantity();
            if ("received".equals(parcel.status())) received += item.quantity();
        }
        long total = orderItems.stream().mapToLong(OrderRepository.ItemRow::quantity).sum();
        int shippedCount = (int) active.stream().filter(p -> !"packed".equals(p.status())).count();
        int receivedCount = (int) active.stream().filter(p -> "received".equals(p.status())).count();
        return new FulfillmentSummary(active.size(), shippedCount, receivedCount, total, packed, shipped, received,
                total - packed, progress(packed, total), progress(shipped, total), progress(received, total), !active.isEmpty());
    }

    private String progress(long quantity, long total) {
        return quantity == 0 ? "none" : quantity == total ? "all" : "partial";
    }

    @Transactional
    public void pack(Principal actor, long orderId, PackRequest request) {
        if (request == null) throw ApiException.badRequest("INVALID_PARCELS", "包裹请求必填。");
        packInternal(actor, orderId, request, true);
    }

    private void packInternal(Principal actor, long orderId, PackRequest request, boolean boundTokens) {
        var order = lock(actor, orderId, request.expectedUpdatedAt());
        var existing = parcels.parcels(orderId);
        validatePack(orderId, request.parcels(), existing);
        for (var parcel : request.parcels()) validateFiles(parcel.files(), actor, orderId, boundTokens ? parcel.parcelUuid() : null, "packed", "order_fulfillment", true, 1);
        String operation = UUID.randomUUID().toString();
        int number = existing.stream().mapToInt(ParcelRepository.ParcelRow::number).max().orElse(0);
        for (var parcel : request.parcels()) {
            long id = parcels.insert(orderId, parcel.parcelUuid(), ++number, actor.userId());
            for (var item : parcel.items()) parcels.insertItem(id, item.orderItemId(), item.quantity());
            addEvent(orderId, id, operation, "parcel_packed", null, "packed", parcel.publicNote(), parcel.files(), actor);
        }
        refreshOrder(order, actor.actor());
    }

    private void validatePack(long orderId, List<PackParcel> requested, List<ParcelRepository.ParcelRow> existing) {
        if (requested == null || requested.isEmpty() || requested.size() > 100)
            throw ApiException.badRequest("INVALID_PARCELS", "每次请提交 1 至 100 个包裹。");
        Map<Long, Long> remaining = new HashMap<>();
        orders.items(orderId).forEach(i -> remaining.put(i.id(), (long) i.quantity()));
        Set<Long> activeIds = existing.stream().filter(p -> !"repacked".equals(p.status())).map(ParcelRepository.ParcelRow::id).collect(Collectors.toSet());
        for (var item : parcels.items(orderId)) {
            if (activeIds.contains(item.parcelId())) remaining.computeIfPresent(item.orderItemId(), (id, count) -> count - item.quantity());
        }
        Set<String> uuids = existing.stream().map(ParcelRepository.ParcelRow::uuid).collect(Collectors.toSet());
        Set<String> fileUuids = new HashSet<>();
        for (var parcel : requested) {
            if (parcel == null || parcel.parcelUuid() == null || !parcel.parcelUuid().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") || !uuids.add(parcel.parcelUuid()))
                throw ApiException.conflict("PARCEL_ALREADY_EXISTS", "包裹标识无效或已使用，请刷新订单。");
            validateNote(parcel.publicNote());
            if (parcel.items() == null || parcel.items().isEmpty()) throw ApiException.badRequest("EMPTY_PARCEL", "包裹至少包含一件商品。");
            Set<Long> itemIds = new HashSet<>();
            for (var item : parcel.items()) {
                if (item == null || item.quantity() < 1 || !itemIds.add(item.orderItemId()) || !remaining.containsKey(item.orderItemId()))
                    throw ApiException.badRequest("INVALID_PARCEL_ITEMS", "包裹商品须属于本订单，且数量为正整数、行不重复。");
                long left = remaining.get(item.orderItemId()) - item.quantity();
                if (left < 0) throw ApiException.conflict("PARCEL_QUANTITY_EXCEEDED", "分包数量超过订单剩余数量。");
                remaining.put(item.orderItemId(), left);
            }
            uniqueFiles(parcel.files(), fileUuids);
        }
    }

    @Transactional
    public void ship(Principal actor, long orderId, ShipRequest request) {
        if (request == null) throw ApiException.badRequest("INVALID_PARCELS", "包裹请求必填。");
        shipInternal(actor, orderId, request, true);
    }

    private void shipInternal(Principal actor, long orderId, ShipRequest request, boolean boundTokens) {
        var order = lock(actor, orderId, request.expectedUpdatedAt());
        if (request.parcels() == null || request.parcels().isEmpty() || request.parcels().size() > 100)
            throw ApiException.badRequest("INVALID_PARCELS", "请选择需要发货的包裹。");
        var byId = parcels.parcels(orderId).stream().collect(Collectors.toMap(ParcelRepository.ParcelRow::id, Function.identity()));
        Set<Long> ids = new HashSet<>();
        Set<String> fileUuids = new HashSet<>();
        String operation = UUID.randomUUID().toString();
        for (var input : request.parcels()) {
            if (input == null || !ids.add(input.parcelId())) throw ApiException.badRequest("INVALID_PARCELS", "包裹不能重复。");
            var parcel = requireParcel(byId, input.parcelId(), "packed");
            if (input.trackingNo() == null || input.trackingNo().isBlank() || input.trackingNo().trim().length() > 128)
                throw ApiException.badRequest("TRACKING_REQUIRED", "每个包裹须填写不超过 128 字的运单号。");
            if (input.carrier() != null && input.carrier().length() > 64) throw ApiException.badRequest("INVALID_CARRIER", "承运商最多 64 字。");
            validateNote(input.publicNote());
            uniqueFiles(input.labelFiles(), fileUuids);
            validateFiles(input.labelFiles(), actor, orderId, boundTokens ? parcel.uuid() : null, "shipped", boundTokens ? "order_shipping_label" : "order_fulfillment", false, 10);
        }
        for (var input : request.parcels()) {
            var parcel = byId.get(input.parcelId());
            parcels.ship(parcel.id(), input.trackingNo().trim(), blankToNull(input.carrier()), actor.userId());
            addEvent(orderId, parcel.id(), operation, "parcel_shipped", "packed", "shipped", input.publicNote(), input.labelFiles(), actor);
        }
        refreshOrder(order, actor.actor());
    }

    @Transactional
    public void receive(Principal actor, long orderId, ReceiveRequest request) {
        if (request == null) throw ApiException.badRequest("INVALID_PARCELS", "包裹请求必填。");
        var order = lock(actor, orderId, request.expectedUpdatedAt());
        if (!request.confirmed()) throw ApiException.badRequest("CONFIRMATION_REQUIRED", "请确认所选包裹已经收货。");
        if (request.parcelIds() == null || request.parcelIds().isEmpty() || request.parcelIds().size() > 100 || new HashSet<>(request.parcelIds()).size() != request.parcelIds().size())
            throw ApiException.badRequest("INVALID_PARCELS", "请选择不重复的包裹。");
        validateNote(request.publicNote());
        var byId = parcels.parcels(orderId).stream().collect(Collectors.toMap(ParcelRepository.ParcelRow::id, Function.identity()));
        for (Long id : request.parcelIds()) requireParcel(byId, id, "shipped");
        String operation = UUID.randomUUID().toString();
        for (Long id : request.parcelIds()) {
            parcels.receive(id, actor.userId());
            addEvent(orderId, id, operation, "parcel_received", "shipped", "received", request.publicNote(), List.of(), actor);
        }
        refreshOrder(order, actor.actor());
    }

    @Transactional(readOnly = true)
    public void requireAllReceived(long orderId) {
        Map<Long, String> states = parcels.parcels(orderId).stream().collect(Collectors.toMap(ParcelRepository.ParcelRow::id, ParcelRepository.ParcelRow::status));
        Map<Long, Long> received = new HashMap<>();
        for (var item : parcels.items(orderId)) if ("received".equals(states.get(item.parcelId())))
            received.merge(item.orderItemId(), (long) item.quantity(), Long::sum);
        var items = orders.items(orderId);
        if (items.isEmpty() || items.stream().anyMatch(i -> received.getOrDefault(i.id(), 0L) != i.quantity()) || received.size() != items.size())
            throw ApiException.conflict("ORDER_NOT_FULLY_RECEIVED", "全部订单商品收货后才能完成订单。");
    }

    @Transactional
    public void legacyAction(Principal actor, OrderRepository.OrderRow order, String action, OrderService.SellerAction request) {
        if ("pack".equals(action)) {
            if (hasParcels(order.id()) || !"pending".equals(order.status()))
                throw ApiException.conflict("PARCEL_SELECTION_REQUIRED", "请使用包裹管理选择具体包裹。");
            var items = orders.items(order.id()).stream().map(i -> new ParcelItem(i.id(), i.quantity())).toList();
            packInternal(actor, order.id(), new PackRequest(List.of(new PackParcel(UUID.randomUUID().toString(), items, request.files(), request.publicNote())), order.updatedAt()), false);
            return;
        }
        var active = parcels.parcels(order.id()).stream().filter(p -> !"repacked".equals(p.status())).toList();
        if (active.isEmpty()) throw ApiException.conflict("INVALID_ORDER_TRANSITION", "Order is not in the required status.");
        if (active.size() != 1 || !"all".equals(summary(order.id(), orders.items(order.id())).packingStatus()))
            throw ApiException.conflict("PARCEL_SELECTION_REQUIRED", "请使用包裹管理选择具体包裹。");
        if ("ship".equals(action)) shipInternal(actor, order.id(), new ShipRequest(List.of(new ShipParcel(active.get(0).id(), request.trackingNo(), request.carrier(), request.files(), request.publicNote())), order.updatedAt()), false);
        else receive(actor, order.id(), new ReceiveRequest(List.of(active.get(0).id()), request.confirmed(), request.publicNote(), order.updatedAt()));
    }

    private OrderRepository.OrderRow lock(Principal actor, long orderId, Instant version) {
        accounts.requireMenu(actor, "orders");
        var order = orders.findForUpdate(actor.merchantId(), orderId, null).orElseThrow(() -> ApiException.notFound("Order not found."));
        if (!"approved".equals(order.auditStatus()))
            throw ApiException.conflict("ORDER_NOT_APPROVED", "订单审核通过后才能操作包裹。");
        if (version == null) throw ApiException.badRequest("ORDER_VERSION_REQUIRED", "请重新打开订单后操作包裹。");
        if (!version.equals(order.updatedAt())) throw ApiException.conflict("ORDER_CHANGED", "订单已变化，请刷新后重试。");
        if (List.of("cancelled", "completed", "received").contains(order.status()))
            throw ApiException.conflict("PARCEL_OPERATION_NOT_ALLOWED", "该订单不能继续操作包裹。");
        return order;
    }

    private ParcelRepository.ParcelRow requireParcel(Map<Long, ParcelRepository.ParcelRow> byId, Long id, String status) {
        var parcel = byId.get(id);
        if (parcel == null) throw ApiException.notFound("Parcel not found in this order.");
        if (!status.equals(parcel.status())) throw ApiException.conflict("INVALID_PARCEL_TRANSITION", "包裹状态已变化，请刷新后重试。");
        return parcel;
    }

    private void refreshOrder(OrderRepository.OrderRow order, com.dailyactivity.auth.domain.Actor actor) {
        var summary = summary(order.id(), orders.items(order.id()));
        String state = "all".equals(summary.receiptStatus()) ? "received" : "all".equals(summary.shippingStatus()) ? "shipped" : "all".equals(summary.packingStatus()) ? "packed" : "pending";
        orders.updateStatus(order.id(), state, null, null, actor);
    }

    private void addEvent(long orderId, long parcelId, String operation, String action, String from, String to,
                          String note, List<OrderService.FileReference> refs, Principal actor) {
        // An operation can touch many parcels; each has a distinct audit operation identity.
        long event = orders.addEvent(orderId, operation + ":" + parcelId, action, from, to, null, null, blankToNull(note), actor.actor());
        parcels.associateEvent(event, parcelId);
        if (refs != null && !refs.isEmpty()) orders.attachFiles(orderId, event, refs.stream().map(OrderService.FileReference::fileUuid).toList(), to);
    }

    private void validateFiles(List<OrderService.FileReference> refs, Principal actor, long orderId, String uuid, String stage, String purpose, boolean required, int maxFiles) {
        if (refs == null || refs.isEmpty()) {
            if (required) throw ApiException.badRequest("FULFILLMENT_FILES_REQUIRED", "每个包裹须上传一张打包图片。");
            return;
        }
        if (refs.size() > maxFiles) throw ApiException.badRequest("TOO_MANY_FILES", maxFiles == 1 ? "每个包裹只能上传一张打包图片。" : "每个包裹每类附件最多 10 个。");
        for (var ref : refs) {
            if (ref == null || ref.fileUuid() == null || ref.uploadToken() == null)
                throw ApiException.badRequest("INVALID_FILE_REFERENCE", "文件引用及上传凭证必填。");
            if (uuid == null) files.validateUploadToken(ref.fileUuid(), purpose, actor.userId(), orderId, stage, ref.uploadToken());
            else files.validateParcelUploadToken(ref.fileUuid(), purpose, actor.userId(), orderId, stage, uuid, ref.uploadToken());
        }
    }

    private void uniqueFiles(List<OrderService.FileReference> refs, Set<String> uuids) {
        if (refs == null) return;
        for (var ref : refs) if (ref == null || ref.fileUuid() == null || !uuids.add(ref.fileUuid()))
            throw ApiException.badRequest("DUPLICATE_PARCEL_FILE", "不同包裹须分别上传凭证，文件不能重复。");
    }

    private void validateNote(String note) {
        if (note != null && note.length() > 256) throw ApiException.badRequest("INVALID_PARCEL_NOTE", "包裹公开备注最多 256 字。");
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record FulfillmentSummary(int parcelCount, int shippedParcelCount, int receivedParcelCount, long totalQuantity,
                                     long packedQuantity, long shippedQuantity, long receivedQuantity, long unpackedQuantity,
                                     String packingStatus, String shippingStatus, String receiptStatus, boolean started) {}
    public record ParcelItem(long orderItemId, int quantity) {}
    public record PackParcel(String parcelUuid, List<ParcelItem> items, List<OrderService.FileReference> files, String publicNote) {}
    public record PackRequest(List<PackParcel> parcels, Instant expectedUpdatedAt) {}
    public record ShipParcel(long parcelId, String trackingNo, String carrier, List<OrderService.FileReference> labelFiles, String publicNote) {}
    public record ShipRequest(List<ShipParcel> parcels, Instant expectedUpdatedAt) {}
    public record ReceiveRequest(List<Long> parcelIds, boolean confirmed, String publicNote, Instant expectedUpdatedAt) {}
}

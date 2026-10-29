package com.dailyactivity.order.domain.port;

import java.time.Instant;
import java.util.List;

public interface ParcelRepository {
    List<ParcelRow> parcels(long orderId);
    List<ParcelItemRow> items(long orderId);
    long insert(long orderId, String uuid, int number, long actor);
    void insertItem(long parcelId, long orderItemId, int quantity);
    void ship(long parcelId, String trackingNo, String carrier, long actor);
    void receive(long parcelId, long actor);
    void associateEvent(long eventId, long parcelId);

    record ParcelRow(long id, long orderId, String uuid, int number, String status, String trackingNo,
                     String carrier, Instant packedAt, Instant shippedAt, Instant receivedAt) {}
    record ParcelItemRow(long parcelId, long orderItemId, int quantity) {}
}

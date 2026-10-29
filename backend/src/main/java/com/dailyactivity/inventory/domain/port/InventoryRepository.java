package com.dailyactivity.inventory.domain.port;

import java.util.List;

public interface InventoryRepository {
    boolean adjust(long productId, int targetStock, String reason, String operationId, long actorId);

    boolean decrement(long productId, int quantity, long actorId, String operationId, long orderId);

    void restore(long productId, int quantity, String reason, String operationId, long orderId, long actorId);

    List<StockMovement> movements(long merchantId, long productId, int page);

    long movementCount(long merchantId, long productId);

    record StockMovement(long id, int delta, int beforeStock, int afterStock, String type, String reason,
                         String operationId, java.time.Instant createdAt) {
    }
}

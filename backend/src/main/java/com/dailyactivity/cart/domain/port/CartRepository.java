package com.dailyactivity.cart.domain.port;

import java.util.List;
import java.util.Optional;

public interface CartRepository {
    List<CartLine> find(long customerId, String tier);

    Optional<CartLine> findLineForUpdate(long customerId, long productId, String tier);

    void setQuantity(long customerId, long productId, int quantity, long actorId);

    void remove(long customerId, long productOrLineId);

    record CartLine(long id, long productId, String sku, String name, String category, String productType,
                    boolean active, boolean categoryActive, int stock, int quantity, java.math.BigDecimal unitPrice,
                    java.math.BigDecimal lineTotal) {
    }
}

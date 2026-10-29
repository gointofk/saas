package com.dailyactivity.catalog.domain.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CatalogRepository {
    List<CategoryView> categories(boolean activeOnly);

    List<CustomerProductView> customerProducts(String priceTier);

    Page<ProductView> adminProducts(String query, Long categoryId, String productType, Boolean active, String sortBy, String sortDirection, int page);

    Optional<ProductView> product(long productId, String priceTier, boolean activeOnly, boolean lock);

    Page<ProductView> orderEditProducts(String query, String priceTier, boolean giftsOnly, int page);

    Optional<ProductView> orderEditProduct(long productId, String priceTier);

    List<PriceView> prices(long productId);

    long createCategory(String name, int sortNo, long actorId);

    void updateCategory(long id, String name, int sortNo, boolean active, long actorId, Instant expectedUpdatedAt);

    void softDeleteCategory(long id, long actorId);

    long createProduct(ProductCommand command, long actorId);

    void updateProduct(long id, ProductCommand command, long actorId);

    void setProductActive(long id, boolean active, long actorId);

    void softDeleteProduct(long id, long actorId);

    record CategoryView(long id, String name, int sortNo, boolean active, long productCount,
                        String updatedBy, Instant updatedAt) {
    }

    record PriceView(String tier, BigDecimal price) {
    }

    record CustomerProductView(long id, long categoryId, String category, String sku, String name,
                               String description, BigDecimal price, int stock,
                               List<CustomerCoaView> coas) {
    }

    record CustomerCoaView(String name, String fileUuid, String lot, String testDate) {
    }

    record ProductView(long id, long categoryId, String category, String sku, String name, String description,
                       String productType, boolean active, BigDecimal price, BigDecimal basePrice,
                       BigDecimal costPrice, int stock, long priceRevision, Instant updatedAt,
                       List<ProductCoaView> coas) {
    }

    record ProductCoaView(long id, String name, String fileUuid, String lot, String testDate) {}

    record ProductCommand(long categoryId, String sku, String name, String description, String productType,
                          boolean active, BigDecimal basePrice, BigDecimal costPrice, int stockAvailable,
                          List<PriceView> tierPrices) {
    }

    record Page<T>(List<T> items, long total) {
    }
}

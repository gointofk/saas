package com.dailyactivity.catalog.domain.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CatalogRepository {
    List<CategoryView> categories(long merchantId, boolean activeOnly);

    List<CustomerProductView> customerProducts(long merchantId, String priceTier);

    Page<ProductView> adminProducts(long merchantId, String query, Long categoryId, String productType, Boolean active, String sortBy, String sortDirection, int page);

    Optional<ProductView> product(long merchantId, long productId, String priceTier, boolean activeOnly, boolean lock);

    Page<ProductView> orderEditProducts(long merchantId, String query, String priceTier, boolean giftsOnly, int page);

    Optional<ProductView> orderEditProduct(long merchantId, long productId, String priceTier);

    List<PriceView> prices(long merchantId, long productId);

    long createCategory(long merchantId, String name, int sortNo, long actorId);

    void updateCategory(long merchantId, long id, String name, int sortNo, boolean active, long actorId, Instant expectedUpdatedAt);

    void softDeleteCategory(long merchantId, long id, long actorId);

    long createProduct(long merchantId, ProductCommand command, long actorId);

    void updateProduct(long merchantId, long id, ProductCommand command, long actorId);

    void setProductActive(long merchantId, long id, boolean active, long actorId);

    void softDeleteProduct(long merchantId, long id, long actorId);

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

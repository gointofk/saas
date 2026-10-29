package com.dailyactivity.catalog.application;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.catalog.domain.port.CatalogRepository;
import com.dailyactivity.catalog.domain.port.QuantityPriceRepository;
import com.dailyactivity.catalog.domain.QuantityPricing;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.common.RequestValidation;
import com.dailyactivity.inventory.domain.port.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Service
public class CatalogService {
    private final CatalogRepository catalog;
    private final QuantityPriceRepository quantityPrices;
    private final AccountService accounts;
    private final com.dailyactivity.merchant.domain.port.MerchantRepository audit;
    private final InventoryRepository inventory;

    public CatalogService(CatalogRepository catalog, AccountService accounts, InventoryRepository inventory, com.dailyactivity.merchant.domain.port.MerchantRepository audit, QuantityPriceRepository quantityPrices) {
        this.audit = audit;
        this.quantityPrices = quantityPrices;
        this.catalog = catalog;
        this.accounts = accounts;
        this.inventory = inventory;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> customerCategories() {
        return catalog.categories(true).stream().map(category -> Map.<String, Object>of(
                "id", category.id(), "name", category.name())).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> customerProducts(Principal principal) {
        var account = accounts.customer(principal);
        var products = catalog.customerProducts(account.priceTier());
        var prices = quantityPrices.prices(products.stream().map(CatalogRepository.CustomerProductView::id).toList(), account.priceTier());
        return products.stream().map(p -> {
            var view = customerProductView(p);
            var bands = prices.getOrDefault(p.id(), List.of());
            view.put("quantityPrices", bands);
            view.put("price", QuantityPricing.price(bands, 1, p.price()));
            return view;
        }).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> adminProducts(Principal principal, String q, Long categoryId, Integer page) {
        return adminProducts(principal, q, categoryId, null, null, null, null, page);
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> adminProducts(Principal principal, String q, Long categoryId,
                                                            String productType, Boolean active, Integer page) {
        return adminProducts(principal, q, categoryId, productType, active, null, null, page);
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> adminProducts(Principal principal, String q, Long categoryId,
                                                            String productType, Boolean active, String sortBy, String sortDirection, Integer page) {
        accounts.seller(principal);
        String normalizedType = productType == null || productType.isBlank() || "all".equals(productType)
                ? null : productType.trim().toLowerCase();
        if (normalizedType != null && !List.of("regular", "gift").contains(normalizedType))
            throw ApiException.badRequest("INVALID_PRODUCT_TYPE_FILTER", "Product type filter is invalid.");
        String normalizedSort = sortBy != null && List.of("stock", "price").contains(sortBy) ? sortBy : null;
        String normalizedDirection = "asc".equalsIgnoreCase(sortDirection) ? "asc" : "desc";
        return page(catalog.adminProducts(q, categoryId, normalizedType, active, normalizedSort, normalizedDirection, RequestValidation.page(page)), page);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> adminCategories(Principal principal) {
        // Category names are already visible to customers.  Product managers need
        // the read-only list to create and edit products, even if they do not have
        // permission to alter the category taxonomy itself.
        accounts.seller(principal);
        return catalog.categories(false).stream().map(this::categoryView).toList();
    }

    @Transactional
    public Map<String, Object> createCategory(Principal principal, CategoryRequest request) {
        accounts.requireMenu(principal, "categories");
        validateCategory(request);
        long id = catalog.createCategory(request.name().trim(), request.sortNo(), principal.userId());
        audit.audit(principal.merchantId(), principal.actor(), "catalog.createCategory", "id=" + id);
        return Map.of("id", id);
    }

    @Transactional
    public void updateCategory(Principal principal, long id, CategoryRequest request) {
        accounts.requireMenu(principal, "categories");
        validateCategory(request);
        catalog.updateCategory(id, request.name().trim(), request.sortNo(), request.active(), principal.userId(), request.expectedUpdatedAt());
        audit.audit(principal.merchantId(), principal.actor(), "catalog.updateCategory", "id=" + id);
    }

    @Transactional
    public void deleteCategory(Principal principal, long id) {
        accounts.requireMenu(principal, "categories");
        catalog.softDeleteCategory(id, principal.userId());
        audit.audit(principal.merchantId(), principal.actor(), "catalog.deleteCategory", "id=" + id);
    }

    @Transactional
    public Map<String, Object> createProduct(Principal principal, ProductRequest request) {
        accounts.requireMenu(principal, "products");
        validateProduct(request);
        long id = catalog.createProduct(toCommand(request), principal.userId());
        quantityPrices.replace(id, request.quantityPrices(), principal.userId());
        audit.audit(principal.merchantId(), principal.actor(), "catalog.createProduct", "id=" + id);
        return Map.of("id", id);
    }

    @Transactional
    public void updateProduct(Principal principal, long id, ProductRequest request) {
        accounts.requireMenu(principal, "products");
        validateProduct(request);
        var current = catalog.product(id, "V1", false, true).orElseThrow(() -> ApiException.notFound("Product not found."));
        checkProductVersion(current, request.expectedUpdatedAt());
        if (request.stockAvailable() != current.stock()
                && (request.stockReason() == null || request.stockReason().isBlank()))
            throw ApiException.badRequest("STOCK_REASON_REQUIRED", "A reason is required when changing stock.");
        catalog.updateProduct(id, toCommand(request), principal.userId());
        quantityPrices.replace(id, "gift".equals(request.productType()) ? List.of() : request.quantityPrices(), principal.userId());
        if (request.stockAvailable() != current.stock())
            inventory.adjust(id, request.stockAvailable(), request.stockReason().trim(), java.util.UUID.randomUUID().toString(), principal.actor());
        audit.audit(principal.merchantId(), principal.actor(), "catalog.updateProduct", "id=" + id);
    }

    @Transactional
    public void setProductActive(Principal principal, long id, boolean active) {
        setProductActive(principal, id, active, null);
    }

    @Transactional
    public void setProductActive(Principal principal, long id, boolean active, Instant expectedUpdatedAt) {
        accounts.requireMenu(principal, "products");
        var current = catalog.product(id, "V1", false, true).orElseThrow(() -> ApiException.notFound("Product not found."));
        checkProductVersion(current, expectedUpdatedAt);
        catalog.setProductActive(id, active, principal.userId());
        audit.audit(principal.merchantId(), principal.actor(), "catalog.setProductActive", "id=" + id);
    }

    @Transactional
    public void deleteProduct(Principal principal, long id) {
        deleteProduct(principal, id, null);
    }

    @Transactional
    public void deleteProduct(Principal principal, long id, Instant expectedUpdatedAt) {
        accounts.requireMenu(principal, "products");
        var current = catalog.product(id, "V1", false, true).orElseThrow(() -> ApiException.notFound("Product not found."));
        checkProductVersion(current, expectedUpdatedAt);
        catalog.softDeleteProduct(id, principal.userId());
        audit.audit(principal.merchantId(), principal.actor(), "catalog.deleteProduct", "id=" + id);
    }

    @Transactional
    public Map<String, Object> adjustStock(Principal p, long id, StockRequest request) {
        accounts.requireMenu(p, "products");
        if (request.targetStock() < 0)
            throw ApiException.badRequest("INVALID_STOCK", "targetStock must be non-negative.");
        var current = catalog.product(id, "V1", false, true).orElseThrow(() -> ApiException.notFound("Product not found."));
        checkProductVersion(current, request.expectedUpdatedAt());
        inventory.adjust(id, request.targetStock(), request.reason(), java.util.UUID.randomUUID().toString(), p.actor());
        var updated = catalog.product(id, "V1", false, false).orElseThrow(() -> ApiException.notFound("Product not found."));
        audit.audit(p.merchantId(), p.actor(), "catalog.adjustStock", "id=" + id);
        return Map.of("productId", id, "stock", request.targetStock(), "updatedAt", updated.updatedAt());
    }

    private void checkProductVersion(CatalogRepository.ProductView current, Instant expectedUpdatedAt) {
        if (expectedUpdatedAt != null && !expectedUpdatedAt.equals(current.updatedAt()))
            throw ApiException.conflict("PRODUCT_CHANGED", "The product changed after it was loaded. Refresh and try again.");
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> stockMovements(Principal p, long productId, Integer page) {
        accounts.seller(p);
        int current = RequestValidation.page(page);
        return PageResponse.of(inventory.movements(p.merchantId(), productId, current).stream().map(m -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", m.id());
            v.put("quantityDelta", m.delta());
            v.put("quantityBefore", m.beforeStock());
            v.put("quantityAfter", m.afterStock());
            v.put("movementType", m.type());
            v.put("reason", m.reason());
            v.put("operationId", m.operationId());
            v.put("createdAt", m.createdAt());
            return v;
        }).toList(), current, inventory.movementCount(p.merchantId(), productId));
    }

    private PageResponse<Map<String, Object>> page(CatalogRepository.Page<CatalogRepository.ProductView> result, Integer requested) {
        int page = RequestValidation.page(requested);
        return PageResponse.of(result.items().stream().map(this::view).toList(), page, result.total());
    }

    private Map<String, Object> view(CatalogRepository.ProductView p) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", p.id());
        value.put("categoryId", p.categoryId());
        value.put("category", p.category());
        value.put("sku", p.sku());
        value.put("name", p.name());
        value.put("description", p.description());
        value.put("productType", p.productType());
        value.put("active", p.active());
        value.put("price", p.price());
        value.put("basePrice", p.basePrice());
        value.put("costPrice", p.costPrice());
        value.put("stock", p.stock());
        value.put("updatedAt", p.updatedAt());
        value.put("tierPrices", catalog.prices(p.id()).stream().map(price -> Map.of("tier", price.tier(), "price", price.price())).toList());
        value.put("quantityPrices", quantityPrices.allPrices(p.id()));
        value.put("coas", p.coas().stream().map(coa -> Map.<String, Object>of("id", coa.id(), "name", coa.name(), "fileUuid", coa.fileUuid(), "lot", coa.lot() == null ? "" : coa.lot(), "testDate", coa.testDate() == null ? "" : coa.testDate())).toList());
        return value;
    }

    private Map<String, Object> customerProductView(CatalogRepository.CustomerProductView p) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", p.id());
        value.put("categoryId", p.categoryId());
        value.put("category", p.category());
        value.put("sku", p.sku());
        value.put("name", p.name());
        value.put("description", p.description());
        value.put("price", p.price());
        value.put("stock", p.stock());
        if (!p.coas().isEmpty()) {
            value.put("coas", p.coas().stream().map(this::customerCoaView).toList());
        }
        return value;
    }

    private Map<String, Object> customerCoaView(CatalogRepository.CustomerCoaView coa) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", coa.name());
        value.put("fileUuid", coa.fileUuid());
        value.put("lot", coa.lot());
        value.put("testDate", coa.testDate());
        return value;
    }

    private Map<String, Object> categoryView(CatalogRepository.CategoryView c) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", c.id());
        value.put("name", c.name());
        value.put("sortNo", c.sortNo());
        value.put("active", c.active());
        value.put("productCount", c.productCount());
        value.put("updatedBy", c.updatedBy());
        value.put("updatedAt", c.updatedAt());
        return value;
    }

    private void validateCategory(CategoryRequest r) {
        if (r.name() == null || r.name().isBlank() || r.name().length() > 100 || r.sortNo() < 1)
            throw ApiException.badRequest("INVALID_CATEGORY", "Category name and positive sortNo are required.");
    }

    private void validateProduct(ProductRequest r) {
        QuantityPricing.validate(r.quantityPrices());
        if ("gift".equals(r.productType()) && r.quantityPrices() != null && !r.quantityPrices().isEmpty())
            throw ApiException.badRequest("INVALID_GIFT_PRICE", "Gifts cannot have quantity prices.");
        if (r.categoryId() < 1 || r.sku() == null || r.sku().isBlank() || r.name() == null || r.name().isBlank()
                || r.basePrice() == null || r.basePrice().scale() > 2
                || (r.costPrice() != null && (r.costPrice().signum() < 0 || r.costPrice().scale() > 2))
                || r.stockAvailable() < 0 || !List.of("regular", "gift").contains(r.productType()))
            throw ApiException.badRequest("INVALID_PRODUCT", "Product fields are invalid.");
        if ("regular".equals(r.productType()) && r.basePrice().signum() <= 0)
            throw ApiException.badRequest("INVALID_PRODUCT_PRICE", "Regular products require a positive base price.");
        if ("gift".equals(r.productType()) && r.basePrice().signum() != 0)
            throw ApiException.badRequest("INVALID_GIFT_PRICE", "Gift products must have a zero base price.");
        var prices = r.tierPrices() == null ? List.<CatalogRepository.PriceView>of() : r.tierPrices();
        var seen = new HashSet<String>();
        for (var price : prices) {
            if (price == null || price.tier() == null || !List.of("V1", "V2", "V3", "V4", "V5", "V6").contains(price.tier())
                    || !seen.add(price.tier()) || price.price() == null || price.price().scale() > 2
                    || ("regular".equals(r.productType()) && price.price().signum() <= 0)
                    || ("gift".equals(r.productType()) && price.price().signum() != 0))
                throw ApiException.badRequest("INVALID_TIER_PRICE", "Tier prices must be valid, unique and match the product type.");
        }
    }

    private CatalogRepository.ProductCommand toCommand(ProductRequest r) {
        return new CatalogRepository.ProductCommand(r.categoryId(), r.sku().trim(), r.name().trim(), r.description(), r.productType(), r.active(), r.basePrice(), r.costPrice(), r.stockAvailable(), r.tierPrices());
    }

    public record CategoryRequest(String name, int sortNo, boolean active, Instant expectedUpdatedAt) {
        public CategoryRequest(String name, int sortNo, boolean active) { this(name, sortNo, active, null); }
    }

    public record ProductRequest(long categoryId, String sku, String name, String description, String productType,
                                 boolean active, BigDecimal basePrice, BigDecimal costPrice, int stockAvailable,
                                 List<CatalogRepository.PriceView> tierPrices, String stockReason,
                                 Instant expectedUpdatedAt, List<QuantityPricing.TierPrices> quantityPrices) {
        public ProductRequest(long categoryId, String sku, String name, String description, String productType,
                              boolean active, BigDecimal basePrice, BigDecimal costPrice, int stockAvailable,
                              List<CatalogRepository.PriceView> tierPrices, String stockReason, Instant expectedUpdatedAt) {
            this(categoryId, sku, name, description, productType, active, basePrice, costPrice, stockAvailable,
                    tierPrices, stockReason, expectedUpdatedAt, null);
        }
        public ProductRequest(long categoryId, String sku, String name, String description, String productType,
                              boolean active, BigDecimal basePrice, BigDecimal costPrice, int stockAvailable,
                              List<CatalogRepository.PriceView> tierPrices) {
            this(categoryId, sku, name, description, productType, active, basePrice, costPrice,
                    stockAvailable, tierPrices, null, null, null);
        }
    }

    public record StockRequest(int targetStock, String reason, Instant expectedUpdatedAt) {
        public StockRequest(int targetStock, String reason) {
            this(targetStock, reason, null);
        }
    }

    public record ProductStatusRequest(Boolean active, Instant expectedUpdatedAt) {
    }

    public record ProductVersionRequest(Instant expectedUpdatedAt) {
    }
}

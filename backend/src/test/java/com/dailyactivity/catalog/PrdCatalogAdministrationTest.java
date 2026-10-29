package com.dailyactivity.catalog;

import com.dailyactivity.catalog.application.CatalogService;
import com.dailyactivity.catalog.domain.port.CatalogRepository;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.support.PrdDatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PRD C-02/C-03 商品、分类与库存后台管理")
class PrdCatalogAdministrationTest extends PrdDatabaseTest {
    @Autowired
    private CatalogService catalog;

    @Autowired
    private CatalogRepository catalogRepository;

    @Test
    @DisplayName("正品支持 V5 和 V6 等级价")
    void regularProductSupportsTierFiveAndSixPrices() {
        Map<String, Object> created = catalog.createProduct(seller(), new CatalogService.ProductRequest(
                data.categoryId(), "TIER-56-" + data.productSku(), "Tier Five Six", "", "regular", true,
                new BigDecimal("10.00"), null, 10, List.of(
                        new CatalogRepository.PriceView("V5", new BigDecimal("5.00")),
                        new CatalogRepository.PriceView("V6", new BigDecimal("6.00")))));
        long id = ((Number) created.get("id")).longValue();

        assertThat(catalogRepository.product(id, "V5", false, true).orElseThrow().price())
                .isEqualByComparingTo("5.00");
        assertThat(catalogRepository.product(id, "V6", false, true).orElseThrow().price())
                .isEqualByComparingTo("6.00");
    }

    @Test
    @DisplayName("正品不能使用零价格")
    void regularProductRequiresPositivePrice() {
        assertThatThrownBy(() -> catalog.createProduct(seller(), new CatalogService.ProductRequest(
                data.categoryId(), "ZERO-" + data.productSku(), "Zero Price", "", "regular", true,
                BigDecimal.ZERO, BigDecimal.ZERO, 1, List.of())))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_PRODUCT_PRICE");
    }

    @Test
    @DisplayName("赠品及赠品等级价必须为零")
    void giftProductRequiresZeroPrices() {
        assertThatThrownBy(() -> catalog.createProduct(seller(), new CatalogService.ProductRequest(
                data.categoryId(), "GIFT-BAD-" + data.productSku(), "Bad Gift", "", "gift", true,
                new BigDecimal("1.00"), BigDecimal.ZERO, 1, List.of())))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_GIFT_PRICE");

        assertThatThrownBy(() -> catalog.createProduct(seller(), new CatalogService.ProductRequest(
                data.categoryId(), "GIFT-TIER-BAD-" + data.productSku(), "Bad Gift Tier", "", "gift", true,
                BigDecimal.ZERO, BigDecimal.ZERO, 1, List.of(new CatalogRepository.PriceView("V1", BigDecimal.ONE)))))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_TIER_PRICE");
    }

    @Test
    @DisplayName("客户商品目录提供前端搜索和分类筛选所需数据")
    void customerCatalogIncludesSearchAndCategoryFields() {
        assertThat(catalog.customerProducts(customer()))
                .anySatisfy(product -> assertThat(product)
                .containsEntry("sku", data.productSku())
                .containsEntry("categoryId", data.categoryId()));
    }

    @Test
    @DisplayName("后台商品列表可按类型、上架状态和关键字筛选")
    void adminProductListFiltersAcrossAllProducts() {
        String giftSku = "FILTER-GIFT-" + data.productSku();
        String offlineSku = "FILTER-OFFLINE-" + data.productSku();
        catalog.createProduct(seller(), new CatalogService.ProductRequest(
                data.categoryId(), giftSku, "Filter Gift " + data.productSku(), "", "gift", true,
                BigDecimal.ZERO, BigDecimal.ZERO, 2, List.of()));
        catalog.createProduct(seller(), new CatalogService.ProductRequest(
                data.categoryId(), offlineSku, "Filter Offline " + data.productSku(), "", "regular", false,
                BigDecimal.ONE, BigDecimal.ZERO, 2, List.of()));

        assertThat(catalog.adminProducts(seller(), giftSku, null, "gift", true, 1).items())
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("sku", giftSku)
                .containsEntry("active", true);
        assertThat(catalog.adminProducts(seller(), offlineSku, null, null, false, 1).items())
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("sku", offlineSku)
                .containsEntry("active", false);
    }

    @Test
    @DisplayName("后台商品类型筛选拒绝未知值")
    void adminProductListRejectsUnknownTypeFilter() {
        assertThatThrownBy(() -> catalog.adminProducts(seller(), null, null, "unknown", null, 1))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_PRODUCT_TYPE_FILTER");
    }

    @Test
    @DisplayName("分类停用后客户看不到该分类下的商品")
    void inactiveCategoryHidesProducts() {
        catalog.updateCategory(seller(), data.categoryId(), new CatalogService.CategoryRequest(
                "Test Category Updated " + data.categoryId(), 1, false));

        assertThat(catalog.customerProducts(customer()))
                .noneMatch(product -> ((Number) product.get("categoryId")).longValue() == data.categoryId());
    }

    @Test
    @DisplayName("商品必须先下架才能删除")
    void productMustBeOfflineBeforeDelete() {
        assertThatThrownBy(() -> catalog.deleteProduct(seller(), data.productId()))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("PRODUCT_MUST_BE_OFFLINE");
    }

    @Test
    @DisplayName("下架商品可删除，删除后不再出现在后台列表")
    void offlineProductCanBeDeleted() {
        catalog.setProductActive(seller(), data.productId(), false);
        catalog.deleteProduct(seller(), data.productId());

        assertThat(catalog.adminProducts(seller(), data.productSku(), null, 1).items())
                .isEmpty();
    }

    @Test
    @DisplayName("库存目标值相同时不追加零变更流水")
    void stockAdjustmentSkipsNoopMovement() {
        Map<String, Object> first = catalog.adjustStock(seller(), data.productId(), new CatalogService.StockRequest(7, "cycle count"));
        Map<String, Object> sameTarget = catalog.adjustStock(seller(), data.productId(), new CatalogService.StockRequest(7, "cycle count"));

        assertThat(((Number) sameTarget.get("productId")).longValue())
                .isEqualTo(((Number) first.get("productId")).longValue());
        assertThat(((Number) sameTarget.get("stock")).intValue())
                .isEqualTo(((Number) first.get("stock")).intValue());
        assertThat(jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?", Integer.class, data.productId())).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movement WHERE product_id=? AND movement_type='manual_adjust'", Long.class, data.productId()))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("库存目标值不能为负数")
    void stockAdjustmentRejectsNegativeTarget() {
        assertThatThrownBy(() -> catalog.adjustStock(seller(), data.productId(), new CatalogService.StockRequest(-1, "invalid")))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_STOCK");
    }
}

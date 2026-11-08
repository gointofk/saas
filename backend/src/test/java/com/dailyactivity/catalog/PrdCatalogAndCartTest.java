package com.dailyactivity.catalog;

import com.dailyactivity.catalog.application.CatalogService;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.support.PrdDatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PRD C-02/C-04 商品目录与购物车")
class PrdCatalogAndCartTest extends PrdDatabaseTest {
    @Autowired
    private CatalogService catalog;

    @Test
    @DisplayName("客户商品目录只返回可售正品、本人适用价和首页字段")
    void customerSeesAllActiveRegularProductsAtApplicablePriceWithoutPrivateFields() {
        // Arrange
        // Act
        java.util.List<Map<String, Object>> products = catalog.customerProducts(customer());
        Map<String, Object> product = products.stream()
                .filter(candidate -> ((Number) candidate.get("id")).longValue() == data.productId())
                .findFirst()
                .orElseThrow();

        // Assert
        assertThat(product)
                .containsEntry("id", data.productId())
                .containsEntry("categoryId", data.categoryId())
                .containsEntry("price", new java.math.BigDecimal("90.00"))
                .containsKeys("sku", "name", "description", "stock")
                .doesNotContainKeys("page", "productType", "active", "basePrice", "costPrice",
                        "tierPrices", "priceTier", "customerType");
        assertThat(products).noneMatch(candidate -> ((Number) candidate.get("id")).longValue() == data.giftProductId());
        assertThat(products).allSatisfy(candidate -> assertThat(candidate)
                .doesNotContainKeys("page", "productType", "active", "basePrice", "costPrice",
                        "tierPrices", "priceTier", "customerType"));
        assertThat(catalog.customerCategories(customer())).anySatisfy(category -> assertThat(category)
                .containsEntry("id", data.categoryId())
                .containsOnlyKeys("id", "name"));
    }

    @Test
    @DisplayName("购物车拒绝非正整数和超过库存的数量，并返回商品小计与等级运费")
    void cartValidatesQuantityAndCalculatesShippingSeparately() {
        // Arrange / Act
        Map<String, Object> cart = carts.set(customer(), data.productId(), 2);

        // Assert
        assertThat((java.math.BigDecimal) cart.get("subtotal")).isEqualByComparingTo("180.00");
        assertThat((java.math.BigDecimal) cart.get("shippingFee")).isEqualByComparingTo("20.00");
        assertThat((java.math.BigDecimal) cart.get("total")).isEqualByComparingTo("200.00");
        assertThat(((java.util.List<?>) cart.get("items")).get(0))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("quantity", 2);

        assertThatThrownBy(() -> carts.set(customer(), data.productId(), 0))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_QUANTITY");
        assertThatThrownBy(() -> carts.set(customer(), data.productId(), 11))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("同一客户的购物车商品在创建订单后被清空，库存尚未超卖")
    void orderCreationConsumesCartAndStockAtomically() {
        // Arrange / Act
        Map<String, Object> order = createOrder("cart-consume-1", 2);
        Map<String, Object> cart = carts.get(customer());

        // Assert
        assertThat(order)
                .containsEntry("status", "pending")
                .containsEntry("paymentStatus", "unpaid");
        assertThat(cart.get("items")).asList().isEmpty();
        assertThat(jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?", Integer.class, data.productId()))
                .isEqualTo(8);
    }
}

package com.dailyactivity.catalog;

import com.dailyactivity.catalog.application.CatalogService;
import com.dailyactivity.catalog.domain.QuantityPricing.Band;
import com.dailyactivity.catalog.domain.QuantityPricing.TierPrices;
import com.dailyactivity.catalog.domain.port.QuantityPriceRepository;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.commission.application.CommissionService;
import com.dailyactivity.commission.domain.port.CommissionRepository;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.support.PrdDatabaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class PrdQuantityPricingTest extends PrdDatabaseTest {
    @Autowired CatalogService catalog;
    @Autowired QuantityPriceRepository prices;
    @Autowired CommissionService commissions;
    private List<TierPrices> configuration() {
        return List.of(new TierPrices("V1", List.of(band(1, 4, "95"), band(5, 29, "85"), band(30, null, "80"))),
                new TierPrices("V2", List.of(band(1, null, "70"))));
    }
    private void configure() { configure(configuration()); }
    private void configure(List<TierPrices> configuration) {
        var row = jdbc.queryForMap("SELECT * FROM product WHERE id=?", data.productId());
        catalog.updateProduct(seller(), data.productId(), new CatalogService.ProductRequest(data.categoryId(), data.productSku(),
                (String) row.get("name"), "", "regular", true, new BigDecimal("100"), null, ((Number)row.get("stock_available")).intValue(), null, null, null, configuration));
    }
    @Test
    void customerOnlySeesApplicableScheduleAndCartUsesQuantityBreaks() {
        configure();
        var product = catalog.customerProducts(customer()).stream().filter(p -> ((Number)p.get("id")).longValue() == data.productId()).findFirst().orElseThrow();
        assertThat(product.get("quantityPrices")).isEqualTo(configuration().get(0).bands());
        assertThat(product).doesNotContainKeys("tier", "priceTier", "tierPrices", "basePrice", "costPrice");
        assertThat((BigDecimal)carts.set(customer(), data.productId(), 5).get("subtotal")).isEqualByComparingTo("425");
        jdbc.update("UPDATE user SET price_tier='V2' WHERE id=?", customer().userId());
        assertThat((BigDecimal)carts.get(customer()).get("subtotal")).isEqualByComparingTo("350");
    }
    @Test
    void snapshotSurvivesProductPriceAndCustomerLevelChanges() {
        configure();
        var order = createPendingOrder("quantity snapshot", 4);
        jdbc.update("UPDATE product_quantity_price SET unit_price=1 WHERE product_id=?", data.productId());
        jdbc.update("UPDATE user SET price_tier='V2' WHERE id=?", customer().userId());
        var updated = orders.updateCustomer(customer(), orderId(order), new OrderService.CustomerUpdate(data.addressId(), null,
                List.of(new OrderService.OrderEditItem(data.productId(), 5, new BigDecimal("95"))), (Instant) order.get("updatedAt")));
        assertThat((BigDecimal)updated.get("subtotal")).isEqualByComparingTo("425");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item_quantity_price qp JOIN order_item i ON i.id=qp.order_item_id WHERE i.order_id=?", Integer.class, orderId(order))).isEqualTo(3);
    }
    @Test
    void oldClientEditsPreserveConfigurationAndExplicitEmptyClearsIt() {
        configure();
        var legacy = new CatalogService.ProductRequest(data.categoryId(), data.productSku(), "Legacy edit", "", "regular", true,
                new BigDecimal("100"), null, 10, null);
        catalog.updateProduct(seller(), data.productId(), legacy);
        assertThat(prices.allPrices(seller().merchantId(), data.productId())).hasSize(2);
        catalog.updateProduct(seller(), data.productId(), new CatalogService.ProductRequest(data.categoryId(), data.productSku(), "Clear", "", "regular", true,
                new BigDecimal("100"), null, 10, null, null, null, List.of()));
        assertThat(prices.allPrices(seller().merchantId(), data.productId())).isEmpty();
    }
    @Test
    void legacyOrderDoesNotAdoptProductQuantityPrices() {
        var old = createPendingOrder("old fixed order", 4);
        configure();
        var updated = orders.updateCustomer(customer(), orderId(old), new OrderService.CustomerUpdate(data.addressId(), null,
                List.of(new OrderService.OrderEditItem(data.productId(), 5, null)), (Instant)old.get("updatedAt")));
        assertThat((BigDecimal)updated.get("subtotal")).isEqualByComparingTo("450");
    }
    @Test
    void manualPricePersistsAcrossLaterQuantityChanges() {
        configure();
        var order = createPendingOrder("manual price", 4);
        var manual = orders.updateAudit(seller(), orderId(order), new OrderService.AuditUpdate(
                List.of(new OrderService.AuditEditItem(data.productId(), new BigDecimal("4"), new BigDecimal("77"), true)), "Agreed price", (Instant)order.get("updatedAt")));
        var updated = orders.updateCustomer(customer(), orderId(order), new OrderService.CustomerUpdate(data.addressId(), null,
                List.of(new OrderService.OrderEditItem(data.productId(), 5, null)), (Instant)manual.get("updatedAt")));
        assertThat((BigDecimal)updated.get("subtotal")).isEqualByComparingTo("385");
    }
    @Test
    void changedConfigurationInvalidatesAnOutstandingQuote() {
        configure();
        carts.set(customer(), data.productId(), 5);
        var quote = orders.quote(customer(), new OrderService.QuoteRequest(data.addressId(), List.of(new OrderService.CartRequest(data.productId(), 5))));
        configure();
        assertThatThrownBy(() -> orders.create(customer(), new OrderService.CreateRequest((String)quote.get("quoteToken"), null,
                List.of(new OrderService.CartRequest(data.productId(), 5)), data.addressId())))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("QUOTE_CHANGED");
    }
    @Test
    void auditAddedProductSavesScheduleAndApprovalFreezesItsPricing() {
        var order = createPendingOrder("add quantity product", 1);
        var added = catalog.createProduct(seller(), new CatalogService.ProductRequest(data.categoryId(), "QTY-" + data.productSku(), "Quantity product", "", "regular", true,
                new BigDecimal("60.00"), null, 10, List.of(), null, null,
                List.of(new TierPrices("V1", List.of(band(1, 2, "50"), band(3, null, "40"))))));
        long productId = ((Number)added.get("id")).longValue();
        var edited = orders.updateAudit(seller(), orderId(order), new OrderService.AuditUpdate(List.of(
                new OrderService.AuditEditItem(data.productId(), 1, null),
                new OrderService.AuditEditItem(productId, 3, null)), null, (Instant)order.get("updatedAt")));
        assertThat((BigDecimal)edited.get("subtotal")).isEqualByComparingTo("210");
        assertThat(jdbc.queryForObject("SELECT price_source FROM order_item WHERE order_id=? AND product_id=? AND deleted=0", String.class, orderId(order), productId)).isEqualTo("quantity_price");
        var changed = orders.updateCustomer(customer(), orderId(order), new OrderService.CustomerUpdate(data.addressId(), null, List.of(
                new OrderService.OrderEditItem(data.productId(), 1, null), new OrderService.OrderEditItem(productId, 2, null)), (Instant)edited.get("updatedAt")));
        var updated = orders.decideAudit(seller(), orderId(order), new OrderService.AuditDecision("approved", null, (Instant)changed.get("updatedAt")));
        assertThat((BigDecimal)updated.get("subtotal")).isEqualByComparingTo("190");
        assertThat(jdbc.queryForObject("SELECT amount FROM billing_entry WHERE order_id=? AND bill_type='order_receivable'", BigDecimal.class, orderId(order))).isEqualByComparingTo("210");
        assertThat(jdbc.queryForObject("SELECT commission_amount FROM commission_accrual WHERE order_id=?", BigDecimal.class, orderId(order))).isEqualByComparingTo("9.50");
    }
    @Test
    void approvalLocksQuantityEvenAtTheSameAmountAndKeepsAddressEditsAvailable() {
        configure(List.of(new TierPrices("V1", List.of(band(1, 4, "95"), band(5, null, "76")))));
        var order = createOrder("approved equal amount", 4);
        long id = orderId(order);
        var before = orders.detail(customer(), id);
        assertThat((List<String>)before.get("allowedActions")).contains("edit", "cancel").doesNotContain("edit_items");
        assertThat(before.get("amountLocked")).isEqualTo(true);
        assertThatThrownBy(() -> orders.updateCustomer(customer(), id, new OrderService.CustomerUpdate(data.addressId(), null,
                List.of(new OrderService.OrderEditItem(data.productId(), 5, null)), (Instant)before.get("updatedAt"))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_ITEMS_LOCKED");
        var updated = orders.updateCustomer(customer(), id, new OrderService.CustomerUpdate(data.addressId(), "Address instructions",
                List.of(new OrderService.OrderEditItem(data.productId(), 4, null)), (Instant)before.get("updatedAt")));
        assertThat(updated.get("buyerNote")).isEqualTo("Address instructions");
        assertThat((BigDecimal)updated.get("subtotal")).isEqualByComparingTo("380");
        assertThat((BigDecimal)updated.get("total")).isEqualByComparingTo("400");
        assertThat(jdbc.queryForObject("SELECT quantity FROM order_item WHERE order_id=? AND deleted=0", Integer.class, id)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?", Integer.class, data.productId())).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT amount FROM billing_entry WHERE order_id=? AND bill_type='order_receivable'", BigDecimal.class, id)).isEqualByComparingTo("400");
    }
    @Test
    void approvedFixedPriceOrderRejectsSellerPriceQuantityAndProductChangesBeforeAndAfterSettlement() {
        var order = createOrder("approved fixed price", 4);
        long id = orderId(order);
        var original = new OrderService.OrderEditItem(data.productId(), 4, new BigDecimal("90.00"));
        var version = (Instant)order.get("updatedAt");
        for (var items : List.of(
                List.of(new OrderService.OrderEditItem(data.productId(), 5, new BigDecimal("72.00"), true)),
                List.of(new OrderService.OrderEditItem(data.productId(), 4, new BigDecimal("80.00"), true)),
                List.of(original, new OrderService.OrderEditItem(data.giftProductId(), 1, BigDecimal.ZERO)),
                List.<OrderService.OrderEditItem>of())) {
            assertThatThrownBy(() -> orders.updateSeller(seller(), id, new OrderService.SellerUpdate(null, null, null, null, null, items, version)))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_ITEMS_LOCKED");
        }
        settle(id);
        var current = orders.detail(customer(), id);
        assertThat((List<String>)current.get("allowedActions")).contains("edit").doesNotContain("edit_items", "cancel");
        assertThatThrownBy(() -> orders.updateCustomer(customer(), id, new OrderService.CustomerUpdate(data.addressId(), null,
                List.of(new OrderService.OrderEditItem(data.productId(), 5, null)), (Instant)current.get("updatedAt"))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_ITEMS_LOCKED");
        var updated = orders.updateSeller(seller(), id, new OrderService.SellerUpdate(null, null, null, null, "Delivery instructions", List.of(original), (Instant)current.get("updatedAt")));
        assertThat((BigDecimal)updated.get("total")).isEqualByComparingTo("380");
        assertThat(jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?", Integer.class, data.productId())).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT commission_amount FROM commission_settlement WHERE order_id=?", BigDecimal.class, id)).isEqualByComparingTo("18");
    }
    private void settle(long id) {
        long accrual = jdbc.queryForObject("SELECT id FROM commission_accrual WHERE order_id=?", Long.class, id);
        BigDecimal amount = jdbc.queryForObject("SELECT commission_amount FROM commission_accrual WHERE id=?", BigDecimal.class, accrual);
        commissions.settle(seller(), new CommissionService.SettlementRequest(List.of(new CommissionRepository.SettlementRequest(accrual, amount)), "Paid offline", true));
    }
    private static Band band(int min, Integer max, String price) { return new Band(min, max, new BigDecimal(price).setScale(2)); }
}

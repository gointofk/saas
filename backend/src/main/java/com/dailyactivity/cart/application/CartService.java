package com.dailyactivity.cart.application;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.cart.domain.port.CartRepository;
import com.dailyactivity.catalog.domain.port.CatalogRepository;
import com.dailyactivity.catalog.domain.port.QuantityPriceRepository;
import com.dailyactivity.catalog.domain.QuantityPricing;
import com.dailyactivity.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CartService {
    private final CartRepository carts;
    private final AccountService accounts;
    private final CatalogRepository catalog;
    private final QuantityPriceRepository quantityPrices;

    public CartService(CartRepository carts, AccountService accounts, CatalogRepository catalog, QuantityPriceRepository quantityPrices) {
        this.carts = carts;
        this.quantityPrices = quantityPrices;
        this.accounts = accounts;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Principal p) {
        var a = accounts.customer(p);
        var lines = carts.find(a.id(), a.priceTier());
        var schedules = quantityPrices.prices(lines.stream().map(CartRepository.CartLine::productId).toList(), a.priceTier());
        List<Map<String, Object>> items = lines.stream().map(line -> {
            var item = view(line);
            var price = QuantityPricing.price(schedules.getOrDefault(line.productId(), List.of()), line.quantity(), line.unitPrice());
            item.put("unitPrice", price);
            item.put("lineTotal", price.multiply(BigDecimal.valueOf(line.quantity())));
            return item;
        }).toList();
        BigDecimal subtotal = items.stream().map(i -> (BigDecimal) i.get("lineTotal")).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean hasInvalid = items.stream().anyMatch(i -> i.get("invalidReason") != null);
        BigDecimal shipping = items.isEmpty() || hasInvalid ? BigDecimal.ZERO : accounts.shippingFee(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("subtotal", subtotal);
        out.put("shippingFee", shipping);
        out.put("total", subtotal.add(shipping));
        return out;
    }

    @Transactional
    public Map<String, Object> set(Principal p, long productId, int quantity) {
        if (quantity < 1) throw ApiException.badRequest("INVALID_QUANTITY", "Quantity must be a positive integer.");
        var a = accounts.customerForUpdate(p);
        var product = catalog.product(productId, a.priceTier(), false, true).orElseThrow(() -> ApiException.notFound("Product not found."));
        if (!product.active() || !"regular".equals(product.productType()))
            throw ApiException.conflict("PRODUCT_NOT_AVAILABLE", "Product is not currently available.");
        if (quantity > product.stock())
            throw ApiException.conflict("INSUFFICIENT_STOCK", "Requested quantity exceeds available stock.");
        carts.setQuantity(a.id(), productId, quantity, p.userId());
        return get(p);
    }

    @Transactional
    public void remove(Principal p, long id) {
        carts.remove(accounts.customerForUpdate(p).id(), id);
    }

    private Map<String, Object> view(CartRepository.CartLine i) {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("id", i.productId());
        product.put("sku", i.sku());
        product.put("name", i.name());
        product.put("category", i.category());
        product.put("stock", i.stock());
        product.put("active", i.active());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", i.id());
        out.put("productId", i.productId());
        out.put("product", product);
        out.put("quantity", i.quantity());
        out.put("unitPrice", i.unitPrice());
        out.put("lineTotal", i.lineTotal());
        if (!i.categoryActive()) out.put("invalidReason", "CATEGORY_OFFLINE");
        else if (!i.active()) out.put("invalidReason", "PRODUCT_OFFLINE");
        else if (i.stock() < i.quantity()) out.put("invalidReason", "INSUFFICIENT_STOCK");
        return out;
    }
}

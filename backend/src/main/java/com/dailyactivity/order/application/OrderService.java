package com.dailyactivity.order.application;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.cart.domain.port.CartRepository;
import com.dailyactivity.catalog.domain.port.CatalogRepository;
import com.dailyactivity.catalog.domain.port.QuantityPriceRepository;
import com.dailyactivity.catalog.domain.QuantityPricing;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.common.RequestValidation;
import com.dailyactivity.config.QuoteProperties;
import com.dailyactivity.file.application.FileService;
import com.dailyactivity.identity.domain.port.AddressRepository;
import com.dailyactivity.inventory.domain.port.InventoryRepository;
import com.dailyactivity.identity.domain.port.CustomerRepository;
import com.dailyactivity.order.domain.OrderNumberCollisionException;
import com.dailyactivity.order.domain.port.OrderRepository;
import com.dailyactivity.order.infrastructure.QuoteTokenService;
import com.dailyactivity.payment.domain.port.PaymentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class OrderService {
    private static final int ORDER_NUMBER_SUFFIX_COUNT = 10_000;
    private static final ZoneId ORDER_NUMBER_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ORDER_NUMBER_DATE_FORMAT = DateTimeFormatter.ofPattern("MMdd", Locale.ROOT);

    private final AccountService accounts;
    private final CatalogRepository catalog;
    private final QuantityPriceRepository quantityPrices;
    private final CartRepository carts;
    private final AddressRepository addresses;
    private final OrderRepository orders;
    private final CustomerRepository customers;
    private final PaymentRepository payments;
    private final InventoryRepository inventory;
    private final FileService fileService;
    private final QuoteTokenService quoteTokens;
    private final QuoteProperties quoteProperties;
    private final ObjectMapper mapper;
    private final OrderParcelService parcels;

    public OrderService(AccountService accounts, CatalogRepository catalog, CartRepository carts, AddressRepository addresses, OrderRepository orders, CustomerRepository customers, PaymentRepository payments, InventoryRepository inventory, FileService fileService, QuoteTokenService quoteTokens, QuoteProperties quoteProperties, ObjectMapper mapper, OrderParcelService parcels, QuantityPriceRepository quantityPrices) {
        this.accounts = accounts;
        this.catalog = catalog;
        this.carts = carts;
        this.quantityPrices = quantityPrices;
        this.addresses = addresses;
        this.orders = orders;
        this.customers = customers;
        this.payments = payments;
        this.inventory = inventory;
        this.fileService = fileService;
        this.quoteTokens = quoteTokens;
        this.quoteProperties = quoteProperties;
        this.mapper = mapper;
        this.parcels = parcels;
    }

    private List<QuantityPricing.Band> productBands(long productId, String tier) {
        return quantityPrices.pricesForUpdate(productId, tier);
    }

    private BigDecimal currentPrice(CatalogRepository.ProductView product, String tier, int quantity) {
        return QuantityPricing.price(productBands(product.id(), tier), quantity, product.price());
    }

    @Transactional
    public Map<String, Object> quote(Principal principal, QuoteRequest request) {
        BigDecimal shippingFee = accounts.shippingFee(principal);
        var customer = accounts.customer(principal);
        var address = addresses.findByIdForUpdate(customer.id(), request.addressId()).orElseThrow(() -> ApiException.notFound("Address not found."));
        if (request.cartItems() == null || request.cartItems().isEmpty())
            throw ApiException.badRequest("EMPTY_CART", "At least one cart item is required.");
        List<QuoteTokenService.QuoteLine> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (CartRequest item : request.cartItems().stream().sorted(Comparator.comparingLong(CartRequest::productId)).toList()) {
            var p = catalog.product(item.productId(), customer.priceTier(), true, true).orElseThrow(() -> ApiException.notFound("Product not found."));
            if (item.quantity() < 1 || item.quantity() > p.stock())
                throw ApiException.conflict("INVALID_QUANTITY", "Requested quantity is not available.");
            BigDecimal unitPrice = currentPrice(p, customer.priceTier(), item.quantity());
            BigDecimal line = unitPrice.multiply(BigDecimal.valueOf(item.quantity()));
            validateEditAmount(line);
            subtotal = subtotal.add(line);
            lines.add(new QuoteTokenService.QuoteLine(p.id(), item.quantity(), p.sku(), p.name(), p.productType(), unitPrice, p.priceRevision()));
        }
        return quoteResponse(customer.id(), address, lines, subtotal, shippingFee);
    }

    private Map<String, Object> quoteResponse(long customerId, AddressRepository.Address address, List<QuoteTokenService.QuoteLine> lines, BigDecimal subtotal, BigDecimal shipping) {
        long now = Instant.now().getEpochSecond();
        var payload = new QuoteTokenService.QuotePayload(customerId, address.id(), now, now + quoteProperties.ttlSeconds(), lines, subtotal, shipping, subtotal.add(shipping), 3, java.util.UUID.randomUUID().toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quoteToken", quoteTokens.issue(payload));
        out.put("items", lines.stream().map(l -> Map.of("productId", l.productId(), "quantity", l.quantity(), "name", l.name(), "unitPrice", l.unitPrice(), "lineTotal", l.unitPrice().multiply(BigDecimal.valueOf(l.quantity())))).toList());
        out.put("subtotal", subtotal);
        out.put("shippingFee", shipping);
        out.put("total", subtotal.add(shipping));
        out.put("address", addressView(address));
        return out;
    }

    @Transactional
    public Map<String, Object> create(Principal principal, CreateRequest request) {
        var customer = accounts.customerForUpdate(principal);
        var q = quoteTokens.parse(request.quoteToken(), customer.id());
        if (q.shippingFee().compareTo(accounts.shippingFee(principal)) != 0)
            throw ApiException.conflict("QUOTE_CHANGED", "Shipping fee changed; request a new quote.");
        String quoteHash = sha256(request.quoteToken());
        if (orders.quoteAlreadyUsed(quoteHash))
            throw ApiException.conflict("QUOTE_ALREADY_USED", "This checkout quote has already created an order.");
        List<QuoteTokenService.QuoteLine> orderedLines = q.lines().stream()
                .sorted(Comparator.comparingLong(QuoteTokenService.QuoteLine::productId))
                .toList();
        requireCartMatchesQuote(customer.id(), customer.priceTier(), orderedLines);
        var address = addresses.findByIdForUpdate(customer.id(), q.addressId()).orElseThrow(() -> ApiException.notFound("Address not found."));
        Map<Long, CatalogRepository.ProductView> quotedProducts = new HashMap<>();
        for (var line : orderedLines) {
            var p = catalog.product(line.productId(), customer.priceTier(), true, true).orElseThrow(() -> ApiException.notFound("Product not found."));
            if (p.priceRevision() != line.priceRevision() || currentPrice(p, customer.priceTier(), line.quantity()).compareTo(line.unitPrice()) != 0)
                throw ApiException.conflict("QUOTE_CHANGED", "Product pricing changed; request a new quote.");
            if (p.stock() < line.quantity())
                throw ApiException.conflict("INSUFFICIENT_STOCK", "Stock changed; request a new quote.");
            quotedProducts.put(line.productId(), p);
        }
        String operationId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        String orderNoPrefix = "YT" + createdAt.atZone(ORDER_NUMBER_ZONE).format(ORDER_NUMBER_DATE_FORMAT);
        if (customer.referrerId() != null) customers.find(principal.merchantId(), customer.referrerId()).orElseThrow(() -> ApiException.badRequest("INVALID_REFERRER", "Referrer is not in this merchant."));
        String referrerName = customer.referrerId() == null ? null : accounts.customerName(customer.referrerId());
        int suffixStart = ThreadLocalRandom.current().nextInt(ORDER_NUMBER_SUFFIX_COUNT);
        int suffixStep = randomOrderNumberStep();
        long orderId = -1;
        for (int attempt = 0; attempt < ORDER_NUMBER_SUFFIX_COUNT; attempt++) {
            int suffix = (int) ((suffixStart + (long) attempt * suffixStep) % ORDER_NUMBER_SUFFIX_COUNT);
            String orderNo = orderNoPrefix + String.format(Locale.ROOT, "%04d", suffix);
            try {
                orderId = orders.insertOrder(principal.merchantId(), new OrderRepository.OrderCreate(orderNo, quoteHash, customer.id(), customer.code(), customer.name(), customer.email(), json(address), address.name(), address.city(), request.buyerNote(), q.subtotal(), q.shippingFee(), q.total(), customer.referrerId(), referrerName, customer.commissionRate(), createdAt));
                break;
            } catch (OrderNumberCollisionException collision) {
                // The unique index is authoritative; try another random candidate within this order transaction.
            }
        }
        if (orderId < 0)
            throw ApiException.conflict("ORDER_NUMBER_EXHAUSTED", "No unique order number is available for this date.");
        for (var line : orderedLines) {
            if (!inventory.decrement(line.productId(), line.quantity(), customer.id(), operationId, orderId))
                throw ApiException.conflict("INSUFFICIENT_STOCK", "Stock changed while creating the order.");
            var product = quotedProducts.get(line.productId());
            String priceSource = product.price().compareTo(product.basePrice()) == 0 ? "base_price" : "tier_price";
            orders.insertItem(orderId, new OrderRepository.ItemCreate(line.productId(), line.sku(), line.name(), line.productType(), line.quantity(), line.unitPrice(), product.costPrice(), priceSource, customer.priceTier()), customer.id());
            orders.saveQuantityPrices(orderId, line.productId(), productBands(line.productId(), customer.priceTier()));
        }
        orders.deleteCart(customer.id(), orderedLines.stream().map(QuoteTokenService.QuoteLine::productId).toList());
        var row = orders.findForUpdate(principal.merchantId(), orderId, customer.id()).orElseThrow(() -> ApiException.notFound("Order was not created."));
        orders.addEvent(orderId, operationId, "submitted", null, "pending", null, null, null, customer.id());
        return detail(row, false, principal);
    }

    private static int randomOrderNumberStep() {
        int step;
        do {
            step = ThreadLocalRandom.current().nextInt(1, ORDER_NUMBER_SUFFIX_COUNT);
        } while (greatestCommonDivisor(step, ORDER_NUMBER_SUFFIX_COUNT) != 1);
        return step;
    }

    private static int greatestCommonDivisor(int left, int right) {
        while (right != 0) {
            int remainder = left % right;
            left = right;
            right = remainder;
        }
        return left;
    }

    private void requireCartMatchesQuote(long customerId, String tier, List<QuoteTokenService.QuoteLine> quoteLines) {
        Map<Long, Integer> quoted = new TreeMap<>();
        quoteLines.forEach(line -> quoted.put(line.productId(), line.quantity()));
        Map<Long, Integer> current = new TreeMap<>();
        carts.find(customerId, tier).forEach(line -> current.put(line.productId(), line.quantity()));
        if (!quoted.equals(current))
            throw ApiException.conflict("CART_CHANGED", "The cart changed after this quote was issued.");
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> customerOrders(Principal p, String status, String paymentStatus, String query, Instant from, Instant to, Integer page) {
        var customer = accounts.customer(p);
        int current = RequestValidation.page(page);
        return page(orders.findPage(p.merchantId(), customer.id(), status, paymentStatus, query, from, to, current), orders.count(p.merchantId(), customer.id(), status, paymentStatus, query, from, to), current, false);
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> adminOrders(Principal p, String status, String paymentStatus, String query, Instant from, Instant to, Integer page) {
        accounts.seller(p);
        int current = RequestValidation.page(page);
        var rows = orders.findPage(p.merchantId(), null, status, paymentStatus, query, from, to, current);
        return PageResponse.of(rows.stream().map(row -> summaryView(row, true)).toList(), current,
                orders.count(p.merchantId(), null, status, paymentStatus, query, from, to));
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> auditOrders(Principal p, String result, String query, Integer page) {
        accounts.requireMenu(p, "order_audit");
        String selectedResult = result == null || result.isBlank() ? "pending" : result.trim();
        if (!List.of("pending", "rejected", "approved", "all").contains(selectedResult))
            throw ApiException.badRequest("INVALID_AUDIT_RESULT_FILTER", "Choose pending, rejected, approved, or all as the review result filter.");
        int current = RequestValidation.page(page);
        return page(orders.findAuditPage(p.merchantId(), selectedResult, query, current), orders.countAudit(p.merchantId(), selectedResult, query), current, true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> auditDetail(Principal p, long id) {
        accounts.requireMenu(p, "order_audit");
        var row = orders.findAuditOrder(p.merchantId(), id).orElseThrow(() -> ApiException.notFound("Order review details not found."));
        return detail(row, true, p);
    }

    @Transactional(readOnly = true)
    public PageResponse<EditProduct> auditEditProducts(Principal p, long id, String query, Integer page) {
        accounts.requireMenu(p, "order_audit");
        var row = orders.findAuditOrder(p.merchantId(), id).orElseThrow(() -> ApiException.notFound("Order not found."));
        requireAuditEditable(row);
        String tier = customers.find(p.merchantId(), row.customerId()).orElseThrow(() -> ApiException.notFound("Customer not found.")).priceTier();
        int current = RequestValidation.page(page);
        boolean locked = orders.receivedAmount(id).signum() > 0;
        var products = catalog.orderEditProducts(query, tier, locked, current);
        var schedules = quantityPrices.prices(products.items().stream().map(CatalogRepository.ProductView::id).toList(), tier);
        return PageResponse.of(products.items().stream().map(product -> new EditProduct(product.id(), product.sku(),
                product.name(), product.productType(), "gift".equals(product.productType()) ? BigDecimal.ZERO : QuantityPricing.price(schedules.getOrDefault(product.id(), List.of()), 1, product.price()), product.stock(), schedules.getOrDefault(product.id(), List.of()))).toList(), current, products.total());
    }

    @Transactional
    public Map<String, Object> updateAudit(Principal p, long id, AuditUpdate request) {
        accounts.requireMenu(p, "order_audit");
        var row = orders.findAuditOrderForUpdate(p.merchantId(), id).orElseThrow(() -> ApiException.conflict("ORDER_NOT_EDITABLE", "订单已审核或取消，请刷新列表。"));
        requireAuditEditable(row);
        if (request == null || request.expectedUpdatedAt() == null)
            throw ApiException.badRequest("ORDER_VERSION_REQUIRED", "请重新打开订单后保存。");
        checkOrderVersion(row, request.expectedUpdatedAt());
        if (request.reason() != null && request.reason().length() > 256)
            throw ApiException.badRequest("INVALID_EDIT_REASON", "修改原因最多 256 字。");
        if (request.items() == null || request.items().isEmpty())
            throw ApiException.badRequest("ORDER_ITEMS_REQUIRED", "订单至少保留一行商品。");
        BigDecimal discount = request.discountAmount() == null ? row.discountAmount() : request.discountAmount();
        boolean locked = orders.receivedAmount(id).signum() > 0;
        if (locked && discount.compareTo(row.discountAmount()) != 0)
            throw ApiException.conflict("ORDER_AMOUNT_LOCKED", "已有确认收款，不能修改优惠额。");
        String tier = customers.find(p.merchantId(), row.customerId()).orElseThrow(() -> ApiException.notFound("Customer not found.")).priceTier();
        List<OrderRepository.ItemRow> before = orders.items(id);
        Map<Long, OrderRepository.ItemRow> existing = new HashMap<>();
        before.forEach(item -> existing.put(item.productId(), item));
        Map<Long, OrderEditItem> requested = new TreeMap<>();
        for (var input : request.items()) {
            if (input == null || input.quantity() == null)
                throw ApiException.badRequest("INVALID_ORDER_ITEMS", "商品数量必须为正整数。");
            int quantity;
            try {
                quantity = input.quantity().intValueExact();
            } catch (ArithmeticException e) {
                throw ApiException.badRequest("INVALID_ORDER_ITEMS", "商品数量必须为正整数且不超出允许范围。");
            }
            var item = new OrderEditItem(input.productId(), quantity, input.unitPrice(), input.manualPrice());
            if (item.productId() < 1 || item.quantity() < 1 || requested.put(item.productId(), item) != null)
                throw ApiException.badRequest("INVALID_ORDER_ITEMS", "商品不能重复，数量必须为正整数。");
        }
        if (locked && before.stream().anyMatch(item -> "regular".equals(item.productType()) && !requested.containsKey(item.productId())))
            throw ApiException.conflict("ORDER_AMOUNT_LOCKED", "已有确认收款，不能删除正品。");
        BigDecimal subtotal = BigDecimal.ZERO;
        List<AuditItemChange> changes = new ArrayList<>();
        for (var item : requested.values()) {
            var old = existing.get(item.productId());
            var product = old == null ? catalog.orderEditProduct(item.productId(), tier)
                    .orElseThrow(() -> ApiException.conflict("PRODUCT_NOT_AVAILABLE", "新增商品必须上架且类目启用。")) : null;
            String type = old == null ? product.productType() : old.productType();
            BigDecimal defaultPrice = old == null ? ("gift".equals(type) ? BigDecimal.ZERO : currentPrice(product, tier, item.quantity()))
                    : QuantityPricing.price(orders.quantityPrices(old.id()), item.quantity(), old.unitPrice());
            if (Boolean.TRUE.equals(item.manualPrice()) && item.unitPrice() == null)
                throw ApiException.badRequest("INVALID_ORDER_PRICE", "An explicit manual price is required.");
            BigDecimal previousPrice = old == null ? ("gift".equals(type) ? BigDecimal.ZERO : currentPrice(product, tier, 1)) : old.unitPrice();
            BigDecimal price = item.unitPrice() == null || (!Boolean.TRUE.equals(item.manualPrice()) && item.unitPrice().compareTo(previousPrice) == 0)
                    ? defaultPrice : item.unitPrice();
            if (price.scale() > 2 || ("gift".equals(type) ? price.signum() != 0 : price.signum() <= 0))
                throw ApiException.badRequest("INVALID_ORDER_PRICE", "正品单价必须大于 0 且最多两位小数；赠品单价必须为 0。");
            if (locked && "regular".equals(type) && (old == null || old.quantity() != item.quantity() || old.unitPrice().compareTo(price) != 0))
                throw ApiException.conflict("ORDER_AMOUNT_LOCKED", "已有确认收款，不能修改正品、数量或单价。");
            boolean manualPrice = Boolean.TRUE.equals(item.manualPrice()) || price.compareTo(defaultPrice) != 0;
            if (manualPrice && (request.reason() == null || request.reason().isBlank()))
                throw ApiException.badRequest("PRICE_CHANGE_REASON_REQUIRED", "调整单价时请填写修改原因。");
            BigDecimal amount = price.multiply(BigDecimal.valueOf(item.quantity()));
            validateEditAmount(price);
            validateEditAmount(amount);
            int increase = item.quantity() - (old == null ? 0 : old.quantity());
            if (increase > 0) {
                var stockProduct = old == null ? product : catalog.product(old.productId(), tier, false, true)
                        .orElseThrow(() -> ApiException.conflict("PRODUCT_NOT_AVAILABLE", "商品已不可用。"));
                if (stockProduct.stock() < increase)
                    throw ApiException.conflict("INSUFFICIENT_STOCK", "商品库存不足，订单未保存。");
            }
            subtotal = subtotal.add(amount);
            changes.add(new AuditItemChange(old, product, item.quantity(), price, manualPrice));
        }
        BigDecimal total = discountedTotal(subtotal, row.shippingFee(), discount);
        if (locked && subtotal.compareTo(row.subtotal()) != 0)
            throw ApiException.conflict("ORDER_AMOUNT_LOCKED", "已有确认收款，订单金额已锁定。");

        String operationId = UUID.randomUUID().toString();
        // Apply inventory in product ID order; all item and payment checks precede writes.
        Map<Long, AuditItemChange> byProduct = new TreeMap<>();
        changes.forEach(change -> byProduct.put(change.old() == null ? change.product().id() : change.old().productId(), change));
        Set<Long> productIds = new TreeSet<>(existing.keySet());
        productIds.addAll(byProduct.keySet());
        for (long productId : productIds) {
            var old = existing.get(productId);
            var change = byProduct.get(productId);
            int delta = (change == null ? 0 : change.quantity()) - (old == null ? 0 : old.quantity());
            if (delta > 0 && !inventory.decrement(productId, delta, p.userId(), operationId, id))
                throw ApiException.conflict("INSUFFICIENT_STOCK", "商品库存不足，订单未保存。");
            if (delta < 0) inventory.restore(productId, -delta, "Order review edit", operationId, id, p.userId());
            if (change == null) {
                orders.removeItem(old.id(), p.userId());
            } else if (old != null) {
                orders.updateItem(old.id(), change.quantity(), change.price(), p.userId());
                if (change.manualPrice()) orders.markItemManualPrice(old.id(), p.userId());
            } else {
                var product = change.product();
                var bands = productBands(product.id(), tier);
                String source = change.manualPrice() ? "manual" : !bands.isEmpty() ? "quantity_price" : product.price().compareTo(product.basePrice()) == 0 ? "base_price" : "tier_price";
                orders.insertItem(id, new OrderRepository.ItemCreate(product.id(), product.sku(), product.name(), product.productType(),
                        change.quantity(), change.price(), product.costPrice(), source, ("tier_price".equals(source) || "quantity_price".equals(source)) ? tier : null), p.userId());
                orders.saveQuantityPrices(id, product.id(), bands);
            }
        }
        orders.updateOrderAmounts(id, subtotal, discount, total, p.userId());
        orders.addEvent(id, operationId, "audit_updated", row.status(), row.status(),
                editSnapshot(before, row.subtotal(), row.discountAmount(), row.total()), editSnapshot(orders.items(id), subtotal, discount, total), request.reason(), p.userId());
        return detail(orders.findAuditOrder(p.merchantId(), id).orElseThrow(), true, p);
    }

    private void requireAuditEditable(OrderRepository.OrderRow row) {
        if (!"pending".equals(row.auditStatus()) || !"pending".equals(row.status()))
            throw ApiException.conflict("ORDER_NOT_EDITABLE", "仅待审核且待处理的订单可以编辑。");
    }

    private void validateEditAmount(BigDecimal amount) {
        if (amount.compareTo(new BigDecimal("9999999999999999.99")) > 0)
            throw ApiException.badRequest("INVALID_ORDER_PRICE", "订单金额超出允许范围。");
    }

    private BigDecimal discountedTotal(BigDecimal subtotal, BigDecimal shippingFee, BigDecimal discountAmount) {
        if (discountAmount.signum() < 0 || discountAmount.scale() > 2 || discountAmount.compareTo(subtotal) > 0)
            throw ApiException.badRequest("INVALID_DISCOUNT_AMOUNT", "优惠额必须为非负金额、最多两位小数，且不能超过商品金额。");
        BigDecimal grossTotal = subtotal.add(shippingFee);
        validateEditAmount(grossTotal);
        BigDecimal total = grossTotal.subtract(discountAmount);
        if (discountAmount.signum() > 0 && total.signum() <= 0)
            throw ApiException.badRequest("INVALID_DISCOUNT_AMOUNT", "优惠后应付金额必须大于 0。");
        return total;
    }

    private String editSnapshot(List<OrderRepository.ItemRow> items, BigDecimal subtotal, BigDecimal discountAmount, BigDecimal total) {
        try {
            return mapper.writeValueAsString(Map.of("items", items, "subtotal", subtotal, "discountAmount", discountAmount, "total", total));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record AuditItemChange(OrderRepository.ItemRow old, CatalogRepository.ProductView product, int quantity,
                                   BigDecimal price, boolean manualPrice) {}

    public record EditProduct(long id, String sku, String name, String productType, BigDecimal unitPrice, int stock, List<QuantityPricing.Band> quantityPrices) {}

    public record AuditEditItem(long productId, BigDecimal quantity, BigDecimal unitPrice, Boolean manualPrice) {
        public AuditEditItem(long productId, BigDecimal quantity, BigDecimal unitPrice) { this(productId, quantity, unitPrice, null); }
        public AuditEditItem(long productId, int quantity, BigDecimal unitPrice) {
            this(productId, BigDecimal.valueOf(quantity), unitPrice, null);
        }
    }

    public record AuditUpdate(List<AuditEditItem> items, String reason, Instant expectedUpdatedAt, BigDecimal discountAmount) {
        public AuditUpdate(List<AuditEditItem> items, String reason, Instant expectedUpdatedAt) {
            this(items, reason, expectedUpdatedAt, null);
        }
    }

    @Transactional
    public Map<String, Object> decideAudit(Principal p, long id, AuditDecision request) {
        accounts.requireMenu(p, "order_audit");
        if (request == null || (!"approved".equals(request.result()) && !"rejected".equals(request.result())))
            throw ApiException.badRequest("INVALID_AUDIT_RESULT", "Choose approved or rejected as the review result.");
        if (request.remark() != null && request.remark().length() > 1000)
            throw ApiException.badRequest("INVALID_AUDIT_REMARK", "Review remark must be no longer than 1000 characters.");
        var row = orders.findAuditOrderForUpdate(p.merchantId(), id).orElseThrow(() -> ApiException.notFound("Pending order review not found."));
        checkOrderVersion(row, request.expectedUpdatedAt());
        String operationId = UUID.randomUUID().toString();
        String remark = request.remark() == null || request.remark().isBlank() ? null : request.remark().trim();
        BigDecimal discount = request.discountAmount() == null ? row.discountAmount() : request.discountAmount();
        BigDecimal total = discountedTotal(row.subtotal(), row.shippingFee(), discount);
        boolean discountChanged = discount.compareTo(row.discountAmount()) != 0;
        if (discountChanged) requireAuditEditable(row);
        if ("rejected".equals(request.result()) && discountChanged)
            throw ApiException.badRequest("DISCOUNT_REQUIRES_APPROVAL", "优惠额仅能随审核通过保存。");
        if ("approved".equals(request.result()) && discountChanged
                && orders.receivedAmount(id).signum() > 0)
            throw ApiException.conflict("ORDER_AMOUNT_LOCKED", "已有确认收款，不能设置优惠额。");
        if ("approved".equals(request.result()) && discountChanged && request.expectedUpdatedAt() == null)
            throw ApiException.badRequest("ORDER_VERSION_REQUIRED", "设置优惠额前请重新打开订单。");
        if ("approved".equals(request.result()) && discountChanged) {
            orders.updateOrderAmounts(id, row.subtotal(), discount, total, p.userId());
            var items = orders.items(id);
            orders.addEvent(id, operationId, "audit_discounted", row.status(), row.status(),
                    editSnapshot(items, row.subtotal(), row.discountAmount(), row.total()),
                    editSnapshot(items, row.subtotal(), discount, total),
                    "订单优惠 " + discount.toPlainString() + " USD；应付 " + total.toPlainString() + " USD", p.userId());
        }
        orders.decideAudit(id, request.result(), remark, p.userId());
        if ("approved".equals(request.result())) {
            var approved = orders.findForUpdate(p.merchantId(), id, row.customerId()).orElseThrow(() -> ApiException.notFound("Order not found."));
            orders.createReceivable(approved, p.userId());
            orders.createAccrual(approved, p.userId());
            orders.updateBillingPayable(id, p.userId());
        } else {
            for (var item : orders.items(id))
                inventory.restore(item.productId(), item.quantity(), "Order review rejected", operationId, id, p.userId());
            orders.updateStatus(id, "cancelled", null, null, p.userId());
            orders.addEvent(id, operationId, "cancelled", "pending", "cancelled", null, null, null, p.userId());
        }
        orders.addEvent(id, operationId, "approved".equals(request.result()) ? "audit_approved" : "audit_rejected",
                "pending_review", request.result(), null, null, remark, p.userId());
        var decided = orders.find(p.merchantId(), id, row.customerId()).orElseThrow(() -> ApiException.notFound("Order not found."));
        return detail(decided, true, p);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> sellerPaymentChannels(Principal p) {
        accounts.requireMenu(p, "orders");
        return payments.find(p.merchantId(), true).stream().map(channel -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", channel.id());
            value.put("name", channel.name());
            value.put("currencyCode", channel.currencyCode());
            value.put("network", channel.network());
            return value;
        }).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> adminCustomerOrders(Principal p, long customerId, String status, String paymentStatus, String query, Instant from, Instant to, Integer page) {
        accounts.requireMenu(p, "customers");
        customers.find(p.merchantId(), customerId).orElseThrow(() -> ApiException.notFound("Customer not found."));
        int current = RequestValidation.page(page);
        return page(orders.findPage(p.merchantId(), customerId, status, paymentStatus, query, from, to, current), orders.count(p.merchantId(), customerId, status, paymentStatus, query, from, to), current, true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> adminCustomerOrderFilterSummary(Principal p, long customerId, String status, String paymentStatus, String query, Instant from, Instant to) {
        accounts.requireMenu(p, "customers");
        customers.find(p.merchantId(), customerId).orElseThrow(() -> ApiException.notFound("Customer not found."));
        var summary = orders.customerOrderFilterSummary(p.merchantId(), customerId, status, paymentStatus, query, from, to);
        return Map.of("orderCount", summary.orderCount(),
                "pendingPaymentOrders", summary.pendingPaymentOrders(),
                "totalAmount", summary.totalAmount(),
                "pendingPaymentAmount", summary.pendingPaymentAmount());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> adminCustomerOrderDetail(Principal p, long customerId, long orderId) {
        accounts.requireMenu(p, "customers");
        customers.find(p.merchantId(), customerId).orElseThrow(() -> ApiException.notFound("Customer not found."));
        var row = orders.find(p.merchantId(), orderId, customerId).orElseThrow(() -> ApiException.notFound("Order not found."));
        return detail(row, true, p);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Principal p, long id) {
        boolean seller = "seller".equals(p.userType());
        if (seller) accounts.seller(p);
        var row = orders.find(p.merchantId(), id, seller ? null : accounts.customer(p).id()).orElseThrow(() -> ApiException.notFound("Order not found."));
        return detail(row, seller, p);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(Principal p) {
        if ("seller".equals(p.userType())) {
            accounts.seller(p);
            var d = orders.dashboard(p.merchantId());
            var summary = orders.adminOrderSummary(p.merchantId());
            return Map.ofEntries(
                    Map.entry("openOrders", d.openOrders()),
                    Map.entry("grossOrderValue", summary.grossOrderValue()),
                    Map.entry("customers", d.customers()),
                    Map.entry("allOrders", summary.allOrders()),
                    Map.entry("pendingPaymentOrders", summary.pendingPaymentOrders()),
                    Map.entry("notShippedOrders", summary.notShippedOrders()),
                    Map.entry("completedOrders", summary.completedOrders()),
                    Map.entry("pendingReceivableAmount", summary.pendingReceivableAmount()),
                    Map.entry("paidAmount", summary.paidAmount()));
        }
        var customer = accounts.customer(p);
        var summary = orders.customerOrderSummary(p.merchantId(), customer.id());
        return Map.ofEntries(
                Map.entry("allOrders", summary.allOrders()),
                Map.entry("pending", summary.pendingOrders()),
                Map.entry("completed", summary.completedOrders()),
                Map.entry("cancelled", summary.cancelledOrders()),
                Map.entry("received", summary.receivedOrders()),
                Map.entry("transit", summary.transitOrders()),
                Map.entry("pendingShip", summary.pendingShipmentOrders()),
                Map.entry("payableOrders", summary.payableOrders()),
                Map.entry("payableTotal", summary.payableTotal()));
    }

    @Transactional
    public Map<String, Object> updateCustomer(Principal p, long id, CustomerUpdate request) {
        var customer = accounts.customer(p);
        var row = orders.findForUpdate(p.merchantId(), id, customer.id()).orElseThrow(() -> ApiException.notFound("Order not found."));
        checkOrderVersion(row, request.expectedUpdatedAt());
        if (!"pending".equals(row.status()) || !"unpaid".equals(row.paymentStatus()) || parcels.hasParcels(id))
            throw ApiException.conflict("ORDER_NOT_EDITABLE", "Only unpaid pending orders can be edited.");
        var address = addresses.findByIdForUpdate(customer.id(), request.addressId()).orElseThrow(() -> ApiException.notFound("Address not found."));
        String operationId = UUID.randomUUID().toString();
        BigDecimal subtotal = applyItems(row, request.items(), customer.priceTier(), customer.id(), operationId, false);
        orders.updateCustomerDetails(id, json(address), address.name(), address.city(), request.buyerNote(), customer.id());
        if (!"approved".equals(row.auditStatus()))
            orders.updateOrderAmounts(id, subtotal, row.discountAmount(), discountedTotal(subtotal, row.shippingFee(), row.discountAmount()), customer.id());
        orders.addEvent(id, operationId, "customer_updated", "pending", "pending", null, null, null, customer.id());
        return detail(orders.find(p.merchantId(), id, customer.id()).orElseThrow(), false, p);
    }

    @Transactional
    public Map<String, Object> updateSeller(Principal p, long id, SellerUpdate request) {
        accounts.requireMenu(p, "orders");
        var row = orders.findForUpdate(p.merchantId(), id, null).orElseThrow(() -> ApiException.notFound("Order not found."));
        checkOrderVersion(row, request.expectedUpdatedAt());
        if (!"pending".equals(row.status()) || !"unpaid".equals(row.paymentStatus()) || parcels.hasParcels(id))
            throw ApiException.conflict("ORDER_NOT_EDITABLE", "Only unpaid pending orders can be edited.");
        String operationId = UUID.randomUUID().toString();
        BigDecimal subtotal = applyItems(row, request.items(), null, p.userId(), operationId, true);
        orders.updateSellerDetails(id, request.addressSnapshot(), request.recipientName(), request.city(), request.buyerNote(), request.internalNote(), p.userId());
        if (!"approved".equals(row.auditStatus()))
            orders.updateOrderAmounts(id, subtotal, row.discountAmount(), discountedTotal(subtotal, row.shippingFee(), row.discountAmount()), p.userId());
        orders.addEvent(id, operationId, "seller_updated", "pending", "pending", null, null, eventNote(request.internalNote(), request.buyerNote()), p.userId());
        return detail(orders.find(p.merchantId(), id, null).orElseThrow(), true, p);
    }

    private BigDecimal applyItems(OrderRepository.OrderRow row, List<OrderEditItem> requested, String tier, long actor, String operationId, boolean allowPriceChange) {
        if (requested == null) throw ApiException.badRequest("ORDER_ITEMS_REQUIRED", "Order item quantities are required.");
        Map<Long, OrderEditItem> requestedByProduct = new HashMap<>();
        for (var item : requested) {
            if (item == null || item.productId() < 1 || requestedByProduct.put(item.productId(), item) != null)
                throw ApiException.badRequest("INVALID_ORDER_ITEMS", "Order items must be unique and valid.");
        }
        var currentItems = orders.items(row.id());
        if ("approved".equals(row.auditStatus())) {
            for (var current : currentItems) {
                var change = requestedByProduct.remove(current.productId());
                // Older clients may omit read-only gifts; omission keeps the existing gift.
                if (change == null && "gift".equals(current.productType())) continue;
                if (change == null || change.quantity() != current.quantity()
                        || (change.unitPrice() != null && change.unitPrice().compareTo(current.unitPrice()) != 0)
                        || Boolean.TRUE.equals(change.manualPrice()))
                    throw ApiException.conflict("ORDER_ITEMS_LOCKED", "Order items and prices cannot be changed after approval.");
            }
            if (!requestedByProduct.isEmpty())
                throw ApiException.conflict("ORDER_ITEMS_LOCKED", "Order items and prices cannot be changed after approval.");
            return row.subtotal();
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        List<ItemQuantityChange> changes = new ArrayList<>();
        for (var current : currentItems) {
            var change = requestedByProduct.remove(current.productId());
            if ("gift".equals(current.productType())) {
                if (change != null && (change.quantity() != current.quantity() || (change.unitPrice() != null && change.unitPrice().signum() != 0)))
                    throw ApiException.badRequest("GIFT_ITEM_READ_ONLY", "Gift quantities and prices cannot be changed.");
                subtotal = subtotal.add(current.lineAmount());
                continue;
            }
            if (change == null || change.quantity() < 1 || (!allowPriceChange && change.unitPrice() != null && change.unitPrice().compareTo(current.unitPrice()) != 0))
                throw ApiException.badRequest("INVALID_ORDER_ITEMS", "Every regular item needs a valid quantity.");
            BigDecimal automatic = QuantityPricing.price(orders.quantityPrices(current.id()), change.quantity(), current.unitPrice());
            BigDecimal price = allowPriceChange && change.unitPrice() != null
                    && (Boolean.TRUE.equals(change.manualPrice()) || change.unitPrice().compareTo(current.unitPrice()) != 0) ? change.unitPrice() : automatic;
            if (price.signum() <= 0 || price.scale() > 2) throw ApiException.badRequest("INVALID_ORDER_PRICE", "Regular product price must be positive.");
            changes.add(new ItemQuantityChange(current, change.quantity(), price));
            subtotal = subtotal.add(price.multiply(BigDecimal.valueOf(change.quantity())));
        }
        if (!requestedByProduct.isEmpty()) throw ApiException.badRequest("INVALID_ORDER_ITEMS", "An item is not part of this order.");
        discountedTotal(subtotal, row.shippingFee(), row.discountAmount());
        changes.sort(Comparator.comparingLong(change -> change.item().productId()));
        for (var change : changes) {
            var current = change.item();
            int delta = change.quantity() - current.quantity();
            if (delta > 0) {
                // An unpaid order remains editable after a product is taken offline.  The
                // edit changes an already accepted order; it must not be blocked by the
                // current storefront visibility of that product.
                var product = catalog.product(current.productId(), tier, false, true).orElseThrow(() -> ApiException.conflict("PRODUCT_NOT_AVAILABLE", "Product is no longer available."));
                if (product.stock() < delta || !inventory.decrement(current.productId(), delta, actor, operationId, row.id()))
                    throw ApiException.conflict("INSUFFICIENT_STOCK", "Stock changed while updating the order.");
            } else if (delta < 0) {
                inventory.restore(current.productId(), -delta, "Order quantity reduced", operationId, row.id(), actor);
            }
            orders.updateItem(current.id(), change.quantity(), change.price(), actor);
            var requestedItem = requested.stream().filter(i -> i.productId() == current.productId()).findFirst().orElseThrow();
            if (allowPriceChange && (Boolean.TRUE.equals(requestedItem.manualPrice()) || change.price().compareTo(
                    QuantityPricing.price(orders.quantityPrices(current.id()), change.quantity(), current.unitPrice())) != 0))
                orders.markItemManualPrice(current.id(), actor);
        }
        return subtotal;
    }

    private record ItemQuantityChange(OrderRepository.ItemRow item, int quantity, BigDecimal price) {}

    @Transactional
    public Map<String, Object> cancel(Principal p, long id, String reason, Instant expectedUpdatedAt) {
        var customer = accounts.customer(p);
        var row = orders.findForUpdate(p.merchantId(), id, customer.id()).orElseThrow(() -> ApiException.notFound("Order not found."));
        checkOrderVersion(row, expectedUpdatedAt);
        if (!"pending".equals(row.status()) || !"unpaid".equals(row.paymentStatus()) || parcels.hasParcels(id))
            throw ApiException.conflict("ORDER_NOT_CANCELLABLE", "Only unpaid pending orders can be cancelled.");
        if (orders.hasSettledCommission(id))
            throw ApiException.conflict("ORDER_COMMISSION_SETTLED", "An order cannot be cancelled after its commission has been settled.");
        String operationId = UUID.randomUUID().toString();
        for (var item : orders.items(id))
            inventory.restore(item.productId(), item.quantity(), "Customer cancelled order", operationId, id, customer.id());
        orders.updateStatus(id, "cancelled", null, reason, customer.id());
        orders.updateBillingVoid(id, customer.id());
        orders.updateCommissionVoid(id, customer.id());
        orders.withdrawPendingAudit(id, customer.id());
        orders.addEvent(id, operationId, "cancelled", "pending", "cancelled", null, null, reason, customer.id());
        return detail(orders.find(p.merchantId(), id, customer.id()).orElseThrow(), false, p);
    }

    @Transactional
    public Map<String, Object> submitPayment(Principal p, long id, PaymentRequest request) {
        var customer = accounts.customer(p);
        var row = orders.findForUpdate(p.merchantId(), id, customer.id()).orElseThrow(() -> ApiException.notFound("Order not found."));
        requirePaymentVersion(request.expectedUpdatedAt());
        checkOrderVersion(row, request.expectedUpdatedAt());
        if ((!"unpaid".equals(row.paymentStatus()) && !"partially_paid".equals(row.paymentStatus())) || "cancelled".equals(row.status()) || orders.pendingPaymentSubmission(id).isPresent())
            throw ApiException.conflict("PAYMENT_NOT_ALLOWED", "Payment cannot be submitted for this order.");
        if (request.expectedTotalAmount() == null || request.expectedTotalAmount().compareTo(row.total()) != 0)
            throw ApiException.conflict("TOTAL_MISMATCH", "Expected total does not match the order.");
        validatePaymentAmount(request.declaredAmount(), row.total().subtract(orders.receivedAmount(id)));
        validateTransactionNo(request.transactionNo());
        var channel = payments.channel(p.merchantId(), request.channelId(), true).orElseThrow(() -> ApiException.notFound("Payment channel not found."));
        orders.savePaymentSubmission(new OrderRepository.PaymentSubmissionInput(id, customer.id(), request.channelId(), request.transactionNo().trim(), row.total(), request.declaredAmount()), channel.snapshot(), customer.id());
        orders.updatePaymentTotals(id, "pending_verification", orders.receivedAmount(id), customer.id());
        orders.addEvent(id, UUID.randomUUID().toString(), "payment_submitted", row.paymentStatus(), "pending_verification", null, null, null, customer.id());
        return detail(orders.find(p.merchantId(), id, customer.id()).orElseThrow(), false, p);
    }

    @Transactional
    public Map<String, Object> sellerAction(Principal p, long id, String action, SellerAction request) {
        accounts.requireMenu(p, "orders");
        var row = orders.findForUpdate(p.merchantId(), id, null).orElseThrow(() -> ApiException.notFound("Order not found."));
        checkOrderVersion(row, request.expectedUpdatedAt());
        String operationId = UUID.randomUUID().toString();
        String next;
        switch (action) {
            case "confirm-payment" -> {
                requirePaymentVersion(request.expectedUpdatedAt());
                if ("cancelled".equals(row.status()) || "paid".equals(row.paymentStatus()))
                    throw ApiException.conflict("PAYMENT_NOT_ALLOWED", "Payment cannot be confirmed.");
                if (request.expectedTotalAmount() == null || request.expectedTotalAmount().compareTo(row.total()) != 0)
                    throw ApiException.conflict("TOTAL_MISMATCH", "Expected total does not match the order.");
                BigDecimal alreadyReceived = orders.receivedAmount(id);
                validatePaymentAmount(request.receivedAmount(), row.total().subtract(alreadyReceived));
                if (request.internalNote() != null && request.internalNote().length() > 1000)
                    throw ApiException.badRequest("INVALID_PAYMENT_NOTE", "收款备注最多 1,000 字。");
                var sub = orders.pendingPaymentSubmission(id);
                if (sub.isPresent() ? !java.util.Objects.equals(request.submissionId(), sub.get().id()) : request.submissionId() != null)
                    throw ApiException.conflict("PAYMENT_SUBMISSION_CHANGED", "付款申报已变化，请重新打开收款窗口。");
                long channelId;
                String snapshot;
                if (sub.isPresent()) {
                    channelId = sub.get().channelId();
                    if (request.channelId() != null && request.channelId() != channelId)
                        throw ApiException.conflict("PAYMENT_CHANNEL_LOCKED", "本笔申报渠道已锁定。");
                    snapshot = sub.get().channelSnapshot();
                    if (request.receivedAmount().compareTo(sub.get().declaredAmount()) != 0 && (request.internalNote() == null || request.internalNote().isBlank()))
                        throw ApiException.badRequest("PAYMENT_DIFFERENCE_REASON_REQUIRED", "核实金额与申报金额不同，请填写差异原因。");
                } else {
                    if (request.channelId() == null)
                        throw ApiException.badRequest("PAYMENT_CHANNEL_REQUIRED", "请选择有效的收款渠道。");
                    var channel = payments.channel(p.merchantId(), request.channelId(), true).orElseThrow(() -> ApiException.notFound("Payment channel not found."));
                    channelId = channel.id();
                    snapshot = channel.snapshot();
                }
                String transactionNo = request.transactionNo() == null || request.transactionNo().isBlank()
                        ? sub.map(OrderRepository.PaymentSubmissionRow::transactionNo).orElse(null) : request.transactionNo().trim();
                validateTransactionNo(transactionNo);
                orders.savePaymentReceipt(new OrderRepository.PaymentReceiptInput(id, sub.map(OrderRepository.PaymentSubmissionRow::id).orElse(null), channelId, transactionNo, request.receivedAmount(), request.internalNote()), snapshot, p.userId());
                sub.ifPresent(value -> orders.confirmSubmission(value.id(), p.userId()));
                BigDecimal received = alreadyReceived.add(request.receivedAmount());
                String paymentStatus = received.compareTo(row.total()) == 0 ? "paid" : "partially_paid";
                orders.updatePaymentTotals(id, paymentStatus, received, p.userId());
                orders.addEvent(id, operationId, "payment_confirmed", row.paymentStatus(), paymentStatus, null, null, eventNote(request.internalNote(), null), p.userId());
                return detail(orders.find(p.merchantId(), id, null).orElseThrow(), true, p);
            }
            case "pack", "ship", "receive" -> {
                parcels.legacyAction(p, row, action, request);
                return detail(orders.find(p.merchantId(), id, null).orElseThrow(), true, p);
            }
            case "complete" -> {
                requireStatus(row, "received");
                parcels.requireAllReceived(id);
                if (!request.confirmed())
                    throw ApiException.badRequest("CONFIRMATION_REQUIRED", "confirmed must be true.");
                next = "completed";
            }
            default -> throw ApiException.badRequest("UNKNOWN_ORDER_ACTION", "Unknown order action.");
        }
        orders.updateStatus(id, next, request.publicNote(), request.internalNote(), p.userId());
        if ("completed".equals(next)) {
            orders.captureCoaSnapshots(id, p.userId());
        }
        orders.addEvent(id, operationId, action, row.status(), next, null, null, eventNote(request.internalNote(), request.publicNote()), p.userId());
        return detail(orders.find(p.merchantId(), id, null).orElseThrow(), true, p);
    }

    @Transactional
    public Map<String, Object> packParcels(Principal p, long id, OrderParcelService.PackRequest request) {
        parcels.pack(p, id, request);
        return detail(orders.find(p.merchantId(), id, null).orElseThrow(), true, p);
    }

    @Transactional
    public Map<String, Object> shipParcels(Principal p, long id, OrderParcelService.ShipRequest request) {
        parcels.ship(p, id, request);
        return detail(orders.find(p.merchantId(), id, null).orElseThrow(), true, p);
    }

    @Transactional
    public Map<String, Object> receiveParcels(Principal p, long id, OrderParcelService.ReceiveRequest request) {
        parcels.receive(p, id, request);
        return detail(orders.find(p.merchantId(), id, null).orElseThrow(), true, p);
    }

    private void requirePaymentVersion(Instant expectedUpdatedAt) {
        if (expectedUpdatedAt == null) throw ApiException.badRequest("ORDER_VERSION_REQUIRED", "请重新打开订单后提交付款或收款。");
    }

    private void validatePaymentAmount(BigDecimal amount, BigDecimal remaining) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2)
            throw ApiException.badRequest("INVALID_PAYMENT_AMOUNT", "本次金额必须大于 0 且最多两位小数。");
        if (amount.compareTo(remaining) > 0)
            throw ApiException.conflict("PAYMENT_EXCEEDS_REMAINING", "本次金额不能超过剩余待收金额。");
    }

    private void validateTransactionNo(String transactionNo) {
        if (transactionNo == null || transactionNo.isBlank())
            throw ApiException.badRequest("TRANSACTION_NO_REQUIRED", "Transaction number is required.");
        if (transactionNo.trim().length() > 256)
            throw ApiException.badRequest("INVALID_TRANSACTION_NO", "Transaction number must not exceed 256 characters.");
    }

    private Map<String, Object> submissionView(OrderRepository.PaymentSubmissionRow s) {
        return Map.of("id", s.id(), "channelId", s.channelId(), "channelSnapshot", parse(s.channelSnapshot()),
                "transactionNo", s.transactionNo(), "expectedTotalAmount", s.declaredTotal(), "declaredAmount", s.declaredAmount(),
                "submittedAt", s.submittedAt(), "status", s.status());
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> paymentRecords(Principal p, long id, Integer requestedPage) {
        boolean seller = "seller".equals(p.userType());
        if (seller) {
            accounts.requireAnyMenu(p, "orders", "bills", "commissions", "customers");
            orders.find(p.merchantId(), id, null).orElseThrow(() -> ApiException.notFound("Order not found."));
        }
        else orders.find(p.merchantId(), id, accounts.customer(p).id()).orElseThrow(() -> ApiException.notFound("Order not found."));
        int page = RequestValidation.page(requestedPage);
        var records = orders.paymentRecords(id, page).stream().map(record -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("submissionId", record.submissionId());
            value.put("receiptId", record.receiptId());
            // Historical receipts may have a different channel from the original declaration.
            if (seller && record.receiptId() != null) {
                value.put("channelId", record.receiptChannelId());
                value.put("channelSnapshot", parse(record.receiptChannelSnapshot()));
            } else {
                value.put("channelId", record.channelId());
                value.put("channelSnapshot", parse(record.channelSnapshot()));
            }
            value.put("originalTransactionNo", record.originalTransactionNo());
            value.put("transactionNo", record.verifiedTransactionNo());
            value.put("orderTotalSnapshot", record.orderTotalSnapshot());
            value.put("declaredAmount", record.declaredAmount());
            value.put("receivedAmount", record.receivedAmount());
            value.put("status", record.status());
            value.put("submittedAt", record.submittedAt());
            value.put("verifiedAt", record.verifiedAt());
            if (seller) {
                value.put("verifiedByName", record.verifiedByName());
                value.put("internalNote", record.internalNote());
            }
            return value;
        }).toList();
        return PageResponse.of(records, page, orders.paymentRecordCount(id));
    }

    private void requireStatus(OrderRepository.OrderRow row, String expected) {
        if (!expected.equals(row.status()))
            throw ApiException.conflict("INVALID_ORDER_TRANSITION", "Order is not in the required status.");
    }

    private void checkOrderVersion(OrderRepository.OrderRow row, Instant expectedUpdatedAt) {
        if (expectedUpdatedAt != null && !expectedUpdatedAt.equals(row.updatedAt()))
            throw ApiException.conflict("ORDER_CHANGED", "The order changed after it was loaded. Refresh and try again.");
    }

    private PageResponse<Map<String, Object>> page(List<OrderRepository.OrderRow> rows, long total, int page, boolean seller) {
        return PageResponse.of(rows.stream().map(r -> summaryView(r, seller)).toList(), page, total);
    }

    @Transactional(readOnly = true)
    public OrderParcelService.FulfillmentSummary fulfillmentSummary(long orderId) {
        return parcels.summary(orderId, orders.items(orderId));
    }

    private static String eventNote(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private Map<String, Object> summaryView(OrderRepository.OrderRow r, boolean seller) {
        Map<String, Object> out = base(r);
        var orderItems = orders.items(r.id());
        out.put("fulfillmentSummary", parcels.summary(r.id(), orderItems));
        out.put("items", orderItems.stream().map(i -> Map.of("orderItemId", i.id(), "productId", i.productId(), "sku", i.sku(), "name", i.name(), "productType", i.productType(), "quantity", i.quantity(), "unitPrice", i.unitPrice(), "lineTotal", i.lineAmount(), "quantityPrices", orders.quantityPrices(i.id()))).toList());
        if (seller) {
            out.put("auditStatus", r.auditStatus());
            out.put("customer", Map.of("id", r.customerId(), "name", r.customerName(), "email", r.customerEmail(), "userCode", r.customerCode()));
        } else {
            boolean amountLocked = orders.hasSettledCommission(r.id());
            out.put("amountLocked", "approved".equals(r.auditStatus()) || amountLocked);
            out.put("allowedActions", customerAllowedActions(r, amountLocked));
        }
        return out;
    }

    private List<String> customerAllowedActions(OrderRepository.OrderRow order, boolean commissionSettled) {
        List<String> allowed = new ArrayList<>();
        if ("pending".equals(order.status()) && "unpaid".equals(order.paymentStatus()) && !parcels.hasParcels(order.id())) {
            allowed.add("edit");
            if ("pending".equals(order.auditStatus()) && !commissionSettled) allowed.add("edit_items");
            if (!commissionSettled) {
                allowed.add("cancel");
            }
        }
        if (!"cancelled".equals(order.status()) && ("unpaid".equals(order.paymentStatus()) || "partially_paid".equals(order.paymentStatus())))
            allowed.add("pay");
        return allowed;
    }

    private Map<String, Object> detail(OrderRepository.OrderRow r, boolean seller, Principal principal) {
        Map<String, Object> out = summaryView(r, seller);
        out.put("address", parse(r.addressSnapshot()));
        if (seller) {
            out.put("auditedByUserId", r.auditedByUserId());
            out.put("auditedByName", r.auditedByName());
            out.put("auditedAt", r.auditedAt());
            out.put("auditRemark", r.auditRemark());
        }
        var events = orders.events(r.id());
        var receiptNotes = seller ? historicalPaymentEventNotes(r.id(), events) : Map.<Long, String>of();
        var eventViews = events.stream().filter(e -> seller || !e.action().startsWith("audit_")).map(e -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", e.action());
            value.put("parcelId", e.parcelId());
            value.put("fromStatus", e.fromStatus());
            value.put("toStatus", e.toStatus());
            String note = e.note() == null || e.note().isBlank() ? receiptNotes.getOrDefault(e.id(), e.note()) : e.note();
            value.put("note", !seller && "payment_confirmed".equals(e.action()) ? null : note);
            value.put("occurredAt", e.occurredAt());
            List<String> fileUuids = orders.eventFiles(r.id(), e.id());
            var urls = signedEventFileUrls(fileUuids, seller, principal);
            List<String> images = new ArrayList<>();
            List<Map<String, String>> labels = new ArrayList<>();
            for (String uuid : fileUuids) {
                var metadata = fileService.attachmentMetadata(uuid);
                if ("order_shipping_label".equals(metadata.get("purpose"))) {
                    if (urls.containsKey(uuid)) labels.add(Map.of("fileUuid", uuid, "url", urls.get(uuid), "mediaType", metadata.get("mediaType")));
                } else images.add(uuid);
            }
            value.put("files", images);
            value.put("signedFileUrls", urls);
            value.put("labels", labels);
            if (seller) {
                value.put("actorName", e.actorName());
            }
            return value;
        }).toList();
        out.put("events", eventViews);
        var parcelItems = parcels.items(r.id());
        var orderItems = orders.items(r.id()).stream().collect(java.util.stream.Collectors.toMap(OrderRepository.ItemRow::id, java.util.function.Function.identity()));
        out.put("parcels", parcels.rows(r.id()).stream().map(parcel -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", parcel.id()); view.put("parcelUuid", parcel.uuid()); view.put("parcelNo", parcel.number());
            view.put("status", parcel.status()); view.put("trackingNo", parcel.trackingNo()); view.put("carrier", parcel.carrier());
            view.put("packedAt", parcel.packedAt()); view.put("shippedAt", parcel.shippedAt()); view.put("receivedAt", parcel.receivedAt());
            view.put("items", parcelItems.stream().filter(i -> i.parcelId() == parcel.id() && orderItems.containsKey(i.orderItemId())).map(i -> {
                var item = orderItems.get(i.orderItemId());
                return Map.of("orderItemId", i.orderItemId(), "productId", item.productId(), "name", item.name(), "sku", item.sku(), "productType", item.productType(), "quantity", i.quantity());
            }).toList());
            view.put("events", eventViews.stream().filter(e -> Objects.equals(e.get("parcelId"), parcel.id())).toList());
            return view;
        }).toList());
        var submission = orders.paymentSubmission(r.id());
        submission.ifPresent(s -> out.put("paymentSubmission", submissionView(s)));
        orders.pendingPaymentSubmission(r.id()).ifPresent(s -> out.put("pendingPaymentSubmission", submissionView(s)));
        orders.paymentReceiptTransactionNo(r.id()).ifPresent(transactionNo -> out.put("paymentReceipt", Map.of("transactionNo", transactionNo)));
        if (!seller) return out;
        List<String> allowed = new ArrayList<>();
        if (seller && "pending".equals(r.auditStatus()) && "pending".equals(r.status())) allowed.add("audit_edit");
        if (seller && "pending".equals(r.auditStatus()) && "pending".equals(r.status())
                && r.paidAmount().signum() == 0) allowed.add("audit_discount");
        if (seller && "approved".equals(r.auditStatus()) && !List.of("completed", "cancelled").contains(r.status())) {
            var active = parcels.rows(r.id()).stream().filter(parcel -> !"repacked".equals(parcel.status())).toList();
            var summary = parcels.summary(r.id(), orders.items(r.id()));
            if (summary != null && summary.unpackedQuantity() > 0) allowed.add("pack_parcel");
            if (active.stream().anyMatch(parcel -> "packed".equals(parcel.status()))) allowed.add("ship_parcel");
            if (active.stream().anyMatch(parcel -> "shipped".equals(parcel.status()))) allowed.add("receive_parcel");
            if ("received".equals(r.status())) allowed.add("complete");
        }
        out.put("allowedActions", allowed);
        return out;
    }

    private Map<Long, String> historicalPaymentEventNotes(long orderId, List<OrderRepository.EventRow> events) {
        var confirmations = events.stream().filter(event -> "payment_confirmed".equals(event.action()))
                .sorted(Comparator.comparingLong(OrderRepository.EventRow::id)).toList();
        if (confirmations.stream().noneMatch(event -> event.note() == null || event.note().isBlank())) return Map.of();
        var receipts = orders.paymentReceiptNotes(orderId);
        // Both facts are appended once per confirmation under the same order lock and transaction.
        // Only use the historical sequence when counts, actors and time boundaries agree.
        if (receipts.size() != confirmations.size()) return Map.of();
        Map<Long, String> notes = new HashMap<>();
        for (int index = 0; index < confirmations.size(); index++) {
            var event = confirmations.get(index);
            var receipt = receipts.get(index);
            if (receipt.verifiedByUserId() != event.actorUserId() || receipt.verifiedAt().isAfter(event.occurredAt())
                    || (index > 0 && receipt.verifiedAt().isBefore(confirmations.get(index - 1).occurredAt()))) return Map.of();
            if ((event.note() == null || event.note().isBlank()) && receipt.internalNote() != null && !receipt.internalNote().isBlank())
                notes.put(event.id(), receipt.internalNote());
        }
        return notes;
    }

    private Map<String, String> signedEventFileUrls(List<String> fileUuids, boolean seller, Principal principal) {
        Map<String, String> signedUrls = new LinkedHashMap<>();
        for (String fileUuid : fileUuids) {
            try {
                String url = seller ? fileService.signedOrderAttachmentUrl(fileUuid, principal) : fileService.customerSignedUrl(fileUuid, principal);
                signedUrls.put(fileUuid, url);
            } catch (ApiException ignored) {
                // A missing, unavailable, or out-of-scope attachment should not hide the order details.
            }
        }
        return signedUrls;
    }

    private Map<String, Object> base(OrderRepository.OrderRow r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.id());
        out.put("orderNo", r.orderNo());
        out.put("status", r.status());
        out.put("paymentStatus", r.paymentStatus());
        out.put("buyerNote", r.buyerNote());
        out.put("subtotal", r.subtotal());
        out.put("shippingFee", r.shippingFee());
        out.put("discountAmount", r.discountAmount());
        out.put("grossTotal", r.subtotal().add(r.shippingFee()));
        out.put("total", r.total());
        out.put("paidAmount", r.paidAmount());
        out.put("remainingAmount", "cancelled".equals(r.status()) ? BigDecimal.ZERO : r.total().subtract(r.paidAmount()));
        out.put("createdAt", r.createdAt());
        out.put("updatedAt", r.updatedAt());
        out.put("recipientName", r.recipientName());
        return out;
    }

    private Map<String, Object> addressView(AddressRepository.Address a) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", a.id());
        v.put("name", a.name());
        v.put("street", a.street());
        v.put("apt", a.apt());
        v.put("city", a.city());
        v.put("region", a.region());
        v.put("postalCode", a.postalCode());
        v.put("phone", a.phone());
        v.put("isDefault", a.isDefault());
        return v;
    }

    private String json(AddressRepository.Address a) {
        try {
            return mapper.writeValueAsString(addressView(a));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> parse(String json) {
        try {
            Map<String, Object> snapshot = mapper.readValue(json, new TypeReference<>() {
            });
            Map<String, Object> view = new LinkedHashMap<>();
            for (String field : List.of("id", "name", "street", "apt", "city", "region", "postalCode", "phone", "isDefault")) {
                if (snapshot.containsKey(field)) view.put(field, snapshot.get(field));
            }
            return view;
        } catch (Exception e) {
            return Map.of();
        }
    }

    public record QuoteRequest(long addressId, List<CartRequest> cartItems) {
    }

    public record CartRequest(long productId, int quantity) {
    }

    public record CreateRequest(String quoteToken, String buyerNote, List<CartRequest> cartItems, long addressId) {
    }

    public record CancelRequest(String reason, Instant expectedUpdatedAt) {
    }

    public record AuditDecision(String result, String remark, Instant expectedUpdatedAt, BigDecimal discountAmount) {
        public AuditDecision(String result, String remark, Instant expectedUpdatedAt) {
            this(result, remark, expectedUpdatedAt, null);
        }
    }

    public record PaymentRequest(long channelId, String transactionNo, BigDecimal expectedTotalAmount, Instant expectedUpdatedAt, BigDecimal declaredAmount) {
        public PaymentRequest(long channelId, String transactionNo, BigDecimal expectedTotalAmount) {
            this(channelId, transactionNo, expectedTotalAmount, null, null);
        }
        public PaymentRequest(long channelId, String transactionNo, BigDecimal expectedTotalAmount, Instant expectedUpdatedAt) {
            this(channelId, transactionNo, expectedTotalAmount, expectedUpdatedAt, null);
        }
    }

    public record CustomerUpdate(long addressId, String buyerNote, List<OrderEditItem> items, Instant expectedUpdatedAt) {
        public CustomerUpdate(long addressId, String buyerNote, List<OrderEditItem> items) {
            this(addressId, buyerNote, items, null);
        }
    }

    public record OrderEditItem(long productId, int quantity, BigDecimal unitPrice, Boolean manualPrice) {
        public OrderEditItem(long productId, int quantity, BigDecimal unitPrice) { this(productId, quantity, unitPrice, null); }
    }

    public record SellerUpdate(String addressSnapshot, String recipientName, String city, String buyerNote,
                               String internalNote, List<OrderEditItem> items, Instant expectedUpdatedAt) {
        public SellerUpdate(String addressSnapshot, String recipientName, String city, String buyerNote,
                            String internalNote, List<OrderEditItem> items) {
            this(addressSnapshot, recipientName, city, buyerNote, internalNote, items, null);
        }
    }

    public record SellerAction(BigDecimal expectedTotalAmount, String transactionNo, Long channelId,
                               String internalNote, String publicNote, String trackingNo, String carrier,
                               List<FileReference> files, boolean confirmed, Instant expectedUpdatedAt, BigDecimal receivedAmount, Long submissionId) {
        public SellerAction(BigDecimal expectedTotalAmount, String transactionNo, Long channelId,
                            String internalNote, String publicNote, String trackingNo, String carrier,
                            List<FileReference> files, boolean confirmed) {
            this(expectedTotalAmount, transactionNo, channelId, internalNote, publicNote, trackingNo, carrier, files, confirmed, null, null, null);
        }
        public SellerAction(BigDecimal expectedTotalAmount, String transactionNo, Long channelId, String internalNote,
                            String publicNote, String trackingNo, String carrier, List<FileReference> files,
                            boolean confirmed, Instant expectedUpdatedAt) {
            this(expectedTotalAmount, transactionNo, channelId, internalNote, publicNote, trackingNo, carrier, files, confirmed, expectedUpdatedAt, null, null);
        }
    }

    public record FileReference(String fileUuid, String uploadToken) {
    }
}

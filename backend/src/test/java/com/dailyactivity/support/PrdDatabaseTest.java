package com.dailyactivity.support;

import com.dailyactivity.DailyActivityApplication;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.cart.application.CartService;
import com.dailyactivity.order.application.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@SpringBootTest(classes = DailyActivityApplication.class)
@ActiveProfiles("test")
@Transactional
public abstract class PrdDatabaseTest {
    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected CartService carts;

    @Autowired
    protected OrderService orders;

    protected PrdTestFixture.Scenario data;

    @org.junit.jupiter.api.BeforeEach
    void seedPrdData() {
        data = PrdTestFixture.seed(jdbc);
    }

    protected Map<String, Object> createOrder(String fixtureLabel, int quantity) {
        Map<String, Object> order = createPendingOrder(fixtureLabel, quantity);
        return orders.decideAudit(data.seller(), orderId(order),
                new OrderService.AuditDecision("approved", "PRD test approval", null));
    }

    protected Map<String, Object> createPendingOrder(String fixtureLabel, int quantity) {
        carts.set(data.customer(), data.productId(), quantity);
        Map<String, Object> quote = orders.quote(
                data.customer(),
                new OrderService.QuoteRequest(data.addressId(), List.of(new OrderService.CartRequest(data.productId(), quantity))));
        return orders.create(
                data.customer(),
                new OrderService.CreateRequest(
                        (String) quote.get("quoteToken"),
                        "PRD test order",
                        List.of(new OrderService.CartRequest(data.productId(), quantity)),
                        data.addressId()));
    }

    // Existing lifecycle fixtures explicitly pay the complete expected amount.
    protected Map<String, Object> submitPayment(Principal principal, long id, OrderService.PaymentRequest request) {
        var current = orders.detail(principal, id);
        return orders.submitPayment(principal, id, new OrderService.PaymentRequest(request.channelId(), request.transactionNo(),
                request.expectedTotalAmount(), request.expectedUpdatedAt() == null ? (java.time.Instant) current.get("updatedAt") : request.expectedUpdatedAt(),
                request.declaredAmount() == null ? request.expectedTotalAmount() : request.declaredAmount()));
    }

    protected Map<String, Object> sellerAction(Principal principal, long id, String action, OrderService.SellerAction request) {
        if (!"confirm-payment".equals(action)) return orders.sellerAction(principal, id, action, request);
        var current = orders.detail(data.customer(), id);
        var pending = (Map<?, ?>) current.get("pendingPaymentSubmission");
        return orders.sellerAction(principal, id, action, new OrderService.SellerAction(request.expectedTotalAmount(), request.transactionNo(),
                request.channelId(), request.internalNote(), request.publicNote(), request.trackingNo(), request.carrier(), request.files(), request.confirmed(),
                request.expectedUpdatedAt() == null ? (java.time.Instant) current.get("updatedAt") : request.expectedUpdatedAt(),
                request.receivedAmount() == null ? request.expectedTotalAmount() : request.receivedAmount(),
                pending == null ? null : ((Number) pending.get("id")).longValue()));
    }

    protected long orderId(Map<String, Object> order) {
        return ((Number) order.get("id")).longValue();
    }

    protected Principal customer() {
        return data.customer();
    }

    protected Principal seller() {
        return data.seller();
    }
}

package com.dailyactivity.order;

import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.support.PrdDatabaseTest;
import com.dailyactivity.support.TestFileServiceConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PRD 5.1/S-05 独立履约状态机")
@Import(TestFileServiceConfiguration.class)
class PrdOrderLifecycleTest extends PrdDatabaseTest {
    @Autowired
    private JdbcTemplate database;

    @Test
    @DisplayName("订单列表统计覆盖全部订单，且客户与商家共享同一金额口径")
    void orderSummariesAggregateBeyondTheCurrentPage() {
        // Arrange
        Map<String, Object> customerBefore = orders.summary(customer());
        Map<String, Object> sellerBefore = orders.summary(seller());
        Map<String, Object> created = createOrder("summary-order-1", 1);
        BigDecimal total = (BigDecimal) created.get("total");

        // Act
        Map<String, Object> customerAfter = orders.summary(customer());
        Map<String, Object> sellerAfter = orders.summary(seller());

        // Assert
        assertThat(((Number) customerAfter.get("allOrders")).longValue())
                .isEqualTo(((Number) customerBefore.get("allOrders")).longValue() + 1);
        assertThat(((Number) customerAfter.get("pendingShip")).longValue())
                .isEqualTo(((Number) customerBefore.get("pendingShip")).longValue() + 1);
        assertThat((BigDecimal) customerAfter.get("payableTotal"))
                .isEqualByComparingTo(((BigDecimal) customerBefore.get("payableTotal")).add(total));
        assertThat(((Number) sellerAfter.get("allOrders")).longValue())
                .isEqualTo(((Number) sellerBefore.get("allOrders")).longValue() + 1);
        assertThat(((Number) sellerAfter.get("pendingPaymentOrders")).longValue())
                .isEqualTo(((Number) sellerBefore.get("pendingPaymentOrders")).longValue() + 1);
        assertThat(((Number) sellerAfter.get("notShippedOrders")).longValue())
                .isEqualTo(((Number) sellerBefore.get("notShippedOrders")).longValue() + 1);
        assertThat((BigDecimal) sellerAfter.get("pendingReceivableAmount"))
                .isEqualByComparingTo(((BigDecimal) sellerBefore.get("pendingReceivableAmount")).add(total));
    }

    @Test
    @DisplayName("待支付订单可独立完成打包、发货、收货和完成，支付状态不被自动修改")
    void unpaidOrderCanCompleteFulfillmentWithoutPayment() {
        // Arrange
        Map<String, Object> created = createOrder("fulfillment-order-1", 1);
        long id = orderId(created);
        OrderService.FileReference packedFile = new OrderService.FileReference("packed-file", "packed-token");
        OrderService.FileReference shippedFile = new OrderService.FileReference("shipped-file", "shipped-token");

        // Act
        Map<String, Object> packed = orders.sellerAction(seller(), id, "pack",
                action(null, null, null, List.of(packedFile), false));
        Map<String, Object> shipped = orders.sellerAction(seller(), id, "ship",
                action(null, null, "TRACK-001", List.of(shippedFile), false));
        Map<String, Object> received = orders.sellerAction(seller(), id, "receive",
                action(null, null, null, null, true));
        Map<String, Object> completed = orders.sellerAction(seller(), id, "complete",
                action(null, null, null, null, true));

        // Assert
        assertThat(packed).containsEntry("status", "packed").containsEntry("paymentStatus", "unpaid");
        assertThat(shipped).containsEntry("status", "shipped").containsEntry("paymentStatus", "unpaid");
        assertThat(firstParcel(shipped)).containsEntry("trackingNo", "TRACK-001").containsEntry("carrier", "Test Carrier");
        assertThat(received).containsEntry("status", "received");
        assertThat(firstParcel(received)).containsEntry("trackingNo", "TRACK-001");
        assertThat(completed).containsEntry("status", "completed").containsEntry("paymentStatus", "unpaid");
        assertThat(firstParcel(completed)).containsEntry("trackingNo", "TRACK-001");
        assertThat(database.queryForObject("SELECT status FROM billing_entry WHERE order_id=? AND bill_type='order_receivable'", String.class, id))
                .isEqualTo("unpaid");
        assertThat(database.queryForObject("SELECT status FROM billing_entry WHERE order_id=? AND bill_type='commission_payable'", String.class, id))
                .isEqualTo("payable");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstParcel(Map<String, Object> order) {
        return ((List<Map<String, Object>>) order.get("parcels")).get(0);
    }

    private OrderService.SellerAction action(BigDecimal expectedTotal, String transactionNo, String trackingNo,
                                             List<OrderService.FileReference> files, boolean confirmed) {
        return new OrderService.SellerAction(expectedTotal, transactionNo, null, null, null, trackingNo, "Test Carrier", files, confirmed);
    }
}

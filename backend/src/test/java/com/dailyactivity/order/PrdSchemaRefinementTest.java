package com.dailyactivity.order;

import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.commission.application.CommissionService;
import com.dailyactivity.commission.domain.port.CommissionRepository;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.identity.application.AddressService;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.payment.application.PaymentService;
import com.dailyactivity.support.PrdDatabaseTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrdSchemaRefinementTest extends PrdDatabaseTest {
    @Autowired private AddressService addresses;
    @Autowired private PaymentService payments;
    @Autowired private CommissionService commissions;
    @Autowired private ObjectMapper mapper;

    @Test
    void longAddressSurvivesCheckoutAndLaterAddressChanges() {
        String street = "街".repeat(199) + "\"";
        addresses.update(customer(), data.addressId(), new AddressService.AddressRequest(
                "N".repeat(100), street, "A".repeat(100), "C".repeat(100), "R".repeat(100),
                "12345-67890", "+12345678901", false));

        var order = createOrder("long-address", 1);
        String snapshot = jdbc.queryForObject("SELECT address_snapshot FROM sales_order WHERE id=?", String.class, orderId(order));
        jdbc.update("UPDATE customer_address SET street='Changed' WHERE id=?", data.addressId());

        assertThat(snapshot.length()).isGreaterThan(512);
        assertThat(((Map<?, ?>) orders.detail(customer(), orderId(order)).get("address")).get("street"))
                .isEqualTo(street);
    }

    @Test
    void longChannelSnapshotRetainsInstructionsAndEscapingThroughVerification() throws Exception {
        String qr = jdbc.queryForObject("SELECT qr_file_uuid FROM payment_channel WHERE id=?", String.class, data.channelId());
        String instructions = "Pay \"once\"\nKeep \\receipt " + "明".repeat(200);
        payments.update(seller(), data.channelId(), new PaymentService.PaymentRequest(
                "N".repeat(32), "USD", "T".repeat(32), "A".repeat(120), instructions, qr, true, 1));
        var order = createOrder("long-channel", 1);
        long id = orderId(order);

        submitPayment(customer(), id, new OrderService.PaymentRequest(data.channelId(), "long-channel-tx", new BigDecimal("110.00")));
        jdbc.update("UPDATE payment_channel SET instructions='Changed',receiving_address='Changed' WHERE id=?", data.channelId());
        sellerAction(seller(), id, "confirm-payment", new OrderService.SellerAction(
                new BigDecimal("110.00"), "long-channel-verified", data.channelId(), null, null, null, null, null, false));

        String submission = jdbc.queryForObject("SELECT channel_snapshot FROM payment_submission WHERE order_id=?", String.class, id);
        String receipt = jdbc.queryForObject("SELECT channel_snapshot FROM payment_receipt WHERE order_id=?", String.class, id);
        assertThat(submission.length()).isGreaterThan(256);
        assertThat(receipt).isEqualTo(submission);
        assertThat(mapper.readTree(receipt).get("instructions").asText()).isEqualTo(instructions);
        assertThat(mapper.readTree(receipt).get("receivingAddress").asText()).isEqualTo("A".repeat(120));
    }

    @Test
    void settlementDetailsAndCustomerHistoryUseTheSameBatchFacts() {
        long firstOrder = orderId(createOrder("batch-first", 1));
        long secondOrder = orderId(createOrder("batch-second", 1));
        long firstAccrual = jdbc.queryForObject("SELECT id FROM commission_accrual WHERE order_id=?", Long.class, firstOrder);
        long secondAccrual = jdbc.queryForObject("SELECT id FROM commission_accrual WHERE order_id=?", Long.class, secondOrder);
        commissions.settle(seller(), new CommissionService.SettlementRequest(List.of(
                new CommissionRepository.SettlementRequest(firstAccrual, new BigDecimal("4.50")),
                new CommissionRepository.SettlementRequest(secondAccrual, new BigDecimal("4.50"))), "Paid together", true));

        var first = commissions.settlementDetailByAccrual(seller(), firstAccrual);
        var second = commissions.settlementDetailByAccrual(seller(), secondAccrual);
        assertThat(first.get("settledAt")).isEqualTo(second.get("settledAt"));
        assertThat(first).containsEntry("remark", "Paid together").containsEntry("settledByUserId", data.sellerId());
        assertThat(jdbc.queryForObject("SELECT settled_at FROM billing_entry WHERE order_id=? AND bill_type='commission_payable'",
                java.sql.Timestamp.class, firstOrder).toInstant()).isEqualTo(first.get("settledAt"));
        var referrer = new Principal(data.referrerId(), "customer", 0, seller().merchantId());
        assertThat(commissions.customerSettlements(referrer, null, 1).items()).hasSize(2)
                .allSatisfy(item -> assertThat(item).containsEntry("settledAt", first.get("settledAt"))
                        .containsEntry("remark", "Paid together"));
    }

    @Test
    void overlongAddressAndChannelAreRejectedBeforeWriting() {
        assertThatThrownBy(() -> addresses.update(customer(), data.addressId(), new AddressService.AddressRequest(
                "N".repeat(101), "Street", null, "City", null, "12345", "+12345678901", false)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_ADDRESS");
        String qr = jdbc.queryForObject("SELECT qr_file_uuid FROM payment_channel WHERE id=?", String.class, data.channelId());
        assertThatThrownBy(() -> payments.update(seller(), data.channelId(), new PaymentService.PaymentRequest(
                "Channel", "USD", "TEST", "A".repeat(121), null, qr, true, 1)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_PAYMENT_CHANNEL");
    }
}

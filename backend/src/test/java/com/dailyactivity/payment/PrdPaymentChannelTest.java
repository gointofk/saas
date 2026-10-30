package com.dailyactivity.payment;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.payment.application.PaymentService;
import com.dailyactivity.support.PrdDatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PRD C-06 支付渠道管理")
class PrdPaymentChannelTest extends PrdDatabaseTest {
    @Autowired
    private PaymentService payments;

    @Test
    @DisplayName("客户只看到启用的支付渠道及完整收款信息")
    void customerSeesActiveChannelDetails() {
        Map<String, Object> channel = channels().stream()
                .filter(item -> item.get("id").equals(data.channelId()))
                .findFirst()
                .orElseThrow();

        assertThat(channel)
                .containsEntry("currencyCode", "USD")
                .containsEntry("network", "TESTNET")
                .containsEntry("receivingAddress", "test-receiving-address")
                .containsEntry("active", true)
                .containsKey("qrFileUuid");
    }

    @Test
    @DisplayName("支付渠道必须引用已上传的二维码文件")
    void paymentChannelRequiresQrFile() {
        assertThatThrownBy(() -> payments.create(seller(), new PaymentService.PaymentRequest(
                "Invalid QR " + data.channelId(), "USD", "TESTNET", "address", null,
                "missing-file-" + data.channelId(), true, 2)))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_PAYMENT_CHANNEL_QR");
    }

    @Test
    @DisplayName("支付渠道新增后可更新并按启用状态对客户隐藏")
    void paymentChannelCanBeUpdatedAndRemovedFromCustomerView() {
        String qr = jdbc.queryForObject("SELECT qr_file_uuid FROM payment_channel WHERE id=?", String.class, data.channelId());
        long id = ((Number) payments.create(seller(), new PaymentService.PaymentRequest(
                "Temporary Channel " + data.channelId(), "USD", "TESTNET", "temporary-address", "Temporary", qr, true, 9)).get("id")).longValue();

        payments.update(seller(), id, new PaymentService.PaymentRequest(
                "Temporary Channel Updated " + id, "EUR", "MAINNET", "updated-address", "Updated", qr, true, 8));
        assertThat(channels()).anySatisfy(item -> assertThat(item)
                .containsEntry("id", id)
                .containsEntry("currencyCode", "EUR")
                .containsEntry("network", "MAINNET"));

        payments.remove(seller(), id);
        assertThat(channels()).noneMatch(item -> item.get("id").equals(id));
    }

    @Test
    @DisplayName("停用后的支付渠道不会出现在客户支付选项")
    void disabledPaymentChannelIsHidden() {
        String qr = jdbc.queryForObject("SELECT qr_file_uuid FROM payment_channel WHERE id=?", String.class, data.channelId());
        payments.update(seller(), data.channelId(), new PaymentService.PaymentRequest(
                "Existing Channel " + data.channelId(), "USD", "TESTNET", "test-receiving-address", "Test", qr, false, 1));

        assertThat(channels()).noneMatch(item -> item.get("id").equals(data.channelId()));
    }

    @Test
    @DisplayName("付款方式开关变化后客户选项随之更新")
    void customerPaymentOptionsReflectMerchantSettings() {
        jdbc.update("UPDATE merchant SET crypto_payment_enabled=0,online_payment_link_enabled=1 WHERE id=?", data.customer().merchantId());

        Map<String, Object> options = payments.customerPaymentOptions(data.customer());
        assertThat(options).containsEntry("cryptoEnabled", false).containsEntry("onlinePaymentLinkEnabled", true);
        assertThat((List<?>) options.get("channels")).isEmpty();

        jdbc.update("UPDATE merchant SET crypto_payment_enabled=1 WHERE id=?", data.customer().merchantId());
        options = payments.customerPaymentOptions(data.customer());
        assertThat(options).containsEntry("cryptoEnabled", true).containsEntry("onlinePaymentLinkEnabled", true);
        assertThat((List<?>) options.get("channels")).isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> channels() {
        return (List<Map<String, Object>>) payments.customerPaymentOptions(data.customer()).get("channels");
    }
}

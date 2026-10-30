package com.dailyactivity.payment.application;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.payment.domain.port.PaymentRepository;
import com.dailyactivity.file.domain.port.FileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.math.BigDecimal;

@Service
public class PaymentService {
    private final PaymentRepository payments;
    private final AccountService accounts;
    private final FileRepository files;

    public PaymentService(PaymentRepository payments, AccountService accounts, FileRepository files) {
        this.payments = payments;
        this.accounts = accounts;
        this.files = files;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> customerPaymentOptions(Principal p) {
        accounts.customer(p);
        var methods = payments.paymentMethods(p.merchantId());
        var channels = methods.cryptoEnabled()
                ? payments.find(p.merchantId(), true).stream().map(this::view).toList()
                : java.util.List.<Map<String, Object>>of();
        return Map.of("cryptoEnabled", methods.cryptoEnabled(),
                "onlinePaymentLinkEnabled", methods.onlinePaymentLinkEnabled(),
                "channels", channels);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> adminPaymentMethods(Principal p) {
        accounts.requireMenu(p, "payment_channels");
        return methodView(payments.paymentMethods(p.merchantId()));
    }

    @Transactional
    public Map<String, Object> updatePaymentMethods(Principal p, PaymentMethodsRequest request) {
        accounts.requireMenu(p, "payment_channels");
        throw ApiException.forbidden("PAYMENT_METHODS_PLATFORM_MANAGED", "Payment method availability is managed in platform merchant settings.");
    }

    private Map<String, Object> methodView(PaymentRepository.PaymentMethods methods) {
        return Map.of("cryptoEnabled", methods.cryptoEnabled(),
                "onlinePaymentLinkEnabled", methods.onlinePaymentLinkEnabled());
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> adminChannels(Principal p, Integer page) {
        accounts.seller(p);
        int current = page == null ? 1 : page;
        if (current < 1) throw ApiException.badRequest("INVALID_PAGE", "page starts at 1.");
        return PageResponse.of(payments.page(p.merchantId(), false, current).stream().map(this::adminView).toList(), current, payments.count(p.merchantId(), false));
    }

    @Transactional
    public Map<String, Object> create(Principal p, PaymentRequest r) {
        accounts.requireMenu(p, "payment_channels");
        validate(p, r);
        return Map.of("id", payments.create(p.merchantId(), r.input(), p.userId()));
    }

    @Transactional
    public void update(Principal p, long id, PaymentRequest r) {
        accounts.requireMenu(p, "payment_channels");
        validate(p, r);
        payments.update(p.merchantId(), id, r.input(), p.userId());
    }

    @Transactional
    public void remove(Principal p, long id) {
        accounts.requireMenu(p, "payment_channels");
        payments.softDelete(p.merchantId(), id, p.userId());
    }

    private Map<String, Object> view(PaymentRepository.Channel c) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", c.id());
        v.put("name", c.name());
        v.put("currencyCode", c.currencyCode());
        v.put("network", c.network());
        v.put("receivingAddress", c.receivingAddress());
        v.put("instructions", c.instructions());
        v.put("additionalRate", c.additionalRate());
        v.put("qrFileUuid", c.qrFileUuid());
        v.put("active", c.active());
        v.put("sortNo", c.sortNo());
        return v;
    }

    private Map<String, Object> adminView(PaymentRepository.Channel c) {
        Map<String, Object> v = view(c);
        v.put("updatedAt", c.updatedAt());
        return v;
    }

    private void validate(Principal p, PaymentRequest r) {
        if (r.name() == null || r.name().isBlank() || r.currencyCode() == null || r.currencyCode().isBlank() || r.network() == null || r.network().isBlank() || r.receivingAddress() == null || r.receivingAddress().isBlank() || r.qrFileUuid() == null || r.qrFileUuid().isBlank() || r.sortNo() < 1)
            throw ApiException.badRequest("INVALID_PAYMENT_CHANNEL", "Payment channel fields are invalid.");
        if (r.name().trim().length() > 32 || r.currencyCode().trim().toUpperCase(java.util.Locale.ROOT).length() > 32
                || r.network().trim().length() > 32 || r.receivingAddress().trim().length() > 120
                || (r.instructions() != null && r.instructions().length() > 240))
            throw ApiException.badRequest("INVALID_PAYMENT_CHANNEL", "Payment channel fields exceed their maximum length.");
        if (r.additionalRate() != null && (r.additionalRate().signum() < 0 || r.additionalRate().compareTo(new BigDecimal("100.00")) > 0 || r.additionalRate().stripTrailingZeros().scale() > 2))
            throw ApiException.badRequest("INVALID_PAYMENT_CHANNEL", "Additional rate must be between 0 and 100 with at most two decimal places.");
        if (!files.belongsToMerchant(r.qrFileUuid().trim(), p.merchantId()) || !files.availableForPurpose(r.qrFileUuid().trim(), "payment_channel_qr"))
            throw ApiException.badRequest("INVALID_PAYMENT_CHANNEL_QR", "A valid payment-channel QR file is required.");
    }

    public record PaymentRequest(String name, String currencyCode, String network, String receivingAddress,
                                 String instructions, BigDecimal additionalRate, String qrFileUuid, boolean active, int sortNo) {
        public PaymentRequest(String name, String currencyCode, String network, String receivingAddress,
                              String instructions, String qrFileUuid, boolean active, int sortNo) {
            this(name, currencyCode, network, receivingAddress, instructions, null, qrFileUuid, active, sortNo);
        }

        PaymentRepository.ChannelInput input() {
            return new PaymentRepository.ChannelInput(name.trim(), currencyCode.trim().toUpperCase(java.util.Locale.ROOT), network.trim(), receivingAddress.trim(), instructions, additionalRate == null ? null : additionalRate.setScale(2), qrFileUuid.trim(), active, sortNo);
        }
    }

    public record PaymentMethodsRequest(Boolean cryptoEnabled, Boolean onlinePaymentLinkEnabled) {}
}

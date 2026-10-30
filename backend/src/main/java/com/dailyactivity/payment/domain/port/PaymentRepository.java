package com.dailyactivity.payment.domain.port;

import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
import java.time.Instant;

public interface PaymentRepository {
    PaymentMethods paymentMethods(long merchantId);

    List<Channel> find(long merchantId, boolean activeOnly);

    List<Channel> page(long merchantId, boolean activeOnly, int page);

    long count(long merchantId, boolean activeOnly);

    Optional<Channel> channel(long merchantId, long id, boolean activeOnly);

    long create(long merchantId, ChannelInput input, long actorId);

    void update(long merchantId, long id, ChannelInput input, long actorId);

    void softDelete(long merchantId, long id, long actorId);

    record PaymentMethods(boolean cryptoEnabled, boolean onlinePaymentLinkEnabled) {}

    record Channel(long id, String name, String currencyCode, String network, String receivingAddress,
                   String instructions, BigDecimal additionalRate, String qrFileUuid, boolean active, int sortNo, Instant updatedAt) {
        public Channel(long id, String name, String currencyCode, String network, String receivingAddress,
                       String instructions, BigDecimal additionalRate, String qrFileUuid, boolean active, int sortNo) {
            this(id, name, currencyCode, network, receivingAddress, instructions, additionalRate, qrFileUuid, active, sortNo, null);
        }
    }

    record ChannelInput(String name, String currencyCode, String network, String receivingAddress, String instructions,
                        BigDecimal additionalRate, String qrFileUuid, boolean active, int sortNo) {
    }
}

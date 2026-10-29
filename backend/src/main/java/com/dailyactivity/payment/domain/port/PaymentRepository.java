package com.dailyactivity.payment.domain.port;

import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;

public interface PaymentRepository {
    List<Channel> find(long merchantId, boolean activeOnly);

    List<Channel> page(long merchantId, boolean activeOnly, int page);

    long count(long merchantId, boolean activeOnly);

    Optional<Channel> channel(long merchantId, long id, boolean activeOnly);

    long create(long merchantId, ChannelInput input, long actorId);

    void update(long merchantId, long id, ChannelInput input, long actorId);

    void softDelete(long merchantId, long id, long actorId);

    record Channel(long id, String name, String currencyCode, String network, String receivingAddress,
                   String instructions, BigDecimal additionalRate, String qrFileUuid, boolean active, int sortNo) {
        public String snapshot() {
            return "{\"id\":" + id + ",\"name\":\"" + escape(name) + "\",\"currencyCode\":\"" + escape(currencyCode) + "\",\"network\":\"" + escape(network) + "\",\"receivingAddress\":\"" + escape(receivingAddress) + "\",\"additionalRate\":" + (additionalRate == null ? "null" : additionalRate.toPlainString()) + ",\"qrFileUuid\":\"" + escape(qrFileUuid) + "\"}";
        }

        private static String escape(String value) {
            return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    record ChannelInput(String name, String currencyCode, String network, String receivingAddress, String instructions,
                        BigDecimal additionalRate, String qrFileUuid, boolean active, int sortNo) {
    }
}

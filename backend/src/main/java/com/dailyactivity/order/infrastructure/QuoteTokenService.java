package com.dailyactivity.order.infrastructure;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.config.QuoteProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@Service
public class QuoteTokenService {
    private final QuoteProperties properties;
    private final ObjectMapper mapper;

    public QuoteTokenService(QuoteProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public String issue(QuotePayload payload) {
        try {
            String body = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(payload));
            return body + "." + sign(body);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create quote token", e);
        }
    }

    public QuotePayload parse(String token, long customerId) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length != 2 || !constantTime(sign(parts[0]), parts[1]))
                throw ApiException.badRequest("INVALID_QUOTE", "Quote token is invalid.");
            QuotePayload p = mapper.readValue(Base64.getUrlDecoder().decode(parts[0]), QuotePayload.class);
            if (p.version() != 3 || p.nonce() == null || p.nonce().isBlank())
                throw ApiException.badRequest("INVALID_QUOTE", "Quote token is invalid.");
            if (p.customerId() != customerId || p.expiresAt() < Instant.now().getEpochSecond())
                throw ApiException.conflict("QUOTE_EXPIRED", "The quote has expired; request a new quote.");
            return p;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_QUOTE", "Quote token is invalid.");
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean constantTime(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    public record QuotePayload(long customerId, long addressId, long createdAt, long expiresAt,
                               java.util.List<QuoteLine> lines, java.math.BigDecimal subtotal,
                               java.math.BigDecimal shippingFee, java.math.BigDecimal total, int version,
                               String nonce) {
    }

    public record QuoteLine(long productId, int quantity, String sku, String name, String productType,
                            java.math.BigDecimal unitPrice, long priceRevision) {
    }
}

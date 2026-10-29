package com.dailyactivity.identity.application;

import com.dailyactivity.identity.domain.port.CustomerRegistrationRepository;
import com.dailyactivity.identity.domain.port.CustomerRepository;
import com.dailyactivity.config.RegistrationProperties;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.merchant.domain.port.MerchantSecretProtector;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;

@Service
public class CustomerRegistrationTransaction {
    private final CustomerRegistrationRepository challenges;
    private final CustomerRepository customers;
    private final RegistrationProperties properties;
    private final MerchantSecretProtector secretCipher;

    public CustomerRegistrationTransaction(CustomerRegistrationRepository challenges, CustomerRepository customers,
                                           RegistrationProperties properties, MerchantSecretProtector secretCipher) {
        this.challenges = challenges;
        this.customers = customers;
        this.properties = properties;
        this.secretCipher = secretCipher;
    }

    @Transactional
    public Result register(long merchantId, String email, String verificationCode, String name, String password) {
        Instant now = Instant.now();
        var challenge = challenges.lock(merchantId, email).orElse(null);
        if (challenge == null || challenge.consumedAt() != null || !challenge.expiresAt().isAfter(now)) return Result.INVALID_CODE;
        if (challenge.attempts() >= properties.maxCodeAttempts()) return Result.TOO_MANY_ATTEMPTS;
        String expectedHash = codeHash(merchantId, email, verificationCode);
        if (!MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.US_ASCII), challenge.codeHash().getBytes(StandardCharsets.US_ASCII))) {
            challenges.recordFailedAttempt(merchantId, email, properties.maxCodeAttempts());
            return Result.INVALID_CODE;
        }
        if (customers.emailExists(merchantId, email)) {
            challenges.consume(merchantId, email);
            return Result.EMAIL_EXISTS;
        }
        String passwordHash = com.dailyactivity.auth.infrastructure.PasswordHasher.hash(password);
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                customers.createSelfRegistered(merchantId, email, name, passwordHash, CustomerCodeGenerator.next(), now);
                challenges.consume(merchantId, email);
                return Result.CREATED;
            } catch (ApiException e) {
                if (!"CUSTOMER_IDENTIFIER_EXISTS".equals(e.code())) throw e;
                if (customers.emailExists(merchantId, email)) {
                    challenges.consume(merchantId, email);
                    return Result.EMAIL_EXISTS;
                }
            }
        }
        throw ApiException.conflict("CUSTOMER_CODE_UNAVAILABLE", "Unable to allocate a customer code. Try again later.");
    }

    private String codeHash(long merchantId, String email, String code) {
        return secretCipher.hmacHex("customer-registration-code:v1", merchantId + "\n" + email.toLowerCase(Locale.ROOT) + "\n" + code);
    }

    public enum Result { CREATED, INVALID_CODE, TOO_MANY_ATTEMPTS, EMAIL_EXISTS }
}

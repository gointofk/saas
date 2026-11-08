package com.dailyactivity.identity.application;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.config.RegistrationProperties;
import com.dailyactivity.identity.domain.port.CustomerRegistrationRepository;
import com.dailyactivity.merchant.domain.port.MerchantEmailSettingsRepository;
import com.dailyactivity.merchant.domain.port.MerchantRepository;
import com.dailyactivity.merchant.domain.port.MerchantSecretProtector;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

@Service
public class CustomerRegistrationService {
    private final MerchantRepository merchants;
    private final MerchantEmailSettingsRepository emailSettings;
    private final CustomerRegistrationRepository challenges;
    private final CustomerRegistrationTransaction registrationTransaction;
    private final RegistrationEmailDispatcher registrationEmailDispatcher;
    private final MerchantSecretProtector secretCipher;
    private final RegistrationProperties properties;
    private final RegistrationRateLimiter rateLimiter;
    private static final SecureRandom RANDOM = new SecureRandom();

    public CustomerRegistrationService(MerchantRepository merchants,
                                       MerchantEmailSettingsRepository emailSettings,
                                       CustomerRegistrationRepository challenges,
                                       CustomerRegistrationTransaction registrationTransaction,
                                       RegistrationEmailDispatcher registrationEmailDispatcher, MerchantSecretProtector secretCipher,
                                       RegistrationProperties properties, RegistrationRateLimiter rateLimiter) {
        this.merchants = merchants;
        this.emailSettings = emailSettings;
        this.challenges = challenges;
        this.registrationTransaction = registrationTransaction;
        this.registrationEmailDispatcher = registrationEmailDispatcher;
        this.secretCipher = secretCipher;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    public void requestCode(long merchantId, String emailInput, String clientIp) {
        MerchantRepository.Merchant merchant = merchant(merchantId);
        requireRegistrationOpen(merchant);
        String email = normalizeEmail(emailInput);
        secretCipher.hmacHex("customer-registration-code:v1", "configuration-check");
        rateLimiter.checkSend(merchantId, email, clientIp);
        var stored = emailSettings.find(merchantId).filter(MerchantEmailSettingsRepository.StoredSettings::configured)
                .orElseThrow(() -> ApiException.conflict("MERCHANT_EMAIL_REQUIRED", "Customer registration email is not configured."));
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        String hash = secretCipher.hmacHex("customer-registration-code:v1", merchantId + "\n" + email + "\n" + code);
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.codeTtlSeconds());
        Instant resendAfter = now.plusSeconds(properties.resendDelaySeconds());
        if (!challenges.issue(merchantId, email, hash, now, expiresAt, resendAfter)) return;
        registrationEmailDispatcher.dispatch(merchantId, email, stored, "Verify your email address",
                "Your " + merchant.name() + " verification code is " + code + ". It expires in "
                        + Math.max(1, properties.codeTtlSeconds() / 60) + " minutes. If you did not request this, ignore this email.");
    }

    public void register(long merchantId, String emailInput, String nameInput, String password, String confirmation,
                         String verificationCode, String inviteCode, String clientIp) {
        requireRegistrationOpen(merchant(merchantId));
        String email = normalizeEmail(emailInput);
        String name = nameInput == null ? "" : nameInput.trim();
        if (name.isBlank() || name.length() > 100)
            throw ApiException.badRequest("INVALID_CUSTOMER_NAME", "Name is required and must be no longer than 100 characters.");
        if (password == null || password.length() < 12 || password.length() > 64 || password.isBlank())
            throw ApiException.badRequest("INVALID_PASSWORD", "Password must be 12–64 characters.");
        if (!password.equals(confirmation))
            throw ApiException.badRequest("PASSWORD_CONFIRMATION_MISMATCH", "Password confirmation does not match.");
        if (verificationCode == null || !verificationCode.matches("[0-9]{6}"))
            throw ApiException.badRequest("REGISTRATION_CODE_INVALID", "The verification code is invalid or expired.");
        rateLimiter.checkVerification(merchantId, email, clientIp);
        CustomerRegistrationTransaction.Result result = registrationTransaction.register(merchantId, email, verificationCode, name, password, inviteCode);
        switch (result) {
            case CREATED -> { return; }
            case EMAIL_EXISTS -> throw ApiException.conflict("CUSTOMER_EMAIL_EXISTS", "An account already uses this email address.");
            case TOO_MANY_ATTEMPTS -> throw ApiException.tooManyRequests((int) properties.codeTtlSeconds(),
                    "Too many registration attempts. Please try again later.");
            case INVALID_CODE -> throw ApiException.badRequest("REGISTRATION_CODE_INVALID", "The verification code is invalid or expired.");
        }
    }

    private MerchantRepository.Merchant merchant(long merchantId) {
        return merchants.find(merchantId).orElseThrow(() -> ApiException.notFound("Storefront not found."));
    }

    private void requireRegistrationOpen(MerchantRepository.Merchant merchant) {
        if (!merchant.active()) throw ApiException.forbidden("This storefront is unavailable.");
        if (!merchant.registrationEnabled()) throw ApiException.forbidden("Customer registration is not available for this storefront.");
    }

    private String normalizeEmail(String input) {
        String email = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 128 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw ApiException.badRequest("INVALID_EMAIL", "Email must be valid and no longer than 128 characters.");
        return email;
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanExpiredChallenges() {
        Instant cutoff = Instant.now().minusSeconds(86_400);
        for (int i = 0; i < 20; i++) {
            if (challenges.deleteExpired(cutoff, 1_000) < 1) return;
        }
    }
}

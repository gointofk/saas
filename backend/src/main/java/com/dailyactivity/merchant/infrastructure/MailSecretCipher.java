package com.dailyactivity.merchant.infrastructure;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.config.RegistrationProperties;
import com.dailyactivity.merchant.domain.port.MerchantSecretProtector;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class MailSecretCipher implements MerchantSecretProtector {
    private static final String PREFIX = "v1:";
    private final RegistrationProperties properties;
    private final SecureRandom random = new SecureRandom();

    public MailSecretCipher(RegistrationProperties properties) {
        this.properties = properties;
    }

    @Override
    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to protect merchant mail credentials.", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        try {
            if (ciphertext == null || !ciphertext.startsWith(PREFIX)) throw new IllegalArgumentException();
            byte[] payload = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            if (payload.length < 29) throw new IllegalArgumentException();
            byte[] nonce = Arrays.copyOfRange(payload, 0, 12);
            byte[] encrypted = Arrays.copyOfRange(payload, 12, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.conflict("MAIL_CREDENTIAL_UNAVAILABLE", "Merchant email credentials cannot be read. Check the configured registration secret.");
        }
    }

    @Override
    public String hmacHex(String purpose, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(derivedKey("dailyactivity:" + purpose), "HmacSHA256"));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to protect registration verification data.", e);
        }
    }

    private SecretKeySpec key() throws Exception {
        return new SecretKeySpec(derivedKey("dailyactivity:merchant-mail-password:v1"), "AES");
    }

    private byte[] derivedKey(String purpose) throws Exception {
        String secret = properties.secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw ApiException.conflict("REGISTRATION_SECRET_NOT_CONFIGURED", "Registration secret is not configured.");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(purpose.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        return digest.digest(secret.getBytes(StandardCharsets.UTF_8));
    }
}

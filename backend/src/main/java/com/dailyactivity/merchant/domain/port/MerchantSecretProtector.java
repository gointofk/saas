package com.dailyactivity.merchant.domain.port;

public interface MerchantSecretProtector {
    String encrypt(String plaintext);

    String decrypt(String ciphertext);

    String hmacHex(String purpose, String value);
}

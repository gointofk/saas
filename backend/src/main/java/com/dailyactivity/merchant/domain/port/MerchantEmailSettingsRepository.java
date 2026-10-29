package com.dailyactivity.merchant.domain.port;

import java.util.Optional;

public interface MerchantEmailSettingsRepository {
    Optional<StoredSettings> find(long merchantId);

    void save(long merchantId, Settings settings, String encryptedPassword);

    record StoredSettings(String smtpHost, int smtpPort, String smtpUsername, String encryptedPassword,
                          String tlsMode, String senderEmail, String senderName) {
        public boolean configured() {
            return smtpHost != null && !smtpHost.isBlank() && smtpPort > 0
                    && smtpUsername != null && !smtpUsername.isBlank()
                    && encryptedPassword != null && !encryptedPassword.isBlank()
                    && tlsMode != null && !tlsMode.isBlank()
                    && senderEmail != null && !senderEmail.isBlank();
        }
    }

    record Settings(String smtpHost, int smtpPort, String smtpUsername, String tlsMode,
                    String senderEmail, String senderName) {
    }
}

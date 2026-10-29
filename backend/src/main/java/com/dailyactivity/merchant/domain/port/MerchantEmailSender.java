package com.dailyactivity.merchant.domain.port;

public interface MerchantEmailSender {
    void send(SmtpConfiguration configuration, String recipient, String subject, String text);

    record SmtpConfiguration(String smtpHost, int smtpPort, String smtpUsername, String smtpPassword,
                             String tlsMode, String senderEmail, String senderName) {
    }
}

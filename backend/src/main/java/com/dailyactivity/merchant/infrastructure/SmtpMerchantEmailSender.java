package com.dailyactivity.merchant.infrastructure;

import com.dailyactivity.merchant.domain.port.MerchantEmailSender;
import jakarta.mail.internet.InternetAddress;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.Properties;

@Component
public class SmtpMerchantEmailSender implements MerchantEmailSender {
    @Override
    public void send(SmtpConfiguration configuration, String recipient, String subject, String text) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(configuration.smtpHost());
        sender.setPort(configuration.smtpPort());
        sender.setUsername(configuration.smtpUsername());
        sender.setPassword(configuration.smtpPassword());
        sender.setDefaultEncoding("UTF-8");
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.transport.protocol", "smtp");
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.connectiontimeout", "5000");
        properties.put("mail.smtp.timeout", "5000");
        properties.put("mail.smtp.writetimeout", "5000");
        if ("ssl".equals(configuration.tlsMode())) {
            properties.put("mail.smtp.ssl.enable", "true");
        } else {
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
        }
        try {
            var message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(new InternetAddress(configuration.senderEmail(), configuration.senderName(), "UTF-8"));
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(text, false);
            sender.send(message);
        } catch (Exception e) {
            throw new IllegalStateException("Merchant email could not be sent.", e);
        }
    }
}

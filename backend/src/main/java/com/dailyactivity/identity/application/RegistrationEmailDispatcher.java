package com.dailyactivity.identity.application;

import com.dailyactivity.identity.domain.port.CustomerRepository;
import com.dailyactivity.merchant.domain.port.MerchantEmailSender;
import com.dailyactivity.merchant.domain.port.MerchantEmailSettingsRepository;
import com.dailyactivity.merchant.domain.port.MerchantSecretProtector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class RegistrationEmailDispatcher {
    private static final Logger log = LoggerFactory.getLogger(RegistrationEmailDispatcher.class);

    private final ThreadPoolTaskExecutor executor;
    private final CustomerRepository customers;
    private final MerchantEmailSender emailSender;
    private final MerchantSecretProtector secretProtector;

    public RegistrationEmailDispatcher(@Qualifier("registrationEmailExecutor") ThreadPoolTaskExecutor registrationEmailExecutor,
                                       CustomerRepository customers, MerchantEmailSender emailSender,
                                       MerchantSecretProtector secretProtector) {
        this.executor = registrationEmailExecutor;
        this.customers = customers;
        this.emailSender = emailSender;
        this.secretProtector = secretProtector;
    }

    public void dispatch(long merchantId, String email, MerchantEmailSettingsRepository.StoredSettings settings,
                         String subject, String text) {
        try {
            executor.execute(() -> {
                try {
                    if (customers.emailExists(merchantId, email)) return;
                    String password = secretProtector.decrypt(settings.encryptedPassword());
                    MerchantEmailSender.SmtpConfiguration smtp = new MerchantEmailSender.SmtpConfiguration(
                            settings.smtpHost(), settings.smtpPort(), settings.smtpUsername(), password,
                            settings.tlsMode(), settings.senderEmail(), settings.senderName());
                    emailSender.send(smtp, email, subject, text);
                } catch (RuntimeException e) {
                    log.warn("Registration email delivery did not complete merchantId={} failureType={}",
                            merchantId, e.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Registration email task was not accepted merchantId={} failureType={}",
                    merchantId, e.getClass().getSimpleName());
        }
    }
}

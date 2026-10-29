package com.dailyactivity.support;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.config.FileProperties;
import com.dailyactivity.file.application.FileService;
import com.dailyactivity.file.domain.port.FileRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Keeps fulfillment state-transition tests focused on the order state machine.
 * File upload and signature validation have their own production service; these
 * tests only need a valid reference at each fulfillment stage.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestFileServiceConfiguration {
    @Bean
    @Primary
    FileService testFileService(FileRepository files, AccountService accounts, FileProperties properties) {
        return new FileService(files, accounts, properties) {
            @Override
            public void validateParcelUploadToken(String uuid, String purpose, long actor, Long orderId,
                                                   String stage, String parcelUuid, String token) {
                // Parcel state tests use synthetic references; production signatures are tested separately.
            }

            @Override
            public void validateUploadToken(String uuid, String purpose, long actor, Long orderId,
                                            String stage, String token) {
                // The transition tests model already-uploaded, valid file references.
            }
        };
    }
}

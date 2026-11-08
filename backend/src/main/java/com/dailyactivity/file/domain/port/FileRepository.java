package com.dailyactivity.file.domain.port;

import java.time.Instant;
import java.util.Optional;

public interface FileRepository {
    void insert(FileAsset asset, long actorId);

    Optional<FileAsset> find(String uuid);

    Optional<FileAsset> findPublicCoa(String uuid);

    boolean belongsToMerchant(String uuid, long merchantId);

    boolean orderBelongsToMerchant(long orderId, long merchantId);

    boolean availableForPurpose(String uuid, String purpose);

    boolean customerCanAccessFile(String uuid, long customerId);

    record FileAsset(String uuid, String storedName, String originalName, String purpose, String mediaType,
                     long sizeBytes, String sha256, String status, Instant uploadedAt) {
    }
}

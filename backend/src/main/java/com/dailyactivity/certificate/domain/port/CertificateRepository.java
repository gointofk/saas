package com.dailyactivity.certificate.domain.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CertificateRepository {
    List<Certificate> adminPage(long merchantId, String query, int page);

    List<Certificate> allCustomer(long merchantId);

    long adminCount(long merchantId, String query);

    Optional<Certificate> find(long merchantId, long id);

    long create(long merchantId, Input input, long actorId);

    void update(long merchantId, long id, Input input, long actorId, java.time.Instant expectedUpdatedAt);

    void softDelete(long merchantId, long id, long actorId);

    void bindProduct(long merchantId, long coaId, Long productId, long actorId);

    List<Certificate> productCertificates(long merchantId, long productId);

    void replaceProductCertificates(long merchantId, long productId, List<Long> coaIds, long actorId);

    List<ProductBinding> productOptions(long merchantId, Long coaId, String query);

    Optional<ProductDetail> productDetail(long merchantId, long coaId);

    boolean fileAvailable(long merchantId, String fileUuid);

    record Certificate(long id, String name, String lot, String purity, String fill, LocalDate testDate,
                       String fileUuid, Long productId, String productName, String sku, String fileName, java.time.Instant updatedAt) {
    }

    record ProductBinding(long id, String sku, String name) {
    }

    record ProductDetail(long id, String sku, String name, String category, String description,
                         String productType, boolean active, int stock, java.math.BigDecimal basePrice) {
    }

    record Input(String name, String lot, String purity, String fill, LocalDate testDate, String fileUuid) {
    }
}

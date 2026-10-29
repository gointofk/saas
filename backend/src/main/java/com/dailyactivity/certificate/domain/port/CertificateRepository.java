package com.dailyactivity.certificate.domain.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CertificateRepository {
    List<Certificate> adminPage(String query, int page);

    List<Certificate> allCustomer();

    long adminCount(String query);

    Optional<Certificate> find(long id);

    long create(Input input, long actorId);

    void update(long id, Input input, long actorId, java.time.Instant expectedUpdatedAt);

    void softDelete(long id, long actorId);

    void bindProduct(long coaId, Long productId, long actorId);

    List<Certificate> productCertificates(long productId);

    void replaceProductCertificates(long productId, List<Long> coaIds, long actorId);

    List<ProductBinding> productOptions(long coaId);

    List<ProductBinding> productOptions(Long coaId, String query);

    Optional<ProductDetail> productDetail(long coaId);

    boolean fileAvailable(String fileUuid);

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

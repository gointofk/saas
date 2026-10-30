package com.dailyactivity.certificate;

import com.dailyactivity.certificate.application.CertificateService;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.support.PrdDatabaseTest;
import com.dailyactivity.support.PrdTestFixture;
import com.dailyactivity.support.TestFileServiceConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestFileServiceConfiguration.class)
@DisplayName("PRD C-05 证书/COA 生命周期")
class PrdCertificateTest extends PrdDatabaseTest {
    @Autowired
    private CertificateService certificates;

    @Test
    @DisplayName("证书必须使用用途为 coa 且可用的文件")
    void certificateRequiresAvailableCoaFile() {
        assertThatThrownBy(() -> certificates.create(seller(), new CertificateService.CertificateRequest(
                "Missing COA", "LOT-MISSING", "99.9", "10g", LocalDate.now(),
                UUID.randomUUID().toString(), "test-token")))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("FILE_NOT_AVAILABLE");
    }

    @Test
    @DisplayName("证书可创建、绑定商品并被客户查询")
    void certificateCanBeCreatedBoundAndViewedByCustomer() {
        String fileUuid = insertCoaFile();
        long id = ((Number) certificates.create(seller(), new CertificateService.CertificateRequest(
                "COA " + data.productSku(), "LOT-" + data.productId(), "99.9", "10g", LocalDate.of(2026, 9, 1),
                fileUuid, "test-token")).get("id")).longValue();

        certificates.bind(seller(), id, data.productId());

        assertThat(certificates.customer(data.customer(), data.productSku(), 1).items())
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("id", id)
                .containsEntry("productId", data.productId())
                .containsEntry("fileUuid", fileUuid)
                .containsEntry("lot", "LOT-" + data.productId());
    }

    @Test
    @DisplayName("同一商品可以同时关联多份证书")
    void productCanHaveMultipleCertificates() {
        String firstFile = insertCoaFile();
        String secondFile = insertCoaFile();
        long first = createCertificate("First", firstFile);
        long second = createCertificate("Second", secondFile);

        certificates.bind(seller(), first, data.productId());
        certificates.bind(seller(), second, data.productId());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_coa_link WHERE product_id=? AND deleted=0", Long.class, data.productId()))
                .isEqualTo(2L);
        var ids = certificates.customer(data.customer(), data.productSku(), 1).items().stream()
                .map(item -> ((Number) ((Map<?, ?>) item).get("id")).longValue()).toList();
        assertThat(ids).containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("删除证书会解除商品绑定并从客户查询移除")
    void deletingCertificateRemovesCustomerVisibility() {
        String fileUuid = insertCoaFile();
        long id = createCertificate("To Delete", fileUuid);
        certificates.bind(seller(), id, data.productId());

        certificates.remove(seller(), id);

        assertThat(certificates.customer(data.customer(), data.productSku(), 1).items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_coa_link WHERE coa_id=? AND deleted=0", Long.class, id))
                .isZero();
    }

    @Test
    @DisplayName("COA 元数据、文件和绑定关系按商户隔离")
    void certificatesAndFilesBelongToOneMerchant() {
        var other = PrdTestFixture.seed(jdbc, PrdTestFixture.merchant(jdbc,
                "COA" + UUID.randomUUID().toString().replace("-", "").substring(0, 24)));
        String aFile = insertCoaFile(data.seller().merchantId(), data.sellerId());
        String bFile = insertCoaFile(other.seller().merchantId(), other.sellerId());
        long aCoa = createCertificate(data.seller(), "A certificate", aFile);
        long bCoa = createCertificate(other.seller(), "B certificate", bFile);
        certificates.bind(data.seller(), aCoa, data.productId());
        certificates.bind(other.seller(), bCoa, other.productId());

        assertThat(certificates.customer(data.customer(), "", 1).items())
                .extracting(item -> ((Number) ((Map<?, ?>) item).get("id")).longValue()).containsExactly(aCoa);
        assertThat(certificates.customer(other.customer(), "", 1).items())
                .extracting(item -> ((Number) ((Map<?, ?>) item).get("id")).longValue()).containsExactly(bCoa);
        assertThatThrownBy(() -> certificates.bind(other.seller(), aCoa, other.productId()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> certificates.create(other.seller(), new CertificateService.CertificateRequest(
                "Foreign file", "LOT", "99", "10g", LocalDate.now(), aFile, "test-token")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("FILE_NOT_AVAILABLE");
    }

    private long createCertificate(String name, String fileUuid) {
        return createCertificate(seller(), name, fileUuid);
    }

    private long createCertificate(com.dailyactivity.auth.domain.Principal owner, String name, String fileUuid) {
        return ((Number) certificates.create(owner, new CertificateService.CertificateRequest(
                name + " " + owner.merchantId(), "LOT-" + name, "99.0", "10g", LocalDate.now(), fileUuid, "test-token")).get("id")).longValue();
    }

    private String insertCoaFile() {
        return insertCoaFile(data.seller().merchantId(), data.sellerId());
    }

    private String insertCoaFile(long merchantId, long sellerId) {
        String uuid = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO file_asset(merchant_id,file_uuid,stored_file_name,original_file_name,purpose,media_type,size_bytes,sha256,status,uploaded_by_user_id,uploaded_at,created_by_user_id,updated_by_user_id) "
                        + "VALUES(?,?,?,?,?,?,?,?,'available',?,?,?,?)",
                merchantId, uuid, uuid + ".pdf", "certificate.pdf", "coa", "application/pdf", 1L, "1".repeat(64),
                sellerId, Timestamp.from(Instant.now()), sellerId, sellerId);
        return uuid;
    }
}

package com.dailyactivity.certificate.application;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.certificate.domain.port.CertificateRepository;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.common.RequestValidation;
import com.dailyactivity.file.application.FileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CertificateService {
    private final CertificateRepository certificates;
    private final AccountService accounts;
    private final com.dailyactivity.merchant.domain.port.MerchantRepository audit;
    private final FileService fileService;

    public CertificateService(CertificateRepository certificates, AccountService accounts, FileService fileService, com.dailyactivity.merchant.domain.port.MerchantRepository audit) {
        this.audit = audit;
        this.certificates = certificates;
        this.accounts = accounts;
        this.fileService = fileService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> customer(Principal principal) {
        long merchantId = accounts.customer(principal).merchantId();
        return certificates.allCustomer(merchantId).stream().map(this::customerView).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> customer(Principal principal, String query, int page) {
        long merchantId = accounts.customer(principal).merchantId();
        String keyword = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        List<Map<String, Object>> matches = certificates.allCustomer(merchantId).stream()
                .filter(coa -> keyword.isEmpty()
                        || contains(coa.name(), keyword) || contains(coa.lot(), keyword)
                        || contains(coa.sku(), keyword) || contains(coa.productName(), keyword)
                        || contains(coa.purity(), keyword) || contains(coa.fill(), keyword)
                        || contains(coa.fileName(), keyword)
                        || coa.testDate() != null && coa.testDate().toString().contains(keyword))
                .map(this::customerView).toList();
        int current = RequestValidation.page(page);
        int from = Math.min(matches.size(), (current - 1) * 10);
        return PageResponse.of(matches.subList(from, Math.min(matches.size(), from + 10)), current, matches.size());
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(keyword);
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> admin(Principal p, String q, Integer page) {
        accounts.seller(p);
        int current = RequestValidation.page(page);
        return PageResponse.of(certificates.adminPage(p.merchantId(), q, current).stream().map(this::view).toList(), current, certificates.adminCount(p.merchantId(), q));
    }

    @Transactional
    public Map<String, Object> create(Principal p, CertificateRequest r) {
        accounts.requireMenu(p, "certificates");
        validate(r);
        validateNewFile(p, r);
        long id = certificates.create(p.merchantId(), r.input(), p.userId());
        audit.audit(p.merchantId(), p.actor(), "coa.create", "id=" + id);
        return Map.of("id", id);
    }

    @Transactional
    public void update(Principal p, long id, CertificateRequest r) {
        accounts.requireMenu(p, "certificates");
        validate(r);
        var existing = certificates.find(p.merchantId(), id).orElseThrow(() -> ApiException.notFound("Certificate not found."));
        if (!existing.fileUuid().equals(r.fileUuid().trim())) validateNewFile(p, r);
        certificates.update(p.merchantId(), id, r.input(), p.userId(), r.expectedUpdatedAt());
        audit.audit(p.merchantId(), p.actor(), "coa.update", "id=" + id);
    }

    @Transactional
    public void remove(Principal p, long id) {
        accounts.requireMenu(p, "certificates");
        certificates.softDelete(p.merchantId(), id, p.userId());
        audit.audit(p.merchantId(), p.actor(), "coa.remove", "id=" + id);
    }

    @Transactional
    public void bind(Principal p, long id, Long productId) {
        accounts.requireMenu(p, "certificates");
        certificates.bindProduct(p.merchantId(), id, productId, p.userId());
        audit.audit(p.merchantId(), p.actor(), "coa.bind", "id=" + id);
    }

    @Transactional
    public void bindFromProduct(Principal p, long id, Long productId) {
        accounts.requireMenu(p, "certificates");
        certificates.bindProduct(p.merchantId(), id, productId, p.userId());
        audit.audit(p.merchantId(), p.actor(), "coa.bindFromProduct", "id=" + id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> productCertificates(Principal p, long productId) {
        accounts.requireMenu(p, "products");
        return certificates.productCertificates(p.merchantId(), productId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> productCoaOptions(Principal p, String query) {
        accounts.requireMenu(p, "products");
        return certificates.adminPage(p.merchantId(), query, 1).stream().map(this::view).toList();
    }

    @Transactional
    public void replaceProductCertificates(Principal p, long productId, List<Long> coaIds) {
        accounts.requireMenu(p, "certificates");
        List<Long> ids = coaIds == null ? List.of() : coaIds.stream().distinct().toList();
        if (coaIds != null && ids.size() != coaIds.size())
            throw com.dailyactivity.common.ApiException.badRequest("INVALID_COA_BINDING", "COA selections must be unique.");
        certificates.replaceProductCertificates(p.merchantId(), productId, ids, p.userId());
        audit.audit(p.merchantId(), p.actor(), "coa.replaceProductCertificates", "id=" + productId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> options(Principal p, long id) {
        return productOptions(p, id, null);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> options(Principal p, long id, String query) {
        return productOptions(p, id, query);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> productOptions(Principal p, String query) {
        accounts.requireMenu(p, "certificates");
        return productOptions(p, null, query);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> productOptions(Principal p, Long id, String query) {
        accounts.requireMenu(p, "certificates");
        return certificates.productOptions(p.merchantId(), id, query).stream().map(x -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", x.id());
            v.put("sku", x.sku());
            v.put("name", x.name());
            return v;
        }).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> productDetail(Principal p, long id) {
        accounts.requireMenu(p, "certificates");
        var product = certificates.productDetail(p.merchantId(), id).orElseThrow(() -> ApiException.notFound("Associated product not found."));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", product.id());
        view.put("sku", product.sku());
        view.put("name", product.name());
        view.put("category", product.category());
        view.put("description", product.description());
        view.put("productType", product.productType());
        view.put("active", product.active());
        view.put("stock", product.stock());
        view.put("basePrice", product.basePrice());
        return view;
    }

    private Map<String, Object> view(CertificateRepository.Certificate c) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", c.id());
        v.put("updatedAt", c.updatedAt());
        v.put("name", c.name());
        v.put("lot", c.lot());
        v.put("purity", c.purity());
        v.put("fill", c.fill());
        v.put("testDate", c.testDate());
        v.put("fileUuid", c.fileUuid());
        v.put("productId", c.productId());
        v.put("productName", c.productName());
        v.put("sku", c.sku());
        v.put("fileName", c.fileName());
        return v;
    }

    private Map<String, Object> customerView(CertificateRepository.Certificate certificate) {
        Map<String, Object> value = view(certificate);
        value.put("publicUrl", fileService.publicCoaUrl(certificate.fileUuid()));
        return value;
    }

    private void validate(CertificateRequest r) {
        if (r.name() == null || r.name().isBlank() || r.testDate() == null || r.fileUuid() == null || r.fileUuid().isBlank())
            throw ApiException.badRequest("INVALID_CERTIFICATE", "Certificate name, testDate, and fileUuid are required.");
    }

    private void validateNewFile(Principal p, CertificateRequest r) {
        fileService.validateUploadToken(r.fileUuid().trim(), "coa", p.userId(), null, "coa", r.uploadToken(), p.merchantId());
    }

    public record CertificateRequest(String name, String lot, String purity, String fill, java.time.LocalDate testDate,
                                     String fileUuid, String uploadToken, java.time.Instant expectedUpdatedAt) {
        public CertificateRequest(String name, String lot, String purity, String fill, java.time.LocalDate testDate, String fileUuid, String uploadToken) {
            this(name,lot,purity,fill,testDate,fileUuid,uploadToken,null);
        }
        CertificateRepository.Input input() {
            return new CertificateRepository.Input(name.trim(), lot, purity, fill, testDate, fileUuid.trim());
        }
    }
}

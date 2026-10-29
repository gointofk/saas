package com.dailyactivity.certificate.api;

import com.dailyactivity.auth.api.AuthSupport;
import com.dailyactivity.auth.api.RequireMenu;
import com.dailyactivity.certificate.application.CertificateService;
import com.dailyactivity.common.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CertificateController {
    private final CertificateService service;

    public CertificateController(CertificateService service) {
        this.service = service;
    }

    @GetMapping("/customer/certificates")
    public List<Map<String, Object>> customer(HttpServletRequest r) {
        AuthSupport.principal(r, "customer");
        return service.customer();
    }

    @RequireMenu("certificates")
    @GetMapping("/admin/certificates")
    public PageResponse<Map<String, Object>> admin(@RequestParam(required = false) String q, @RequestParam(required = false) Integer page, HttpServletRequest r) {
        return service.admin(AuthSupport.principal(r, "seller"), q, page);
    }

    @RequireMenu("certificates")
    @PostMapping("/admin/certificates")
    public Map<String, Object> create(@RequestBody CertificateService.CertificateRequest b, HttpServletRequest r) {
        return service.create(AuthSupport.principal(r, "seller"), b);
    }

    @RequireMenu("certificates")
    @PutMapping("/admin/certificates/{id}")
    public void update(@PathVariable long id, @RequestBody CertificateService.CertificateRequest b, HttpServletRequest r) {
        service.update(AuthSupport.principal(r, "seller"), id, b);
    }

    @RequireMenu("certificates")
    @DeleteMapping("/admin/certificates/{id}")
    public void delete(@PathVariable long id, HttpServletRequest r) {
        service.remove(AuthSupport.principal(r, "seller"), id);
    }

    @RequireMenu("certificates")
    @PutMapping("/admin/certificates/{id}/product")
    public void bind(@PathVariable long id, @RequestBody Map<String, Long> b, HttpServletRequest r) {
        service.bind(AuthSupport.principal(r, "seller"), id, b.get("productId"));
    }

    @RequireMenu("certificates")
    @GetMapping("/admin/certificates/{id}/product-options")
    public List<Map<String, Object>> options(@PathVariable long id, @RequestParam(required = false) String q, HttpServletRequest r) {
        return service.options(AuthSupport.principal(r, "seller"), id, q);
    }

    @RequireMenu("certificates")
    @GetMapping("/admin/certificate-product-options")
    public List<Map<String, Object>> productOptions(@RequestParam(required = false) String q, HttpServletRequest r) {
        return service.productOptions(AuthSupport.principal(r, "seller"), q);
    }

    @RequireMenu("certificates")
    @GetMapping("/admin/certificates/{id}/product")
    public Map<String, Object> product(@PathVariable long id, HttpServletRequest r) {
        return service.productDetail(AuthSupport.principal(r, "seller"), id);
    }

    @RequireMenu("certificates")
    @PutMapping("/admin/products/{productId}/certificates")
    public void bindProduct(@PathVariable long productId, @RequestBody Map<String, List<Long>> b, HttpServletRequest r) {
        var principal = AuthSupport.principal(r, "seller");
        List<Long> ids = b.getOrDefault("coaIds", List.of());
        service.replaceProductCertificates(principal, productId, ids);
    }

    @RequireMenu("products")
    @GetMapping("/admin/products/{productId}/certificates")
    public List<Map<String, Object>> productCertificates(@PathVariable long productId, HttpServletRequest r) {
        return service.productCertificates(AuthSupport.principal(r, "seller"), productId);
    }

    @RequireMenu("products")
    @GetMapping("/admin/product-coa-options")
    public List<Map<String, Object>> productCoaOptions(@RequestParam(required = false) String q, HttpServletRequest r) {
        return service.productCoaOptions(AuthSupport.principal(r, "seller"), q);
    }
}

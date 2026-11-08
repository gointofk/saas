package com.dailyactivity.platform.api;

import com.dailyactivity.auth.api.AuthSupport;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.platform.application.PlatformOperationsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/platform")
public class PlatformOperationsController {
    private final PlatformOperationsService service;

    public PlatformOperationsController(PlatformOperationsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest request) {
        return service.overview(AuthSupport.principal(request, "platform"));
    }

    @GetMapping("/orders")
    public PageResponse<Map<String, Object>> orders(@RequestParam(required = false) Long merchantId,
                                                    @RequestParam(required = false) String q,
                                                    @RequestParam(required = false) String orderStatus,
                                                    @RequestParam(required = false) String paymentStatus,
                                                    @RequestParam(required = false) String auditStatus,
                                                    @RequestParam(required = false) Instant from,
                                                    @RequestParam(required = false) Instant to,
                                                    @RequestParam(required = false) Integer page,
                                                    HttpServletRequest request) {
        return service.orders(AuthSupport.principal(request, "platform"), merchantId, q, orderStatus, paymentStatus, auditStatus, from, to, page);
    }

    @PostMapping("/orders/{merchantId}/{orderId}/access")
    public Map<String, Object> orderAccess(@PathVariable long merchantId, @PathVariable long orderId,
                                           @RequestBody AccessRequest body, HttpServletRequest request) {
        return service.orderAccess(AuthSupport.principal(request, "platform"), merchantId, orderId, body == null ? null : body.purpose());
    }

    @PostMapping("/orders/{merchantId}/{orderId}/contact")
    public Map<String, Object> orderContact(@PathVariable long merchantId, @PathVariable long orderId,
                                            @RequestBody AccessRequest body, HttpServletRequest request) {
        return service.orderContact(AuthSupport.principal(request, "platform"), merchantId, orderId, body == null ? null : body.purpose());
    }

    @GetMapping("/buyers")
    public PageResponse<Map<String, Object>> buyers(@RequestParam(required = false) Long merchantId,
                                                    @RequestParam(required = false) String q,
                                                    @RequestParam(required = false) Boolean active,
                                                    @RequestParam(required = false) Instant from,
                                                    @RequestParam(required = false) Instant to,
                                                    @RequestParam(required = false) Integer page,
                                                    HttpServletRequest request) {
        return service.buyers(AuthSupport.principal(request, "platform"), merchantId, q, active, from, to, page);
    }

    @PostMapping("/buyers/{merchantId}/{customerId}/access")
    public Map<String, Object> buyerAccess(@PathVariable long merchantId, @PathVariable long customerId,
                                           @RequestBody AccessRequest body, HttpServletRequest request) {
        return service.buyerAccess(AuthSupport.principal(request, "platform"), merchantId, customerId, body == null ? null : body.purpose());
    }

    @PostMapping("/buyers/{merchantId}/{customerId}/contact")
    public Map<String, Object> buyerContact(@PathVariable long merchantId, @PathVariable long customerId,
                                            @RequestBody AccessRequest body, HttpServletRequest request) {
        return service.buyerContact(AuthSupport.principal(request, "platform"), merchantId, customerId, body == null ? null : body.purpose());
    }

    @GetMapping("/tasks/{type}")
    public PageResponse<Map<String, Object>> tasks(@PathVariable String type,
                                                   @RequestParam(required = false) Long merchantId,
                                                   @RequestParam(required = false) Integer page,
                                                   HttpServletRequest request) {
        return service.tasks(AuthSupport.principal(request, "platform"), type, merchantId, page);
    }

    @GetMapping("/merchant-performance")
    public Map<String, Object> merchantPerformance(@RequestParam(required = false) Instant from,
                                                  @RequestParam(required = false) Instant to,
                                                  @RequestParam(required = false) Integer page,
                                                  HttpServletRequest request) {
        return service.merchantPerformance(AuthSupport.principal(request, "platform"), from, to, page);
    }

    @GetMapping("/audit")
    public PageResponse<Map<String, Object>> audit(@RequestParam(required = false) Integer page, HttpServletRequest request) {
        return service.audit(AuthSupport.principal(request, "platform"), page);
    }

    @PostMapping("/exports/{type}")
    public ResponseEntity<byte[]> export(@PathVariable String type, @RequestBody(required = false) PlatformOperationsService.ExportRequest body,
                                         HttpServletRequest request) {
        PlatformOperationsService.ExportFile file = service.export(AuthSupport.principal(request, "platform"), type, body);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .contentLength(file.content().length).body(file.content());
    }

    public record AccessRequest(String purpose) {}
}

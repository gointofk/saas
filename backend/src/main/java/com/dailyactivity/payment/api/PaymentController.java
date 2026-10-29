package com.dailyactivity.payment.api;

import com.dailyactivity.auth.api.AuthSupport;
import com.dailyactivity.auth.api.RequireMenu;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.payment.application.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @GetMapping("/customer/payment-channels")
    public List<Map<String, Object>> customer(HttpServletRequest r) {
        AuthSupport.principal(r, "customer");
        return service.customerChannels(AuthSupport.principal(r, "customer"));
    }

    @RequireMenu("payment_channels")
    @GetMapping("/admin/payment-channels")
    public PageResponse<Map<String, Object>> admin(@RequestParam(required = false) Integer page, HttpServletRequest r) {
        return service.adminChannels(AuthSupport.principal(r, "seller"), page);
    }

    @RequireMenu("payment_channels")
    @PostMapping("/admin/payment-channels")
    public Map<String, Object> create(@Valid @RequestBody PaymentService.PaymentRequest b, HttpServletRequest r) {
        return service.create(AuthSupport.principal(r, "seller"), b);
    }

    @RequireMenu("payment_channels")
    @PutMapping("/admin/payment-channels/{id}")
    public void update(@PathVariable long id, @Valid @RequestBody PaymentService.PaymentRequest b, HttpServletRequest r) {
        service.update(AuthSupport.principal(r, "seller"), id, b);
    }

    @RequireMenu("payment_channels")
    @DeleteMapping("/admin/payment-channels/{id}")
    public void delete(@PathVariable long id, HttpServletRequest r) {
        service.remove(AuthSupport.principal(r, "seller"), id);
    }
}

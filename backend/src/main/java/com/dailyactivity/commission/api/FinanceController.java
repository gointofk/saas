package com.dailyactivity.commission.api;

import com.dailyactivity.auth.api.AuthSupport;
import com.dailyactivity.auth.api.RequireMenu;
import com.dailyactivity.billing.application.BillingService;
import com.dailyactivity.commission.application.CommissionService;
import com.dailyactivity.common.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class FinanceController {
    private final CommissionService service;
    private final BillingService billing;

    public FinanceController(CommissionService service, BillingService billing) {
        this.service = service;
        this.billing = billing;
    }

    @RequireMenu("commissions")
    @GetMapping("/admin/commissions")
    public PageResponse<Map<String, Object>> commissions(@RequestParam(required = false) String status,
                                                         @RequestParam(required = false) String q,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                                         @RequestParam(required = false) Integer page,
                                                         HttpServletRequest r) {
        return service.admin(AuthSupport.principal(r, "seller"), status, q, from, to, page);
    }

    @RequireMenu("commissions")
    @GetMapping("/admin/commissions/summary")
    public Map<String, Object> commissionSummary(HttpServletRequest r) {
        return service.summary(AuthSupport.principal(r, "seller"));
    }

    @RequireMenu("commissions")
    @GetMapping("/admin/commission-settlements/{id}")
    public Map<String, Object> settlementDetail(@PathVariable long id, HttpServletRequest r) {
        return service.settlementDetail(AuthSupport.principal(r, "seller"), id);
    }

    @RequireMenu("commissions")
    @GetMapping("/admin/commissions/{accrualId}/order")
    public Map<String, Object> commissionOrder(@PathVariable long accrualId, HttpServletRequest r) {
        return service.accrualOrder(AuthSupport.principal(r, "seller"), accrualId);
    }

    @RequireMenu("commissions")
    @GetMapping("/admin/commissions/{accrualId}/settlement")
    public Map<String, Object> accrualSettlement(@PathVariable long accrualId, HttpServletRequest r) {
        return service.settlementDetailByAccrual(AuthSupport.principal(r, "seller"), accrualId);
    }

    @RequireMenu("commissions")
    @PostMapping("/admin/commission-settlement-batches")
    public Map<String, Object> settle(@RequestBody CommissionService.SettlementRequest b, HttpServletRequest r) {
        return service.settle(AuthSupport.principal(r, "seller"), b);
    }

    @RequireMenu("bills")
    @GetMapping("/admin/bills")
    public PageResponse<Map<String, Object>> bills(@RequestParam(required = false) String type, @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String q,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                                   @RequestParam(required = false) Integer page, HttpServletRequest r) {
        return billing.page(AuthSupport.principal(r, "seller"), type, status, q, from, to, page);
    }

    @RequireMenu("bills")
    @GetMapping("/admin/bills/summary")
    public Map<String, Object> billSummary(HttpServletRequest r) {
        return billing.summary(AuthSupport.principal(r, "seller"));
    }

    @RequireMenu("bills")
    @GetMapping("/admin/bills/{billId}/order")
    public Map<String, Object> billOrder(@PathVariable long billId, HttpServletRequest r) {
        return billing.billOrder(AuthSupport.principal(r, "seller"), billId);
    }

    @GetMapping("/customer/commission-settlements")
    public PageResponse<Map<String, Object>> settlements(@RequestParam(required = false) Long referredCustomerId, @RequestParam(required = false) Integer page, HttpServletRequest r) {
        return service.customerSettlements(AuthSupport.principal(r, "customer"), referredCustomerId, page);
    }

    @GetMapping("/customer/referrals/{customerId}/commissions")
    public PageResponse<Map<String, Object>> referralCommissions(@PathVariable long customerId, @RequestParam(required = false) Integer page, HttpServletRequest r) {
        return service.customerCommissions(AuthSupport.principal(r, "customer"), customerId, page);
    }
}

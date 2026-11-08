package com.dailyactivity.identity.api;

import com.dailyactivity.auth.api.AuthSupport;
import com.dailyactivity.auth.api.RequireMenu;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.identity.application.CustomerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CustomerController {
    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @RequireMenu("customers")
    @GetMapping("/admin/customers")
    public PageResponse<Map<String, Object>> page(@RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) String customerType, @RequestParam(required = false) Boolean unpaid,
                                                   HttpServletRequest r) {
        return service.page(AuthSupport.principal(r, "seller"), q, page, customerType, unpaid);
    }

    @RequireMenu("customers")
    @GetMapping("/admin/customers/{id}")
    public Map<String, Object> get(@PathVariable long id, HttpServletRequest r) {
        return service.get(AuthSupport.principal(r, "seller"), id);
    }

    @RequireMenu("customers")
    @GetMapping("/admin/customers/{id}/referrals")
    public PageResponse<Map<String, Object>> referrals(@PathVariable long id, @RequestParam(required = false) Integer page, HttpServletRequest r) {
        return service.adminReferrals(AuthSupport.principal(r, "seller"), id, page);
    }

    @RequireMenu("customers")
    @PostMapping("/admin/customers")
    public Map<String, Object> create(@Valid @RequestBody CustomerService.CustomerRequest b, HttpServletRequest r) {
        return service.create(AuthSupport.principal(r, "seller"), b);
    }

    @RequireMenu("customers")
    @PutMapping("/admin/customers/{id}")
    public void update(@PathVariable long id, @Valid @RequestBody CustomerService.CustomerRequest b, HttpServletRequest r) {
        service.update(AuthSupport.principal(r, "seller"), id, b);
    }

    @RequireMenu("customers")
    @PatchMapping("/admin/customers/{id}/status")
    public void status(@PathVariable long id, @RequestBody Map<String, Boolean> b, HttpServletRequest r) {
        service.status(AuthSupport.principal(r, "seller"), id, Boolean.TRUE.equals(b.get("active")));
    }

    @RequireMenu("customers")
    @PutMapping("/admin/customers/{id}/password")
    public void password(@PathVariable long id, @RequestBody CustomerService.CustomerRequest b, HttpServletRequest r) {
        service.password(AuthSupport.principal(r, "seller"), id, b.password(), b.confirmation());
    }

    @GetMapping("/customer/referrals")
    public List<Map<String, Object>> referrals(HttpServletRequest r) {
        return service.referrals(AuthSupport.principal(r, "customer"));
    }

    @GetMapping("/customer/referrals/summary")
    public Map<String, Object> referralSummary(HttpServletRequest r) {
        return service.referralSummary(AuthSupport.principal(r, "customer"));
    }
}

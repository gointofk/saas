package com.dailyactivity.order.api;

import com.dailyactivity.auth.api.AuthSupport;
import com.dailyactivity.auth.api.RequireMenu;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.order.application.OrderParcelService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping("/customer/order-quotes")
    public Map<String, Object> quote(@RequestBody OrderService.QuoteRequest b, HttpServletRequest r) {
        return service.quote(AuthSupport.principal(r, "customer"), b);
    }

    @PostMapping("/customer/orders")
    public Map<String, Object> create(@RequestBody OrderService.CreateRequest b, HttpServletRequest r) {
        return service.create(AuthSupport.principal(r, "customer"), b);
    }

    @GetMapping("/customer/orders")
    public PageResponse<Map<String, Object>> customerOrders(@RequestParam(required = false) String status, @RequestParam(required = false) String paymentStatus, @RequestParam(required = false) String q, @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to, @RequestParam(required = false) Integer page, HttpServletRequest r) {
        return service.customerOrders(AuthSupport.principal(r, "customer"), status, paymentStatus, q, from, to, page);
    }

    @GetMapping("/customer/orders/summary")
    public Map<String, Object> customerSummary(HttpServletRequest r) {
        return service.summary(AuthSupport.principal(r, "customer"));
    }

    @GetMapping("/customer/orders/{id}")
    public Map<String, Object> customerDetail(@PathVariable long id, HttpServletRequest r) {
        return service.detail(AuthSupport.principal(r, "customer"), id);
    }

    @PatchMapping("/customer/orders/{id}")
    public Map<String, Object> customerUpdate(@PathVariable long id, @RequestBody OrderService.CustomerUpdate b, HttpServletRequest r) {
        return service.updateCustomer(AuthSupport.principal(r, "customer"), id, b);
    }

    @PostMapping("/customer/orders/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable long id, @RequestBody(required = false) OrderService.CancelRequest body, HttpServletRequest r) {
        return service.cancel(AuthSupport.principal(r, "customer"), id,
                body == null ? null : body.reason(), body == null ? null : body.expectedUpdatedAt());
    }

    @PutMapping("/customer/orders/{id}/payment-submission")
    public Map<String, Object> payment(@PathVariable long id, @RequestBody OrderService.PaymentRequest b, HttpServletRequest r) {
        return service.submitPayment(AuthSupport.principal(r, "customer"), id, b);
    }

    @PostMapping("/customer/orders/{id}/payment-submissions")
    public Map<String, Object> createPayment(@PathVariable long id, @RequestBody OrderService.PaymentRequest body, HttpServletRequest r) {
        return service.submitPayment(AuthSupport.principal(r, "customer"), id, body);
    }

    @PostMapping("/customer/orders/{id}/payment-link-request")
    public Map<String, Object> requestPaymentLink(@PathVariable long id, HttpServletRequest r) {
        return service.requestPaymentLink(AuthSupport.principal(r, "customer"), id);
    }

    @GetMapping("/customer/orders/{id}/payments")
    public PageResponse<Map<String, Object>> customerPayments(@PathVariable long id, @RequestParam(required = false) Integer page, HttpServletRequest r) {
        return service.paymentRecords(AuthSupport.principal(r, "customer"), id, page);
    }

    @GetMapping("/admin/orders/{id}/payments")
    public PageResponse<Map<String, Object>> adminPayments(@PathVariable long id, @RequestParam(required = false) Integer page, HttpServletRequest r) {
        return service.paymentRecords(AuthSupport.principal(r, "seller"), id, page);
    }

    @RequireMenu("orders")
    @GetMapping("/admin/orders")
    public PageResponse<Map<String, Object>> adminOrders(@RequestParam(required = false) String status, @RequestParam(required = false) String paymentStatus, @RequestParam(required = false) String q, @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to, @RequestParam(required = false) Integer page, HttpServletRequest r) {
        return service.adminOrders(AuthSupport.principal(r, "seller"), status, paymentStatus, q, from, to, page);
    }

    @RequireMenu("order_audit")
    @GetMapping("/admin/order-audits")
    public PageResponse<Map<String, Object>> audits(@RequestParam(required = false) String result, @RequestParam(required = false) String q, @RequestParam(required = false) Integer page, HttpServletRequest r) {
        return service.auditOrders(AuthSupport.principal(r, "seller"), result, q, page);
    }

    @RequireMenu("order_audit")
    @GetMapping("/admin/order-audits/{id}")
    public Map<String, Object> auditDetail(@PathVariable long id, HttpServletRequest r) {
        return service.auditDetail(AuthSupport.principal(r, "seller"), id);
    }

    @RequireMenu("order_audit")
    @PostMapping("/admin/order-audits/{id}/decision")
    public Map<String, Object> auditDecision(@PathVariable long id, @RequestBody OrderService.AuditDecision body, HttpServletRequest r) {
        return service.decideAudit(AuthSupport.principal(r, "seller"), id, body);
    }

    @RequireMenu("order_audit")
    @GetMapping("/admin/order-audits/{id}/products")
    public PageResponse<OrderService.EditProduct> auditProducts(@PathVariable long id, @RequestParam(required = false) String q,
                                                               @RequestParam(required = false) Integer page, HttpServletRequest r) {
        return service.auditEditProducts(AuthSupport.principal(r, "seller"), id, q, page);
    }

    @RequireMenu("order_audit")
    @PatchMapping("/admin/order-audits/{id}")
    public Map<String, Object> auditUpdate(@PathVariable long id, @RequestBody OrderService.AuditUpdate body, HttpServletRequest r) {
        return service.updateAudit(AuthSupport.principal(r, "seller"), id, body);
    }

    @RequireMenu("customers")
    @GetMapping("/admin/customers/{customerId}/orders")
    public PageResponse<Map<String, Object>> adminCustomerOrders(@PathVariable long customerId, @RequestParam(required = false) String status, @RequestParam(required = false) String paymentStatus, @RequestParam(required = false) String q, @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to, @RequestParam(required = false) Integer page, HttpServletRequest r) {
        return service.adminCustomerOrders(AuthSupport.principal(r, "seller"), customerId, status, paymentStatus, q, from, to, page);
    }

    @RequireMenu("customers")
    @GetMapping("/admin/customers/{customerId}/orders/summary")
    public Map<String, Object> adminCustomerOrderFilterSummary(@PathVariable long customerId, @RequestParam(required = false) String status, @RequestParam(required = false) String paymentStatus, @RequestParam(required = false) String q, @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to, HttpServletRequest r) {
        return service.adminCustomerOrderFilterSummary(AuthSupport.principal(r, "seller"), customerId, status, paymentStatus, q, from, to);
    }

    @RequireMenu("customers")
    @GetMapping("/admin/customers/{customerId}/orders/{orderId}")
    public Map<String, Object> adminCustomerOrderDetail(@PathVariable long customerId, @PathVariable long orderId, HttpServletRequest r) {
        return service.adminCustomerOrderDetail(AuthSupport.principal(r, "seller"), customerId, orderId);
    }

    @RequireMenu("orders")
    @GetMapping("/admin/orders/summary")
    public Map<String, Object> adminSummary(HttpServletRequest r) {
        return service.summary(AuthSupport.principal(r, "seller"));
    }

    @RequireMenu("orders")
    @GetMapping("/admin/orders/{id}/payment-channels")
    public java.util.List<Map<String, Object>> sellerPaymentChannels(@PathVariable long id, HttpServletRequest r) {
        return service.sellerPaymentChannels(AuthSupport.principal(r, "seller"), id);
    }

    @RequireMenu("orders")
    @GetMapping("/admin/dashboard")
    public Map<String, Object> dashboard(HttpServletRequest r) {
        return service.summary(AuthSupport.principal(r, "seller"));
    }

    @RequireMenu("orders")
    @GetMapping("/admin/orders/{id}")
    public Map<String, Object> adminDetail(@PathVariable long id, HttpServletRequest r) {
        return service.detail(AuthSupport.principal(r, "seller"), id);
    }

    @RequireMenu("orders")
    @PatchMapping("/admin/orders/{id}")
    public Map<String, Object> adminUpdate(@PathVariable long id, @RequestBody OrderService.SellerUpdate b, HttpServletRequest r) {
        return service.updateSeller(AuthSupport.principal(r, "seller"), id, b);
    }

    @RequireMenu("orders")
    @PostMapping("/admin/orders/{id}/{action:confirm-payment|pack|ship|receive|complete}")
    public Map<String, Object> action(@PathVariable long id, @PathVariable String action, @RequestBody(required = false) OrderService.SellerAction b, HttpServletRequest r) {
        return service.sellerAction(AuthSupport.principal(r, "seller"), id, action, b == null ? new OrderService.SellerAction(null, null, null, null, null, null, null, null, false, null) : b);
    }
    @RequireMenu("orders")
    @PostMapping("/admin/orders/{id}/parcels")
    public Map<String, Object> packParcels(@PathVariable long id, @RequestBody OrderParcelService.PackRequest body, HttpServletRequest r) {
        return service.packParcels(AuthSupport.principal(r, "seller"), id, body);
    }

    @RequireMenu("orders")
    @PostMapping("/admin/orders/{id}/parcels/ship")
    public Map<String, Object> shipParcels(@PathVariable long id, @RequestBody OrderParcelService.ShipRequest body, HttpServletRequest r) {
        return service.shipParcels(AuthSupport.principal(r, "seller"), id, body);
    }

    @RequireMenu("orders")
    @PostMapping("/admin/orders/{id}/parcels/receive")
    public Map<String, Object> receiveParcels(@PathVariable long id, @RequestBody OrderParcelService.ReceiveRequest body, HttpServletRequest r) {
        return service.receiveParcels(AuthSupport.principal(r, "seller"), id, body);
    }

}

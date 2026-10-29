package com.dailyactivity.billing.application;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.commission.domain.port.CommissionRepository;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.common.RequestValidation;
import com.dailyactivity.order.application.OrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Instant;

@Service
public class BillingService {
    private final CommissionRepository bills;
    private final AccountService accounts;
    private final OrderService orders;

    public BillingService(CommissionRepository bills, AccountService accounts, OrderService orders) {
        this.bills = bills;
        this.accounts = accounts;
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> page(Principal principal, String type, String status, Integer requestedPage) {
        return page(principal, type, status, null, null, null, requestedPage);
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> page(Principal principal, String type, String status, String query, Instant from, Instant to, Integer requestedPage) {
        accounts.seller(principal);
        int page = RequestValidation.page(requestedPage);
        if (type != null && !type.isBlank() && !java.util.List.of("order_receivable", "commission_payable").contains(type))
            throw com.dailyactivity.common.ApiException.badRequest("INVALID_BILL_TYPE", "Bill type filter is invalid.");
        if (status != null && !status.isBlank()) {
            java.util.List<String> supportedStatuses = "order_receivable".equals(type)
                    ? java.util.List.of("unpaid", "pending_verification", "partially_paid", "paid")
                    : "commission_payable".equals(type) ? java.util.List.of("estimated", "payable", "settled") : java.util.List.of();
            if (!supportedStatuses.contains(status))
                throw com.dailyactivity.common.ApiException.badRequest("INVALID_BILL_STATUS", "Choose a status supported by the selected bill type.");
        }
        if (from != null && to != null && from.isAfter(to))
            throw com.dailyactivity.common.ApiException.badRequest("INVALID_DATE_RANGE", "The start date must not be after the end date.");
        var filter = new CommissionRepository.BillFilter(type, status, query, from, to);
        return PageResponse.of(bills.bills(principal.merchantId(), filter, page).stream().map(this::view).toList(), page, bills.billCount(principal.merchantId(), filter));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(Principal principal) {
        accounts.seller(principal);
        var summary = bills.billSummary(principal.merchantId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalBills", summary.totalBills());
        result.put("orderBills", summary.orderBills());
        result.put("pendingOrders", summary.pendingOrders());
        result.put("orderAmount", summary.orderAmount());
        result.put("receivedAmount", summary.receivedAmount());
        result.put("pendingReceivableAmount", summary.pendingReceivableAmount());
        result.put("commissionBills", summary.commissionBills());
        result.put("payableCommissionBills", summary.payableCommissionBills());
        result.put("commissionAmount", summary.commissionAmount());
        result.put("settledCommissionAmount", summary.settledCommissionAmount());
        result.put("payableCommissionAmount", summary.payableCommissionAmount());
        result.put("estimatedCommissionAmount", summary.estimatedCommissionAmount());
        // Keep the original keys available for clients that already consume them.
        result.put("receivable", summary.orderBills());
        result.put("payable", summary.commissionBills());
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> billOrder(Principal principal, long billId) {
        accounts.requireMenu(principal, "bills");
        var bill = bills.bill(principal.merchantId(), billId).orElseThrow(() -> com.dailyactivity.common.ApiException.notFound("Billing entry not found."));
        return orders.detail(principal, bill.orderId());
    }

    private Map<String, Object> view(CommissionRepository.Bill bill) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", bill.id());
        v.put("billNo", bill.billNo());
        v.put("billType", bill.type());
        v.put("direction", bill.direction());
        v.put("orderId", bill.orderId());
        v.put("orderNo", bill.orderNo());
        v.put("customerId", bill.customerId());
        v.put("customer", bill.customer());
        v.put("email", bill.email());
        v.put("amount", bill.amount());
        v.put("settledAmount", bill.settledAmount());
        v.put("remainingAmount", "void".equals(bill.status()) ? java.math.BigDecimal.ZERO : bill.amount().subtract(bill.settledAmount()));
        v.put("status", bill.status());
        v.put("orderCreatedAt", bill.orderCreatedAt());
        v.put("settledAt", bill.settledAt());
        v.put("orderStatus", bill.orderStatus());
        v.put("paymentStatus", bill.paymentStatus());
        v.put("fulfillmentSummary", orders.fulfillmentSummary(bill.orderId()));
        return v;
    }
}

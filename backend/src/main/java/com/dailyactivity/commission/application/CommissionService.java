package com.dailyactivity.commission.application;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.commission.domain.port.CommissionRepository;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.common.RequestValidation;
import com.dailyactivity.order.application.OrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.Set;

@Service
public class CommissionService {
    private final CommissionRepository repository;
    private final AccountService accounts;
    private final OrderService orders;

    public CommissionService(CommissionRepository repository, AccountService accounts, OrderService orders) {
        this.repository = repository;
        this.accounts = accounts;
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> admin(Principal p, String status, Integer page) {
        return admin(p, status, null, null, null, page);
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> admin(Principal p, String status, String query, Instant from, Instant to, Integer page) {
        accounts.seller(p);
        accounts.requireCommissionEnabled(p.merchantId());
        int current = RequestValidation.page(page);
        if (from != null && to != null && from.isAfter(to))
            throw com.dailyactivity.common.ApiException.badRequest("INVALID_DATE_RANGE", "The start date must not be after the end date.");
        String normalizedStatus = status == null || status.isBlank() || "all".equals(status) ? null : status;
        if (normalizedStatus != null && !Set.of("estimated", "payable", "settled").contains(normalizedStatus))
            throw com.dailyactivity.common.ApiException.badRequest("INVALID_COMMISSION_STATUS_FILTER", "status must be estimated, payable or settled.");
        String normalizedQuery = query == null || query.isBlank() ? null : query.trim();
        if (normalizedQuery != null && normalizedQuery.length() > 100) normalizedQuery = normalizedQuery.substring(0, 100);
        CommissionRepository.AccrualFilter filter = new CommissionRepository.AccrualFilter(normalizedStatus, normalizedQuery, from, to);
        return PageResponse.of(repository.adminPage(p.merchantId(), filter, current).stream().map(this::adminAccrual).toList(), current, repository.adminCount(p.merchantId(), filter));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(Principal p) {
        accounts.seller(p);
        accounts.requireCommissionEnabled(p.merchantId());
        var summary = repository.summary(p.merchantId());
        return Map.of("count", summary.count(), "estimated", summary.estimatedCount(), "payable", summary.payableCount(),
                "settled", summary.settledCount(), "commissionAmount", summary.commissionAmount(), "settledAmount", summary.settledAmount());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> settlementDetail(Principal p, long id) {
        accounts.requireMenu(p, "commissions");
        accounts.requireCommissionEnabled(p.merchantId());
        var s = repository.settlement(p.merchantId(), id).orElseThrow(() -> com.dailyactivity.common.ApiException.notFound("Commission settlement not found."));
        return settlementView(s);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> settlementDetailByAccrual(Principal p, long accrualId) {
        accounts.requireMenu(p, "commissions");
        accounts.requireCommissionEnabled(p.merchantId());
        var s = repository.settlementByAccrual(p.merchantId(), accrualId).orElseThrow(() -> com.dailyactivity.common.ApiException.notFound("Commission settlement not found."));
        return settlementView(s);
    }

    private Map<String, Object> settlementView(CommissionRepository.SettlementDetail s) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", s.id());
        v.put("batchId", s.batchId());
        v.put("batchNo", s.batchNo());
        v.put("accrualId", s.accrualId());
        v.put("orderId", s.orderId());
        v.put("orderNo", s.orderNo());
        v.put("referrerId", s.referrerId());
        v.put("referrer", s.referrer());
        v.put("referredCustomerId", s.referredCustomerId());
        v.put("referred", s.referred());
        v.put("baseAmount", s.baseAmount());
        v.put("rate", s.rate());
        v.put("commissionAmount", s.commissionAmount());
        v.put("settledAt", s.settledAt());
        v.put("settledByUserId", s.settledByUserId());
        v.put("remark", s.remark());
        return v;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> accrualOrder(Principal p, long accrualId) {
        accounts.requireMenu(p, "commissions");
        accounts.requireCommissionEnabled(p.merchantId());
        var accrual = repository.findAccrual(p.merchantId(), accrualId).orElseThrow(() -> com.dailyactivity.common.ApiException.notFound("Commission record not found."));
        return orders.detail(p, accrual.orderId());
    }

    @Transactional
    public Map<String, Object> settle(Principal p, SettlementRequest r) {
        accounts.requireMenu(p, "commissions");
        accounts.requireCommissionEnabled(p.merchantId());
        if (r == null || !r.offlinePaidConfirmed())
            throw com.dailyactivity.common.ApiException.badRequest("OFFLINE_PAYMENT_CONFIRMATION_REQUIRED", "Confirm offline payment before settling commissions.");
        return Map.of("batchId", repository.settle(p.merchantId(), r.items(), r.remark(), p.userId()));
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> customerSettlements(Principal p, Long referred, Integer page) {
        long id = accounts.customer(p).id();
        accounts.requireCommissionEnabled(p.merchantId());
        int current = RequestValidation.page(page);
        return PageResponse.of(repository.settlements(p.merchantId(), id, referred, current).stream().map(this::settlement).toList(), current, repository.settlementCount(p.merchantId(), id, referred));
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> customerCommissions(Principal p, long referred, Integer page) {
        long id = accounts.customer(p).id();
        accounts.requireCommissionEnabled(p.merchantId());
        int current = RequestValidation.page(page);
        return PageResponse.of(repository.pageForReferred(p.merchantId(), id, referred, current).stream().map(this::accrual).toList(), current, repository.countForReferred(p.merchantId(), id, referred));
    }

    private Map<String, Object> accrual(CommissionRepository.Accrual a) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", a.id());
        v.put("orderId", a.orderId());
        v.put("orderNo", a.orderNo());
        v.put("referrerId", a.referrerId());
        v.put("referrer", a.referrer());
        v.put("referred", a.referred());
        v.put("status", a.status());
        v.put("rate", a.rate());
        v.put("baseAmount", a.baseAmount());
        v.put("commissionAmount", a.commissionAmount());
        v.put("orderStatus", a.orderStatus());
        v.put("paymentStatus", a.paymentStatus());
        v.put("orderCreatedAt", a.orderCreatedAt());
        return v;
    }

    private Map<String, Object> adminAccrual(CommissionRepository.Accrual accrual) {
        var view = accrual(accrual);
        view.put("fulfillmentSummary", orders.fulfillmentSummary(accrual.orderId()));
        return view;
    }

    private Map<String, Object> settlement(CommissionRepository.Settlement s) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", s.id());
        value.put("orderId", s.orderId());
        value.put("orderNo", s.orderNo());
        value.put("referredCustomerId", s.referredCustomerId());
        value.put("referred", s.referred());
        value.put("rate", s.rate());
        value.put("baseAmount", s.baseAmount());
        value.put("commissionAmount", s.commissionAmount());
        value.put("settledAt", s.settledAt());
        value.put("remark", s.remark());
        return value;
    }

    public record SettlementRequest(List<CommissionRepository.SettlementRequest> items, String remark,
                                    boolean offlinePaidConfirmed) {
    }
}

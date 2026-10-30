package com.dailyactivity.identity.application;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.auth.domain.port.AccountRepository;
import com.dailyactivity.auth.infrastructure.PasswordHasher;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.common.RequestValidation;
import com.dailyactivity.identity.domain.port.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CustomerService {
    private final CustomerRepository customers;
    private final AccountService accounts;
    private final AccountRepository users;

    public CustomerService(CustomerRepository customers, AccountService accounts, AccountRepository users) {
        this.customers = customers;
        this.accounts = accounts;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> page(Principal p, String q, Integer page, String customerType, Boolean unpaid) {
        accounts.seller(p);
        boolean commissionEnabled = accounts.commissionEnabled(p.merchantId());
        String type = customerType == null || customerType.isBlank() || "all".equals(customerType) ? null : customerType;
        if (type != null && !List.of("partner", "regular").contains(type))
            throw ApiException.badRequest("INVALID_CUSTOMER_TYPE", "Customer type filter is invalid.");
        boolean unpaidOnly = Boolean.TRUE.equals(unpaid);
        int current = RequestValidation.page(page);
        return PageResponse.of(customers.page(p.merchantId(), q, type, unpaidOnly, current).stream().map(c -> view(c, commissionEnabled)).toList(), current, customers.count(p.merchantId(), q, type, unpaidOnly));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Principal p, long id) {
        accounts.seller(p);
        return view(customers.find(p.merchantId(), id).orElseThrow(() -> ApiException.notFound("Customer not found.")), accounts.commissionEnabled(p.merchantId()));
    }

    @Transactional
    public Map<String, Object> create(Principal p, CustomerRequest r) {
        accounts.requireMenu(p, "customers");
        validate(r, true);
        if (!r.password().equals(r.confirmation()))
            throw ApiException.badRequest("PASSWORD_CONFIRMATION_MISMATCH", "Password confirmation does not match.");
        boolean commissionEnabled = accounts.commissionEnabled(p.merchantId());
        if (commissionEnabled) validateReferrer(p, r.referrerId(), r.commissionRate(), null);
        else rejectCommissionInputWhenDisabled(r.referrerId(), r.commissionRate());
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        if (users.findCustomerByEmail(p.merchantId(), email).isPresent())
            throw ApiException.conflict("CUSTOMER_EMAIL_EXISTS", "Email is already in use.");
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                long id = customers.create(p.merchantId(), new CustomerRepository.CustomerInput(email, r.name().trim(), PasswordHasher.hash(r.password()), r.customerType(), r.priceTier(), commissionEnabled ? r.referrerId() : null, commissionEnabled ? r.commissionRate() : null), newCode(), p.userId());
                return Map.of("id", id);
            } catch (com.dailyactivity.common.ApiException e) {
                if (!"CUSTOMER_IDENTIFIER_EXISTS".equals(e.code())) throw e;
                if (users.findCustomerByEmail(p.merchantId(), email).isPresent())
                    throw ApiException.conflict("CUSTOMER_EMAIL_EXISTS", "Email is already in use.");
            }
        }
        throw ApiException.conflict("CUSTOMER_CODE_UNAVAILABLE", "Unable to allocate a customer code. Try again later.");
    }

    @Transactional
    public void update(Principal p, long id, CustomerRequest r) {
        accounts.requireMenu(p, "customers");
        validate(r, false);
        boolean commissionEnabled = accounts.commissionEnabled(p.merchantId());
        CustomerRepository.Customer existing = null;
        if (commissionEnabled) validateReferrer(p, r.referrerId(), r.commissionRate(), id);
        else {
            rejectCommissionInputWhenDisabled(r.referrerId(), r.commissionRate());
            existing = customers.find(p.merchantId(), id).orElseThrow(() -> ApiException.notFound("Customer not found."));
        }
        boolean resetPassword = r.password() != null && !r.password().isBlank();
        if (resetPassword && !r.password().equals(r.confirmation()))
            throw ApiException.badRequest("PASSWORD_CONFIRMATION_MISMATCH", "Password confirmation does not match.");
        Long referrerId = commissionEnabled ? r.referrerId() : existing.referrerId();
        BigDecimal commissionRate = commissionEnabled ? r.commissionRate() : existing.commissionRate();
        customers.update(p.merchantId(), id, new CustomerRepository.CustomerInput(r.email().trim().toLowerCase(Locale.ROOT), r.name().trim(), null, r.customerType(), r.priceTier(), referrerId, commissionRate), p.userId());
        if (resetPassword)
            customers.setPassword(p.merchantId(), id, PasswordHasher.hash(r.password()), p.userId());
    }

    @Transactional
    public void status(Principal p, long id, boolean active) {
        accounts.requireMenu(p, "customers");
        customers.setActive(p.merchantId(), id, active, p.userId());
    }

    @Transactional
    public void password(Principal p, long id, String password, String confirmation) {
        accounts.requireMenu(p, "customers");
        if (password == null || !password.equals(confirmation))
            throw ApiException.badRequest("PASSWORD_CONFIRMATION_MISMATCH", "Password confirmation does not match.");
        customers.setPassword(p.merchantId(), id, PasswordHasher.hash(password), p.userId());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> referrals(Principal p) {
        var a = accounts.customer(p);
        accounts.requireCommissionEnabled(p.merchantId());
        return customers.customerReferrals(p.merchantId(), a.id()).stream().map(this::referralView).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> adminReferrals(Principal p, long customerId, Integer page) {
        accounts.requireMenu(p, "customers");
        accounts.requireCommissionEnabled(p.merchantId());
        customers.find(p.merchantId(), customerId).orElseThrow(() -> ApiException.notFound("Customer not found."));
        int current = RequestValidation.page(page);
        return PageResponse.of(customers.directReferrals(p.merchantId(), customerId, current).stream().map(c -> view(c, true)).toList(), current, customers.directReferralCount(p.merchantId(), customerId));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> referralSummary(Principal p) {
        accounts.requireCommissionEnabled(p.merchantId());
        var s = customers.referralSummary(p.merchantId(), accounts.customer(p).id());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentCount", s.currentCount());
        result.put("pendingBase", s.pendingBase());
        result.put("pendingCommission", s.pendingCommission());
        result.put("settledAmount", s.settledAmount());
        result.put("orderCount", s.orderCount());
        result.put("pendingVerificationOrderCount", s.pendingVerificationOrderCount());
        result.put("pendingPaymentOrderCount", s.pendingPaymentOrderCount());
        result.put("paidOrderCount", s.paidOrderCount());
        result.put("commissionAmount", s.commissionAmount());
        result.put("estimatedCommissionAmount", s.estimatedCommissionAmount());
        result.put("payableCommissionAmount", s.payableCommissionAmount());
        result.put("settledCommissionAmount", s.settledCommissionAmount());
        return result;
    }

    private void validate(CustomerRequest r, boolean passwordRequired) {
        String email = r.email() == null ? null : r.email().trim();
        boolean emailValid = email != null && email.length() <= 128 && email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
        boolean passwordProvided = r.password() != null && !r.password().isBlank();
        boolean confirmationProvided = r.confirmation() != null && !r.confirmation().isBlank();
        if (!emailValid)
            throw ApiException.badRequest("INVALID_EMAIL", "Email must be valid and no longer than 128 characters.");
        if (r.name() == null || r.name().isBlank() || r.name().trim().length() > 100)
            throw ApiException.badRequest("INVALID_CUSTOMER_NAME", "Name is required and must be no longer than 100 characters.");
        if (!"partner".equals(r.customerType()) && !"regular".equals(r.customerType()))
            throw ApiException.badRequest("INVALID_CUSTOMER_TYPE", "Choose a valid customer type.");
        if (r.priceTier() == null || !List.of("V1", "V2", "V3", "V4", "V5", "V6").contains(r.priceTier()))
            throw ApiException.badRequest("INVALID_PRICE_TIER", "Choose a valid customer price tier.");
        if (passwordRequired && !passwordProvided)
            throw ApiException.badRequest("INVALID_PASSWORD", "A customer password is required.");
        if (passwordProvided && (r.password().length() < 12 || r.password().length() > 64))
            throw ApiException.badRequest("INVALID_PASSWORD", "Password must be 12–64 characters.");
        if (!passwordProvided && confirmationProvided)
            throw ApiException.badRequest("PASSWORD_CONFIRMATION_MISMATCH", "Password confirmation does not match.");
    }

    private void validateReferrer(Principal p, Long referrer, BigDecimal rate, Long self) {
        if (referrer == null) {
            if (rate != null)
                throw ApiException.badRequest("INVALID_COMMISSION_RATE", "A commission rate requires a referrer.");
            return;
        }
        if (referrer.equals(self))
            throw ApiException.badRequest("SELF_REFERRAL", "A customer cannot refer itself.");
        var a = users.findCustomerById(referrer).filter(candidate -> candidate.merchantId() == p.merchantId()).orElseThrow(() -> ApiException.badRequest("INVALID_REFERRER", "Referrer is not a customer."));
        if (!a.active()) throw ApiException.conflict("REFERRER_INACTIVE", "Referrer is inactive.");
        if (rate == null || rate.stripTrailingZeros().scale() > 0 || rate.signum() < 0 || rate.compareTo(new BigDecimal("20")) > 0)
            throw ApiException.badRequest("INVALID_COMMISSION_RATE", "Commission rate must be an integer between 0 and 20.");
    }

    private void rejectCommissionInputWhenDisabled(Long referrerId, BigDecimal rate) {
        if (referrerId != null || (rate != null && rate.signum() != 0))
            throw ApiException.forbidden("COMMISSION_FEATURE_DISABLED", "Commission features are disabled for this merchant.");
    }

    private String newCode() {
        return CustomerCodeGenerator.next();
    }

    private Map<String, Object> view(CustomerRepository.Customer c, boolean commissionEnabled) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", c.id());
        v.put("userCode", c.code());
        v.put("name", c.name());
        v.put("email", c.email());
        v.put("customerType", c.customerType());
        v.put("priceTier", c.priceTier());
        v.put("active", c.active());
        v.put("orderCount", c.orderCount());
        if (commissionEnabled) {
            v.put("referrerId", c.referrerId());
            v.put("commissionRate", c.commissionRate());
            v.put("referralCount", c.referralCount());
        }
        v.put("totalAmount", c.totalAmount());
        v.put("unpaidCount", c.unpaidCount());
        v.put("unpaidAmount", c.unpaidAmount());
        v.put("lastLogin", c.lastLoginAt());
        v.put("createdAt", c.createdAt());
        return v;
    }

    private Map<String, Object> referralView(CustomerRepository.ReferralCustomer referral) {
        CustomerRepository.Customer c = referral.customer();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", c.id());
        v.put("userCode", c.code());
        v.put("name", c.name());
        v.put("active", c.active());
        v.put("currentRelation", referral.currentRelation());
        v.put("commissionRate", c.commissionRate());
        v.put("historicalRateCount", referral.historicalRateCount());
        v.put("historicalRate", referral.historicalRate());
        v.put("pendingBase", referral.pendingBase());
        v.put("pendingCommission", referral.pendingCommission());
        v.put("settledCommissionAmount", referral.settledCommissionAmount());
        return v;
    }

    public record CustomerRequest(String email, String name, String password, String confirmation, String customerType,
                                  String priceTier, Long referrerId, BigDecimal commissionRate) {
    }
}

package com.dailyactivity.identity.domain.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CustomerRepository {
    List<Customer> page(long merchantId, String query, String customerType, boolean unpaidOnly, int page);

    long count(long merchantId, String query, String customerType, boolean unpaidOnly);

    Optional<Customer> find(long merchantId, long id);

    long create(long merchantId, CustomerInput input, String code, long actorId);

    void update(long merchantId, long id, CustomerInput input, long actorId);

    void setActive(long merchantId, long id, boolean active, long actorId);

    void setPassword(long merchantId, long id, String passwordHash, long actorId);

    List<Customer> directReferrals(long merchantId, long referrerId);

    List<Customer> directReferrals(long merchantId, long referrerId, int page);

    List<ReferralCustomer> customerReferrals(long merchantId, long referrerId);

    long directReferralCount(long merchantId, long referrerId);

    ReferralSummary referralSummary(long merchantId, long referrerId);

    record Customer(long id, String code, String name, String email, String customerType, String priceTier,
                    boolean active, Long referrerId, BigDecimal commissionRate, long orderCount,
                    long referralCount, BigDecimal totalAmount, long unpaidCount, BigDecimal unpaidAmount,
                    Instant lastLoginAt, Instant createdAt) {
    }

    record CustomerInput(String email, String name, String passwordHash, String customerType, String priceTier,
                         Long referrerId, BigDecimal commissionRate) {
    }

    record ReferralCustomer(Customer customer, boolean currentRelation, long historicalRateCount,
                            BigDecimal historicalRate, BigDecimal pendingBase, BigDecimal pendingCommission,
                            BigDecimal settledCommissionAmount) {
    }

    record ReferralSummary(long currentCount, BigDecimal pendingBase, BigDecimal pendingCommission,
                           BigDecimal settledAmount, long orderCount, long pendingVerificationOrderCount,
                           long pendingPaymentOrderCount, long paidOrderCount, BigDecimal commissionAmount,
                           BigDecimal estimatedCommissionAmount, BigDecimal payableCommissionAmount,
                           BigDecimal settledCommissionAmount) {
    }
}

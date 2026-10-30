package com.dailyactivity.commission.domain.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CommissionRepository {
    List<Accrual> page(long merchantId, Long referrerId, String status, int page);

    List<Accrual> adminPage(long merchantId, AccrualFilter filter, int page);

    List<Accrual> pageForReferred(long merchantId, long referrerId, long referredCustomerId, int page);

    long count(long merchantId, Long referrerId, String status);

    long adminCount(long merchantId, AccrualFilter filter);

    Optional<Accrual> findAccrual(long merchantId, long id);

    Summary summary(long merchantId);

    long countForReferred(long merchantId, long referrerId, long referredCustomerId);

    List<Settlement> settlements(long merchantId, Long referrerId, Long referredCustomerId, int page);

    long settlementCount(long merchantId, Long referrerId, Long referredCustomerId);

    Optional<SettlementDetail> settlement(long merchantId, long id);

    Optional<SettlementDetail> settlementByAccrual(long merchantId, long accrualId);

    long settle(long merchantId, List<SettlementRequest> items, String remark, long actorId);

    List<Bill> bills(long merchantId, BillFilter filter, int page);

    long billCount(long merchantId, BillFilter filter);

    BillSummary billSummary(long merchantId);

    Optional<Bill> bill(long merchantId, long id);

    CommissionDisableReadiness commissionDisableReadiness(long merchantId);

    record Accrual(long id, long orderId, String orderNo, long referrerId, String referrer, String referred,
                   String status, BigDecimal rate, BigDecimal baseAmount, BigDecimal commissionAmount,
                   String orderStatus, String paymentStatus, Instant orderCreatedAt) {
    }

    record Settlement(long id, long orderId, String orderNo, long referredCustomerId, String referred, BigDecimal rate,
                      BigDecimal baseAmount, BigDecimal commissionAmount, Instant settledAt, String remark) {
    }

    record SettlementDetail(long id, long batchId, String batchNo, long accrualId, long orderId, String orderNo,
                            long referrerId, String referrer, long referredCustomerId, String referred,
                            BigDecimal baseAmount, BigDecimal rate, BigDecimal commissionAmount, Instant settledAt,
                            long settledByUserId, String remark) {
    }

    record SettlementRequest(long accrualId, BigDecimal expectedAmount) {
    }

    record Summary(long count, long estimatedCount, long payableCount, long settledCount,
                   BigDecimal commissionAmount, BigDecimal settledAmount) {
    }

    record AccrualFilter(String status, String query, Instant from, Instant to) {
    }

    record Bill(long id, String billNo, String type, String direction, long orderId, String orderNo, long customerId,
                String customer, String email, BigDecimal amount, BigDecimal settledAmount, String status, Instant orderCreatedAt,
                Instant settledAt, String orderStatus, String paymentStatus) {
    }

    record BillFilter(String type, String status, String query, Instant from, Instant to) {
    }

    record BillSummary(long orderBills, long pendingOrders, BigDecimal orderAmount,
                       BigDecimal receivedAmount, BigDecimal pendingReceivableAmount, long commissionBills,
                       long payableCommissionBills, BigDecimal commissionAmount, BigDecimal settledCommissionAmount,
                       BigDecimal payableCommissionAmount, BigDecimal estimatedCommissionAmount) {
    }

    record CommissionDisableReadiness(long outstandingAccrualCount, BigDecimal outstandingAmount,
                                      long pendingEligibleOrderCount) {
        public boolean canDisable() {
            return outstandingAccrualCount == 0 && pendingEligibleOrderCount == 0;
        }
    }
}

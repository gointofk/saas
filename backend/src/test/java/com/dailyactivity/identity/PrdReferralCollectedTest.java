package com.dailyactivity.identity;

import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.commission.application.CommissionService;
import com.dailyactivity.commission.domain.port.CommissionRepository;
import com.dailyactivity.identity.application.CustomerService;
import com.dailyactivity.support.PrdDatabaseTest;
import com.dailyactivity.support.PrdTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("My Referrals 逐用户已结佣金汇总")
class PrdReferralCollectedTest extends PrdDatabaseTest {
    @Autowired
    private CustomerService customers;

    @Autowired
    private CommissionService commissions;

    @Test
    @DisplayName("无结算时显示零，待验证、待收取及订单已付款均不计已结佣金")
    void uncollectedCommissionsDisplayZero() {
        // Arrange
        var referrer = new Principal(data.referrerId(), "customer", 0, data.seller().merchantId());
        createAccrual(data.referrerId(), data.customerId(), "estimated", "5.00");
        var payable = createAccrual(data.referrerId(), data.customerId(), "payable", "7.50");
        jdbc.update("UPDATE sales_order SET payment_status='paid' WHERE id=(SELECT order_id FROM commission_accrual WHERE id=?)",
                payable.accrualId());

        // Act
        var referrals = customers.referrals(referrer);

        // Assert
        assertThat(referrals).singleElement().satisfies(row -> {
            assertThat((BigDecimal) row.get("settledCommissionAmount")).isZero();
            assertThat((BigDecimal) row.get("pendingCommission")).isEqualByComparingTo("12.50");
        });
    }

    @Test
    @DisplayName("按用户汇总多笔结算，未结佣金和其他用户的结算不混入")
    void collectedCommissionGroupsByReferredCustomer() {
        // Arrange
        var other = PrdTestFixture.seed(jdbc);
        var first = createAccrual(data.referrerId(), data.customerId(), "payable", "5.00");
        var second = createAccrual(data.referrerId(), data.customerId(), "payable", "7.50");
        var otherCustomer = createAccrual(data.referrerId(), other.customerId(), "payable", "3.00");
        createAccrual(data.referrerId(), data.customerId(), "estimated", "2.00");
        createAccrual(data.referrerId(), data.customerId(), "payable", "4.00");

        // Act
        settle(first, second, otherCustomer);
        var referrals = customers.referrals(new Principal(data.referrerId(), "customer", 0, data.seller().merchantId()));

        // Assert
        assertThat(referrals).hasSize(2);
        assertThat((BigDecimal) referral(referrals, data.customerId()).get("settledCommissionAmount"))
                .isEqualByComparingTo("12.50");
        assertThat((BigDecimal) referral(referrals, data.customerId()).get("pendingCommission"))
                .isEqualByComparingTo("6.00");
        assertThat((BigDecimal) referral(referrals, other.customerId()).get("settledCommissionAmount"))
                .isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("推荐关系和费率变更后保留历史已结金额，并隔离其他推荐人的结算")
    void collectedCommissionPreservesHistoricalOwnership() {
        // Arrange
        var other = PrdTestFixture.seed(jdbc);
        settle(createAccrual(data.referrerId(), data.customerId(), "payable", "5.00"));
        jdbc.update("UPDATE `user` SET referrer_id=?,commission_rate=0 WHERE id=?", other.referrerId(), data.customerId());
        settle(createAccrual(other.referrerId(), data.customerId(), "payable", "9.00"));

        // Act
        var original = customers.referrals(new Principal(data.referrerId(), "customer", 0, data.seller().merchantId()));
        var current = customers.referrals(new Principal(other.referrerId(), "customer", 0, data.seller().merchantId()));

        // Assert
        assertThat(original).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("id", data.customerId()).containsEntry("currentRelation", false);
            assertThat((BigDecimal) row.get("settledCommissionAmount")).isEqualByComparingTo("5.00");
        });
        assertThat((BigDecimal) referral(current, data.customerId()).get("settledCommissionAmount"))
                .isEqualByComparingTo("9.00");
    }

    private CommissionRepository.SettlementRequest createAccrual(long referrerId, long customerId, String status, String amount) {
        String orderNo = "REF-" + UUID.randomUUID();
        BigDecimal commission = new BigDecimal(amount);
        BigDecimal base = commission.multiply(new BigDecimal("20"));
        jdbc.update("INSERT INTO sales_order(order_no,customer_id,customer_code_snapshot,customer_name_snapshot,customer_email_snapshot,"
                        + "order_status,audit_status,subtotal,shipping_fee,total_amount,address_snapshot,recipient_name,city,status_changed_at) "
                        + "SELECT ?,id,code,name,email,?,'approved',?,20.00,?,'{}','Test Recipient','Test City',UTC_TIMESTAMP(6) FROM `user` WHERE id=?",
                orderNo, "payable".equals(status) ? "completed" : "pending", base, base.add(new BigDecimal("20.00")), customerId);
        long orderId = jdbc.queryForObject("SELECT id FROM sales_order WHERE order_no=?", Long.class, orderNo);
        jdbc.update("INSERT INTO commission_accrual(order_id,referrer_id,referred_customer_id,referrer_name_snapshot,referred_name_snapshot,"
                        + "rate,base_amount,commission_amount,status) VALUES(?,?,?,'Test Referrer','Test Customer',5.00,?,?,?)",
                orderId, referrerId, customerId, base, commission, status);
        long accrualId = jdbc.queryForObject("SELECT id FROM commission_accrual WHERE order_id=?", Long.class, orderId);
        return new CommissionRepository.SettlementRequest(accrualId, commission);
    }

    private void settle(CommissionRepository.SettlementRequest... items) {
        commissions.settle(seller(), new CommissionService.SettlementRequest(List.of(items), "Paid offline for test", true));
    }

    private Map<String, Object> referral(List<Map<String, Object>> referrals, long customerId) {
        return referrals.stream().filter(row -> ((Number) row.get("id")).longValue() == customerId).findFirst().orElseThrow();
    }
}

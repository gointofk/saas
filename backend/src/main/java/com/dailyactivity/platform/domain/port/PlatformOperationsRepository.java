package com.dailyactivity.platform.domain.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PlatformOperationsRepository {
    Map<String, Object> overview(Instant from, Instant to);
    List<Map<String, Object>> orderPage(OrderFilter filter, int page);
    long orderCount(OrderFilter filter);
    Optional<Map<String, Object>> orderDetail(long merchantId, long orderId);
    Optional<Map<String, Object>> orderContact(long merchantId, long orderId);
    List<Map<String, Object>> customerPage(CustomerFilter filter, int page);
    long customerCount(CustomerFilter filter);
    Optional<Map<String, Object>> customerDetail(long merchantId, long customerId);
    Optional<Map<String, Object>> customerContact(long merchantId, long customerId);
    List<Map<String, Object>> merchantPerformance(Instant from, Instant to, int page, int limit);
    long merchantCount();
    List<Map<String, Object>> alertPage(String type, Long merchantId, int page);
    long alertCount(String type, Long merchantId);
    List<Map<String, Object>> auditPage(int page);
    long auditCount();
    void recordAccess(long platformAdminId, long merchantId, String resourceType, long resourceId, String accessType, String purpose);
    List<Map<String, Object>> exportOrders(OrderFilter filter, int limit);
    List<Map<String, Object>> exportCustomers(CustomerFilter filter, int limit);
    List<Map<String, Object>> exportMerchantPerformance(Instant from, Instant to, int limit);

    record OrderFilter(Long merchantId, String query, String orderStatus, String paymentStatus,
                       String auditStatus, Instant from, Instant to) {}
    record CustomerFilter(Long merchantId, String query, Boolean active, Instant from, Instant to) {}
}

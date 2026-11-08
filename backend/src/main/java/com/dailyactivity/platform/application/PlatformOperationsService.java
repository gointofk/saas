package com.dailyactivity.platform.application;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.common.RequestValidation;
import com.dailyactivity.platform.domain.port.PlatformOperationsRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PlatformOperationsService {
    private static final int EXPORT_LIMIT = 10_000;
    private static final Set<String> ORDER_STATUSES = Set.of("pending", "packed", "shipped", "received", "completed", "cancelled");
    private static final Set<String> PAYMENT_STATUSES = Set.of("unpaid", "pending_verification", "partially_paid", "paid");
    private static final Set<String> AUDIT_STATUSES = Set.of("pending", "approved", "rejected", "withdrawn");

    private final PlatformOperationsRepository repository;
    private final AccountService accounts;
    private final ObjectMapper mapper;

    public PlatformOperationsService(PlatformOperationsRepository repository, AccountService accounts, ObjectMapper mapper) {
        this.repository = repository;
        this.accounts = accounts;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview(Principal principal) {
        accounts.requirePlatformAdmin(principal);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant from = today.minusDays(6).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Map<String, Object> raw = repository.overview(from, to);
        Map<String, Object> overview = camelMap(raw);
        overview.put("trend", raw.get("trend"));
        overview.put("trendFrom", from);
        overview.put("trendTo", to);
        return overview;
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> orders(Principal principal, Long merchantId, String query,
                                                     String orderStatus, String paymentStatus, String auditStatus,
                                                     Instant from, Instant to, Integer page) {
        accounts.requirePlatformAdmin(principal);
        int current = RequestValidation.page(page);
        var filter = new PlatformOperationsRepository.OrderFilter(merchantId, cleanQuery(query), status(orderStatus, ORDER_STATUSES, "INVALID_ORDER_STATUS_FILTER"),
                status(paymentStatus, PAYMENT_STATUSES, "INVALID_PAYMENT_STATUS_FILTER"), status(auditStatus, AUDIT_STATUSES, "INVALID_AUDIT_STATUS_FILTER"), from, to);
        validateRange(from, to);
        List<Map<String, Object>> rows = repository.orderPage(filter, current).stream().map(this::maskOrder).toList();
        return PageResponse.of(rows, current, repository.orderCount(filter));
    }

    @Transactional
    public Map<String, Object> orderAccess(Principal principal, long merchantId, long orderId, String purpose) {
        accounts.requirePlatformAdmin(principal);
        String reason = purpose(purpose);
        Map<String, Object> order = repository.orderDetail(merchantId, orderId).orElseThrow(() -> ApiException.notFound("Order not found."));
        repository.recordAccess(principal.userId(), merchantId, "order", orderId, "detail_view", reason);
        return maskOrder(order);
    }

    @Transactional
    public Map<String, Object> orderContact(Principal principal, long merchantId, long orderId, String purpose) {
        accounts.requirePlatformAdmin(principal);
        String reason = purpose(purpose);
        Map<String, Object> contact = repository.orderContact(merchantId, orderId).orElseThrow(() -> ApiException.notFound("Order not found."));
        repository.recordAccess(principal.userId(), merchantId, "order", orderId, "contact_reveal", reason);
        String snapshot = (String) contact.remove("addressSnapshot");
        Map<String, Object> address = parseAddress(snapshot);
        contact.put("address", address);
        return contact;
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> buyers(Principal principal, Long merchantId, String query, Boolean active,
                                                     Instant from, Instant to, Integer page) {
        accounts.requirePlatformAdmin(principal);
        int current = RequestValidation.page(page);
        validateRange(from, to);
        var filter = new PlatformOperationsRepository.CustomerFilter(merchantId, cleanQuery(query), active, from, to);
        List<Map<String, Object>> rows = repository.customerPage(filter, current).stream().map(this::maskBuyer).toList();
        return PageResponse.of(rows, current, repository.customerCount(filter));
    }

    @Transactional
    public Map<String, Object> buyerAccess(Principal principal, long merchantId, long customerId, String purpose) {
        accounts.requirePlatformAdmin(principal);
        String reason = purpose(purpose);
        Map<String, Object> buyer = repository.customerDetail(merchantId, customerId).orElseThrow(() -> ApiException.notFound("Buyer not found."));
        repository.recordAccess(principal.userId(), merchantId, "buyer", customerId, "detail_view", reason);
        return maskBuyer(buyer);
    }

    @Transactional
    public Map<String, Object> buyerContact(Principal principal, long merchantId, long customerId, String purpose) {
        accounts.requirePlatformAdmin(principal);
        String reason = purpose(purpose);
        Map<String, Object> contact = repository.customerContact(merchantId, customerId).orElseThrow(() -> ApiException.notFound("Buyer not found."));
        repository.recordAccess(principal.userId(), merchantId, "buyer", customerId, "contact_reveal", reason);
        return contact;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> merchantPerformance(Principal principal, Instant from, Instant to, Integer page) {
        accounts.requirePlatformAdmin(principal);
        int current = RequestValidation.page(page);
        var range = range(from, to);
        List<Map<String, Object>> rows = repository.merchantPerformance(range.from(), range.to(), current, 10);
        PageResponse<Map<String, Object>> result = PageResponse.of(rows, current, repository.merchantCount());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", result.items());
        response.put("page", result.page());
        response.put("pageSize", result.pageSize());
        response.put("total", result.total());
        response.put("totalPages", result.totalPages());
        response.put("from", range.from());
        response.put("to", range.to());
        return response;
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> tasks(Principal principal, String type, Long merchantId, Integer page) {
        accounts.requirePlatformAdmin(principal);
        if (!Set.of("audit", "payment", "fulfillment").contains(type))
            throw ApiException.badRequest("INVALID_PLATFORM_TASK_TYPE", "Choose audit, payment, or fulfillment.");
        int current = RequestValidation.page(page);
        var rows = repository.alertPage(type, merchantId, current).stream().map(this::maskOrder).toList();
        return PageResponse.of(rows, current, repository.alertCount(type, merchantId));
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> audit(Principal principal, Integer page) {
        accounts.requirePlatformAdmin(principal);
        int current = RequestValidation.page(page);
        return PageResponse.of(repository.auditPage(current), current, repository.auditCount());
    }

    @Transactional(readOnly = true)
    public ExportFile export(Principal principal, String type, ExportRequest request) {
        accounts.requirePlatformAdmin(principal);
        ExportRequest filters = request == null ? new ExportRequest(null, null, null, null, null, null, null, null) : request;
        Instant from = filters.from();
        Instant to = filters.to();
        validateRange(from, to);
        String query = cleanQuery(filters.query());
        List<Map<String, Object>> rows = switch (type) {
            case "orders" -> repository.exportOrders(new PlatformOperationsRepository.OrderFilter(filters.merchantId(), query,
                    status(filters.orderStatus(), ORDER_STATUSES, "INVALID_ORDER_STATUS_FILTER"),
                    status(filters.paymentStatus(), PAYMENT_STATUSES, "INVALID_PAYMENT_STATUS_FILTER"),
                    status(filters.auditStatus(), AUDIT_STATUSES, "INVALID_AUDIT_STATUS_FILTER"), from, to), EXPORT_LIMIT + 1).stream().map(this::maskOrder).toList();
            case "buyers" -> repository.exportCustomers(new PlatformOperationsRepository.CustomerFilter(filters.merchantId(), query, filters.active(), from, to), EXPORT_LIMIT + 1).stream().map(this::maskBuyer).toList();
            case "merchants" -> {
                var range = range(from, to);
                yield repository.exportMerchantPerformance(range.from(), range.to(), EXPORT_LIMIT + 1);
            }
            default -> throw ApiException.badRequest("INVALID_PLATFORM_EXPORT_TYPE", "Choose orders, buyers, or merchants.");
        };
        if (rows.size() > EXPORT_LIMIT) throw ApiException.unprocessable("EXPORT_LIMIT_EXCEEDED", "The export exceeds 10000 rows. Narrow the filters and try again.");
        return new ExportFile(csv(type, rows), type + "-" + LocalDate.now(ZoneOffset.UTC) + ".csv");
    }

    private Map<String, Object> maskOrder(Map<String, Object> source) {
        Map<String, Object> row = new LinkedHashMap<>(source);
        row.put("buyerName", maskName((String) row.get("buyerName")));
        row.put("email", maskEmail((String) row.get("email")));
        row.remove("customerNameSnapshot");
        row.remove("customerEmailSnapshot");
        row.remove("recipientName");
        Object rawEvents = row.get("events");
        if (rawEvents instanceof List<?> events) {
            for (Object rawEvent : events) {
                if (rawEvent instanceof Map<?, ?> event && "customer".equals(event.get("actorType")) && event.get("actorName") instanceof String name) {
                    @SuppressWarnings("unchecked") Map<String, Object> mutableEvent = (Map<String, Object>) event;
                    mutableEvent.put("actorName", maskName(name));
                }
            }
        }
        return row;
    }

    private Map<String, Object> maskBuyer(Map<String, Object> source) {
        Map<String, Object> row = new LinkedHashMap<>(source);
        row.put("customerName", maskName((String) row.get("customerName")));
        row.put("email", maskEmail((String) row.get("email")));
        return row;
    }

    private Map<String, Object> parseAddress(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> camelMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            StringBuilder name = new StringBuilder();
            boolean upper = false;
            for (char c : key.toCharArray()) {
                if (c == '_') upper = true;
                else { name.append(upper ? Character.toUpperCase(c) : c); upper = false; }
            }
            result.put(name.toString(), value);
        });
        return result;
    }

    private String maskName(String name) {
        if (name == null || name.isBlank()) return "—";
        return name.substring(0, 1) + "＊".repeat(Math.min(4, Math.max(1, name.length() - 1)));
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) return "—";
        int at = email.indexOf('@');
        if (at < 1) return "***";
        String local = email.substring(0, at);
        String masked = local.length() == 1 ? "*" : local.substring(0, 1) + "***";
        return masked + email.substring(at);
    }

    private String purpose(String value) {
        if (value == null || value.isBlank() || value.trim().length() < 5 || value.trim().length() > 500)
            throw ApiException.badRequest("PLATFORM_ACCESS_PURPOSE_REQUIRED", "Enter an access purpose between 5 and 500 characters.");
        return value.trim();
    }

    private String cleanQuery(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > 100) throw ApiException.badRequest("INVALID_SEARCH_QUERY", "Search text must be 100 characters or fewer.");
        return trimmed;
    }

    private String status(String value, Set<String> allowed, String code) {
        if (value == null || value.isBlank()) return null;
        String candidate = value.trim();
        if (!allowed.contains(candidate)) throw ApiException.badRequest(code, "Choose a valid status filter.");
        return candidate;
    }

    private void validateRange(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to))
            throw ApiException.badRequest("INVALID_DATE_RANGE", "from must be before to.");
    }

    private DateRange range(Instant from, Instant to) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant end = to == null ? today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC) : to;
        Instant start = from == null ? end.minusSeconds(30L * 24 * 60 * 60) : from;
        if (!start.isBefore(end)) throw ApiException.badRequest("INVALID_DATE_RANGE", "from must be before to.");
        return new DateRange(start, end);
    }

    private byte[] csv(String type, List<Map<String, Object>> rows) {
        List<String> headers = switch (type) {
            case "orders" -> List.of("商户", "订单号", "下单时间", "买家", "邮箱", "订单状态", "审核状态", "付款状态", "订单金额", "已收金额");
            case "buyers" -> List.of("商户", "客户编号", "买家", "邮箱", "账号状态", "注册时间", "订单数", "累计订单金额");
            case "merchants" -> List.of("商户编号", "商户", "状态", "区间订单数", "区间订单金额", "区间已收金额", "待处理订单", "买家数", "新增买家", "最近订单时间");
            default -> throw new IllegalArgumentException("Unsupported platform export type");
        };
        StringBuilder output = new StringBuilder("\ufeff");
        appendCsvLine(output, headers);
        for (Map<String, Object> row : rows) {
            List<Object> values = switch (type) {
                case "orders" -> List.of(orEmpty(row.get("merchantName")), orEmpty(row.get("orderNo")), orEmpty(row.get("createdAt")), orEmpty(row.get("buyerName")), orEmpty(row.get("email")), orEmpty(row.get("orderStatus")), orEmpty(row.get("auditStatus")), orEmpty(row.get("paymentStatus")), orEmpty(row.get("totalAmount")), orEmpty(row.get("paidAmount")));
                case "buyers" -> List.of(orEmpty(row.get("merchantName")), orEmpty(row.get("customerCode")), orEmpty(row.get("customerName")), orEmpty(row.get("email")), isTrue(row.get("active")) ? "启用" : "停用", orEmpty(row.get("createdAt")), orEmpty(row.get("orderCount")), orEmpty(row.get("orderAmount")));
                case "merchants" -> List.of(orEmpty(row.get("merchantCode")), orEmpty(row.get("merchantName")), isTrue(row.get("active")) ? "启用" : "停用", orEmpty(row.get("orderCount")), orEmpty(row.get("grossAmount")), orEmpty(row.get("receivedAmount")), orEmpty(row.get("openOrderCount")), orEmpty(row.get("buyerCount")), orEmpty(row.get("newBuyerCount")), orEmpty(row.get("lastOrderAt")));
                default -> List.of();
            };
            appendCsvLine(output, values);
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Object orEmpty(Object value) { return value == null ? "" : value; }

    private boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0;
    }

    private void appendCsvLine(StringBuilder output, Collection<?> values) {
        boolean first = true;
        for (Object value : values) {
            if (!first) output.append(',');
            first = false;
            output.append(quote(value));
        }
        output.append("\r\n");
    }

    private String quote(Object value) {
        String text = value == null ? "" : value.toString();
        if (!text.isEmpty() && ("=+-@".indexOf(text.charAt(0)) >= 0 || text.charAt(0) < 0x20)) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    public record ExportRequest(Long merchantId, String query, String orderStatus, String paymentStatus,
                                String auditStatus, Boolean active, Instant from, Instant to) {}
    public record ExportFile(byte[] content, String filename) {}
    private record DateRange(Instant from, Instant to) {}
}

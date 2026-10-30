package com.dailyactivity.export.application;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.config.ExportProperties;
import com.dailyactivity.export.domain.port.ExportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ExportService {
    private static final List<String> ORDER_COLUMNS = List.of("订单号", "下单时间", "下单客户", "邮箱", "商品总价", "运费", "优惠额", "订单总额", "支付状态", "订单状态", "运单号", "买家备注", "累计已收", "剩余待收", "包裹数");
    private static final List<String> COMMISSION_COLUMNS = List.of("订单号", "结佣客户", "下单客户", "佣金基数", "锁定佣金率", "佣金额", "订单状态", "佣金状态", "下单时间");
    private static final List<String> BILL_COLUMNS = List.of("账单号", "类型", "方向", "订单日期", "订单号", "客户", "邮箱", "账单金额", "已收/已付金额", "账单状态", "订单状态", "剩余待收/待付");
    private final ExportRepository repository;
    private final AccountService accounts;
    private final ExportProperties properties;

    public ExportService(ExportRepository repository, AccountService accounts, ExportProperties properties) {
        this.repository = repository;
        this.accounts = accounts;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public ExportFile export(Principal principal, String typeValue, ExportRequest request) {
        String type = normalizeType(typeValue);
        requireMenu(principal, type);
        long merchantId = principal.merchantId();
        List<Long> ids = resolveIds(principal.merchantId(), type, request, accounts.commissionEnabled(merchantId));
        if (ids.isEmpty()) throw ApiException.badRequest("EMPTY_EXPORT_SELECTION", "No records match the export selection.");
        List<Map<String, Object>> rows = repository.rows(merchantId, type, ids);
        if (rows.size() > properties.maxRows())
            throw ApiException.unprocessable("EXPORT_LIMIT_EXCEEDED", "The export output exceeds 10000 rows.");
        return new ExportFile(csv(type, rows), filename(type));
    }

    @Transactional(readOnly = true)
    public ExportPreview preview(Principal principal, String typeValue, ExportRequest request) {
        String type = normalizeType(typeValue);
        requireMenu(principal, type);
        long merchantId = principal.merchantId();
        ExportRepository.Filter filter = filter(type, request == null ? null : request.filter(), accounts.commissionEnabled(merchantId), merchantId);
        return new ExportPreview(repository.matchingRecordCount(merchantId, type, filter), repository.outputRowCount(merchantId, type, filter), properties.maxRows());
    }

    private List<Long> resolveIds(long merchantId, String type, ExportRequest request, boolean commissionEnabled) {
        ExportRepository.Filter filter = filter(type, request == null ? null : request.filter(), commissionEnabled, merchantId);
        List<Long> ids = repository.matchingIds(merchantId, type, filter, properties.maxRows() + 1);
        if (ids.size() > properties.maxRows())
            throw ApiException.unprocessable("EXPORT_LIMIT_EXCEEDED", "The export selection exceeds 10000 rows.");
        ensureOutputLimit(merchantId, type, ids);
        return ids;
    }

    private void ensureOutputLimit(long merchantId, String type, List<Long> ids) {
        if (ids.size() > properties.maxRows() || repository.outputRowCount(merchantId, type, ids) > properties.maxRows())
            throw ApiException.unprocessable("EXPORT_LIMIT_EXCEEDED", "The export output exceeds 10000 rows.");
    }

    private byte[] csv(String type, List<Map<String, Object>> rows) {
        StringBuilder output = new StringBuilder("\ufeff");
        List<String> headers = switch (type) {
            case "orders" -> ORDER_COLUMNS;
            case "commissions" -> COMMISSION_COLUMNS;
            case "bills" -> BILL_COLUMNS;
            default -> throw new IllegalArgumentException("Unsupported export type: " + type);
        };
        appendLine(output, headers);
        for (Map<String, Object> row : rows) {
            List<Object> values;
            if ("orders".equals(type)) {
                values = java.util.Arrays.asList(row.get("orderNo"), row.get("createdAt"), row.get("customerName"), row.get("email"), row.get("subtotal"), row.get("shippingFee"), row.get("discountAmount"), row.get("totalAmount"), row.get("paymentStatus"), row.get("orderStatus"), row.get("trackingNo"), row.get("buyerNote"), row.get("paidAmount"), row.get("remainingAmount"), row.get("parcelCount"));
            } else if ("commissions".equals(type)) {
                values = java.util.Arrays.asList(row.get("orderNo"), row.get("referrer"), row.get("customer"), row.get("baseAmount"), row.get("rate"), row.get("commissionAmount"), row.get("orderStatus"), row.get("commissionStatus"), row.get("orderCreatedAt"));
            } else {
                values = java.util.Arrays.asList(row.get("billNo"), row.get("billType"), row.get("direction"), row.get("orderCreatedAt"), row.get("orderNo"), row.get("customer"), row.get("email"), row.get("amount"), row.get("settledAmount"), row.get("status"), row.get("orderStatus"), row.get("remainingAmount"));
            }
            appendLine(output, values);
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendLine(StringBuilder output, Collection<?> values) {
        boolean first = true;
        for (Object value : values) {
            if (!first) output.append(',');
            first = false;
            output.append(quote(value));
        }
        output.append("\r\n");
    }

    private String quote(Object value) {
        if (value == null) return "\"\"";
        String text = value instanceof Instant instant ? instant.toString() : value.toString();
        if (value instanceof String && !text.isEmpty() && ("=+-@".indexOf(text.charAt(0)) >= 0 || text.charAt(0) < 0x20)) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private String filename(String type) {
        String prefix = switch (type) {
            case "orders" -> "order-details";
            case "commissions" -> "commission-details";
            case "bills" -> "billing-details";
            default -> "export";
        };
        return prefix + "-" + DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.of("UTC")).format(Instant.now()) + ".csv";
    }

    private ExportRepository.Filter filter(String type, Map<String, String> raw, boolean commissionEnabled, long merchantId) {
        Map<String, String> values = raw == null ? Map.of() : raw;
        Instant from = instant(values.get("from"), "from");
        Instant to = instant(values.get("to"), "to");
        if (from != null && to != null && !from.isBefore(to)) throw ApiException.badRequest("INVALID_DATE_RANGE", "from must be before to.");
        String billType = values.get("type");
        if ("bills".equals(type) && !commissionEnabled) {
            if ("commission_payable".equals(billType)) accounts.requireCommissionEnabled(merchantId);
            billType = "order_receivable";
        }
        return new ExportRepository.Filter(values.get("status"), values.get("paymentStatus"), limitQuery(values.get("q")), from, to, billType);
    }

    private Instant instant(String value, String name) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_DATE", name + " must be an ISO-8601 instant.");
        }
    }

    private String limitQuery(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    private void requireMenu(Principal principal, String type) {
        if ("commissions".equals(type)) accounts.requireCommissionEnabled(principal.merchantId());
        accounts.requireMenu(principal, switch (type) {
            case "orders" -> "orders";
            case "commissions" -> "commissions";
            case "bills" -> "bills";
            default -> throw new IllegalArgumentException("Unsupported export type: " + type);
        });
    }

    private String normalizeType(String type) {
        if (type == null || !Set.of("orders", "commissions", "bills").contains(type))
            throw ApiException.badRequest("INVALID_EXPORT_TYPE", "type must be orders, commissions or bills.");
        return type;
    }

    public record ExportRequest(Map<String, String> filter) {
    }

    public record ExportFile(byte[] content, String filename) {
    }

    public record ExportPreview(long recordCount, long rowCount, int maxRows) {
    }
}

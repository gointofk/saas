package com.dailyactivity.export.domain.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface ExportRepository {
    List<Long> matchingIds(long merchantId, String type, Filter filter, int limit);

    long matchingRecordCount(long merchantId, String type, Filter filter);

    long outputRowCount(long merchantId, String type, Filter filter);

    int outputRowCount(long merchantId, String type, List<Long> ids);

    List<Map<String, Object>> rows(long merchantId, String type, List<Long> ids);

    record Filter(String status, String paymentStatus, String query, Instant from, Instant to, String billType) {
    }
}

package com.dailyactivity.common;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int pageSize, long total, int totalPages) {
    public static <T> PageResponse<T> of(List<T> items, int page, long total) {
        int pages = Math.max(1, (int) ((total + 9) / 10));
        return new PageResponse<>(items, page, 10, total, pages);
    }
}

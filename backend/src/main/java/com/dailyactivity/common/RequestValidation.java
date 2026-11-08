package com.dailyactivity.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class RequestValidation {
    private RequestValidation() {
    }

    public static void pageSize(Integer pageSize) {
        if (pageSize != null && pageSize != 10)
            throw ApiException.badRequest("INVALID_PAGE_SIZE", "pageSize must be 10.");
    }

    public static int page(Integer page) {
        int value = page == null ? 1 : page;
        if (value < 1) throw ApiException.badRequest("INVALID_PAGE", "page starts at 1.");
        return value;
    }

    public static int positiveQuantity(int value) {
        if (value < 1) throw ApiException.badRequest("INVALID_QUANTITY", "Quantity must be a positive integer.");
        return value;
    }

    public static BigDecimal amount(String value, String field, boolean allowZero) {
        try {
            BigDecimal result = new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
            if (result.signum() < 0 || (!allowZero && result.signum() == 0)) throw new IllegalArgumentException();
            return result;
        } catch (Exception exception) {
            throw ApiException.badRequest("INVALID_AMOUNT", field + " must be a non-negative amount with at most two decimals.");
        }
    }
}

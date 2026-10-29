package com.dailyactivity.auth.domain;

public record Principal(long userId, String userType, long authVersion, long merchantId) {
    public boolean customer() {
        return "customer".equals(userType);
    }

    public boolean seller() {
        return "seller".equals(userType);
    }
}

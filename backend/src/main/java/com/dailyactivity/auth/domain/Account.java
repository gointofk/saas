package com.dailyactivity.auth.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record Account(long id, String userType, String account, String email, String name, String passwordHash,
                      String code, String customerType, String priceTier, boolean active, Long referrerId,
                      BigDecimal commissionRate, long authVersion, Instant lastLoginAt, long merchantId) {
    public boolean customer() { return "customer".equals(userType); }
    public boolean seller() { return "seller".equals(userType); }
}

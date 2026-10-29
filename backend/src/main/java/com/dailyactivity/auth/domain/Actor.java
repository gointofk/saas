package com.dailyactivity.auth.domain;

import java.util.Set;

/** Account identity for audit writes. IDs are only unique within an account type. */
public record Actor(String type, long id) {
    public Actor {
        if (type == null || !Set.of("customer","seller","platform").contains(type) || id < 1)
            throw new IllegalArgumentException("Invalid actor identity.");
    }
}

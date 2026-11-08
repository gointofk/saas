package com.dailyactivity.auth.infrastructure;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import de.mkammerer.argon2.Argon2Factory.Argon2Types;

public final class PasswordHasher {
    private static final Argon2 ARGON2 = Argon2Factory.create(Argon2Types.ARGON2id);

    private PasswordHasher() {
    }

    public static String hash(String raw) {
        if (raw == null || raw.length() < 12 || raw.length() > 64)
            throw new IllegalArgumentException("Password must be 12–64 characters.");

        char[] value = raw.toCharArray();
        try {
            return ARGON2.hash(3, 19456, 1, value);
        } finally {
            ARGON2.wipeArray(value);
        }
    }

    public static boolean matches(String raw, String encoded) {
        if (raw == null || encoded == null) return false;
        char[] value = raw.toCharArray();
        try {
            return ARGON2.verify(encoded, value);
        } finally {
            ARGON2.wipeArray(value);
        }
    }

    public static void main(String[] s) {
        System.out.println(hash("xu0123456789"));
    }
}

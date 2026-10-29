package com.dailyactivity.identity.application;

import java.security.SecureRandom;

public final class CustomerCodeGenerator {
    private static final char[] CODE_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private CustomerCodeGenerator() {
    }

    public static String next() {
        char[] code = new char[3];
        for (int i = 0; i < code.length; i++) code[i] = CODE_CHARS[RANDOM.nextInt(CODE_CHARS.length)];
        return new String(code);
    }
}

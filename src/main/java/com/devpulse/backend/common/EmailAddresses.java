package com.devpulse.backend.common;

import java.util.Locale;

public final class EmailAddresses {

    private EmailAddresses() {
    }

    /**
     * Trimmed and lower-cased, so "Ada@Example.com " and "ada@example.com"
     * are the same account. Locale.ROOT avoids locale-specific lowercasing
     * (e.g. the Turkish dotless i).
     */
    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}

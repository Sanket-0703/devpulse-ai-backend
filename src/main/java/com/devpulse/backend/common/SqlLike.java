package com.devpulse.backend.common;

import java.util.Locale;

/** Building LIKE patterns from user input. */
public final class SqlLike {

    /** The escape character the patterns below use; pass it to cb.like(..., ESCAPE). */
    public static final char ESCAPE = '\\';

    private SqlLike() {
    }

    /**
     * A lower-case "contains" pattern. %, _ and \ in the input are escaped,
     * so a search for "50%" or "my_repo" matches those characters literally.
     */
    public static String containsPattern(String input) {
        String escaped = input.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}

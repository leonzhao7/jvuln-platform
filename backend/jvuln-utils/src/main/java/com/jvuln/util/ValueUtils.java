package com.jvuln.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ValueUtils {

    private ValueUtils() {
    }

    public static String text(String value) {
        return value == null ? "" : value;
    }

    public static String limit(String value, int maxLength) {
        requireNonNegative(maxLength);
        String normalized = text(value);
        return normalized.length() <= maxLength
                ? normalized : normalized.substring(0, maxLength);
    }

    public static String errorMessage(Throwable error, int maxLength) {
        requireNonNegative(maxLength);
        if (error == null) {
            return "";
        }
        String message = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        return limit(message, maxLength);
    }

    public static <T> List<T> immutableList(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }

    private static void requireNonNegative(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Length limit must not be negative");
        }
    }
}

package com.jvuln.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValueUtilsTest {

    @Test
    void normalizesBoundsAndCopiesAuditValues() {
        assertEquals("", ValueUtils.text(null));
        assertEquals("abc", ValueUtils.limit("abcdef", 3));
        assertEquals("abc", ValueUtils.limit("abc", 3));
        assertEquals("failure", ValueUtils.errorMessage(
                new IllegalStateException("failure"), 20));

        List<String> mutable = new ArrayList<>(Arrays.asList("a", "b"));
        List<String> copy = ValueUtils.immutableList(mutable);
        mutable.add("c");
        assertEquals(Arrays.asList("a", "b"), copy);
        assertThrows(UnsupportedOperationException.class, () -> copy.add("d"));
    }

    @Test
    void rejectsNegativeLimits() {
        assertThrows(IllegalArgumentException.class, () -> ValueUtils.limit("abc", -1));
        assertThrows(IllegalArgumentException.class, () -> ValueUtils.errorMessage(
                new RuntimeException("failure"), -1));
    }
}

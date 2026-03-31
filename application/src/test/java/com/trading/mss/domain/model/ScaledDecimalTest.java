package com.trading.mss.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScaledDecimalTest {

    @Test
    void parsesIntegerValue() {
        assertEquals(100_000_000L, ScaledDecimal.parse("1"));
        assertEquals(5_000_000_000L, ScaledDecimal.parse("50"));
    }

    @Test
    void parsesDecimalValue() {
        assertEquals(5_982_795_000_000L, ScaledDecimal.parse("59827.95000000"));
        assertEquals(551_000L, ScaledDecimal.parse("0.00551000"));
    }

    @Test
    void parsesZero() {
        assertEquals(0L, ScaledDecimal.parse("0"));
        assertEquals(0L, ScaledDecimal.parse("0.00000000"));
    }

    @Test
    void parsesSmallFraction() {
        assertEquals(1L, ScaledDecimal.parse("0.00000001"));
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> ScaledDecimal.parse(null));
    }
}

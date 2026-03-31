package com.trading.mss.domain.model;

import java.math.BigDecimal;

public final class ScaledDecimal {

    public static final long SCALE = 100_000_000L;
    public static final int SCALE_DIGITS = 8;
    private static final BigDecimal SCALE_BD = BigDecimal.valueOf(SCALE);

    private ScaledDecimal() {}

    public static long parse(String value) {
        return new BigDecimal(value).multiply(SCALE_BD).longValueExact();
    }

    public static String format(long scaledValue) {
        return BigDecimal.valueOf(scaledValue, SCALE_DIGITS).toPlainString();
    }
}

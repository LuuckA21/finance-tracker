package me.luucka.finance.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Shared arithmetic settings for monetary calculations.
 * <p>
 * Intermediate results keep full {@link MathContext#DECIMAL128} precision; only values
 * returned to clients are rounded with {@link #round(BigDecimal)}.
 */
public final class Money {

    public static final MathContext CONTEXT = MathContext.DECIMAL128;
    public static final int OUTPUT_SCALE = 2;

    private Money() {
    }

    /**
     * Rounds a value for presentation (2 decimals, banker's rounding).
     */
    public static BigDecimal round(BigDecimal value) {
        return value.setScale(OUTPUT_SCALE, RoundingMode.HALF_EVEN);
    }

    /** Drops trailing zeros without switching to scientific notation (1000.000 -> 1000, not 1E+3). */
    public static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    public static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        return a.multiply(b, CONTEXT);
    }
}

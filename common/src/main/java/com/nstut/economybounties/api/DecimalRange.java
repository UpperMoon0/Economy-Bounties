package com.nstut.economybounties.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.SplittableRandom;

/** Inclusive decimal reward range. Values are normalized to two decimal places. */
public record DecimalRange(BigDecimal min, BigDecimal max) {
    public DecimalRange {
        min = normalize(Objects.requireNonNull(min, "min"));
        max = normalize(Objects.requireNonNull(max, "max"));
        if (min.signum() < 0 || max.compareTo(min) < 0) {
            throw new IllegalArgumentException("Invalid decimal range " + min + ".." + max);
        }
    }

    public static DecimalRange fixed(BigDecimal value) {
        return new DecimalRange(value, value);
    }

    public BigDecimal choose(SplittableRandom random) {
        long minCents = min.movePointRight(2).longValueExact();
        long maxCents = max.movePointRight(2).longValueExact();
        if (minCents == maxCents) return min;
        long bound = Math.addExact(Math.subtractExact(maxCents, minCents), 1L);
        long cents = Math.addExact(minCents, random.nextLong(bound));
        return BigDecimal.valueOf(cents, 2);
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
}

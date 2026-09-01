package com.nstut.economybounties.api;

/** Inclusive non-negative integer range used for objective quantities. */
public record LongRange(long min, long max) {
    public LongRange {
        if (min < 0 || max < min) {
            throw new IllegalArgumentException("Invalid range " + min + ".." + max);
        }
    }

    public static LongRange fixed(long value) {
        return new LongRange(value, value);
    }

    public long choose(java.util.SplittableRandom random) {
        if (min == max) return min;
        long bound = Math.addExact(Math.subtractExact(max, min), 1L);
        return Math.addExact(min, random.nextLong(bound));
    }
}

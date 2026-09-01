package com.nstut.economybounties.core;

import com.nstut.economybounties.api.NamespacedId;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class DeterministicSeed {
    private DeterministicSeed() {}

    static long offer(long worldSeed, UUID playerId, NamespacedId group, long rotationEpoch, int rerollOrdinal) {
        long value = mix64(worldSeed);
        value = mix64(value ^ playerId.getMostSignificantBits());
        value = mix64(value ^ playerId.getLeastSignificantBits());
        value = mix64(value ^ fnv1a64(group.toString()));
        value = mix64(value ^ rotationEpoch);
        return mix64(value ^ rerollOrdinal);
    }

    static UUID uuid(java.util.SplittableRandom random) {
        long most = random.nextLong();
        long least = random.nextLong();
        most = (most & 0xffffffffffff0fffL) | 0x0000000000004000L;
        least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(most, least);
    }

    private static long fnv1a64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}

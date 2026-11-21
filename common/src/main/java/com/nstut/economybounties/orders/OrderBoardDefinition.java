package com.nstut.economybounties.orders;

import com.nstut.economybounties.api.LongRange;
import com.nstut.economybounties.api.NamespacedId;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/** Immutable, datapack-defined input and reward pools. No client-provided prices are trusted. */
public record OrderBoardDefinition(
        NamespacedId id, Scope scope, int slots, Duration restock, Duration rotation,
        boolean allowDiscard, long activeFrom, long activeUntil,
        BigDecimal minReward, BigDecimal maxReward,
        List<Input> inputs, List<Prize> prizes
) {
    public enum Scope { PERSONAL, SHARED }
    public enum Timer { NONE, OFFER, ACCEPT }

    public OrderBoardDefinition {
        Objects.requireNonNull(id); Objects.requireNonNull(scope);
        Objects.requireNonNull(restock); Objects.requireNonNull(rotation);
        Objects.requireNonNull(minReward); Objects.requireNonNull(maxReward);
        inputs = List.copyOf(Objects.requireNonNull(inputs));
        prizes = List.copyOf(Objects.requireNonNull(prizes));
        if (slots < 1 || slots > 12) throw new IllegalArgumentException("Order board requires 1..12 slots");
        if (restock.toSeconds() < 5 || restock.toSeconds() > 604800
                || rotation.toSeconds() < 60 || rotation.toSeconds() > 2592000)
            throw new IllegalArgumentException("Order restock/rotation interval outside safe bounds");
        if (inputs.isEmpty() || inputs.size() > 128 || prizes.isEmpty() || prizes.size() > 64)
            throw new IllegalArgumentException("Order pools must have a bounded number of entries");
        if (activeUntil < activeFrom || minReward.signum() < 0
                || maxReward.compareTo(minReward) < 0 || maxReward.compareTo(new BigDecimal("1000000")) > 0)
            throw new IllegalArgumentException("Invalid event or reward bounds");
    }

    public boolean active(Instant now) {
        long at = now.getEpochSecond();
        return at >= activeFrom && at < activeUntil;
    }

    public record Input(String target, LongRange amount, int weight, BigDecimal referencePrice, int minimumLevel) {
        public Input {
            Objects.requireNonNull(target); Objects.requireNonNull(amount); Objects.requireNonNull(referencePrice);
            if (!target.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || target.length() > 128
                    || amount.min() < 1 || amount.max() > 4096
                    || weight < 1 || weight > 10000 || referencePrice.signum() <= 0
                    || referencePrice.compareTo(new BigDecimal("1000000")) > 0 || minimumLevel < 0)
                throw new IllegalArgumentException("Invalid order input " + target);
        }
    }

    public record Prize(String id, String title, String rarity, int weight, BigDecimal multiplier,
                        BigDecimal bonus, Timer timer, Duration duration,
                        int startHourUtc, int endHourUtc) {
        public Prize {
            Objects.requireNonNull(id); Objects.requireNonNull(title); Objects.requireNonNull(rarity);
            Objects.requireNonNull(multiplier); Objects.requireNonNull(bonus);
            Objects.requireNonNull(timer); Objects.requireNonNull(duration);
            if (!id.matches("[a-z0-9_-]{1,40}") || title.isBlank() || title.length() > 60
                    || rarity.isBlank() || rarity.length() > 32
                    || weight < 1 || weight > 10000 || multiplier.signum() < 0
                    || multiplier.compareTo(new BigDecimal("100")) > 0 || bonus.signum() < 0
                    || bonus.compareTo(new BigDecimal("1000000")) > 0
                    || duration.toSeconds() < 60 || duration.toSeconds() > 2592000
                    || startHourUtc < 0 || startHourUtc > 23 || endHourUtc < 0 || endHourUtc > 24)
                throw new IllegalArgumentException("Invalid reward tier " + id);
        }

        public boolean active(Instant now) {
            int hour = now.atOffset(ZoneOffset.UTC).getHour();
            return startHourUtc == endHourUtc || (startHourUtc < endHourUtc
                    ? hour >= startHourUtc && hour < endHourUtc
                    : hour >= startHourUtc || hour < endHourUtc);
        }
    }
}

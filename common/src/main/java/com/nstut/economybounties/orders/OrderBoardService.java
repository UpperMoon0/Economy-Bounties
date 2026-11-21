package com.nstut.economybounties.orders;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nstut.economybounties.api.*;
import com.nstut.economybounties.core.DefaultBountyService;

import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Server-owned automatic order board. It owns only the rotating *offers*, not
 * the bounty/payment lifecycle. Accepted offers become normal persisted
 * DefaultBountyService instances, retaining scoped delivery and idempotent pay.
 *
 * Persistence order: lock slot -> fsync/atomic rename -> grant bounty -> mark
 * slot taken. After a crash, the same claimant's pending grant is reconciled
 * using an instance UUID derived from (offer UUID, player UUID), so no offer
 * can be awarded to two players and grants cannot be duplicated.
 */
public final class OrderBoardService {
    private static final UUID SHARED = new UUID(0, 0);
    private static final long NO_DEADLINE_SECONDS = 3650L * 86400L;
    private static final Gson GSON = new Gson();
    private final Path dataRoot;
    private final long worldSeed;
    private final DefaultBountyService generated;
    private final ValueProvider valueProvider;
    private final Map<NamespacedId, OrderBoardDefinition> definitions = new LinkedHashMap<>();
    private final Map<Key, State> states = new LinkedHashMap<>();

    /** Allows an Economy market quote provider to override configured safe reference prices. */
    @FunctionalInterface
    public interface ValueProvider {
        BigDecimal unitValue(OrderBoardDefinition.Input input);
    }

    public record Card(
            String board, String scope, int slot, String offerId, String title,
            String item, long quantity, String reward, String rarity, String timer,
            String status, long deadlineSeconds, long expiresAt, long restocksAt,
            boolean canAccept, boolean canDiscard
    ) { }

    public OrderBoardService(Path dataRoot, long worldSeed, DefaultBountyService generated) {
        this(dataRoot, worldSeed, generated, OrderBoardDefinition.Input::referencePrice);
    }

    public OrderBoardService(Path dataRoot, long worldSeed, DefaultBountyService generated, ValueProvider provider) {
        this.dataRoot = Objects.requireNonNull(dataRoot).toAbsolutePath().normalize();
        this.worldSeed = worldSeed;
        this.generated = Objects.requireNonNull(generated);
        this.valueProvider = Objects.requireNonNull(provider);
    }

    public synchronized void replaceDefinitions(Collection<OrderBoardDefinition> values) {
        Map<NamespacedId,OrderBoardDefinition> copy = new LinkedHashMap<>();
        for (OrderBoardDefinition value : values) {
            if (copy.putIfAbsent(value.id(), value) != null)
                throw new IllegalArgumentException("Duplicate order board " + value.id());
        }
        definitions.clear();
        definitions.putAll(copy);
        states.keySet().removeIf(key -> !definitions.containsKey(key.board()));
    }

    public synchronized List<Card> list(UUID player, int level, Instant now) {
        List<Card> result = new ArrayList<>();
        for (OrderBoardDefinition board : definitions.values()) {
            if (!board.active(now)) continue;
            State state = state(board, player);
            reconcile(board, state, level, now);
            for (Slot slot : state.slots) {
                boolean available = slot.offer != null && slot.claimant == null;
                String status = available ? "AVAILABLE" : slot.claimant != null
                        ? (slot.claimant.equals(player) ? "TAKEN" : "CLAIMED") : "RESTOCKING";
                result.add(new Card(board.id().toString(), board.scope().name(), slot.index,
                        slot.offer == null ? "" : slot.offer.toString(),
                        slot.title, slot.item, slot.quantity, slot.reward.toPlainString(),
                        slot.rarity, slot.timer.name(), status,
                        slot.duration, slot.expires, slot.restockAt, available,
                        available && board.allowDiscard() && board.scope() == OrderBoardDefinition.Scope.PERSONAL));
            }
        }
        return List.copyOf(result);
    }

    public synchronized String accept(UUID player, int level, NamespacedId boardId, int index,
                                      UUID offer, Instant now) {
        OrderBoardDefinition board = definitions.get(boardId);
        if (board == null || !board.active(now)) return "This order board is not available";
        State state = state(board, player);
        reconcile(board, state, level, now);
        if (index < 0 || index >= state.slots.size()) return "Invalid order slot";
        Slot slot = state.slots.get(index);
        if (slot.offer == null || !slot.offer.equals(offer)) return "This order has rotated; refresh the board";
        if (slot.claimant != null && !slot.claimant.equals(player)) return "Another player already accepted this order";
        if (slot.claimant == null) {
            long outstanding = generated.list(player, now).stream()
                    .filter(view -> view.status() == BountyStatus.OFFERED
                            || view.status() == BountyStatus.ACTIVE
                            || view.status() == BountyStatus.COMPLETED).count();
            if (outstanding >= 24) return "Finish or claim existing contracts before accepting more orders";
        }
        if (slot.claimant != null && slot.granted) return "Order already accepted; see Generated contracts";
        if (slot.claimant == null) {
            slot.claimant = player;
            slot.restockAt = now.getEpochSecond() + board.restock().toSeconds();
            save(board, state);
        }
        return finishClaim(board, state, slot, now) ?
                "Order accepted. Deliver items and claim the reward in Generated contracts" :
                "Order reserved. Grant is pending; refresh to retry safely";
    }

    public synchronized String discard(UUID player, int level, NamespacedId boardId, int index,
                                       UUID offer, Instant now) {
        OrderBoardDefinition board = definitions.get(boardId);
        if (board == null || board.scope() != OrderBoardDefinition.Scope.PERSONAL || !board.allowDiscard())
            return "This order cannot be discarded";
        State state = state(board, player);
        reconcile(board, state, level, now);
        if (index < 0 || index >= state.slots.size()) return "Invalid order slot";
        Slot slot = state.slots.get(index);
        if (slot.offer == null || !slot.offer.equals(offer) || slot.claimant != null)
            return "This order is no longer available";
        clear(slot, now.getEpochSecond() + board.restock().toSeconds());
        save(board, state);
        return "Order discarded; a replacement will arrive after the restock cooldown";
    }

    /** Called while loading a board and periodically by the server. No client triggers payout. */
    public synchronized void refresh(UUID player, int level, Instant now) {
        for (OrderBoardDefinition board : definitions.values()) {
            if (!board.active(now)) continue;
            reconcile(board, state(board, player), level, now);
        }
    }

    private void reconcile(OrderBoardDefinition board, State state, int level, Instant now) {
        boolean dirty = false;
        int count = board.slots();
        while (state.slots.size() > count) { state.slots.remove(state.slots.size()-1); dirty = true; }
        while (state.slots.size() < count) {
            state.slots.add(new Slot(state.slots.size()));
            dirty = true;
        }
        long epoch = now.getEpochSecond();
        for (Slot slot : state.slots) {
            if (slot.claimant != null && !slot.granted) {
                if (finishClaim(board, state, slot, now)) dirty = true;
            }
            if (slot.claimant != null && slot.granted && epoch >= slot.restockAt) {
                clear(slot, epoch);
                dirty = true;
            }
            if (slot.offer != null && slot.claimant == null
                    && (epoch >= slot.expires || epoch >= slot.rotationAt)) {
                clear(slot, epoch + board.restock().toSeconds());
                dirty = true;
            }
            if (slot.offer == null && epoch >= slot.restockAt) {
                if (generate(board, state, slot, level, now)) dirty = true;
            }
        }
        if (dirty) save(board, state);
    }

    private boolean generate(OrderBoardDefinition board, State state, Slot slot, int level, Instant now) {
        List<OrderBoardDefinition.Input> inputs = board.inputs().stream()
                .filter(i -> level >= i.minimumLevel()).toList();
        List<OrderBoardDefinition.Prize> prizes = board.prizes().stream()
                .filter(r -> r.active(now)).toList();
        if (inputs.isEmpty() || prizes.isEmpty()) {
            slot.restockAt = now.getEpochSecond() + Math.max(60, board.restock().toSeconds());
            return true;
        }
        long seed = worldSeed ^ board.id().toString().hashCode() * 0x9e3779b97f4a7c15L
                ^ state.owner.getMostSignificantBits() ^ state.owner.getLeastSignificantBits()
                ^ ((long) slot.index << 32) ^ (++state.counter * 0xbf58476d1ce4e5b9L);
        SplittableRandom rng = new SplittableRandom(seed);
        OrderBoardDefinition.Input input = pick(inputs, rng, OrderBoardDefinition.Input::weight);
        OrderBoardDefinition.Prize prize = pick(prizes, rng, OrderBoardDefinition.Prize::weight);
        long quantity = input.amount().choose(rng);
        BigDecimal unit = valueProvider.unitValue(input);
        // Live Market asks/bids are manipulable. Bounded quote anchoring prevents
        // price-wash or self-trading from minting disproportionate order rewards.
        BigDecimal reference = input.referencePrice();
        if (unit == null || unit.signum() <= 0
                || unit.compareTo(reference.multiply(new BigDecimal("10"))) > 0) {
            unit = reference;
        } else {
            unit = unit.max(reference.multiply(new BigDecimal("0.50")))
                    .min(reference.multiply(new BigDecimal("2.00")));
        }
        BigDecimal amount = unit.multiply(BigDecimal.valueOf(quantity))
                .multiply(prize.multiplier()).add(prize.bonus())
                .max(board.minReward()).min(board.maxReward()).setScale(2, RoundingMode.HALF_UP);
        slot.generation = state.counter;
        slot.offer = UUID.nameUUIDFromBytes((board.id() + ":" + state.owner + ":" + slot.index +
                ":" + slot.generation).getBytes(StandardCharsets.UTF_8));
        slot.item = input.target();
        slot.quantity = quantity;
        slot.reward = amount;
        slot.rarity = prize.rarity();
        slot.title = prize.title();
        slot.timer = prize.timer();
        slot.duration = prize.duration().getSeconds();
        slot.created = now.getEpochSecond();
        slot.expires = switch (slot.timer) {
            case OFFER -> slot.created + slot.duration;
            case ACCEPT, NONE -> slot.created + board.rotation().getSeconds();
        };
        slot.rotationAt = slot.created + board.rotation().getSeconds();
        slot.restockAt = 0;
        slot.claimant = null; slot.granted = false;
        return true;
    }

    private boolean finishClaim(OrderBoardDefinition board, State state, Slot slot, Instant now) {
        if (slot.claimant == null || slot.offer == null) return false;
        UUID instance = instanceId(slot, slot.claimant);
        long expires = switch (slot.timer) {
            case NONE -> now.getEpochSecond() + NO_DEADLINE_SECONDS;
            case ACCEPT -> now.getEpochSecond() + slot.duration;
            case OFFER -> slot.created + slot.duration;
        };
        if (now.getEpochSecond() >= expires) {
            // A crash during lock->grant may leave a pending order after its
            // offer deadline. If the bounty was granted, preserve it; if not,
            // release the stale lock after cooldown instead of blocking forever.
            if (generated.get(slot.claimant, instance, now).isPresent()) {
                slot.granted = true;
                save(board, state);
                return true;
            }
            clear(slot, now.getEpochSecond() + board.restock().toSeconds());
            save(board, state);
            return false;
        }
        BountyDefinition bounty = new BountyDefinition(
                NamespacedId.parse("economy_bounties:order_" + slot.offer.toString().replace("-", "")),
                NamespacedId.parse("economy_bounties:orders"),
                1, 0, Integer.MAX_VALUE, 1,
                List.of(new ObjectiveDefinition(BuiltinObjectiveTypes.DELIVER_ITEM, slot.item,
                        LongRange.fixed(slot.quantity), Map.of())),
                new RewardDefinition(DecimalRange.fixed(slot.reward),
                        Map.of("funding","mint","order_board", board.id().toString(),"order_rarity",slot.rarity)),
                Duration.ofSeconds(Math.max(1,expires - now.getEpochSecond())), Duration.ZERO,
                java.util.Set.of("automatic_order",slot.rarity.toLowerCase(java.util.Locale.ROOT)));
        try {
            generated.grant(slot.claimant, instance, bounty, now, Instant.ofEpochSecond(expires));
        } catch (RuntimeException error) {
            System.getLogger(OrderBoardService.class.getName()).log(System.Logger.Level.WARNING,
                    "Deferred order grant recovery: " + error.getClass().getSimpleName());
            return false;
        }
        slot.granted = true;
        save(board,state);
        return true;
    }

    private static UUID instanceId(Slot slot, UUID player) {
        return UUID.nameUUIDFromBytes((slot.offer + ":" + player).getBytes(StandardCharsets.UTF_8));
    }

    private static void clear(Slot slot, long restockAt) {
        slot.offer = null; slot.claimant = null; slot.granted = false;
        slot.restockAt = restockAt; slot.item = ""; slot.quantity = 0;
        slot.title = "Next order"; slot.reward = BigDecimal.ZERO; slot.rarity = "";
    }

    private static <T> T pick(List<T> values, SplittableRandom rng, java.util.function.ToIntFunction<T> weight) {
        long total = 0;
        for (T value : values) total += weight.applyAsInt(value);
        long roll = rng.nextLong(total);
        for (T value : values) {
            if (roll < weight.applyAsInt(value)) return value;
            roll -= weight.applyAsInt(value);
        }
        throw new IllegalStateException("Weighted selection failed");
    }

    private State state(OrderBoardDefinition board, UUID player) {
        UUID owner = board.scope() == OrderBoardDefinition.Scope.SHARED ? SHARED : player;
        Key key = new Key(board.id(), owner);
        return states.computeIfAbsent(key, ignored -> load(board, owner));
    }

    private record Key(NamespacedId board,UUID owner) {}
    private static final class State {
        final UUID owner;
        long counter;
        final List<Slot> slots = new ArrayList<>();
        State(UUID owner) { this.owner = owner; }
    }
    private static final class Slot {
        final int index;
        long generation, quantity, duration, created, expires, rotationAt, restockAt;
        UUID offer, claimant;
        String item = "", title = "Next order", rarity = "";
        BigDecimal reward = BigDecimal.ZERO;
        OrderBoardDefinition.Timer timer = OrderBoardDefinition.Timer.NONE;
        boolean granted;
        Slot(int index) { this.index = index; }
    }

    private Path path(OrderBoardDefinition board, UUID owner) {
        // ResourceLocation already constrains components, but normalize and confine too.
        String folder = board.id().toString().replace(':', '_').replace('/', '_');
        Path target = dataRoot.resolve("order_boards").resolve(folder)
                .resolve(owner.equals(SHARED) ? "shared.json" : owner + ".json").normalize();
        if (!target.startsWith(dataRoot)) throw new IllegalArgumentException("Invalid board path");
        return target;
    }

    private State load(OrderBoardDefinition board, UUID owner) {
        State state = new State(owner);
        Path path = path(board,owner);
        if (!Files.isRegularFile(path)) return state;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!owner.toString().equals(root.get("owner").getAsString()))
                throw new IllegalStateException("Order board state belongs to another owner");
            state.counter = root.get("counter").getAsLong();
            for (var value : root.getAsJsonArray("slots")) {
                JsonObject j = value.getAsJsonObject();
                Slot slot = new Slot(j.get("index").getAsInt());
                if (slot.index != state.slots.size()) throw new IllegalStateException("Order slots out of order");
                slot.generation = j.get("generation").getAsLong();
                slot.quantity = j.get("quantity").getAsLong();
                slot.duration = j.get("duration").getAsLong();
                slot.created = j.get("created").getAsLong();
                slot.expires = j.get("expires").getAsLong();
                slot.rotationAt = j.get("rotation_at").getAsLong();
                slot.restockAt = j.get("restock_at").getAsLong();
                slot.item = j.get("item").getAsString();
                slot.title = j.get("title").getAsString();
                slot.rarity = j.get("rarity").getAsString();
                slot.reward = new BigDecimal(j.get("reward").getAsString());
                slot.timer = OrderBoardDefinition.Timer.valueOf(j.get("timer").getAsString());
                slot.offer = optionalUuid(j,"offer");
                slot.claimant = optionalUuid(j,"claimant");
                slot.granted = j.get("granted").getAsBoolean();
                state.slots.add(slot);
            }
            return state;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot load persisted order board " + path, error);
        }
    }
    private static UUID optionalUuid(JsonObject j,String key) {
        String value = j.get(key).getAsString();
        return value.isEmpty() ? null : UUID.fromString(value);
    }

    private void save(OrderBoardDefinition board, State state) {
        Path path = path(board,state.owner);
        JsonObject root = new JsonObject();
        root.addProperty("version",1);
        root.addProperty("owner",state.owner.toString());
        root.addProperty("counter",state.counter);
        JsonArray slots = new JsonArray();
        for (Slot slot : state.slots) {
            JsonObject j = new JsonObject();
            j.addProperty("index",slot.index);j.addProperty("generation",slot.generation);
            j.addProperty("quantity",slot.quantity);j.addProperty("duration",slot.duration);
            j.addProperty("created",slot.created);j.addProperty("expires",slot.expires);
            j.addProperty("rotation_at",slot.rotationAt);j.addProperty("restock_at",slot.restockAt);
            j.addProperty("item",slot.item);j.addProperty("title",slot.title);
            j.addProperty("rarity",slot.rarity);j.addProperty("reward",slot.reward.toPlainString());
            j.addProperty("timer",slot.timer.name());
            j.addProperty("offer",slot.offer == null ? "" : slot.offer.toString());
            j.addProperty("claimant",slot.claimant == null ? "" : slot.claimant.toString());
            j.addProperty("granted",slot.granted);
            slots.add(j);
        }
        root.add("slots",slots);
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp,GSON.toJson(root),StandardCharsets.UTF_8);
            try { Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot persist order board " + path,error);
        }
    }
}

package com.nstut.economybounties.minecraft;

import com.nstut.economybounties.api.*;
import com.nstut.economybounties.core.DefaultBountyService;
import com.nstut.economybounties.core.DefaultPostedBountyService;
import com.nstut.economybounties.data.JsonDirectoryBountyStateStore;
import com.nstut.economybounties.data.JsonFilePostedBountyStore;
import dev.architectury.event.events.common.LifecycleEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Shared server runtime for the 1.20.1 and 1.21.1 loader families. */
public final class EconomyBountiesRuntime {
    public static final String MOD_ID = "economy_bounties";
    private static final ObjectiveRegistry OBJECTIVES = new ObjectiveRegistry();
    private static volatile List<BountyDefinition> definitions = List.of();
    private static volatile Map<NamespacedId, BountyPoolDefinition> pools = Map.of();
    private static volatile MinecraftServer server;
    private static volatile DefaultBountyService generated;
    private static volatile DefaultPostedBountyService posted;
    private static boolean initialized;

    private EconomyBountiesRuntime() { }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        BuiltinObjectiveTypes.registerAll(OBJECTIVES);
        BountyDataReloadListener.register();
        BountyObjectiveEvents.register();
        LifecycleEvent.SERVER_STARTED.register(EconomyBountiesRuntime::start);
        LifecycleEvent.SERVER_STOPPED.register(ignored -> stop());
    }

    private static synchronized void start(MinecraftServer minecraftServer) {
        server = Objects.requireNonNull(minecraftServer, "minecraftServer");
        Path dataRoot = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(MOD_ID);
        EconomyMoneyAdapter money = new EconomyMoneyAdapter();
        ProgressionProvider progression = (playerId, group) -> MinecraftAudienceProvider.experienceLevel(server, playerId);
        generated = new DefaultBountyService(progression, money, OBJECTIVES,
                new JsonDirectoryBountyStateStore(dataRoot.resolve("players")), 12);
        generated.replaceDefinitions(definitions);
        posted = new DefaultPostedBountyService(new MinecraftAudienceProvider(server), OBJECTIVES,
                new JsonFilePostedBountyStore(dataRoot.resolve("posted_bounties.json")), money);
        posted.recover(Instant.now());
    }

    private static synchronized void stop() { posted = null; generated = null; server = null; }

    public static synchronized void replaceData(Collection<BountyDefinition> loadedDefinitions,
                                                Map<NamespacedId, BountyPoolDefinition> loadedPools) {
        definitions = List.copyOf(loadedDefinitions);
        pools = Map.copyOf(loadedPools);
        if (generated != null) generated.replaceDefinitions(definitions);
    }

    public static boolean ready() { return server != null && generated != null && posted != null; }
    public static MinecraftServer server() { return require(server, "server"); }
    public static BountyService generated() { return require(generated, "generated bounty service"); }
    public static PostedBountyService posted() { return require(posted, "posted bounty service"); }
    public static ObjectiveRegistry objectives() { return OBJECTIVES; }
    public static Map<NamespacedId, BountyPoolDefinition> pools() { return pools; }

    public static List<BountyView> generatedFor(UUID playerId, Instant now) {
        return ready() ? generated.list(playerId, now) : List.of();
    }
    public static List<PostedBountyView> postedFor(UUID playerId, Instant now) {
        return ready() ? posted.listVisible(playerId, now) : List.of();
    }

    public static void recordProgress(ProgressEvent event) {
        if (!ready()) return;
        Instant now = Instant.now();
        generated.recordProgress(event, now);
        posted.recordProgress(event, now);
    }

    public static Optional<BountyView> roll(ServerPlayer player, NamespacedId poolId) {
        if (!ready()) return Optional.empty();
        BountyPoolDefinition pool = pools.get(poolId);
        if (pool == null) return Optional.empty();
        Instant now = Instant.now();
        int ordinal = Math.toIntExact(Math.min(Integer.MAX_VALUE, generated.list(player.getUUID(), now).size()));
        long epoch = Math.floorDiv(now.getEpochSecond(), 86_400L);
        return generated.rollOffer(player.getUUID(), pool,
                new BountyService.RollContext(server.overworld().getSeed(), epoch, ordinal, now));
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalStateException("Economy Bounties " + name + " is not ready");
        return value;
    }
}

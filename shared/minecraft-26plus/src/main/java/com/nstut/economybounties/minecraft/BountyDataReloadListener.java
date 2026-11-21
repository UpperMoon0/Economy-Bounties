package com.nstut.economybounties.minecraft;

import com.nstut.economybounties.api.BountyDefinition;
import com.nstut.economybounties.api.BountyPoolDefinition;
import com.nstut.economybounties.api.NamespacedId;
import com.nstut.economybounties.data.BountyJsonCodec;
import com.nstut.economybounties.data.BountyPoolJsonCodec;
import com.nstut.economybounties.orders.OrderBoardDefinition;
import com.nstut.economybounties.orders.OrderBoardJsonCodec;
import dev.architectury.registry.ReloadListenerRegistry;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Atomically reloads data/economy_bounties/{bounties,bounty_pools} JSON. */
public final class BountyDataReloadListener extends SimplePreparableReloadListener<BountyDataReloadListener.Loaded> {
    private static boolean registered;

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ReloadListenerRegistry.register(PackType.SERVER_DATA, new BountyDataReloadListener());
    }

    @Override
    protected Loaded prepare(ResourceManager manager, ProfilerFiller profiler) {
        BountyJsonCodec bountyCodec = new BountyJsonCodec();
        BountyPoolJsonCodec poolCodec = new BountyPoolJsonCodec();
        OrderBoardJsonCodec orderCodec = new OrderBoardJsonCodec();
        Map<NamespacedId, OrderBoardDefinition> boards = new LinkedHashMap<>();
        Map<NamespacedId, BountyDefinition> definitions = new LinkedHashMap<>();
        Map<NamespacedId, BountyPoolDefinition> pools = new LinkedHashMap<>();
        manager.listResources("bounties", id -> id.getPath().endsWith(".json"))
                .forEach((id, resource) -> {
                    try (Reader reader = resource.openAsReader()) {
                        BountyDefinition value = bountyCodec.decode(reader);
                        if (definitions.putIfAbsent(value.id(), value) != null) throw new IllegalArgumentException("Duplicate bounty id " + value.id());
                    } catch (IOException error) {
                        throw new IllegalStateException("Failed to read " + id, error);
                    }
                });
        manager.listResources("bounty_pools", id -> id.getPath().endsWith(".json"))
                .forEach((id, resource) -> {
                    try (Reader reader = resource.openAsReader()) {
                        BountyPoolDefinition value = poolCodec.decode(reader);
                        if (pools.putIfAbsent(value.id(), value) != null) throw new IllegalArgumentException("Duplicate bounty pool id " + value.id());
                    } catch (IOException error) {
                        throw new IllegalStateException("Failed to read " + id, error);
                    }
                });
        manager.listResources("order_boards", id -> id.getPath().endsWith(".json"))
                .forEach((id, resource) -> {
                    try (Reader reader = resource.openAsReader()) {
                        OrderBoardDefinition value = orderCodec.decode(reader);
                        if (boards.putIfAbsent(value.id(), value) != null)
                            throw new IllegalArgumentException("Duplicate order board " + value.id());
                    } catch (IOException error) {
                        throw new IllegalStateException("Failed to read order board " + id, error);
                    }
                });
        return new Loaded(List.copyOf(definitions.values()), Map.copyOf(pools),
                List.copyOf(boards.values()));
    }

    @Override protected void apply(Loaded loaded, ResourceManager manager, ProfilerFiller profiler) {
        EconomyBountiesRuntime.replaceData(loaded.definitions(), loaded.pools(), loaded.boards());
        System.getLogger(BountyDataReloadListener.class.getName()).log(System.Logger.Level.INFO,
                "Loaded " + loaded.definitions().size() + " Economy Bounties definitions and "
                        + loaded.pools().size() + " bounty pools");
    }

    public record Loaded(List<BountyDefinition> definitions, Map<NamespacedId, BountyPoolDefinition> pools,
                         List<OrderBoardDefinition> boards) { }
}

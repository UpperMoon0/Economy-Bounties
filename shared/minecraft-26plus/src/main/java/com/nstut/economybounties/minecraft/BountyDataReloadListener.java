package com.nstut.economybounties.minecraft;

import com.nstut.economybounties.api.BountyDefinition;
import com.nstut.economybounties.api.BountyPoolDefinition;
import com.nstut.economybounties.api.NamespacedId;
import com.nstut.economybounties.data.BountyJsonCodec;
import com.nstut.economybounties.data.BountyPoolJsonCodec;
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
        Map<NamespacedId, BountyDefinition> definitions = new LinkedHashMap<>();
        Map<NamespacedId, BountyPoolDefinition> pools = new LinkedHashMap<>();
        manager.listResources("economy_bounties/bounties", id -> id.getPath().endsWith(".json"))
                .forEach((id, resource) -> {
                    try (Reader reader = resource.openAsReader()) {
                        BountyDefinition value = bountyCodec.decode(reader);
                        if (definitions.putIfAbsent(value.id(), value) != null) throw new IllegalArgumentException("Duplicate bounty id " + value.id());
                    } catch (IOException error) {
                        throw new IllegalStateException("Failed to read " + id, error);
                    }
                });
        manager.listResources("economy_bounties/bounty_pools", id -> id.getPath().endsWith(".json"))
                .forEach((id, resource) -> {
                    try (Reader reader = resource.openAsReader()) {
                        BountyPoolDefinition value = poolCodec.decode(reader);
                        if (pools.putIfAbsent(value.id(), value) != null) throw new IllegalArgumentException("Duplicate bounty pool id " + value.id());
                    } catch (IOException error) {
                        throw new IllegalStateException("Failed to read " + id, error);
                    }
                });
        return new Loaded(List.copyOf(definitions.values()), Map.copyOf(pools));
    }

    @Override protected void apply(Loaded loaded, ResourceManager manager, ProfilerFiller profiler) {
        EconomyBountiesRuntime.replaceData(loaded.definitions(), loaded.pools());
    }

    public record Loaded(List<BountyDefinition> definitions, Map<NamespacedId, BountyPoolDefinition> pools) { }
}

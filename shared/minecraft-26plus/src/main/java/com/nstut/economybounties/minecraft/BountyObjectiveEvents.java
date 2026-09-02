package com.nstut.economybounties.minecraft;

import com.nstut.economybounties.api.BuiltinObjectiveTypes;
import com.nstut.economybounties.api.ProgressEvent;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.BlockEvent;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/** Converts vanilla gameplay into loader-neutral bounty progress events on Minecraft 26.1.x. */
public final class BountyObjectiveEvents {
    private static boolean registered;
    private BountyObjectiveEvents() { }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            if (source.getEntity() instanceof ServerPlayer player) {
                String target = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
                EconomyBountiesRuntime.recordProgress(new ProgressEvent(player.getUUID(), BuiltinObjectiveTypes.KILL_ENTITY, target, 1, Map.of()));
            }
            return EventResult.pass();
        });
        BlockEvent.BREAK.register((level, pos, state, player, xp) -> {
            String target = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            EconomyBountiesRuntime.recordProgress(new ProgressEvent(player.getUUID(), BuiltinObjectiveTypes.MINE_BLOCK, target, 1, Map.of()));
            return EventResult.pass();
        });
        PlayerEvent.CRAFT_ITEM.register((player, stack, inventory) -> {
            if (player instanceof ServerPlayer serverPlayer && !stack.isEmpty()) {
                String target = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                EconomyBountiesRuntime.recordProgress(new ProgressEvent(serverPlayer.getUUID(), BuiltinObjectiveTypes.CRAFT_ITEM,
                        target, Math.max(1, stack.getCount()), Map.of()));
            }
        });
        PlayerEvent.CHANGE_DIMENSION.register((player, oldLevel, newLevel) ->
                EconomyBountiesRuntime.recordProgress(new ProgressEvent(player.getUUID(), BuiltinObjectiveTypes.VISIT_LOCATION,
                        newLevel.identifier().toString(), 1, Map.of("kind", "dimension"))));
        TickEvent.PLAYER_POST.register(player -> {
            if (player instanceof ServerPlayer serverPlayer && serverPlayer.tickCount % 20 == 0) {
                EconomyBountiesRuntime.recordProgress(new ProgressEvent(serverPlayer.getUUID(), BuiltinObjectiveTypes.VISIT_LOCATION,
                        serverPlayer.level().dimension().identifier().toString(), 1, Map.of("kind", "dimension")));
            }
        });
    }
}

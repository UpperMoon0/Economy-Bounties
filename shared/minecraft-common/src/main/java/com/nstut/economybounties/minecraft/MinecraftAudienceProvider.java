package com.nstut.economybounties.minecraft;

import com.nstut.economybounties.api.AudienceProvider;
import com.nstut.economybounties.api.NamespacedId;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.UUID;

/** Built-in audience resolver: player UUIDs plus scoreboard team/tag groups and XP level. */
public final class MinecraftAudienceProvider implements AudienceProvider {
    private final MinecraftServer server;

    public MinecraftAudienceProvider(MinecraftServer server) { this.server = Objects.requireNonNull(server, "server"); }

    @Override
    public boolean isGroupMember(UUID playerId, String groupId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null || groupId == null || groupId.isBlank()) return false;
        String raw = groupId.trim();
        if (raw.startsWith("team:")) return player.getTeam() != null && raw.substring(5).equals(player.getTeam().getName());
        if (raw.startsWith("tag:")) return player.getTags().contains(raw.substring(4));
        return player.getTeam() != null && raw.equals(player.getTeam().getName()) || player.getTags().contains(raw);
    }

    @Override public int progressionLevel(UUID playerId, NamespacedId progressionGroup) { return experienceLevel(server, playerId); }

    public static int experienceLevel(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player == null ? 0 : Math.max(0, player.experienceLevel);
    }
}

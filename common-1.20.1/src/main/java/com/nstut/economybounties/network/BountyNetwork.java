package com.nstut.economybounties.network;

import com.nstut.economybounties.board.BoardCodec;
import com.nstut.economybounties.board.BoardRequest;
import com.nstut.economybounties.board.BoardSnapshot;
import com.nstut.economybounties.client.BountyBoardClient;
import com.nstut.economybounties.minecraft.BountyBoardServer;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Architectury's pre-payload networking API used by Minecraft 1.20.1. */
public final class BountyNetwork {
    private static final ResourceLocation REQUEST = new ResourceLocation("economy_bounties", "board_request");
    private static final ResourceLocation SNAPSHOT = new ResourceLocation("economy_bounties", "board_snapshot");
    private static boolean commonRegistered;
    private static boolean clientRegistered;

    private BountyNetwork() { }

    public static synchronized void registerCommon() {
        if (commonRegistered) return;
        commonRegistered = true;
        NetworkManager.registerReceiver(NetworkManager.c2s(), REQUEST, (buf, context) -> {
            String json = buf.readUtf(BoardCodec.MAX_JSON_CHARS);
            BoardRequest request;
            try { request = BoardCodec.decodeRequest(json); }
            catch (RuntimeException malformed) { return; }
            ServerPlayer player = (ServerPlayer) context.getPlayer();
            context.queue(() -> BountyBoardServer.handle(player, request));
        });
    }

    public static synchronized void registerClient() {
        if (clientRegistered) return;
        clientRegistered = true;
        NetworkManager.registerReceiver(NetworkManager.s2c(), SNAPSHOT, (buf, context) -> {
            String json = buf.readUtf(BoardCodec.MAX_JSON_CHARS);
            BoardSnapshot snapshot;
            try { snapshot = BoardCodec.decodeSnapshot(json); }
            catch (RuntimeException malformed) { return; }
            context.queue(() -> BountyBoardClient.receive(snapshot));
        });
    }

    public static void sendRequest(BoardRequest request) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf(BoardCodec.encodeRequest(request), BoardCodec.MAX_JSON_CHARS);
        NetworkManager.sendToServer(REQUEST, buf);
    }

    public static void sendSnapshot(ServerPlayer player, BoardSnapshot snapshot) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf(BoardCodec.encodeSnapshot(snapshot), BoardCodec.MAX_JSON_CHARS);
        NetworkManager.sendToPlayer(player, SNAPSHOT, buf);
    }
}

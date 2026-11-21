package com.nstut.economybounties.network;

import com.nstut.economybounties.board.BoardCodec;
import com.nstut.economybounties.board.BoardRequest;
import com.nstut.economybounties.board.BoardSnapshot;
import com.nstut.economybounties.minecraft.BountyBoardServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/** Typed-payload networking for Minecraft 1.21.1. */
public final class BountyNetwork {
    private static final NetworkChannel CHANNEL = NetworkChannel.create(
            ResourceLocation.fromNamespaceAndPath("economy_bounties", "board"));
    private static boolean commonRegistered;
    private static boolean clientRegistered;
    private static Consumer<BoardSnapshot> clientReceiver = snapshot -> { };

    private BountyNetwork() { }

    public static synchronized void registerCommon() {
        if (commonRegistered) return;
        commonRegistered = true;
        CHANNEL.registerC2S(BoardRequest.class,
                (value, buf) -> buf.writeUtf(BoardCodec.encodeRequest(value), BoardCodec.MAX_JSON_CHARS),
                buf -> BoardCodec.decodeRequest(buf.readUtf(BoardCodec.MAX_JSON_CHARS)),
                (request, context) -> {
                    if (!(context.get().getPlayer() instanceof ServerPlayer player)) return;
                    context.get().queue(() -> BountyBoardServer.handle(player, request));
                });
        CHANNEL.registerS2C(BoardSnapshot.class,
                (value, buf) -> buf.writeUtf(BoardCodec.encodeSnapshot(value), BoardCodec.MAX_JSON_CHARS),
                buf -> BoardCodec.decodeSnapshot(buf.readUtf(BoardCodec.MAX_JSON_CHARS)),
                (snapshot, context) -> context.get().queue(() -> clientReceiver.accept(snapshot)));
    }

    public static synchronized void registerClient() {
        if (clientRegistered) return;
        clientRegistered = true;
        // Keep this client-only class reference out of common registration so a
        // dedicated server can load networking without resolving OpenUI/Minecraft client classes.
        clientReceiver = com.nstut.economybounties.client.BountyBoardClient::receive;
        NetworkChannel.registerClientReceivers();
    }

    public static void sendRequest(BoardRequest request) { CHANNEL.sendToServer(request); }
    public static void sendSnapshot(ServerPlayer player, BoardSnapshot snapshot) { CHANNEL.sendToPlayer(player, snapshot); }
}

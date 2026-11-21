package com.nstut.economybounties.network;

import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Typed-payload channel facade for Minecraft 26.1.2 / NeoForge. */
final class NetworkChannel {
    private static final List<NetworkChannel> CHANNELS = new ArrayList<>();
    private final Identifier id;
    private final List<Registration<?>> s2c = new ArrayList<>();
    private final Map<Class<?>, CustomPacketPayload.Type<?>> types = new HashMap<>();

    private record Registration<T>(CustomPacketPayload.Type<Adapted<T>> type,
                                   StreamCodec<? super RegistryFriendlyByteBuf, Adapted<T>> codec,
                                   BiConsumer<T, Supplier<NetworkManager.PacketContext>> handler) { }
    private record Adapted<T>(CustomPacketPayload.Type<Adapted<T>> type, T value) implements CustomPacketPayload { }

    private NetworkChannel(Identifier id) { this.id = id; CHANNELS.add(this); }
    static NetworkChannel create(Identifier id) { return new NetworkChannel(id); }

    <T> void registerC2S(Class<T> javaType, BiConsumer<T, FriendlyByteBuf> encoder,
                         Function<FriendlyByteBuf, T> decoder,
                         BiConsumer<T, Supplier<NetworkManager.PacketContext>> handler) {
        Registration<T> registration = registration(javaType, encoder, decoder, handler);
        types.put(javaType, registration.type());
        NetworkManager.registerReceiver(NetworkManager.c2s(), registration.type(), registration.codec(),
                (payload, context) -> registration.handler().accept(payload.value(), () -> context));
    }

    <T> void registerS2C(Class<T> javaType, BiConsumer<T, FriendlyByteBuf> encoder,
                         Function<FriendlyByteBuf, T> decoder,
                         BiConsumer<T, Supplier<NetworkManager.PacketContext>> handler) {
        Registration<T> registration = registration(javaType, encoder, decoder, handler);
        types.put(javaType, registration.type());
        s2c.add(registration);
        if (Platform.getEnvironment() != Env.CLIENT) {
            NetworkManager.registerS2CPayloadType(registration.type(), registration.codec());
        }
    }

    static void registerClientReceivers() {
        for (NetworkChannel channel : CHANNELS) {
            for (Registration<?> registration : channel.s2c) registerClient(registration);
        }
    }

    private static <T> void registerClient(Registration<T> registration) {
        NetworkManager.registerReceiver(NetworkManager.s2c(), registration.type(), registration.codec(),
                (payload, context) -> registration.handler().accept(payload.value(), () -> context));
    }

    private <T> Registration<T> registration(Class<T> javaType, BiConsumer<T, FriendlyByteBuf> encoder,
                                               Function<FriendlyByteBuf, T> decoder,
                                               BiConsumer<T, Supplier<NetworkManager.PacketContext>> handler) {
        Identifier packetId = Identifier.fromNamespaceAndPath(id.getNamespace(),
                id.getPath() + "/" + javaType.getSimpleName().toLowerCase(Locale.ROOT));
        CustomPacketPayload.Type<Adapted<T>> type = new CustomPacketPayload.Type<>(packetId);
        StreamCodec<RegistryFriendlyByteBuf, Adapted<T>> codec = CustomPacketPayload.codec(
                (Adapted<T> payload, RegistryFriendlyByteBuf buf) -> encoder.accept(payload.value(), buf),
                buf -> new Adapted<>(type, decoder.apply(buf)));
        return new Registration<>(type, codec, handler);
    }

    void sendToServer(Object payload) { NetworkManager.sendToServer(adapt(payload)); }
    void sendToPlayer(ServerPlayer player, Object payload) { NetworkManager.sendToPlayer(player, adapt(payload)); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private CustomPacketPayload adapt(Object payload) {
        CustomPacketPayload.Type type = types.get(payload.getClass());
        if (type == null) throw new IllegalArgumentException("Unregistered payload " + payload.getClass().getName());
        return new Adapted(type, payload);
    }
}

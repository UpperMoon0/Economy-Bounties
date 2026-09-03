package com.nstut.economybounties.network;

import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import net.fabricmc.api.EnvType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Small channel facade over Architectury's 1.21 typed-payload networking. */
final class NetworkChannel {
    private static final List<NetworkChannel> CHANNELS = new ArrayList<>();
    private final ResourceLocation id;
    private final List<Registration<?>> s2c = new ArrayList<>();
    private final Map<Class<?>, CustomPacketPayload.Type<?>> types = new HashMap<>();

    private record Registration<T>(CustomPacketPayload.Type<Adapted<T>> type,
                                   StreamCodec<? super RegistryFriendlyByteBuf, Adapted<T>> codec,
                                   BiConsumer<T, Supplier<NetworkManager.PacketContext>> handler) { }
    private record Adapted<T>(CustomPacketPayload.Type<Adapted<T>> type, T value) implements CustomPacketPayload { }

    private NetworkChannel(ResourceLocation id) { this.id = id; CHANNELS.add(this); }
    static NetworkChannel create(ResourceLocation id) { return new NetworkChannel(id); }

    <T> void registerC2S(Class<T> javaType, BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder,
                         BiConsumer<T, Supplier<NetworkManager.PacketContext>> handler) {
        Registration<T> r = registration(javaType, encoder, decoder, handler);
        types.put(javaType, r.type());
        NetworkManager.registerReceiver(NetworkManager.c2s(), r.type(), r.codec(),
                (payload, context) -> r.handler().accept(payload.value(), () -> context));
    }

    <T> void registerS2C(Class<T> javaType, BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder,
                         BiConsumer<T, Supplier<NetworkManager.PacketContext>> handler) {
        Registration<T> r = registration(javaType, encoder, decoder, handler);
        types.put(javaType, r.type());
        s2c.add(r);
        if (Platform.getEnv() != EnvType.CLIENT) NetworkManager.registerS2CPayloadType(r.type(), r.codec());
    }

    static void registerClientReceivers() {
        for (NetworkChannel channel : CHANNELS) for (Registration<?> registration : channel.s2c) registerClient(registration);
    }

    private static <T> void registerClient(Registration<T> r) {
        NetworkManager.registerReceiver(NetworkManager.s2c(), r.type(), r.codec(),
                (payload, context) -> r.handler().accept(payload.value(), () -> context));
    }

    private <T> Registration<T> registration(Class<T> javaType, BiConsumer<T, FriendlyByteBuf> encoder,
                                               Function<FriendlyByteBuf, T> decoder,
                                               BiConsumer<T, Supplier<NetworkManager.PacketContext>> handler) {
        ResourceLocation packetId = ResourceLocation.fromNamespaceAndPath(id.getNamespace(),
                id.getPath() + "/" + javaType.getSimpleName().toLowerCase(Locale.ROOT));
        CustomPacketPayload.Type<Adapted<T>> type = new CustomPacketPayload.Type<>(packetId);
        StreamCodec<RegistryFriendlyByteBuf, Adapted<T>> codec = StreamCodec.of(
                (RegistryFriendlyByteBuf buf, Adapted<T> payload) -> encoder.accept(payload.value(), buf),
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

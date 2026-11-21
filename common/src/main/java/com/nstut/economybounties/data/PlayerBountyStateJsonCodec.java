package com.nstut.economybounties.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nstut.economybounties.api.BountyDefinition;
import com.nstut.economybounties.api.BountyObjectiveView;
import com.nstut.economybounties.api.BountyStatus;
import com.nstut.economybounties.api.BountyView;
import com.nstut.economybounties.api.NamespacedId;
import com.nstut.economybounties.api.PlayerBountyStateSnapshot;

import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Versioned persistence codec for one player's generated bounty state. */
public final class PlayerBountyStateJsonCodec {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int FORMAT_VERSION = 1;
    private final BountyJsonCodec definitionCodec = new BountyJsonCodec();

    public String encode(PlayerBountyStateSnapshot snapshot) {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("player", snapshot.playerId().toString());
        JsonArray bounties = new JsonArray();
        for (BountyView view : snapshot.bounties()) bounties.add(encodeBounty(view));
        root.add("bounties", bounties);
        JsonArray recent = new JsonArray();
        snapshot.recentDefinitionIds().forEach(id -> recent.add(id.toString()));
        root.add("recent", recent);
        JsonArray cooldowns = new JsonArray();
        for (PlayerBountyStateSnapshot.Cooldown cooldown : snapshot.cooldowns()) {
            JsonObject json = new JsonObject();
            json.addProperty("definition", cooldown.definitionId().toString());
            json.addProperty("until", cooldown.until().toString());
            cooldowns.add(json);
        }
        root.add("cooldowns", cooldowns);
        return GSON.toJson(root);
    }

    public PlayerBountyStateSnapshot decode(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        int version = root.has("version") ? root.get("version").getAsInt() : 1;
        if (version != FORMAT_VERSION) throw new IllegalArgumentException("Unsupported player bounty state version " + version);
        UUID player = UUID.fromString(root.get("player").getAsString());
        List<BountyView> bounties = new ArrayList<>();
        if (root.has("bounties")) for (JsonElement element : root.getAsJsonArray("bounties")) {
            bounties.add(decodeBounty(player, element.getAsJsonObject()));
        }
        List<NamespacedId> recent = new ArrayList<>();
        if (root.has("recent")) for (JsonElement element : root.getAsJsonArray("recent")) recent.add(NamespacedId.parse(element.getAsString()));
        List<PlayerBountyStateSnapshot.Cooldown> cooldowns = new ArrayList<>();
        if (root.has("cooldowns")) for (JsonElement element : root.getAsJsonArray("cooldowns")) {
            JsonObject json = element.getAsJsonObject();
            cooldowns.add(new PlayerBountyStateSnapshot.Cooldown(NamespacedId.parse(json.get("definition").getAsString()),
                    Instant.parse(json.get("until").getAsString())));
        }
        return new PlayerBountyStateSnapshot(player, bounties, recent, cooldowns);
    }

    private JsonObject encodeBounty(BountyView view) {
        JsonObject json = new JsonObject();
        json.addProperty("instance", view.instanceId().toString());
        json.add("definition", JsonParser.parseString(definitionCodec.encode(view.definition())));
        json.addProperty("offered_at", view.offeredAt().toString());
        json.addProperty("expires_at", view.expiresAt().toString());
        json.addProperty("reward", view.rewardAmount().toPlainString());
        json.addProperty("status", view.status().name());
        json.addProperty("seed", view.deterministicSeed());
        json.addProperty("payout_tx", view.payoutTransactionId());
        JsonArray objectives = new JsonArray();
        for (BountyObjectiveView objective : view.objectives()) {
            JsonObject item = new JsonObject();
            item.addProperty("required", objective.requiredAmount());
            item.addProperty("progress", objective.progress());
            objectives.add(item);
        }
        json.add("objectives", objectives);
        return json;
    }

    private BountyView decodeBounty(UUID player, JsonObject json) {
        BountyDefinition definition = definitionCodec.decode(new StringReader(json.getAsJsonObject("definition").toString()));
        JsonArray progress = json.getAsJsonArray("objectives");
        if (progress.size() != definition.objectives().size()) {
            throw new IllegalArgumentException("Persisted objective count does not match definition " + definition.id());
        }
        List<BountyObjectiveView> objectives = new ArrayList<>();
        for (int i = 0; i < progress.size(); i++) {
            JsonObject item = progress.get(i).getAsJsonObject();
            objectives.add(new BountyObjectiveView(definition.objectives().get(i), item.get("required").getAsLong(), item.get("progress").getAsLong()));
        }
        return new BountyView(UUID.fromString(json.get("instance").getAsString()), player, definition,
                Instant.parse(json.get("offered_at").getAsString()), Instant.parse(json.get("expires_at").getAsString()),
                new BigDecimal(json.get("reward").getAsString()), objectives, BountyStatus.valueOf(json.get("status").getAsString()),
                json.get("seed").getAsLong(), json.has("payout_tx") ? json.get("payout_tx").getAsString() : "");
    }
}

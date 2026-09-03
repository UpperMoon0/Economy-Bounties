package com.nstut.economybounties.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nstut.economybounties.api.BountyAudience;
import com.nstut.economybounties.api.LongRange;
import com.nstut.economybounties.api.NamespacedId;
import com.nstut.economybounties.api.ObjectiveDefinition;
import com.nstut.economybounties.api.PostedBountyObjectiveView;
import com.nstut.economybounties.api.PostedBountyStatus;
import com.nstut.economybounties.api.PostedBountyView;

import java.io.Reader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Stable JSON persistence codec for player-created bounties. */
public final class PostedBountyJsonCodec {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int FORMAT_VERSION = 1;

    public String encode(Collection<PostedBountyView> views) {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        JsonArray array = new JsonArray();
        views.stream().sorted(java.util.Comparator.comparing(PostedBountyView::createdAt))
                .forEach(view -> array.add(encodeView(view)));
        root.add("bounties", array);
        return GSON.toJson(root);
    }

    public List<PostedBountyView> decode(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        int version = root.has("version") ? root.get("version").getAsInt() : 1;
        if (version != FORMAT_VERSION) throw new IllegalArgumentException("Unsupported posted bounty format version " + version);
        List<PostedBountyView> result = new ArrayList<>();
        JsonArray array = root.has("bounties") ? root.getAsJsonArray("bounties") : new JsonArray();
        for (JsonElement element : array) result.add(decodeView(element.getAsJsonObject()));
        return List.copyOf(result);
    }

    private JsonObject encodeView(PostedBountyView view) {
        JsonObject json = new JsonObject();
        json.addProperty("id", view.bountyId().toString());
        json.addProperty("creator", view.creatorId().toString());
        if (view.claimantId() != null) json.addProperty("claimant", view.claimantId().toString());
        json.addProperty("title", view.title());
        json.addProperty("description", view.description());
        json.addProperty("icon", view.icon());
        json.addProperty("reward", view.rewardAmount().toPlainString());
        json.addProperty("created_at", view.createdAt().toString());
        json.addProperty("expires_at", view.expiresAt().toString());
        json.addProperty("status", view.status().name());
        json.addProperty("funding_tx", view.fundingTransactionId());
        json.addProperty("payout_tx", view.payoutTransactionId());
        json.addProperty("refund_tx", view.refundTransactionId());
        json.add("audience", encodeAudience(view.audience()));
        JsonArray objectives = new JsonArray();
        for (PostedBountyObjectiveView objective : view.objectives()) objectives.add(encodeObjective(objective));
        json.add("objectives", objectives);
        return json;
    }

    private PostedBountyView decodeView(JsonObject json) {
        List<PostedBountyObjectiveView> objectives = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("objectives")) objectives.add(decodeObjective(element.getAsJsonObject()));
        return new PostedBountyView(
                UUID.fromString(json.get("id").getAsString()), UUID.fromString(json.get("creator").getAsString()),
                json.has("claimant") ? UUID.fromString(json.get("claimant").getAsString()) : null,
                json.get("title").getAsString(), json.get("description").getAsString(), json.get("icon").getAsString(),
                objectives, new BigDecimal(json.get("reward").getAsString()), decodeAudience(json.getAsJsonObject("audience")),
                Instant.parse(json.get("created_at").getAsString()), Instant.parse(json.get("expires_at").getAsString()),
                PostedBountyStatus.valueOf(json.get("status").getAsString()), string(json, "funding_tx"),
                string(json, "payout_tx"), string(json, "refund_tx"));
    }

    private static JsonObject encodeAudience(BountyAudience audience) {
        JsonObject json = new JsonObject();
        json.addProperty("public", audience.publicAccess());
        json.add("players", uuidArray(audience.allowedPlayers()));
        json.add("groups", stringArray(audience.allowedGroups()));
        json.add("denied_players", uuidArray(audience.deniedPlayers()));
        audience.progressionGroup().ifPresent(id -> json.addProperty("progression_group", id.toString()));
        json.addProperty("min_level", audience.minLevel());
        json.addProperty("max_level", audience.maxLevel());
        return json;
    }

    private static BountyAudience decodeAudience(JsonObject json) {
        Set<UUID> players = new LinkedHashSet<>();
        if (json.has("players")) for (JsonElement element : json.getAsJsonArray("players")) players.add(UUID.fromString(element.getAsString()));
        Set<String> groups = new LinkedHashSet<>();
        if (json.has("groups")) for (JsonElement element : json.getAsJsonArray("groups")) groups.add(element.getAsString());
        Set<UUID> denied = new LinkedHashSet<>();
        if (json.has("denied_players")) for (JsonElement element : json.getAsJsonArray("denied_players")) denied.add(UUID.fromString(element.getAsString()));
        Optional<NamespacedId> progression = json.has("progression_group")
                ? Optional.of(NamespacedId.parse(json.get("progression_group").getAsString())) : Optional.empty();
        return new BountyAudience(json.get("public").getAsBoolean(), players, groups, denied, progression,
                json.has("min_level") ? json.get("min_level").getAsInt() : 0,
                json.has("max_level") ? json.get("max_level").getAsInt() : Integer.MAX_VALUE);
    }

    private static JsonObject encodeObjective(PostedBountyObjectiveView view) {
        ObjectiveDefinition definition = view.definition();
        JsonObject json = new JsonObject();
        json.addProperty("type", definition.type().toString());
        json.addProperty("target", definition.target());
        json.addProperty("target_amount", view.targetAmount());
        json.addProperty("progress", view.progress());
        JsonObject metadata = new JsonObject();
        definition.metadata().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> metadata.addProperty(entry.getKey(), entry.getValue()));
        json.add("metadata", metadata);
        return json;
    }

    private static PostedBountyObjectiveView decodeObjective(JsonObject json) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (json.has("metadata")) for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("metadata").entrySet()) {
            metadata.put(entry.getKey(), entry.getValue().getAsString());
        }
        long targetAmount = json.get("target_amount").getAsLong();
        ObjectiveDefinition definition = new ObjectiveDefinition(NamespacedId.parse(json.get("type").getAsString()),
                json.get("target").getAsString(), LongRange.fixed(targetAmount), metadata);
        return new PostedBountyObjectiveView(definition, targetAmount, json.get("progress").getAsLong());
    }

    private static JsonArray uuidArray(Collection<UUID> values) {
        JsonArray array = new JsonArray(); values.stream().map(UUID::toString).sorted().forEach(array::add); return array;
    }
    private static JsonArray stringArray(Collection<String> values) {
        JsonArray array = new JsonArray(); values.stream().sorted().forEach(array::add); return array;
    }
    private static String string(JsonObject json, String key) { return json.has(key) ? json.get(key).getAsString() : ""; }
}

package com.nstut.economybounties.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nstut.economybounties.api.BountyDefinition;
import com.nstut.economybounties.api.DecimalRange;
import com.nstut.economybounties.api.LongRange;
import com.nstut.economybounties.api.NamespacedId;
import com.nstut.economybounties.api.ObjectiveDefinition;
import com.nstut.economybounties.api.RewardDefinition;

import java.io.Reader;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Codec for data-pack-friendly bounty definition JSON. */
public final class BountyJsonCodec {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public BountyDefinition decode(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        NamespacedId id = NamespacedId.parse(requiredString(root, "id"));
        NamespacedId group = NamespacedId.parse(requiredString(root, "group"));
        int tier = integer(root, "tier", 0);
        int minLevel = integer(root, "min_level", 0);
        int maxLevel = integer(root, "max_level", Integer.MAX_VALUE);
        int weight = integer(root, "weight", 1);
        Duration offerDuration = Duration.ofSeconds(longValue(root, "offer_duration_seconds", 1800L));
        Duration cooldown = Duration.ofSeconds(longValue(root, "cooldown_seconds", 0L));

        JsonArray objectiveArray = required(root, "objectives").getAsJsonArray();
        List<ObjectiveDefinition> objectives = new ArrayList<>();
        for (JsonElement element : objectiveArray) {
            JsonObject objective = element.getAsJsonObject();
            objectives.add(new ObjectiveDefinition(
                    NamespacedId.parse(requiredString(objective, "type")),
                    requiredString(objective, "target"),
                    longRange(required(objective, "amount")),
                    metadata(objective.get("metadata"))
            ));
        }

        JsonObject rewardJson = required(root, "reward").getAsJsonObject();
        RewardDefinition reward = new RewardDefinition(
                decimalRange(required(rewardJson, "currency")),
                metadata(rewardJson.get("metadata"))
        );

        Set<String> tags = new LinkedHashSet<>();
        if (root.has("tags")) {
            for (JsonElement tag : root.getAsJsonArray("tags")) tags.add(tag.getAsString());
        }

        return new BountyDefinition(id, group, tier, minLevel, maxLevel, weight, objectives, reward,
                offerDuration, cooldown, tags);
    }

    public String encode(BountyDefinition definition) {
        JsonObject root = new JsonObject();
        root.addProperty("id", definition.id().toString());
        root.addProperty("group", definition.group().toString());
        root.addProperty("tier", definition.tier());
        root.addProperty("min_level", definition.minLevel());
        root.addProperty("max_level", definition.maxLevel());
        root.addProperty("weight", definition.weight());
        root.addProperty("offer_duration_seconds", definition.offerDuration().getSeconds());
        root.addProperty("cooldown_seconds", definition.cooldown().getSeconds());

        JsonArray objectives = new JsonArray();
        for (ObjectiveDefinition objective : definition.objectives()) {
            JsonObject json = new JsonObject();
            json.addProperty("type", objective.type().toString());
            json.addProperty("target", objective.target());
            json.add("amount", longRange(objective.amount()));
            if (!objective.metadata().isEmpty()) json.add("metadata", metadata(objective.metadata()));
            objectives.add(json);
        }
        root.add("objectives", objectives);

        JsonObject reward = new JsonObject();
        reward.add("currency", decimalRange(definition.reward().currency()));
        if (!definition.reward().metadata().isEmpty()) reward.add("metadata", metadata(definition.reward().metadata()));
        root.add("reward", reward);

        if (!definition.tags().isEmpty()) {
            JsonArray tags = new JsonArray();
            definition.tags().stream().sorted().forEach(tags::add);
            root.add("tags", tags);
        }
        return GSON.toJson(root);
    }

    private static JsonElement required(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) throw new IllegalArgumentException("Missing field: " + field);
        return value;
    }

    private static String requiredString(JsonObject object, String field) {
        String value = required(object, field).getAsString();
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static int integer(JsonObject object, String field, int fallback) {
        return object.has(field) ? object.get(field).getAsInt() : fallback;
    }

    private static long longValue(JsonObject object, String field, long fallback) {
        return object.has(field) ? object.get(field).getAsLong() : fallback;
    }

    private static LongRange longRange(JsonElement element) {
        if (element.isJsonPrimitive()) return LongRange.fixed(element.getAsLong());
        JsonObject object = element.getAsJsonObject();
        return new LongRange(required(object, "min").getAsLong(), required(object, "max").getAsLong());
    }

    private static JsonObject longRange(LongRange range) {
        JsonObject object = new JsonObject();
        object.addProperty("min", range.min());
        object.addProperty("max", range.max());
        return object;
    }

    private static DecimalRange decimalRange(JsonElement element) {
        if (element.isJsonPrimitive()) return DecimalRange.fixed(new BigDecimal(element.getAsString()));
        JsonObject object = element.getAsJsonObject();
        return new DecimalRange(new BigDecimal(required(object, "min").getAsString()),
                new BigDecimal(required(object, "max").getAsString()));
    }

    private static JsonObject decimalRange(DecimalRange range) {
        JsonObject object = new JsonObject();
        object.addProperty("min", range.min().toPlainString());
        object.addProperty("max", range.max().toPlainString());
        return object;
    }

    private static Map<String, String> metadata(JsonElement element) {
        if (element == null || element.isJsonNull()) return Map.of();
        JsonObject object = element.getAsJsonObject();
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            values.put(entry.getKey(), entry.getValue().getAsString());
        }
        return Map.copyOf(values);
    }

    private static JsonObject metadata(Map<String, String> metadata) {
        JsonObject object = new JsonObject();
        metadata.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> object.addProperty(entry.getKey(), entry.getValue()));
        return object;
    }
}

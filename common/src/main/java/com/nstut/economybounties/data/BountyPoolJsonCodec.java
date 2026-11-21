package com.nstut.economybounties.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nstut.economybounties.api.BountyPoolDefinition;
import com.nstut.economybounties.api.NamespacedId;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/** Codec for data-pack-friendly weighted bounty pools. */
public final class BountyPoolJsonCodec {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public BountyPoolDefinition decode(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        NamespacedId id = NamespacedId.parse(required(root, "id").getAsString());
        JsonArray groups = required(root, "groups").getAsJsonArray();
        List<BountyPoolDefinition.GroupEntry> entries = new ArrayList<>();
        for (JsonElement element : groups) {
            JsonObject group = element.getAsJsonObject();
            entries.add(new BountyPoolDefinition.GroupEntry(
                    NamespacedId.parse(required(group, "id").getAsString()),
                    group.has("weight") ? group.get("weight").getAsInt() : 1
            ));
        }
        return new BountyPoolDefinition(id, entries);
    }

    public String encode(BountyPoolDefinition pool) {
        JsonObject root = new JsonObject();
        root.addProperty("id", pool.id().toString());
        JsonArray groups = new JsonArray();
        for (BountyPoolDefinition.GroupEntry entry : pool.groups()) {
            JsonObject group = new JsonObject();
            group.addProperty("id", entry.group().toString());
            group.addProperty("weight", entry.weight());
            groups.add(group);
        }
        root.add("groups", groups);
        return GSON.toJson(root);
    }

    private static JsonElement required(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) throw new IllegalArgumentException("Missing field: " + field);
        return value;
    }
}

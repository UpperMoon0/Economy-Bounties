package com.nstut.economybounties.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nstut.economybounties.api.LongRange;
import com.nstut.economybounties.api.NamespacedId;

import java.io.Reader;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Validates the server-side data/economy_bounties/order_boards/*.json schema. */
public final class OrderBoardJsonCodec {
    public OrderBoardDefinition decode(Reader reader) {
        JsonObject j = JsonParser.parseReader(reader).getAsJsonObject();
        List<OrderBoardDefinition.Input> inputs = new ArrayList<>();
        JsonArray items = required(j, "inputs").getAsJsonArray();
        for (JsonElement element : items) {
            JsonObject input = element.getAsJsonObject();
            JsonObject quantity = required(input, "quantity").getAsJsonObject();
            inputs.add(new OrderBoardDefinition.Input(
                    string(input, "item"), new LongRange(number(quantity,"min",1), number(quantity,"max",1)),
                    (int)number(input,"weight",1), decimal(input,"reference_price","1"),
                    (int)number(input,"min_level",0)));
        }
        List<OrderBoardDefinition.Prize> prizes = new ArrayList<>();
        for (JsonElement element : required(j, "rewards").getAsJsonArray()) {
            JsonObject r = element.getAsJsonObject();
            prizes.add(new OrderBoardDefinition.Prize(
                    string(r,"id"), string(r,"title"), string(r,"rarity"), (int)number(r,"weight",1),
                    decimal(r,"multiplier","1"), decimal(r,"bonus","0"),
                    OrderBoardDefinition.Timer.valueOf(string(r,"timer").toUpperCase(java.util.Locale.ROOT)),
                    Duration.ofSeconds(number(r,"duration_seconds",3600)),
                    (int)number(r,"start_hour_utc",0), (int)number(r,"end_hour_utc",0)));
        }
        return new OrderBoardDefinition(NamespacedId.parse(string(j,"id")),
                OrderBoardDefinition.Scope.valueOf(string(j,"scope").toUpperCase(java.util.Locale.ROOT)),
                (int)number(j,"slots",6),
                Duration.ofSeconds(number(j,"restock_seconds",300)),
                Duration.ofSeconds(number(j,"rotation_seconds",86400)),
                !j.has("allow_discard") || j.get("allow_discard").getAsBoolean(),
                number(j,"active_from_epoch",0), number(j,"active_until_epoch",253402300799L),
                decimal(j,"minimum_reward","1"), decimal(j,"maximum_reward","10000"),
                inputs, prizes);
    }

    private static JsonElement required(JsonObject j, String key) {
        JsonElement value = j.get(key);
        if (value == null || value.isJsonNull()) throw new IllegalArgumentException("Missing order field " + key);
        return value;
    }
    private static String string(JsonObject j,String key) { return required(j,key).getAsString(); }
    private static long number(JsonObject j,String key,long fallback) {
        return j.has(key) ? j.get(key).getAsLong() : fallback;
    }
    private static BigDecimal decimal(JsonObject j,String key,String fallback) {
        return new BigDecimal(j.has(key) ? j.get(key).getAsString() : fallback);
    }
}

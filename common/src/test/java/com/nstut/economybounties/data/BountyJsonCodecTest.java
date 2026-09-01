package com.nstut.economybounties.data;

import com.nstut.economybounties.api.BountyDefinition;
import com.nstut.economybounties.api.BuiltinObjectiveTypes;
import com.nstut.economybounties.api.NamespacedId;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BountyJsonCodecTest {
    @Test
    void decodesAndRoundTripsDefinition() {
        String json = """
                {
                  "id": "bounty_harvest:carrot_delivery",
                  "group": "economy_bounties:farming",
                  "min_level": 5,
                  "max_level": 20,
                  "weight": 8,
                  "offer_duration_seconds": 1800,
                  "cooldown_seconds": 3600,
                  "objectives": [
                    {
                      "type": "economy_bounties:deliver_item",
                      "target": "minecraft:carrot",
                      "amount": { "min": 48, "max": 80 },
                      "metadata": { "quality": "normal" }
                    }
                  ],
                  "reward": {
                    "currency": { "min": "160.00", "max": "230.00" },
                    "metadata": { "funding": "treasury" }
                  },
                  "tags": ["farming", "early_game"]
                }
                """;

        BountyJsonCodec codec = new BountyJsonCodec();
        BountyDefinition definition = codec.decode(new StringReader(json));
        assertEquals(NamespacedId.parse("bounty_harvest:carrot_delivery"), definition.id());
        assertEquals(5, definition.minLevel());
        assertEquals(20, definition.maxLevel());
        assertEquals(BuiltinObjectiveTypes.DELIVER_ITEM, definition.objectives().get(0).type());
        assertEquals(48, definition.objectives().get(0).amount().min());
        assertEquals("230.00", definition.reward().currency().max().toPlainString());

        BountyDefinition roundTripped = codec.decode(new StringReader(codec.encode(definition)));
        assertEquals(definition, roundTripped);
    }
}

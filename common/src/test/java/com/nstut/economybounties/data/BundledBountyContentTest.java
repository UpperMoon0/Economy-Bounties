package com.nstut.economybounties.data;

import com.nstut.economybounties.api.BountyDefinition;
import com.nstut.economybounties.api.BountyPoolDefinition;
import com.nstut.economybounties.api.NamespacedId;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BundledBountyContentTest {
    @Test
    void everyExampleGroupHasAPlayableDefinition() throws Exception {
        var definitions = new HashSet<NamespacedId>();
        var groups = new HashSet<NamespacedId>();
        var codec = new BountyJsonCodec();
        String base = "/data/economy_bounties/bounties/";
        for (String name : new String[]{"example_farming", "example_oak_delivery",
                "example_stone_mining", "example_cobblestone_delivery",
                "example_zombie_hunting", "example_plank_crafting"}) {
            try (InputStream stream = getClass().getResourceAsStream(base + name + ".json")) {
                assertNotNull(stream, "Bundled definition is missing: " + name);
                BountyDefinition definition = codec.decode(new InputStreamReader(stream, StandardCharsets.UTF_8));
                assertTrue(definitions.add(definition.id()), "Duplicate definition " + definition.id());
                assertEquals(0, definition.minLevel(), "Starter content must be playable at level zero");
                groups.add(definition.group());
            }
        }
        try (InputStream stream = getClass().getResourceAsStream(
                "/data/economy_bounties/bounty_pools/example_board.json")) {
            assertNotNull(stream, "Example bounty pool is missing");
            BountyPoolDefinition pool = new BountyPoolJsonCodec().decode(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals(NamespacedId.parse("economy_bounties:example_board"), pool.id());
            for (BountyPoolDefinition.GroupEntry entry : pool.groups()) {
                assertTrue(groups.contains(entry.group()), "Pool contains empty group: " + entry.group());
            }
        }
    }
}

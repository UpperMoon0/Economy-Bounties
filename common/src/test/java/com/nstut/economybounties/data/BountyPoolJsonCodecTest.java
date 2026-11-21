package com.nstut.economybounties.data;

import com.nstut.economybounties.api.BountyPoolDefinition;
import com.nstut.economybounties.api.NamespacedId;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BountyPoolJsonCodecTest {
    @Test
    void decodesAndRoundTripsPool() {
        String json = """
                {
                  "id": "test:board",
                  "groups": [
                    { "id": "test:farming", "weight": 5 },
                    { "id": "test:mining", "weight": 2 }
                  ]
                }
                """;
        BountyPoolJsonCodec codec = new BountyPoolJsonCodec();
        BountyPoolDefinition pool = codec.decode(new StringReader(json));
        assertEquals(NamespacedId.parse("test:board"), pool.id());
        assertEquals(2, pool.groups().size());
        assertEquals(5, pool.groups().get(0).weight());
        assertEquals(pool, codec.decode(new StringReader(codec.encode(pool))));
    }
}

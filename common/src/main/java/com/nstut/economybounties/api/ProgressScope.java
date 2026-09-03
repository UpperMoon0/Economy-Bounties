package com.nstut.economybounties.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Optional routing metadata for progress events that consume a finite resource.
 * Unscoped gameplay events intentionally remain eligible for every matching active bounty.
 */
public final class ProgressScope {
    public static final String SOURCE_KEY = "economy_bounties:scope_source";
    public static final String BOUNTY_ID_KEY = "economy_bounties:scope_bounty_id";

    private ProgressScope() { }

    public static boolean applies(ProgressEvent event, String source, UUID bountyId) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(bountyId, "bountyId");
        String scopedSource = event.metadata().get(SOURCE_KEY);
        String scopedId = event.metadata().get(BOUNTY_ID_KEY);
        if (scopedSource == null && scopedId == null) return true;
        return source.equals(scopedSource) && bountyId.toString().equals(scopedId);
    }

    public static Map<String, String> metadata(String source, UUID bountyId, Map<String, String> extra) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
        Objects.requireNonNull(bountyId, "bountyId");
        Map<String, String> values = new LinkedHashMap<>();
        if (extra != null) values.putAll(extra);
        values.put(SOURCE_KEY, source);
        values.put(BOUNTY_ID_KEY, bountyId.toString());
        return Map.copyOf(values);
    }
}

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
    public static final String OBJECTIVE_INDEX_KEY = "economy_bounties:scope_objective_index";

    private ProgressScope() { }

    public static boolean applies(ProgressEvent event, String source, UUID bountyId, int objectiveIndex) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(bountyId, "bountyId");
        String scopedSource = event.metadata().get(SOURCE_KEY);
        String scopedId = event.metadata().get(BOUNTY_ID_KEY);
        String scopedObjective = event.metadata().get(OBJECTIVE_INDEX_KEY);
        if (scopedSource == null && scopedId == null && scopedObjective == null) return true;
        if (!source.equals(scopedSource) || !bountyId.toString().equals(scopedId)) return false;
        return scopedObjective == null || Integer.toString(objectiveIndex).equals(scopedObjective);
    }

    public static Map<String, String> metadata(String source, UUID bountyId, int objectiveIndex,
                                                Map<String, String> extra) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
        Objects.requireNonNull(bountyId, "bountyId");
        if (objectiveIndex < 0) throw new IllegalArgumentException("objectiveIndex must be >= 0");
        Map<String, String> values = new LinkedHashMap<>();
        if (extra != null) values.putAll(extra);
        values.put(SOURCE_KEY, source);
        values.put(BOUNTY_ID_KEY, bountyId.toString());
        values.put(OBJECTIVE_INDEX_KEY, Integer.toString(objectiveIndex));
        return Map.copyOf(values);
    }
}

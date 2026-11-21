package com.nstut.economybounties.core;

import com.nstut.economybounties.api.BountyDefinition;
import com.nstut.economybounties.api.NamespacedId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BountyCatalog {
    private final Map<NamespacedId, BountyDefinition> definitions = new LinkedHashMap<>();

    synchronized void replaceAll(Collection<BountyDefinition> incoming) {
        Map<NamespacedId, BountyDefinition> next = new LinkedHashMap<>();
        for (BountyDefinition definition : incoming) {
            BountyDefinition previous = next.putIfAbsent(definition.id(), definition);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate bounty definition id: " + definition.id());
            }
        }
        definitions.clear();
        definitions.putAll(next);
    }

    synchronized List<BountyDefinition> eligible(NamespacedId group, int level) {
        List<BountyDefinition> result = new ArrayList<>();
        for (BountyDefinition definition : definitions.values()) {
            if (definition.group().equals(group) && definition.supportsLevel(level)) {
                result.add(definition);
            }
        }
        result.sort(Comparator.comparing(d -> d.id().toString()));
        return result;
    }

    synchronized Collection<BountyDefinition> all() {
        return List.copyOf(definitions.values());
    }
}

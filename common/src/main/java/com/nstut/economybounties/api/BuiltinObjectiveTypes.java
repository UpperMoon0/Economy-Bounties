package com.nstut.economybounties.api;

import java.util.List;

public final class BuiltinObjectiveTypes {
    public static final NamespacedId DELIVER_ITEM = NamespacedId.parse("economy_bounties:deliver_item");
    public static final NamespacedId DELIVER_FLUID = NamespacedId.parse("economy_bounties:deliver_fluid");
    public static final NamespacedId KILL_ENTITY = NamespacedId.parse("economy_bounties:kill_entity");
    public static final NamespacedId CRAFT_ITEM = NamespacedId.parse("economy_bounties:craft_item");
    public static final NamespacedId MINE_BLOCK = NamespacedId.parse("economy_bounties:mine_block");
    public static final NamespacedId VISIT_LOCATION = NamespacedId.parse("economy_bounties:visit_location");

    private BuiltinObjectiveTypes() {}

    public static List<NamespacedId> ids() {
        return List.of(DELIVER_ITEM, DELIVER_FLUID, KILL_ENTITY, CRAFT_ITEM, MINE_BLOCK, VISIT_LOCATION);
    }

    public static void registerAll(ObjectiveRegistry registry) {
        for (NamespacedId id : ids()) {
            registry.register(new MatchingObjectiveType(id));
        }
    }
}

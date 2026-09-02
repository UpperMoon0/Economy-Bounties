package com.nstut.economybounties.board;

import java.util.List;

/** Client intent only. The server derives the acting player from the packet context. */
public record BoardRequest(
        Action action,
        String bountyId,
        String poolId,
        String objectiveType,
        String objectiveTarget,
        CreateDraft create
) {
    public BoardRequest {
        if (action == null) throw new NullPointerException("action");
        bountyId = clean(bountyId);
        poolId = clean(poolId);
        objectiveType = clean(objectiveType);
        objectiveTarget = clean(objectiveTarget);
    }

    public enum Action {
        REFRESH,
        ROLL,
        ACCEPT_GENERATED,
        CANCEL_GENERATED,
        CLAIM_GENERATED,
        ACCEPT_POSTED,
        CANCEL_POSTED,
        CLAIM_POSTED,
        CREATE_POSTED,
        DELIVER
    }

    public record CreateDraft(
            String title,
            String description,
            String icon,
            String reward,
            long lifetimeMinutes,
            List<ObjectiveDraft> objectives,
            AudienceDraft audience
    ) {
        public CreateDraft {
            title = clean(title);
            description = clean(description);
            icon = clean(icon);
            reward = clean(reward);
            objectives = List.copyOf(objectives == null ? List.of() : objectives);
            audience = audience == null ? AudienceDraft.publicAudience() : audience;
        }
    }

    public record ObjectiveDraft(String type, String target, long amount) {
        public ObjectiveDraft {
            type = clean(type);
            target = clean(target);
        }
    }

    public record AudienceDraft(
            boolean publicAccess,
            List<String> allowedPlayers,
            List<String> allowedGroups,
            List<String> deniedPlayers,
            String progressionGroup,
            int minLevel,
            int maxLevel
    ) {
        public AudienceDraft {
            allowedPlayers = List.copyOf(allowedPlayers == null ? List.of() : allowedPlayers);
            allowedGroups = List.copyOf(allowedGroups == null ? List.of() : allowedGroups);
            deniedPlayers = List.copyOf(deniedPlayers == null ? List.of() : deniedPlayers);
            progressionGroup = clean(progressionGroup);
        }

        public static AudienceDraft publicAudience() {
            return new AudienceDraft(true, List.of(), List.of(), List.of(), "", 0, Integer.MAX_VALUE);
        }
    }

    public static BoardRequest refresh() {
        return new BoardRequest(Action.REFRESH, "", "", "", "", null);
    }

    public static BoardRequest bounty(Action action, String id) {
        return new BoardRequest(action, id, "", "", "", null);
    }

    public static BoardRequest roll(String poolId) {
        return new BoardRequest(Action.ROLL, "", poolId, "", "", null);
    }

    public static BoardRequest deliver(String type, String target) {
        return new BoardRequest(Action.DELIVER, "", "", type, target, null);
    }

    public static BoardRequest create(CreateDraft draft) {
        return new BoardRequest(Action.CREATE_POSTED, "", "", "", "", draft);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}

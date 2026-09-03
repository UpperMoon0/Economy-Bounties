package com.nstut.economybounties.board;

import java.util.ArrayList;
import java.util.List;

/** Client intent only. The server derives the acting player from the packet context. */
public record BoardRequest(
        Action action,
        String bountyId,
        String bountySource,
        String poolId,
        String objectiveType,
        String objectiveTarget,
        int objectiveIndex,
        CreateDraft create
) {
    public static final int MAX_IDENTIFIER_CHARS = 128;
    public static final int MAX_TITLE_CHARS = 128;
    public static final int MAX_DESCRIPTION_CHARS = 512;
    public static final int MAX_REWARD_CHARS = 64;
    public static final int MAX_OBJECTIVES = 16;
    public static final int MAX_AUDIENCE_ENTRIES = 128;
    public static final int MAX_AUDIENCE_VALUE_CHARS = 128;

    public BoardRequest {
        if (action == null) throw new NullPointerException("action");
        bountyId = bounded(bountyId, "bounty id", 64);
        bountySource = bounded(bountySource, "bounty source", 32);
        poolId = bounded(poolId, "pool id", MAX_IDENTIFIER_CHARS);
        objectiveType = bounded(objectiveType, "objective type", MAX_IDENTIFIER_CHARS);
        objectiveTarget = bounded(objectiveTarget, "objective target", MAX_IDENTIFIER_CHARS);
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
            title = bounded(title, "title", MAX_TITLE_CHARS);
            description = bounded(description, "description", MAX_DESCRIPTION_CHARS);
            icon = bounded(icon, "icon", MAX_IDENTIFIER_CHARS);
            reward = bounded(reward, "reward", MAX_REWARD_CHARS);
            objectives = List.copyOf(objectives == null ? List.of() : objectives);
            if (objectives.size() > MAX_OBJECTIVES) {
                throw new IllegalArgumentException("Too many bounty objectives");
            }
            audience = audience == null ? AudienceDraft.publicAudience() : audience;
        }
    }

    public record ObjectiveDraft(String type, String target, long amount) {
        public ObjectiveDraft {
            type = bounded(type, "objective type", MAX_IDENTIFIER_CHARS);
            target = bounded(target, "objective target", MAX_IDENTIFIER_CHARS);
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
            allowedPlayers = boundedList(allowedPlayers, "allowed player", MAX_AUDIENCE_ENTRIES, MAX_AUDIENCE_VALUE_CHARS);
            allowedGroups = boundedList(allowedGroups, "allowed group", MAX_AUDIENCE_ENTRIES, MAX_AUDIENCE_VALUE_CHARS);
            deniedPlayers = boundedList(deniedPlayers, "denied player", MAX_AUDIENCE_ENTRIES, MAX_AUDIENCE_VALUE_CHARS);
            progressionGroup = bounded(progressionGroup, "progression group", MAX_IDENTIFIER_CHARS);
        }

        public static AudienceDraft publicAudience() {
            return new AudienceDraft(true, List.of(), List.of(), List.of(), "", 0, Integer.MAX_VALUE);
        }
    }

    public static BoardRequest refresh() {
        return new BoardRequest(Action.REFRESH, "", "", "", "", "", -1, null);
    }

    public static BoardRequest bounty(Action action, String id) {
        return new BoardRequest(action, id, "", "", "", "", -1, null);
    }

    public static BoardRequest roll(String poolId) {
        return new BoardRequest(Action.ROLL, "", "", poolId, "", "", -1, null);
    }

    public static BoardRequest deliver(String source, String bountyId, int objectiveIndex, String type, String target) {
        return new BoardRequest(Action.DELIVER, bountyId, source, "", type, target, objectiveIndex, null);
    }

    public static BoardRequest create(CreateDraft draft) {
        return new BoardRequest(Action.CREATE_POSTED, "", "", "", "", "", -1, draft);
    }

    private static String bounded(String value, String label, int maxChars) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.length() > maxChars) {
            throw new IllegalArgumentException(label + " exceeds " + maxChars + " characters");
        }
        return cleaned;
    }

    private static List<String> boundedList(List<String> values, String label, int maxEntries, int maxChars) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() > maxEntries) throw new IllegalArgumentException("Too many " + label + " entries");
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) result.add(bounded(value, label, maxChars));
        return List.copyOf(result);
    }
}

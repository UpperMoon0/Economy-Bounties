package com.nstut.economybounties.board;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Small JSON wire codec shared by all Minecraft networking generations. */
public final class BoardCodec {
    public static final int MAX_JSON_CHARS = 48_000;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private BoardCodec() { }

    public static String encodeRequest(BoardRequest value) { return checked(GSON.toJson(value)); }
    public static String encodeSnapshot(BoardSnapshot value) { return checked(GSON.toJson(value)); }

    public static BoardRequest decodeRequest(String json) {
        BoardRequest value = GSON.fromJson(requireBounded(json), BoardRequest.class);
        if (value == null) throw new IllegalArgumentException("Empty board request");
        return value;
    }

    public static BoardSnapshot decodeSnapshot(String json) {
        BoardSnapshot value = GSON.fromJson(requireBounded(json), BoardSnapshot.class);
        if (value == null) throw new IllegalArgumentException("Empty board snapshot");
        return value;
    }

    private static String checked(String json) {
        if (json.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("Bounty board payload exceeds " + MAX_JSON_CHARS + " characters");
        return json;
    }

    private static String requireBounded(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("Empty bounty board payload");
        if (json.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("Bounty board payload is too large");
        return json;
    }
}

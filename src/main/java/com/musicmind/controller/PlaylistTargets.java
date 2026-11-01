package com.musicmind.controller;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.musicmind.domain.AgentSession;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * A playlist's target length over time, read from the sessions that changed it. The creating session holds
 * the first target in its spec. Every later edit or restore records, in its own derived data, the target
 * after it ({@code targetMinutes}), the target before it ({@code previousTarget}) and the snapshot it saved
 * ({@code snapshotId}); an undo records the target it went back to and which record it reverted
 * ({@code reverts}). The newest record that has a {@code targetMinutes} is the current target, the newest
 * change that nothing reverted is what the next undo reverts, and the snapshot id says which target a
 * restore of that snapshot returns to. Sessions stored before these fields existed have none of them and
 * are ignored. Only numbers and ids are stored, never Spotify content.
 */
final class PlaylistTargets {

    /** An edit or restore that recorded its targets: the session, and the target in force before it. */
    record Change(String sessionId, int before) { }

    private final List<JsonObject> records;       // newest first, only this playlist's sessions that carry data
    private final List<String> ids;

    /** @param history the user's sessions, newest first */
    PlaylistTargets(List<AgentSession> history, String playlistId) {
        List<JsonObject> found = new java.util.ArrayList<>();
        List<String> foundIds = new java.util.ArrayList<>();
        for (AgentSession s : history) {
            if (playlistId.equals(s.playlistId())) {
                JsonObject o = parse(s.derivedData());
                if (o != null) {
                    found.add(o);
                    foundIds.add(s.id());
                }
            }
        }
        this.records = found;
        this.ids = foundIds;
    }

    /** The target the newest record set, if any record set one. */
    OptionalInt current() {
        for (JsonObject o : records) {
            if (o.has("targetMinutes")) {
                return OptionalInt.of(o.get("targetMinutes").getAsInt());
            }
        }
        return OptionalInt.empty();
    }

    /** The newest edit or restore that no undo has reverted. */
    Optional<Change> lastUnreverted() {
        Set<String> reverted = new HashSet<>();
        for (JsonObject o : records) {
            if (o.has("reverts")) {
                reverted.add(o.get("reverts").getAsString());
            }
        }
        for (int i = 0; i < records.size(); i++) {
            JsonObject o = records.get(i);
            if (o.has("previousTarget") && !reverted.contains(ids.get(i))) {
                return Optional.of(new Change(ids.get(i), o.get("previousTarget").getAsInt()));
            }
        }
        return Optional.empty();
    }

    /** The target in force when the given snapshot was saved, if the edit or restore that saved it recorded one. */
    OptionalInt targetAt(int snapshotId) {
        for (JsonObject o : records) {
            if (o.has("snapshotId") && o.has("previousTarget") && o.get("snapshotId").getAsInt() == snapshotId) {
                return OptionalInt.of(o.get("previousTarget").getAsInt());
            }
        }
        return OptionalInt.empty();
    }

    /** Derived data for an edit or restore: the targets around it and the snapshot saved just before. */
    static Map<String, Object> change(int after, int before, int snapshotId) {
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("targetMinutes", after);
        derived.put("previousTarget", before);
        derived.put("snapshotId", snapshotId);
        return derived;
    }

    /** Derived data for an undo: the target it returned to and the record it reverted. */
    static Map<String, Object> revert(int target, String revertedSessionId) {
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("targetMinutes", target);
        derived.put("reverts", revertedSessionId);
        return derived;
    }

    private static JsonObject parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject o = root.getAsJsonObject();
            // Reject records whose number fields are not numbers, so a damaged row is ignored instead of failing.
            for (String key : List.of("targetMinutes", "previousTarget", "snapshotId")) {
                if (o.has(key) && !(o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isNumber())) {
                    return null;
                }
            }
            if (o.has("reverts") && !(o.get("reverts").isJsonPrimitive() && o.get("reverts").getAsJsonPrimitive().isString())) {
                return null;
            }
            return o;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }
}

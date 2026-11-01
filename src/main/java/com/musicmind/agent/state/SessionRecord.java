package com.musicmind.agent.state;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Recommendation;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The derived data a creation run stores with its session: the spec it ended with (so the playlist can
 * be validated against it later), the genres that steered it and the chosen track ids. Ids and MusicMind's
 * own settings only; no titles or names from Spotify.
 */
public final class SessionRecord {

    private static final Gson GSON = new Gson();

    private SessionRecord() {
    }

    static String derivedData(RunContext ctx) {
        Map<String, Object> derived = new LinkedHashMap<>();
        if (ctx.plan != null) {
            PlaylistSpec spec = ctx.spec();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("targetMinutes", spec.targetMinutes());
            s.put("allowExplicit", spec.allowExplicit());
            s.put("familiarRatio", spec.familiarRatio());
            s.put("blockedArtists", spec.blockedArtists());
            s.put("name", spec.name());
            derived.put("spec", s);
            derived.put("seedGenres", ctx.plan.seedGenres());
        }
        if (ctx.playlist != null) {
            derived.put("trackIds", ctx.recommendations.stream().map(r -> r.track().id()).toList());
        }
        return GSON.toJson(derived);
    }

    /**
     * The spec a creation run stored in its derived data, or empty if there is none (a session of another
     * kind, no data, or data that cannot be read).
     */
    public static Optional<PlaylistSpec> specOf(String derivedJson) {
        if (derivedJson == null || derivedJson.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonElement root = JsonParser.parseString(derivedJson);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("spec")) {
                return Optional.empty();
            }
            JsonObject o = root.getAsJsonObject().getAsJsonObject("spec");
            List<String> blocked = new ArrayList<>();
            if (o.has("blockedArtists")) {
                o.getAsJsonArray("blockedArtists").forEach(e -> blocked.add(e.getAsString()));
            }
            String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : null;
            return Optional.of(new PlaylistSpec(o.get("targetMinutes").getAsInt(), o.get("allowExplicit").getAsBoolean(),
                    o.get("familiarRatio").getAsDouble(), blocked, name));
        } catch (JsonParseException | IllegalStateException | NullPointerException | ClassCastException e) {
            return Optional.empty();
        }
    }
}

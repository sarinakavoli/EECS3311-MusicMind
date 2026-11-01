package com.musicmind.agent.state;

import com.google.gson.Gson;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Recommendation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The derived data a creation run stores with its session: the spec it ended with (so the playlist can
 * be validated against it later), the genres that steered it and the chosen track ids. Ids and MusicMind's
 * own settings only; no titles or names from Spotify.
 */
final class SessionRecord {

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
}

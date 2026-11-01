package com.musicmind.agent;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A curated mood-to-genre mapping that stands in for Spotify's audio features, which Development
 * Mode apps cannot read. The data lives in {@code mood-genres.json}, not in code.
 */
public class MoodGenreMap {

    private static final String RESOURCE = "/mood-genres.json";
    private static final Type SHAPE = new TypeToken<Map<String, List<String>>>() { }.getType();

    private final Map<String, List<String>> genresByMood;

    private MoodGenreMap(Map<String, List<String>> genresByMood) {
        this.genresByMood = genresByMood;
    }

    /** The bundled map from {@code mood-genres.json}. */
    public static MoodGenreMap load() {
        try (InputStream in = MoodGenreMap.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(new Gson().fromJson(reader, SHAPE));
            }
        } catch (IOException | JsonSyntaxException e) {
            throw new IllegalStateException("Could not read mood-genres.json: " + e.getMessage(), e);
        }
    }

    /** A map from JSON text of the same shape as {@code mood-genres.json}. */
    public static MoodGenreMap fromJson(String json) {
        try {
            return parse(new Gson().fromJson(json, SHAPE));
        } catch (JsonSyntaxException e) {
            throw new IllegalStateException("Could not read mood-genres.json: " + e.getMessage(), e);
        }
    }

    private static MoodGenreMap parse(Map<String, List<String>> raw) {
        if (raw == null) {
            throw new IllegalStateException("mood-genres.json is empty.");
        }
        Map<String, List<String>> normalised = new HashMap<>();
        raw.forEach((mood, genres) -> normalised.put(key(mood), genres == null ? List.of() : List.copyOf(genres)));
        return new MoodGenreMap(normalised);
    }

    /** Genres that suit the mood, best first; empty for a blank or unknown mood. */
    public List<String> genresFor(String mood) {
        return mood == null ? List.of() : genresByMood.getOrDefault(key(mood), List.of());
    }

    /** Every genre name the map lists (lower case), except those that are also mood words. */
    public java.util.Set<String> genreNames() {
        java.util.Set<String> names = new java.util.TreeSet<>();
        genresByMood.values().forEach(list -> list.forEach(g -> names.add(key(g))));
        names.removeAll(genresByMood.keySet());
        return names;
    }

    /** The moods this map knows, alphabetically. */
    public List<String> moods() {
        return genresByMood.keySet().stream().sorted().toList();
    }

    private static String key(String mood) {
        return mood.strip().toLowerCase(Locale.ROOT);
    }
}

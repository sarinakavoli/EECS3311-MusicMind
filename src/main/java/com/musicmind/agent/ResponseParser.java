package com.musicmind.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.musicmind.config.Config;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.SearchQuery;
import com.musicmind.domain.Track;
import com.musicmind.llm.ParseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks everything the LLM returns before the agent uses it. JSON may arrive in a code
 * fence or with surrounding prose; malformed or unverifiable output raises
 * {@link ParseException} so callers fall back. Numbers are clamped to the bounds in
 * {@code musicmind.properties}. {@code parseCommands} (which needs real playlist commands)
 * is added with those commands; this class yields {@link CommandSpec}s.
 */
public class ResponseParser {

    private static final Pattern BRACKETED = Pattern.compile("\\[([^\\]]+)]");
    private static final Pattern TRACK_URI = Pattern.compile("spotify:track:([A-Za-z0-9]+)");
    private static final Pattern BARE_ID = Pattern.compile("\\b[A-Za-z0-9]{22}\\b");

    private final Config config;

    public ResponseParser(Config config) {
        this.config = config;
    }

    public PlaylistSpec parsePlaylistSpec(String json) {
        JsonObject o = object(json);
        int minutes = o.has("targetMinutes") && !o.get("targetMinutes").isJsonNull()
                ? wholeNumber(o, "targetMinutes") : config.parserDefaultTargetMinutes();
        minutes = Math.max(config.parserMinTargetMinutes(), Math.min(config.parserMaxTargetMinutes(), minutes));
        double ratio = o.has("familiarRatio") && !o.get("familiarRatio").isJsonNull()
                ? number(o, "familiarRatio") : config.parserDefaultFamiliarRatio();
        ratio = clamp01(ratio);
        boolean explicit = o.has("allowExplicit") && !o.get("allowExplicit").isJsonNull()
                && bool(o, "allowExplicit");
        String name = text(o, "name");
        return new PlaylistSpec(minutes, explicit, ratio, strings(o, "blockedArtists"),
                name.isEmpty() ? config.parserDefaultPlaylistName() : name);
    }

    public SearchQuery parseSearchQuery(String json) {
        JsonObject o = object(json);
        String keywords = text(o, "keywords");
        String genre = text(o, "genre");
        Map<String, String> filters = new LinkedHashMap<>();
        List<String> allowed = config.searchAllowedFilters();
        if (o.has("filters") && !o.get("filters").isJsonNull()) {
            if (!o.get("filters").isJsonObject()) {
                throw new ParseException("'filters' must be an object");
            }
            o.getAsJsonObject("filters").entrySet().forEach(e -> {
                if (allowed.contains(e.getKey().toLowerCase(Locale.ROOT))
                        && e.getValue().isJsonPrimitive() && !e.getValue().getAsString().isBlank()) {
                    filters.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString().trim());
                }
            });
        }
        if (keywords.isEmpty() && genre.isEmpty() && filters.isEmpty()) {
            throw new ParseException("The search query had no keywords, genre or filters");
        }
        double confidence = o.has("confidence") && !o.get("confidence").isJsonNull()
                ? clamp01(number(o, "confidence")) : 0.0;
        return new SearchQuery(keywords, genre.isEmpty() ? null : genre, filters, confidence);
    }

    public List<CommandSpec> parseCommandSpecs(String json) {
        JsonObject o = object(json);
        if (!o.has("commands") || !o.get("commands").isJsonArray() || o.getAsJsonArray("commands").isEmpty()) {
            throw new ParseException("Expected a non-empty 'commands' array");
        }
        List<CommandSpec> specs = new ArrayList<>();
        for (JsonElement el : o.getAsJsonArray("commands")) {
            if (!el.isJsonObject()) {
                throw new ParseException("Each command must be an object");
            }
            specs.add(commandSpec(el.getAsJsonObject()));
        }
        return specs;
    }

    private CommandSpec commandSpec(JsonObject c) {
        CommandSpec.Type type;
        try {
            type = CommandSpec.Type.valueOf(text(c, "type").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ParseException("Unknown command type '" + text(c, "type") + "'");
        }
        String track = text(c, "track");
        String query = text(c, "query");
        boolean needsTrack = type != CommandSpec.Type.ADD;
        boolean needsQuery = type == CommandSpec.Type.ADD || type == CommandSpec.Type.REPLACE;
        if (needsTrack && track.isEmpty()) {
            throw new ParseException(type + " needs a 'track'");
        }
        if (needsQuery && query.isEmpty()) {
            throw new ParseException(type + " needs a 'query'");
        }
        Integer position = null;
        if (type == CommandSpec.Type.REORDER) {
            if (!c.has("position") || c.get("position").isJsonNull()) {
                throw new ParseException("REORDER needs a 'position'");
            }
            position = Math.max(0, wholeNumber(c, "position"));
        }
        return new CommandSpec(type, needsTrack ? track : null, needsQuery ? query : null, position);
    }

    /** The relaxation options from an options reply (F11). */
    public List<String> parseOptions(String json) {
        List<String> options = strings(object(json), "options");
        if (options.isEmpty()) {
            throw new ParseException("The reply contained no options");
        }
        return options;
    }

    /** A plain-text reply (summary, trends): trimmed, and not blank or JSON. */
    public String parseText(String reply) {
        String t = reply == null ? "" : reply.strip();
        if (t.isEmpty()) {
            throw new ParseException("The reply was empty");
        }
        if (t.startsWith("{") || t.startsWith("[")) {
            throw new ParseException("Expected prose but the reply was JSON");
        }
        return t;
    }

    /** Keeps, in order and without duplicates, only the proposed tracks that Spotify results confirm by id. */
    public List<Track> verifyTracks(List<Track> tracks, List<Track> results) {
        if (tracks == null || results == null) {
            return List.of();
        }
        Set<String> real = new LinkedHashSet<>();
        results.forEach(t -> real.add(t.id()));
        Set<String> seen = new LinkedHashSet<>();
        List<Track> kept = new ArrayList<>();
        for (Track t : tracks) {
            if (real.contains(t.id()) && seen.add(t.id())) {
                kept.add(t);
            }
        }
        return kept;
    }

    /**
     * Returns the trimmed explanation if every track id it names is in the trace; otherwise
     * throws. An id is a bracketed token like {@code [id]}, a {@code spotify:track:} URI, or a
     * bare Spotify-style 22-character id.
     */
    public String verifyExplanation(String text, DecisionTrace trace) {
        if (trace == null) {
            throw new ParseException("No decision trace to verify the explanation against");
        }
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            throw new ParseException("The explanation was empty");
        }
        Set<String> named = new LinkedHashSet<>();
        collect(BRACKETED, t, 1, named);
        collect(TRACK_URI, t, 1, named);
        Matcher m = BARE_ID.matcher(t);
        while (m.find()) {
            if (looksLikeId(m.group())) {
                named.add(m.group());
            }
        }
        for (String id : named) {
            if (!trace.reasons().containsKey(id.trim())) {
                throw new ParseException("The explanation names '" + id + "', which is not in the decision trace");
            }
        }
        return t;
    }

    private static void collect(Pattern p, String text, int group, Set<String> out) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(m.group(group));
        }
    }

    /** A real Spotify id mixes digits or capitals into the letters; plain long words do not. */
    private static boolean looksLikeId(String token) {
        return token.chars().anyMatch(Character::isDigit)
                || token.substring(1).chars().anyMatch(Character::isUpperCase);
    }

    // ---- JSON helpers ----

    private static JsonObject object(String reply) {
        if (reply == null || reply.isBlank()) {
            throw new ParseException("The reply was empty");
        }
        String body = reply.strip();
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new ParseException("The reply did not contain a JSON object");
        }
        try {
            JsonElement el = JsonParser.parseString(body.substring(start, end + 1));
            if (!el.isJsonObject()) {
                throw new ParseException("The reply was not a JSON object");
            }
            return el.getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            throw new ParseException("The reply was not valid JSON: " + e.getMessage(), e);
        }
    }

    private static double number(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new ParseException("'" + key + "' must be a number");
        }
        return el.getAsDouble();
    }

    private static int wholeNumber(JsonObject o, String key) {
        double d = number(o, key);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(d)));
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) {
            throw new ParseException("'" + key + "' must be true or false");
        }
        return el.getAsBoolean();
    }

    private static String text(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || !el.isJsonPrimitive() ? "" : el.getAsString().trim();
    }

    private static List<String> strings(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return List.of();
        }
        if (!o.get(key).isJsonArray()) {
            throw new ParseException("'" + key + "' must be an array");
        }
        JsonArray arr = o.getAsJsonArray(key);
        List<String> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (el.isJsonPrimitive() && !el.getAsString().isBlank()) {
                out.add(el.getAsString().trim());
            }
        }
        return out;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}

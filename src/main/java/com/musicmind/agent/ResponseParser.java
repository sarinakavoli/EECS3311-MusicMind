package com.musicmind.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.musicmind.agent.command.AddItemsCommand;
import com.musicmind.agent.command.PlaylistCommand;
import com.musicmind.agent.command.RemoveTrackCommand;
import com.musicmind.agent.command.ReorderCommand;
import com.musicmind.agent.command.ReplaceTrackCommand;
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
 * {@code musicmind.properties}. {@code parseCommands} turns the
 * LLM's {@link CommandSpec}s into real playlist commands, finding tracks to add through Spotify
 * search (the LLM never picks tracks).
 */
public class ResponseParser {

    private static final Pattern BRACKETED = Pattern.compile("\\[([^\\]]+)]");
    private static final Pattern TRACK_URI = Pattern.compile("spotify:track:([A-Za-z0-9]+)");
    private static final Pattern BARE_ID = Pattern.compile("\\b[A-Za-z0-9]{22}\\b");

    private final Config config;
    private final PlaylistContext context;

    public ResponseParser(Config config) {
        this(config, null);
    }

    private ResponseParser(Config config, PlaylistContext context) {
        if (config.parserMinTargetMinutes() > config.parserMaxTargetMinutes()) {
            throw new IllegalStateException("parser.minTargetMinutes must not exceed parser.maxTargetMinutes");
        }
        this.config = config;
        this.context = context;
    }

    /** A parser bound to one playlist, so {@link #parseCommands} can resolve the edit against it. */
    public ResponseParser forPlaylist(PlaylistContext playlist) {
        return new ResponseParser(config, playlist);
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

    /** What a plan reply says beyond the constraints: how sure the model is, whether it stated a familiar share, the mood. */
    public record PlanExtras(double confidence, boolean familiarStated, String mood) { }

    /**
     * The confidence (the configured default when the reply gives none), whether the request stated a
     * familiar share, and the mood word (empty when none) of a plan reply.
     */
    public PlanExtras parsePlanExtras(String json) {
        JsonObject o = object(json);
        double confidence = o.has("confidence") && !o.get("confidence").isJsonNull()
                ? clamp01(number(o, "confidence")) : config.planDefaultConfidence();
        boolean stated = o.has("familiarRatio") && !o.get("familiarRatio").isJsonNull();
        return new PlanExtras(confidence, stated, text(o, "mood"));
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

    /**
     * Binds the LLM's edit commands to the playlist this parser was bound to with {@link #forPlaylist}. {@code track} references (a 1-based position
     * or a track id/URI) are resolved against the playlist as the user saw it; tracks to add come
     * from a Spotify search for the command's query, skipping tracks already in the playlist or
     * already chosen. Anything that cannot be resolved raises {@link ParseException} before any
     * change is made.
     */
    public List<PlaylistCommand> parseCommands(String json) {
        if (context == null) {
            throw new IllegalStateException(config.wording("cmdNoContext"));
        }
        List<PlaylistCommand> commands = new ArrayList<>();
        Set<String> taken = new LinkedHashSet<>();
        context.tracks().forEach(t -> taken.add(t.id()));
        String pid = context.playlistId();
        for (CommandSpec spec : parseCommandSpecs(json)) {
            switch (spec.type()) {
                case ADD -> commands.add(new AddItemsCommand(config, context.music(), pid,
                        List.of(findNew(spec.query(), taken))));
                case REMOVE -> commands.add(new RemoveTrackCommand(config, context.music(), pid,
                        resolve(spec.track())));
                case REPLACE -> commands.add(new ReplaceTrackCommand(config, context.music(), pid,
                        resolve(spec.track()), findNew(spec.query(), taken)));
                case REORDER -> commands.add(new ReorderCommand(config, context.music(), pid,
                        resolve(spec.track()), spec.position()));
            }
        }
        return commands;
    }

    private Track resolve(String reference) {
        for (Track t : context.tracks()) {
            if (reference.equals(t.id()) || reference.equals(t.uri())) {
                return t;
            }
        }
        if (reference.matches("\\d{1,6}")) {
            int position = Integer.parseInt(reference);
            if (position >= 1 && position <= context.tracks().size()) {
                return context.tracks().get(position - 1);
            }
        }
        throw new ParseException(String.format(config.wording("cmdRefUnknown"), reference));
    }

    private Track findNew(String query, Set<String> taken) {
        for (Track t : context.music().search(query, config.commandSearchLimit())) {
            if (taken.add(t.id())) {
                return t;
            }
        }
        throw new ParseException(String.format(config.wording("cmdNoTrackFound"), query));
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

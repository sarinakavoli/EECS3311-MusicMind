package com.musicmind.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.musicmind.agent.Planner;
import com.musicmind.agent.ProfileBuilder;
import com.musicmind.agent.PromptBuilder;
import com.musicmind.agent.ResponseParser;
import com.musicmind.agent.SearchRanker;
import com.musicmind.agent.TrendComparator;
import com.musicmind.config.Config;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.RequestKind;
import com.musicmind.domain.SearchQuery;
import com.musicmind.domain.TopItems;
import com.musicmind.domain.Track;
import com.musicmind.domain.TrendReport;
import com.musicmind.domain.User;
import com.musicmind.llm.LLMProvider;
import com.musicmind.memory.MemoryManager;
import com.musicmind.music.MusicServiceException;
import com.musicmind.tool.ToolManager;
import com.musicmind.tool.ToolResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Facade (GoF) over the agent subsystem: one high-level method per feature. This
 * class implements the SD03 interaction for F1 (tools fetch the data, the
 * {@link ProfileBuilder} aggregates it, the LLM writes the summary sentence, the
 * {@link MemoryManager} saves the session) and the time-range variant for F2.
 * The GUI and the CLI both go through {@link AppController}, so neither
 * duplicates any of this.
 *
 * <p>The LLM only phrases results. With no provider, or when it fails or answers
 * with something unusable, the Stage 1 fallback applies: a sentence computed from
 * the same data, ending with the configured "computed" label.
 */
public class MusicAgentFacade {

    private static final String TOP_ITEMS = "getTopItems";
    private static final String SAVED_TRACKS = "getSavedTracks";
    private static final Gson GSON = new Gson();

    private final ToolManager tools;
    private final MemoryManager memory;
    private final ProfileBuilder profileBuilder;
    private final TrendComparator trendComparator;
    private final PromptBuilder prompts;
    private final LLMProvider llm; // null when no key is configured
    private final Config config;
    private final Planner planner;
    private final ResponseParser parser;
    private final SearchRanker ranker;
    private volatile String lastSearchNotice;
    private volatile String lastSearchLabel;

    public MusicAgentFacade(ToolManager tools, MemoryManager memory, ProfileBuilder profileBuilder,
                            TrendComparator trendComparator, PromptBuilder prompts, LLMProvider llm,
                            Config config, Planner planner, ResponseParser parser, SearchRanker ranker) {
        this.tools = tools;
        this.memory = memory;
        this.profileBuilder = profileBuilder;
        this.trendComparator = trendComparator;
        this.prompts = prompts;
        this.llm = llm;
        this.config = config;
        this.planner = planner;
        this.parser = parser;
        this.ranker = ranker;
    }

    // ---- F1 ----

    /** F1 over the default time range. */
    public ListeningProfile analyzeTaste(User user) {
        return analyzeTaste(user, config.profileDefaultRange());
    }

    /**
     * F1 — always rebuilds. Fetches top artists, top tracks and saved tracks through
     * the tools, builds the profile, adds the summary and saves the session. A read
     * that fails or comes back thin yields a partial profile with a notice; only when
     * every read fails does this throw.
     *
     * @throws IllegalArgumentException if {@code range} is not one of the configured ranges
     * @throws com.musicmind.music.AuthExpiredException if Spotify rejects the login
     */
    public ListeningProfile analyzeTaste(User user, String range) {
        Objects.requireNonNull(user, "user");
        String r = normalizeRange(range);

        Fetch<Artist> artists = fetch(TOP_ITEMS, Map.of("type", "artists", "range", r), "Top artists");
        Fetch<Track> topTracks = fetch(TOP_ITEMS, Map.of("type", "tracks", "range", r), "Top tracks");
        Fetch<Track> saved = fetch(SAVED_TRACKS, Map.of(), "Saved tracks");

        List<String> errors = Fetch.errors(artists, topTracks, saved);
        if (errors.size() == 3) {
            throw new MusicServiceException("Could not read your Spotify listening data. "
                    + String.join(" ", errors));
        }
        ListeningProfile built = profileBuilder.build(
                new TopItems(artists.items(), topTracks.items()), saved.items(), errors);
        ListeningProfile profile = withSummary(built);
        profile.setRange(r);

        AgentSession session = new AgentSession(UUID.randomUUID().toString(), Instant.now(), null);
        session.setStatus("done");
        session.setRequest(new AgentRequest("view taste profile (" + r + ")", RequestKind.ANALYZE_TASTE));
        session.setDerivedData(derivedJson(profile, r));
        memory.saveSession(session);
        return profile;
    }

    /** The user's most recent stored profile, rebuilt from its derived data (ids only, no names). */
    public Optional<ListeningProfile> lastProfile(User user) {
        Objects.requireNonNull(user, "user");
        for (AgentSession s : memory.loadHistory(user.id())) {
            if (s.request() != null && s.request().kind() == RequestKind.ANALYZE_TASTE) {
                Optional<ListeningProfile> profile = parseDerived(s.derivedData());
                if (profile.isPresent()) {
                    return profile;
                }
            }
        }
        return Optional.empty();
    }

    // ---- F2 ----

    /**
     * F2 — fetches top artists for each configured range through the tools,
     * compares them deterministically, and has the LLM narrate the result (or
     * falls back to a labelled computed narrative). Not persisted.
     */
    public TrendReport analyzeTrends(User user) {
        Objects.requireNonNull(user, "user");
        Map<String, List<Artist>> byRange = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (String range : config.profileRanges()) {
            Fetch<Artist> f = fetch(TOP_ITEMS, Map.of("type", "artists", "range", range), "Top artists");
            byRange.put(range, f.items());
            if (f.error() != null) {
                errors.put(range, f.error());
            }
        }
        TrendReport report = trendComparator.compare(byRange, errors);
        long populated = byRange.values().stream().filter(artists -> !artists.isEmpty()).count();
        if (populated < 2) {
            return report.withNarrative("There is not enough data in more than one time range "
                    + "to describe a change in your taste.");
        }
        String narrative = fromLlm(prompts.buildTrendPrompt(report));
        return report.withNarrative(narrative != null ? narrative : fallbackNarrative(report, config.profileRanges()));
    }

    // ---- F3 ----

    /**
     * F3 — the Planner structures the text (or falls back to a raw keyword query), the
     * {@code search} tool fetches the tracks, {@link ResponseParser#verifyTracks} keeps only
     * confirmed ones and the {@link SearchRanker} orders them. An empty result sets
     * {@link #lastSearchNotice()}; a keyword fallback sets {@link #lastSearchLabel()}.
     *
     * @throws IllegalArgumentException if {@code query} is blank
     * @throws MusicServiceException if the search itself fails
     */
    public List<Track> searchMusic(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Type something to search for.");
        }
        lastSearchNotice = null;
        lastSearchLabel = null;
        SearchQuery parsed = planner.parseSearchQuery(query);
        if (parsed.confidence() < config.searchMinConfidence()) {
            lastSearchLabel = config.searchFallbackLabel();
        }
        ToolResult result = tools.execute("search",
                Map.of("query", parsed.toSpotifyQuery(), "limit", config.searchResultLimit()));
        if (!result.ok()) {
            throw new MusicServiceException("Search failed: " + result.error());
        }
        @SuppressWarnings("unchecked")
        List<Track> results = (List<Track>) result.payload();
        List<Track> ranked = ranker.rank(parsed, parser.verifyTracks(results, results));
        if (ranked.isEmpty()) {
            String hint = suggestRelaxedQuery(parsed)
                    .map(q -> String.format(config.searchRelaxedHintTemplate(), q))
                    .orElse(config.searchNoRelaxedHint());
            lastSearchNotice = String.format(config.searchNoResultsTemplate(), query.strip()) + " " + hint;
        }
        return ranked;
    }

    /** Why the last search found nothing and what to try instead; null when it found something. */
    public String lastSearchNotice() {
        return lastSearchNotice;
    }

    /** The "plain keyword search" label when the last search could not be interpreted; otherwise null. */
    public String lastSearchLabel() {
        return lastSearchLabel;
    }

    /**
     * A looser version of a query that found nothing: the keywords without the genre and
     * filters, or else the keywords without their last word. Empty when nothing can be dropped.
     */
    public Optional<String> suggestRelaxedQuery(SearchQuery query) {
        String keywords = query.keywords() == null ? "" : query.keywords().strip();
        boolean structured = (query.genre() != null && !query.genre().isBlank()) || !query.filters().isEmpty();
        if (structured) {
            String base = !keywords.isEmpty() ? keywords : query.genre() == null ? "" : query.genre().strip();
            return base.isEmpty() ? Optional.empty() : Optional.of(base);
        }
        int cut = keywords.lastIndexOf(' ');
        return cut < 0 ? Optional.empty() : Optional.of(keywords.substring(0, cut).strip());
    }

    // ---- helpers ----

    private String normalizeRange(String range) {
        String r = range == null ? config.profileDefaultRange() : range.trim().toLowerCase(Locale.ROOT);
        if (r.endsWith("_term")) {
            r = r.substring(0, r.length() - "_term".length());
        }
        if (!config.profileRanges().contains(r)) {
            throw new IllegalArgumentException("Unknown time range '" + range + "'. Use one of: "
                    + String.join(", ", config.profileRanges()) + ".");
        }
        return r;
    }

    /** A tool's list payload, or the reason it could not be read. */
    private record Fetch<T>(List<T> items, String error) {
        static List<String> errors(Fetch<?>... fetches) {
            List<String> out = new ArrayList<>();
            for (Fetch<?> f : fetches) {
                if (f.error != null) {
                    out.add(f.error);
                }
            }
            return out;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Fetch<T> fetch(String tool, Map<String, Object> args, String what) {
        ToolResult result = tools.execute(tool, args);
        if (!result.ok()) {
            return new Fetch<>(List.of(), what + " could not be read: " + result.error());
        }
        return new Fetch<>((List<T>) result.payload(), null);
    }

    private ListeningProfile withSummary(ListeningProfile p) {
        String summary;
        if (p.isEmpty()) {
            summary = "Not enough listening history yet to describe your taste.";
        } else {
            String fromLlm = fromLlm(prompts.buildSummaryPrompt(p));
            summary = fromLlm != null ? fromLlm : fallbackSummary(p);
        }
        p.setSummary(summary);
        return p;
    }

    /** The LLM's first usable line for a prompt, or null when there is no provider or no usable answer. */
    private String fromLlm(String prompt) {
        if (llm == null) {
            return null;
        }
        try {
            String line = firstLine(llm.complete(prompt));
            return line.isBlank() || line.startsWith("{") ? null : line;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String fallbackSummary(ListeningProfile p) {
        String genres = p.rankedGenres(config.profileFallbackGenres()).stream()
                .map(Map.Entry::getKey).collect(Collectors.joining(", "));
        String text = genres.isEmpty()
                ? "Your top genres could not be worked out from your Spotify data."
                : "Your taste leans toward " + genres + ".";
        return text + " " + config.computedLabel();
    }

    private String fallbackNarrative(TrendReport report, List<String> ranges) {
        String newest = ranges.get(0);
        String oldest = ranges.get(ranges.size() - 1);
        StringBuilder sb = new StringBuilder("Compared with your ").append(oldest).append("-term listening, ")
                .append("your ").append(newest).append("-term listening has ");
        sb.append(report.addedGenres().isEmpty() ? "no newly prominent genres"
                : "newly prominent genres: " + limited(report.addedGenres()));
        sb.append(" and ");
        sb.append(report.droppedGenres().isEmpty() ? "no faded genres"
                : "faded genres: " + limited(report.droppedGenres()));
        sb.append(". ").append(report.addedArtistIds().size()).append(" artists are new and ")
                .append(report.droppedArtistIds().size()).append(" have faded.");
        if (!report.gaps().isEmpty()) {
            sb.append(" Some data was missing or incomplete.");
        }
        return sb.append(' ').append(config.computedLabel()).toString();
    }

    private String limited(List<String> items) {
        return items.stream().limit(config.profileFallbackGenres()).collect(Collectors.joining(", "));
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        for (String raw : text.strip().split("\\R")) {
            String line = raw.strip();
            if (!line.isEmpty()) {
                boolean quoted = line.length() > 1 && line.startsWith("\"") && line.endsWith("\"");
                return quoted ? line.substring(1, line.length() - 1) : line;
            }
        }
        return "";
    }

    /** Only MusicMind's derived data: ids, the genre histogram, notices, the summary. No titles or names. */
    private static String derivedJson(ListeningProfile p, String range) {
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("range", range);
        derived.put("genreHistogram", p.genreHistogram());
        derived.put("artistIds", p.topArtists().stream().map(Artist::id).toList());
        derived.put("trackIds", p.topTracks().stream().map(Track::id).toList());
        derived.put("savedTrackIds", p.savedTrackIds());
        derived.put("summary", p.summary());
        derived.put("partial", p.isPartial());
        derived.put("notices", p.notices());
        derived.put("generatedAt", p.generatedAt() == null ? null : p.generatedAt().toString());
        return GSON.toJson(derived);
    }

    private static Optional<ListeningProfile> parseDerived(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (!o.has("genreHistogram")) {
                return Optional.empty();
            }
            Map<String, Double> histogram = new LinkedHashMap<>();
            o.getAsJsonObject("genreHistogram").entrySet()
                    .forEach(e -> histogram.put(e.getKey(), e.getValue().getAsDouble()));
            List<Artist> artists = strings(o, "artistIds").stream()
                    .map(id -> new Artist(id, "", List.of())).toList();
            List<Track> tracks = strings(o, "trackIds").stream()
                    .map(id -> new Track(id, "spotify:track:" + id, "", 0, false, List.of())).toList();
            String summary = o.has("summary") && !o.get("summary").isJsonNull() ? o.get("summary").getAsString() : null;
            Instant at = o.has("generatedAt") && !o.get("generatedAt").isJsonNull()
                    ? Instant.parse(o.get("generatedAt").getAsString()) : null;
            String storedRange = o.has("range") && !o.get("range").isJsonNull() ? o.get("range").getAsString() : null;
            boolean partial = o.has("partial") && o.get("partial").getAsBoolean();
            ListeningProfile stored = new ListeningProfile(histogram, artists, tracks, strings(o, "savedTrackIds"),
                    summary, at, partial, strings(o, "notices"));
            stored.setRange(storedRange);
            return Optional.of(stored);
        } catch (JsonSyntaxException | IllegalStateException | ClassCastException
                 | java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static List<String> strings(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return List.of();
        }
        JsonArray array = o.getAsJsonArray(key);
        List<String> out = new ArrayList<>();
        for (JsonElement e : array) {
            out.add(e.getAsString());
        }
        return out;
    }
}

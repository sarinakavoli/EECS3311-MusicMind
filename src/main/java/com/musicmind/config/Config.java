package com.musicmind.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.UnaryOperator;

/**
 * Typed access to configuration. Secrets and per-machine settings come from the
 * git-ignored {@code .env} file (falling back to real environment variables).
 * Tunables (page sizes, retry counts, tolerances, URLs, default model) come from
 * {@code musicmind.properties} on the classpath, so nothing is hard-coded in code.
 */
public final class Config {

    private static final String TUNABLES_RESOURCE = "/musicmind.properties";
    private static final String PLACEHOLDER_MODEL = "your_model_name_here";
    private static final String PLACEHOLDER_LLM_KEY = "your_llm_api_key_here";
    private static final String PLACEHOLDER_CLIENT_ID = "your_client_id_here";
    private static final String LEGACY_MODEL_PREFIX = "gemini-";

    private static volatile Config defaults;

    private final UnaryOperator<String> lookup;
    private final Properties tunables;

    private Config(UnaryOperator<String> lookup, Properties tunables) {
        this.lookup = lookup;
        this.tunables = tunables;
    }

    /** Load configuration from {@code .env} in the working directory (optional). */
    public static Config load() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        return new Config(key -> firstNonBlank(dotenv.get(key), System.getenv(key)), loadTunables());
    }

    /**
     * Configuration for components that are built without a controller: tunables
     * from {@code musicmind.properties} and process environment variables only.
     */
    public static Config defaults() {
        Config c = defaults;
        if (c == null) {
            c = new Config(System::getenv, loadTunables());
            defaults = c;
        }
        return c;
    }

    /** Configuration with explicit settings (for tests) plus the bundled tunables. */
    public static Config of(Map<String, String> settings) {
        return new Config(settings::get, loadTunables());
    }

    /** A copy of this configuration with some tunables replaced (for tests of tunable-driven behaviour). */
    public Config withTunables(Map<String, String> overrides) {
        Properties merged = new Properties();
        merged.putAll(tunables);
        merged.putAll(overrides);
        return new Config(lookup, merged);
    }

    private static Properties loadTunables() {
        Properties props = new Properties();
        try (InputStream in = Config.class.getResourceAsStream(TUNABLES_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + TUNABLES_RESOURCE);
            }
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + TUNABLES_RESOURCE, e);
        }
        return props;
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    /** Raw lookup of a setting: {@code .env} first, then the process environment. */
    public String get(String key, String defaultValue) {
        String value = lookup.apply(key);
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    public String get(String key) {
        return get(key, null);
    }

    // ---- Spotify ----
    public String spotifyClientId() {
        return get("SPOTIFY_CLIENT_ID");
    }

    public String spotifyRedirectUri() {
        return get("SPOTIFY_REDIRECT_URI", "http://127.0.0.1:8080/callback");
    }

    /** PKCE is a public-client flow, so a client secret is not required. */
    public boolean spotifyConfigured() {
        return isReal(spotifyClientId(), PLACEHOLDER_CLIENT_ID);
    }

    // ---- LLM ----
    public String llmApiKey() {
        return get("LLM_API_KEY");
    }

    /**
     * The configured model, or the default from {@code musicmind.properties} if it
     * is unset, still a placeholder, or a leftover Gemini model name.
     */
    public String llmModel() {
        String model = get("LLM_MODEL");
        boolean usable = isReal(model, PLACEHOLDER_MODEL)
                && !model.toLowerCase().startsWith(LEGACY_MODEL_PREFIX);
        return usable ? model : text("llm.defaultModel");
    }

    public boolean llmConfigured() {
        return isReal(llmApiKey(), PLACEHOLDER_LLM_KEY);
    }

    public String openAiBaseUrl() {
        return text("openai.baseUrl");
    }

    public int openAiTimeoutSeconds() {
        return integer("openai.timeoutSeconds");
    }

    // ---- Persistence (PostgreSQL) ----
    public String databaseUrl() {
        return get("DATABASE_URL", "jdbc:postgresql://localhost:5432/musicmind");
    }

    public String databaseUser() {
        return get("DATABASE_USER", "musicmind");
    }

    public String databasePassword() {
        return get("DATABASE_PASSWORD", "");
    }

    public int databaseConnectTimeoutSeconds() {
        return integer("database.connectTimeoutSeconds");
    }

    // ---- Tunables ----
    public double playlistDurationTolerance() {
        return Double.parseDouble(text("playlist.durationTolerance"));
    }

    /** Fewest tracks a valid playlist holds (F7 minimum-length rule). */
    public int playlistMinTracks() {
        return integer("playlist.minTracks");
    }

    /** Most tracks a valid playlist holds (F7 maximum-length rule); Spotify's own limit by default. */
    public int playlistMaxTracks() {
        return integer("playlist.maxTracks");
    }

    public int avgTrackMinutes() {
        return integer("strategy.avgTrackMinutes");
    }

    public int defaultTrackCount() {
        return integer("strategy.defaultTrackCount");
    }

    public String spotifyApiBaseUrl() {
        return text("spotify.apiBaseUrl");
    }

    public String spotifyTokenUrl() {
        return text("spotify.tokenUrl");
    }

    public int spotifySearchPageSize() {
        return integer("spotify.searchPageSize");
    }

    public int spotifyLibraryPageSize() {
        return integer("spotify.libraryPageSize");
    }

    public int spotifyMaxItems() {
        return integer("spotify.maxItems");
    }

    /** Most saved tracks read from the library; beyond it the profile says some were not checked. */
    public int spotifyMaxSavedTracks() {
        return integer("spotify.maxSavedTracks");
    }

    public int spotifyRetryCount() {
        return integer("spotify.retryCount");
    }

    public int spotifyMaxWaitSeconds() {
        return integer("spotify.maxWaitSeconds");
    }

    // ---- MusicBrainz (genre metadata) ----

    public String musicBrainzBaseUrl() {
        return text("musicbrainz.baseUrl");
    }

    public String musicBrainzUserAgent() {
        return text("musicbrainz.userAgent");
    }

    public long musicBrainzMinIntervalMillis() {
        return Long.parseLong(text("musicbrainz.minIntervalMillis"));
    }

    public int musicBrainzMinScore() {
        return integer("musicbrainz.minScore");
    }

    public int musicBrainzResultLimit() {
        return integer("musicbrainz.resultLimit");
    }

    public int musicBrainzRetryWaitSeconds() {
        return integer("musicbrainz.retryWaitSeconds");
    }

    public int musicBrainzMaxWaitSeconds() {
        return integer("musicbrainz.maxWaitSeconds");
    }

    public int musicBrainzTimeoutSeconds() {
        return integer("musicbrainz.timeoutSeconds");
    }

    public int musicBrainzCacheDays() {
        return integer("musicbrainz.cacheDays");
    }

    /** Failed lookups in a row after which MusicBrainz lookups pause. */
    public int musicBrainzMaxConsecutiveFailures() {
        return integer("musicbrainz.maxConsecutiveFailures");
    }

    /** How long, in seconds, lookups pause after too many failures in a row. */
    public int musicBrainzCooldownSeconds() {
        return integer("musicbrainz.cooldownSeconds");
    }

    /** Credits MusicBrainz as the genre source; shown wherever genres are shown. */
    public String genreAttribution() {
        return text("text.genreAttribution");
    }

    /** Maximum characters of a raw HTTP response body to include in an error message. */
    public int errorBodyLimit() {
        return integer("http.errorBodyLimit");
    }

    public String spotifyAuthorizeUrl() {
        return text("spotify.authorizeUrl");
    }

    /** Page size when listing the user's playlists. */
    public int spotifyUserPlaylistPageSize() {
        return integer("spotify.userPlaylistPageSize");
    }

    /** Page size when reading playlist items. */
    public int spotifyPlaylistPageSize() {
        return integer("spotify.playlistPageSize");
    }

    /** Most URIs sent in one add-items or remove-items request. */
    public int spotifyAddBatchSize() {
        return integer("spotify.addBatchSize");
    }

    public int spotifyTopItemsLimit() {
        return integer("spotify.topItemsLimit");
    }

    public int spotifyRecentlyPlayedLimit() {
        return integer("spotify.recentlyPlayedLimit");
    }

    /** Page size for {@code /artists/{id}/albums}; Spotify rejects anything above 10. */
    public int spotifyArtistAlbumPageSize() {
        return integer("spotify.artistAlbumPageSize");
    }

    /** Page size for {@code /albums/{id}/tracks}. */
    public int spotifyAlbumTrackPageSize() {
        return integer("spotify.albumTrackPageSize");
    }

    public int promptMaxGenres() {
        return integer("prompt.maxGenres");
    }

    public int parserMinTargetMinutes() {
        return integer("parser.minTargetMinutes");
    }

    public int parserMaxTargetMinutes() {
        return integer("parser.maxTargetMinutes");
    }

    public int parserDefaultTargetMinutes() {
        return integer("parser.defaultTargetMinutes");
    }

    public double parserDefaultFamiliarRatio() {
        return Double.parseDouble(text("parser.defaultFamiliarRatio"));
    }

    public String parserDefaultPlaylistName() {
        return text("parser.defaultPlaylistName");
    }

    // ---- Playlist commands (F5, F6) ----

    /** Undoable steps kept in memory per run; older ones fall off the end. */
    public int commandHistoryLimit() {
        return integer("command.historyLimit");
    }

    /** Results asked of Spotify when an edit instruction names a track to add or swap in (at most 10). */
    public int commandSearchLimit() {
        return integer("command.searchLimit");
    }

    /** Most tracks an edit prompt lists; longer playlists are refused. */
    public int editMaxPromptTracks() {
        return integer("edit.maxPromptTracks");
    }

    /** Most tracks a mood edit swaps out (F6 genre heuristic). */
    public int editMoodMaxChanges() {
        return integer("edit.mood.maxChanges");
    }

    /** Tracks a mood edit adds when it cannot tell which existing tracks misfit. */
    public int editMoodAddCount() {
        return integer("edit.mood.addCount");
    }

    // ---- Playlist creation (F5) and negotiation (F11) ----

    /** Validation failures that send a run back to selection before it gives up. */
    public int planMaxValidationRetries() {
        return integer("plan.maxValidationRetries");
    }

    /** A goal parsed with less confidence than this asks the user to clarify. */
    public double planMinConfidence() {
        return Double.parseDouble(text("plan.minConfidence"));
    }

    /** The confidence assumed when the model's plan reply does not state one. */
    public double planDefaultConfidence() {
        return Double.parseDouble(text("plan.defaultConfidence"));
    }

    /** Most genres from the mood map and preferred styles that steer one playlist. */
    public int planMaxSeedGenres() {
        return integer("plan.maxSeedGenres");
    }

    /** Relaxations accepted in one run before the request is dropped. */
    public int planMaxNegotiationRounds() {
        return integer("plan.maxNegotiationRounds");
    }

    // ---- Natural-language search (F3) ----

    /** Total tracks requested per search; the adapter fetches them in pages of at most the search page size. */
    public int searchResultLimit() {
        return integer("search.resultLimit");
    }

    /** Filter fields an LLM-built search may use; any other field is dropped so it cannot empty a search. */
    public List<String> searchAllowedFilters() {
        return Arrays.stream(text("search.allowedFilters").split(","))
                .map(s -> s.trim().toLowerCase(java.util.Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
    }

    /** A parsed search query below this confidence falls back to a raw keyword search. */
    public double searchMinConfidence() {
        return Double.parseDouble(text("search.minConfidence"));
    }

    /** Genres from the mood map that replace the mood words taken out of a search query. */
    public int searchMoodGenres() {
        return integer("search.moodGenres");
    }

    public double searchTitleMatchWeight() {
        return Double.parseDouble(text("search.weight.titleMatch"));
    }

    public double searchSpotifyOrderWeight() {
        return Double.parseDouble(text("search.weight.spotifyOrder"));
    }

    /** Shown whenever a search ran as a plain keyword search instead of an interpreted one. */
    public String searchFallbackLabel() {
        return text("text.searchFallbackLabel");
    }

    /** Template with one {@code %s}: the text that found nothing. */
    public String searchNoResultsTemplate() {
        return text("text.searchNoResults");
    }

    /** Template with one {@code %s}: the relaxed query to try. */
    public String searchRelaxedHintTemplate() {
        return text("text.searchRelaxedHint");
    }

    public String searchNoRelaxedHint() {
        return text("text.searchNoRelaxedHint");
    }

    // ---- Personalised discovery (F4) ----

    public double discoverDefaultFamiliarRatio() {
        return Double.parseDouble(text("discover.defaultFamiliarRatio"));
    }

    /** A requested familiar share at or above this selects the FamiliarStrategy. */
    public double discoverFamiliarStrategyMin() {
        return Double.parseDouble(text("discover.familiarStrategyMin"));
    }

    /** A requested familiar share at or below this selects the DiscoveryStrategy. */
    public double discoverDiscoveryStrategyMax() {
        return Double.parseDouble(text("discover.discoveryStrategyMax"));
    }

    public int discoverInitialGenres() {
        return integer("discover.initialGenres");
    }

    public int discoverArtistSeeds() {
        return integer("discover.artistSeeds");
    }

    public int discoverAlbumsPerArtist() {
        return integer("discover.albumsPerArtist");
    }

    public int discoverSearchLimit() {
        return integer("discover.searchLimit");
    }

    public int discoverMinResults() {
        return integer("discover.minResults");
    }

    public int discoverWidenSteps() {
        return integer("discover.widenSteps");
    }

    public int discoverWidenGenresPerStep() {
        return integer("discover.widenGenresPerStep");
    }

    public double discoverWidenNoveltyStep() {
        return Double.parseDouble(text("discover.widenNoveltyStep"));
    }

    public double discoverFitWeight() {
        return Double.parseDouble(text("discover.weight.fit"));
    }

    public double discoverGenreWeight() {
        return Double.parseDouble(text("discover.weight.genre"));
    }

    public double discoverGenreTitlePenalty() {
        return Double.parseDouble(text("discover.weight.genreTitlePenalty"));
    }

    public double discoverAcceptBoost() {
        return Double.parseDouble(text("discover.feedback.acceptBoost"));
    }

    public double discoverRejectPenalty() {
        return Double.parseDouble(text("discover.feedback.rejectPenalty"));
    }

    /** What a track id or Spotify track URI typed for feedback must look like. */
    public java.util.regex.Pattern feedbackTrackIdPattern() {
        return java.util.regex.Pattern.compile(text("feedback.trackIdPattern"));
    }

    /** Wording template or phrase from {@code musicmind.properties}; keys are {@code text.*}. */
    public String wording(String key) {
        return text("text." + key);
    }

    // ---- Taste profile and trends ----

    /** Time ranges from most recent to oldest, as the user types them (short, medium, long). */
    public List<String> profileRanges() {
        return Arrays.stream(text("profile.ranges").split(",")).map(String::trim).toList();
    }

    public String profileDefaultRange() {
        return text("profile.defaultRange");
    }

    /** Fewer top artists or top tracks than this makes a profile partial. */
    public int profileMinItems() {
        return integer("profile.minItems");
    }

    /** A stored profile older than this many hours is rebuilt before discovery uses it. */
    public int profileMaxAgeHours() {
        return integer("profile.maxAgeHours");
    }

    /** A range with genre data for a smaller share of its artists than this makes the trend genre changes partial. */
    public double trendMinGenreCoverage() {
        return Double.parseDouble(text("trends.minGenreCoverage"));
    }

    public int profileFallbackGenres() {
        return integer("profile.fallbackGenres");
    }

    /** Appended to every non-LLM summary or narrative so it is never mistaken for AI output. */
    public String computedLabel() {
        return text("text.computedLabel");
    }

    /** The line both interfaces show to credit Spotify for the data. */
    public String spotifyAttribution() {
        return text("text.spotifyAttribution");
    }

    /** The single shared truncation of a raw HTTP body for error messages. */
    public String truncateBody(String body) {
        int limit = errorBodyLimit();
        return body == null || body.length() <= limit ? body : body.substring(0, limit) + "...";
    }

    private String text(String key) {
        String value = tunables.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("musicmind.properties is missing " + key);
        }
        return value.trim();
    }

    private int integer(String key) {
        return Integer.parseInt(text(key));
    }

    /** True when a value is present and is not the shipped placeholder. */
    private static boolean isReal(String value, String placeholder) {
        return value != null && !value.isBlank() && !value.equals(placeholder);
    }
}

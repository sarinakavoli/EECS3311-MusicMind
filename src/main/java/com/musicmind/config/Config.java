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

    public int spotifyRetryCount() {
        return integer("spotify.retryCount");
    }

    public int spotifyMaxWaitSeconds() {
        return integer("spotify.maxWaitSeconds");
    }

    /** Maximum characters of a raw HTTP response body to include in an error message. */
    public int errorBodyLimit() {
        return integer("http.errorBodyLimit");
    }

    public String spotifyAuthorizeUrl() {
        return text("spotify.authorizeUrl");
    }

    /** Page size when reading playlist items. */
    public int spotifyPlaylistPageSize() {
        return integer("spotify.playlistPageSize");
    }

    /** Most URIs sent in one add-items request. */
    public int spotifyAddBatchSize() {
        return integer("spotify.addBatchSize");
    }

    public int spotifyTopItemsLimit() {
        return integer("spotify.topItemsLimit");
    }

    public int spotifyRecentlyPlayedLimit() {
        return integer("spotify.recentlyPlayedLimit");
    }

    /** Page size for artist albums and album tracks. */
    public int spotifyAlbumPageSize() {
        return integer("spotify.albumPageSize");
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

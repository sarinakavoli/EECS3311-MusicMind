package com.musicmind.config;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * Typed access to configuration from the local {@code .env} file (git-ignored)
 * with a fallback to real environment variables. Secrets never live in code —
 * only {@code .env.example} is tracked; real keys are read from {@code .env}.
 */
public final class Config {

    private final Dotenv dotenv;

    private Config(Dotenv dotenv) {
        this.dotenv = dotenv;
    }

    /** Load configuration from {@code .env} in the working directory (optional). */
    public static Config load() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        return new Config(dotenv);
    }

    /** Raw lookup: {@code .env} first, then the process environment. */
    public String get(String key, String defaultValue) {
        String value = dotenv.get(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key);
        }
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
        return isReal(spotifyClientId(), "your_client_id_here");
    }

    // ---- LLM ----
    public String llmApiKey() {
        return get("LLM_API_KEY");
    }

    public String llmModel() {
        return get("LLM_MODEL", "gemini-2.0-flash");
    }

    public boolean llmConfigured() {
        return isReal(llmApiKey(), "your_llm_api_key_here");
    }

    // ---- Persistence ----
    /** Path to the local SQLite database file (created on first run). */
    public String databasePath() {
        return get("DATABASE_PATH", "musicmind.db");
    }

    /** JDBC URL for the SQLite store. */
    public String databaseUrl() {
        return "jdbc:sqlite:" + databasePath();
    }

    /** True when a value is present and is not the shipped placeholder. */
    private static boolean isReal(String value, String placeholder) {
        return value != null && !value.isBlank() && !value.equals(placeholder);
    }
}

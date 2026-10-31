package com.musicmind.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    @Test
    void modelDefaultsToTheValueInMusicmindProperties() {
        assertEquals("gpt-4o", Config.of(Map.of()).llmModel());
    }

    @Test
    void placeholderAndGeminiModelNamesAreIgnored() {
        assertEquals("gpt-4o", Config.of(Map.of("LLM_MODEL", "your_model_name_here")).llmModel());
        assertEquals("gpt-4o", Config.of(Map.of("LLM_MODEL", "gemini-2.0-flash")).llmModel());
        assertEquals("gpt-4o", Config.of(Map.of("LLM_MODEL", "Gemini-1.5-pro")).llmModel());
    }

    @Test
    void aRealModelNameIsUsed() {
        assertEquals("gpt-4o-mini", Config.of(Map.of("LLM_MODEL", "gpt-4o-mini")).llmModel());
    }

    @Test
    void llmIsConfiguredOnlyWithARealKey() {
        assertFalse(Config.of(Map.of()).llmConfigured());
        assertFalse(Config.of(Map.of("LLM_API_KEY", "your_llm_api_key_here")).llmConfigured());
        assertTrue(Config.of(Map.of("LLM_API_KEY", "k")).llmConfigured());
    }

    @Test
    void tunablesComeFromTheBundledProperties() {
        Config c = Config.of(Map.of());
        assertEquals(0.10, c.playlistDurationTolerance(), 1e-9);
        assertEquals(4, c.avgTrackMinutes());
        assertEquals(20, c.defaultTrackCount());
        assertEquals(10, c.spotifySearchPageSize());
        assertEquals(50, c.spotifyLibraryPageSize());
        assertEquals(500, c.spotifyMaxItems());
        assertEquals(3, c.spotifyRetryCount());
        assertEquals(60, c.spotifyMaxWaitSeconds());
        assertEquals("https://api.spotify.com/v1", c.spotifyApiBaseUrl());
        assertEquals("https://accounts.spotify.com/api/token", c.spotifyTokenUrl());
        assertEquals("https://api.openai.com/v1", c.openAiBaseUrl());
        assertEquals(30, c.openAiTimeoutSeconds());
        assertEquals(5, c.databaseConnectTimeoutSeconds());
        assertTrue(c.errorBodyLimit() > 0);
    }

    @Test
    void remainingSpotifyLiteralsAreTunables() {
        Config c = Config.of(Map.of());
        assertEquals("https://accounts.spotify.com/authorize", c.spotifyAuthorizeUrl());
        assertEquals(100, c.spotifyPlaylistPageSize());
        assertEquals(100, c.spotifyAddBatchSize());
        assertEquals(20, c.spotifyTopItemsLimit());
        assertEquals(50, c.spotifyRecentlyPlayedLimit());
        assertEquals(50, c.spotifyAlbumPageSize());
    }

    @Test
    void databasePasswordHasNoBuiltInDefault() {
        assertEquals("", Config.of(Map.of()).databasePassword());
        assertEquals("pw", Config.of(Map.of("DATABASE_PASSWORD", "pw")).databasePassword());
    }

    @Test
    void truncateBodyKeepsShortTextAndCutsLongText() {
        Config c = Config.of(Map.of());
        assertEquals("short", c.truncateBody("short"));
        assertEquals(null, c.truncateBody(null));
        String cut = c.truncateBody("x".repeat(c.errorBodyLimit() + 50));
        assertEquals(c.errorBodyLimit() + 3, cut.length());
        assertTrue(cut.endsWith("..."));
    }
}

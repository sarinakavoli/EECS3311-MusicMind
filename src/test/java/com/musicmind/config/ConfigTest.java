package com.musicmind.config;

import org.junit.jupiter.api.Test;

import java.util.List;
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
        assertEquals(10, c.spotifyArtistAlbumPageSize());
        assertEquals(50, c.spotifyAlbumTrackPageSize());
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

    @Test
    void profileTunablesComeFromTheBundledProperties() {
        Config c = Config.of(Map.of());
        assertEquals(List.of("short", "medium", "long"), c.profileRanges());
        assertEquals("medium", c.profileDefaultRange());
        assertEquals(5, c.profileMinItems());
        assertEquals(3, c.profileFallbackGenres());
        assertTrue(c.computedLabel().contains("not AI-written"));
        assertTrue(c.spotifyAttribution().contains("Spotify"));
    }

    @Test
    void searchTunablesComeFromTheBundledProperties() {
        Config c = Config.of(Map.of());
        assertEquals(20, c.searchResultLimit());
        assertEquals(0.5, c.searchMinConfidence());
        assertEquals(1.0, c.searchTitleMatchWeight() + c.searchSpotifyOrderWeight(), 1e-9);
        assertTrue(c.searchFallbackLabel().contains("keyword"));
        assertTrue(c.searchNoResultsTemplate().contains("%s"));
        assertTrue(c.searchRelaxedHintTemplate().contains("%s"));
        assertFalse(c.searchNoRelaxedHint().isBlank());
    }

    @Test
    void promptAndParserTunablesComeFromTheBundledProperties() {
        Config c = Config.of(Map.of());
        assertEquals(10, c.promptMaxGenres());
        assertEquals(1, c.parserMinTargetMinutes());
        assertEquals(600, c.parserMaxTargetMinutes());
        assertEquals(60, c.parserDefaultTargetMinutes());
        assertEquals(0.5, c.parserDefaultFamiliarRatio(), 1e-9);
        assertEquals("MusicMind Playlist", c.parserDefaultPlaylistName());
    }

    @Test
    void environmentSettingsFallBackToTheirDefaultsAndAreTrimmed() {
        Config none = Config.of(Map.of());
        assertEquals("http://127.0.0.1:8080/callback", none.spotifyRedirectUri());
        assertEquals("jdbc:postgresql://localhost:5432/musicmind", none.databaseUrl());
        assertEquals("musicmind", none.databaseUser());
        assertFalse(none.spotifyConfigured());

        Config set = Config.of(Map.of("SPOTIFY_CLIENT_ID", "  id123 ", "DATABASE_USER", "me"));
        assertEquals("id123", set.spotifyClientId());
        assertTrue(set.spotifyConfigured());
        assertEquals("me", set.databaseUser());
        assertFalse(Config.of(Map.of("SPOTIFY_CLIENT_ID", "your_client_id_here")).spotifyConfigured());
    }

    @Test
    void discoveryTunablesAndWordingComeFromTheBundledProperties() {
        Config c = Config.of(Map.of());
        assertEquals(0.7, c.discoverDefaultFamiliarRatio(), 1e-9);
        assertTrue(c.discoverFamiliarStrategyMin() > c.discoverDiscoveryStrategyMax());
        assertEquals(10, c.discoverMinResults());
        assertEquals(2, c.discoverWidenSteps());
        assertEquals(0.2, c.discoverWidenNoveltyStep(), 1e-9);
        assertTrue(c.discoverRejectPenalty() > 0 && c.discoverAcceptBoost() > 0);
        assertTrue(c.wording("discoverNoResults").length() > 0);
    }

    @Test
    void footerShowsLastfmCredit() {
        assertEquals("Song tags from Last.fm", Config.of(Map.of()).lastfmAttribution());
        assertTrue(Config.of(Map.of()).lastfmOffNotice().contains("LASTFM_API_KEY"));
    }

    @Test
    void noMusicBrainzKeysLeft() throws java.io.IOException {
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream in = Config.class.getResourceAsStream("/musicmind.properties")) {
            props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        assertTrue(props.stringPropertyNames().stream().noneMatch(k -> k.toLowerCase().contains("musicbrainz")),
                props.stringPropertyNames().toString());
        assertFalse(props.stringPropertyNames().contains("text.genreAttribution"));
    }

    @Test
    void lastfmTagTunablesAndBanner() {
        Config c = Config.of(Map.of());
        assertEquals(10, c.lastfmMinTagCount());
        assertEquals(10, c.lastfmArtistTagsKept());
        assertEquals(30, c.lastfmCacheDays());
        assertEquals(3, c.lastfmMaxConsecutiveFailures());
        assertEquals(300, c.lastfmCooldownSeconds());
        assertTrue(c.lastfmTagBlocklist().containsAll(java.util.Set.of("seen live", "favorites", "under 2000 listeners")));
        assertEquals("Song and artist tags from Last.fm are off (no LASTFM_API_KEY), so genres are unknown.",
                c.tagsOffBanner());
    }

    @Test
    void guiDisplayCountsComeFromTheBundledProperties() {
        Config c = Config.of(Map.of());
        assertEquals(8, c.guiGenresShown());
        assertEquals(10, c.guiArtistsShown());
        assertEquals(20, c.guiBlockedTitlesShown());
        assertEquals(10, c.guiTracksShown());
        assertEquals(3, c.guiTrendGenresShown());
    }

    @Test
    void synonymsUseTheWholeTagKey_andASingleWordKeyOnlyForAOneWordTag() {
        Config c = Config.of(Map.of());
        assertFalse(c.lastfmSynonyms("late night running").contains("cardio"));
        assertTrue(c.lastfmSynonyms("running").contains("cardio"));
        assertTrue(c.lastfmSynonyms("slow dance").contains("romantic"));
    }
}

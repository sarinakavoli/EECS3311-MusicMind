package com.musicmind.music;

import com.musicmind.config.Config;
import com.musicmind.domain.TagTrack;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LastFmAdapter against a local HttpServer: no network. */
class LastFmAdapterTest {

    private HttpServer server;
    private final List<String> rawQueries = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/2.0/", exchange -> {
            rawQueries.add(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private LastFmAdapter adapter(boolean withKey) {
        Map<String, String> settings = withKey ? Map.of("LASTFM_API_KEY", "test-key") : Map.of();
        Config config = Config.of(settings).withTunables(Map.of(
                "lastfm.apiBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/2.0/"));
        return new LastFmAdapter(config);
    }

    @Test
    void topTracks_parsesTitlesAndArtists() {
        body = "{\"tracks\":{\"track\":[{\"name\":\"Song A\",\"artist\":{\"name\":\"Band A\"}},"
                + "{\"name\":\"Song B\",\"artist\":{\"name\":\"Band B\"}}]}}";
        List<TagTrack> tracks = adapter(true).topTracks("study", 10);
        assertEquals(List.of(new TagTrack("Song A", "Band A"), new TagTrack("Song B", "Band B")), tracks);
        assertTrue(rawQueries.get(0).contains("method=tag.gettoptracks"));
        assertTrue(rawQueries.get(0).contains("api_key=test-key"));
        assertTrue(rawQueries.get(0).contains("format=json"));
    }

    @Test
    void topTracks_noKey_emptyWithoutCalling() {
        assertTrue(adapter(false).topTracks("study", 10).isEmpty());
        assertTrue(rawQueries.isEmpty());
    }

    @Test
    void topTracks_httpError_throws() {
        status = 500;
        body = "boom";
        MusicServiceException e = assertThrows(MusicServiceException.class, () -> adapter(true).topTracks("study", 10));
        assertFalse(e.getMessage().contains("test-key"));
    }

    @Test
    void topTracks_errorBodyWithHttp200_throws() {
        body = "{\"error\":6,\"message\":\"Tag not found\"}";
        MusicServiceException e = assertThrows(MusicServiceException.class, () -> adapter(true).topTracks("zzz", 10));
        assertFalse(e.getMessage().contains("test-key"));
    }

    @Test
    void topTracks_malformedBody_throws() {
        body = "not json at all {";
        assertThrows(MusicServiceException.class, () -> adapter(true).topTracks("study", 10));
        body = "[1,2]";
        assertThrows(MusicServiceException.class, () -> adapter(true).topTracks("study", 10));
    }

    @Test
    void topTracks_tagIsUrlEncoded() {
        body = "{\"tracks\":{\"track\":[]}}";
        assertTrue(adapter(true).topTracks("hip hop & rap", 5).isEmpty());
        assertTrue(rawQueries.get(0).contains("tag=hip+hop+%26+rap"), rawQueries.get(0));
    }

    @Test
    void topTracks_respectsLimit() {
        body = "{\"tracks\":{\"track\":[{\"name\":\"A\",\"artist\":{\"name\":\"X\"}},"
                + "{\"name\":\"B\",\"artist\":{\"name\":\"Y\"}},{\"name\":\"C\",\"artist\":{\"name\":\"Z\"}}]}}";
        assertEquals(2, adapter(true).topTracks("study", 2).size());
        assertTrue(rawQueries.get(0).contains("limit=2"));
    }

    @Test
    void topTracks_singleObjectTrack_parses() {
        body = "{\"tracks\":{\"track\":{\"name\":\"Only\",\"artist\":{\"name\":\"Solo\"}}}}";
        assertEquals(List.of(new TagTrack("Only", "Solo")), adapter(true).topTracks("study", 10));
    }

    @Test
    void topTracks_malformedElementSkipped_restKept() {
        body = "{\"tracks\":{\"track\":[{\"name\":\"A\"},42,{\"name\":\"B\",\"artist\":{\"name\":\"Y\"}},"
                + "{\"artist\":{\"name\":\"Q\"}}]}}";
        assertEquals(List.of(new TagTrack("B", "Y")), adapter(true).topTracks("study", 10));
    }

    @Test
    void topTracks_blankTagOrNonPositiveLimit_noRequest() {
        assertTrue(adapter(true).topTracks("  ", 10).isEmpty());
        assertTrue(adapter(true).topTracks(null, 10).isEmpty());
        assertTrue(adapter(true).topTracks("study", 0).isEmpty());
        assertTrue(adapter(true).topTracks("study", -3).isEmpty());
        assertTrue(rawQueries.isEmpty());
    }

    @Test
    void topTracks_connectionFailure_messageHasNoKey() {
        int port = server.getAddress().getPort();
        server.stop(0);
        Config config = Config.of(Map.of("LASTFM_API_KEY", "secret-key-123")).withTunables(Map.of(
                "lastfm.apiBaseUrl", "http://127.0.0.1:" + port + "/2.0/"));
        MusicServiceException e = assertThrows(MusicServiceException.class,
                () -> new LastFmAdapter(config).topTracks("study", 5));
        assertFalse(e.getMessage().contains("secret-key-123"));
        assertFalse(String.valueOf(e.getCause()).contains("secret-key-123"));
    }

    @Test
    void userAgent_isNotMusicBrainzSpecific() {
        Config c = Config.of(Map.of());
        assertTrue(c.lastfmUserAgent().startsWith("MusicMind/"));
        assertFalse(c.lastfmUserAgent().toLowerCase().contains("musicbrainz"));
        assertFalse(c.lastfmUserAgent().isBlank());
    }

    // ---- track.gettoptags ----

    @Test
    void trackTags_parsesNamesInOrder() {
        body = "{\"toptags\":{\"tag\":[{\"count\":100,\"name\":\"Slow Dance\"},{\"count\":60,\"name\":\"romantic\"}]}}";
        assertEquals(List.of("Slow Dance", "romantic"), adapter(true).trackTags("Some Band", "Some Song"));
        assertTrue(rawQueries.get(0).contains("method=track.gettoptags"), rawQueries.get(0));
        assertTrue(rawQueries.get(0).contains("artist=Some+Band"), rawQueries.get(0));
        assertTrue(rawQueries.get(0).contains("track=Some+Song"), rawQueries.get(0));
        assertTrue(rawQueries.get(0).contains("autocorrect=1"), rawQueries.get(0));
    }

    @Test
    void trackTags_noKeyOrBlankNames_noRequest() {
        assertTrue(adapter(false).trackTags("A", "B").isEmpty());
        assertTrue(adapter(true).trackTags(" ", "B").isEmpty());
        assertTrue(adapter(true).trackTags("A", null).isEmpty());
        assertTrue(rawQueries.isEmpty());
    }

    @Test
    void trackTags_unknownSongIsEmptyNotAnError() {
        body = "{\"error\":6,\"message\":\"Track not found\"}";
        assertTrue(adapter(true).trackTags("A", "B").isEmpty());
        body = "{\"toptags\":{}}";
        assertTrue(adapter(true).trackTags("A", "B").isEmpty());
    }

    @Test
    void trackTags_otherErrorsAndMalformedBodiesThrowWithoutTheKey() {
        body = "{\"error\":10,\"message\":\"Invalid API key\"}";
        MusicServiceException e = assertThrows(MusicServiceException.class, () -> adapter(true).trackTags("A", "B"));
        assertFalse(e.getMessage().contains("test-key"));
        body = "nope {";
        assertThrows(MusicServiceException.class, () -> adapter(true).trackTags("A", "B"));
        status = 503;
        MusicServiceException http = assertThrows(MusicServiceException.class, () -> adapter(true).trackTags("A", "B"));
        assertFalse(http.getMessage().contains("test-key"));
    }

    @Test
    void trackTags_singleObjectAndMalformedElements() {
        body = "{\"toptags\":{\"tag\":{\"name\":\"only\"}}}";
        assertEquals(List.of("only"), adapter(true).trackTags("A", "B"));
        body = "{\"toptags\":{\"tag\":[{\"count\":1},7,{\"name\":\"ok\"},{\"name\":\" \"}]}}";
        assertEquals(List.of("ok"), adapter(true).trackTags("A", "B"));
    }

    @Test
    void trackTags_keepsOnlyTheConfiguredNumberOfTopTags() {
        StringBuilder sb = new StringBuilder("{\"toptags\":{\"tag\":[");
        for (int i = 0; i < 30; i++) sb.append(i > 0 ? "," : "").append("{\"name\":\"t").append(i).append("\"}");
        body = sb.append("]}}").toString();
        assertEquals(Config.of(Map.of()).lastfmTrackTagsKept(), adapter(true).trackTags("A", "B").size());
    }

    @Test
    void trackTags_connectionFailure_messageHasNoKeyOrTitle() {
        int port = server.getAddress().getPort();
        server.stop(0);
        Config config = Config.of(Map.of("LASTFM_API_KEY", "secret-key-123")).withTunables(Map.of(
                "lastfm.apiBaseUrl", "http://127.0.0.1:" + port + "/2.0/"));
        MusicServiceException e = assertThrows(MusicServiceException.class,
                () -> new LastFmAdapter(config).trackTags("Band", "Secret Title"));
        assertFalse(e.getMessage().contains("secret-key-123"));
        assertFalse(e.getMessage().contains("Secret Title"));
        assertFalse(String.valueOf(e.getCause()).contains("secret-key-123"));
    }
}

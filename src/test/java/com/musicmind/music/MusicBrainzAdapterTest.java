package com.musicmind.music;

import com.musicmind.config.Config;
import com.musicmind.domain.Artist;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MusicBrainzAdapter against a local HttpServer: no network, no real sleeping. */
class MusicBrainzAdapterTest {

    record Req(String path, String query, String userAgent) {
        String decodedQuery() { return URLDecoder.decode(query, StandardCharsets.UTF_8); }
    }

    record Reply(int status, String body, Map<String, String> headers) {
        static Reply ok(String body) { return new Reply(200, body, Map.of()); }
        static Reply status(int s) { return new Reply(s, "", Map.of()); }
    }

    /** Virtual time: sleeping advances the clock, so pacing is observable without waiting. */
    static class FakeTime implements MusicBrainzAdapter.Clock, MusicBrainzAdapter.Sleeper {
        long nowMillis = 1_000_000;
        final List<Long> sleeps = new ArrayList<>();
        @Override public long millis() { return nowMillis; }
        @Override public void sleepMillis(long ms) { sleeps.add(ms); nowMillis += ms; }
    }

    private HttpServer server;
    private final List<Req> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Req, Reply> handler = r -> Reply.status(404);
    private final FakeTime time = new FakeTime();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ws/2", exchange -> {
            Req req = new Req(exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("User-Agent"));
            requests.add(req);
            Reply reply = handler.apply(req);
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
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

    private MusicBrainzAdapter adapter() {
        Config config = Config.of(Map.of()).withTunables(Map.of(
                "musicbrainz.baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/ws/2"));
        return new MusicBrainzAdapter(config, time, time);
    }

    private static String search(String id, String name, int score, String... aliases) {
        StringBuilder a = new StringBuilder();
        for (String alias : aliases) {
            a.append(a.length() == 0 ? "" : ",").append("{\"name\":\"").append(alias).append("\"}");
        }
        return "{\"artists\":[{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"score\":" + score
                + ",\"aliases\":[" + a + "]}]}";
    }

    private static final String GENRES =
            "{\"genres\":[{\"name\":\"rock\",\"count\":2},{\"name\":\"jazz\",\"count\":9},{\"name\":\"folk\",\"count\":4}]}";

    /** Search returns {@code searchBody}; the genre lookup returns {@link #GENRES}. */
    private void serve(String searchBody) {
        handler = r -> r.path().endsWith("/artist/") || r.path().endsWith("/artist")
                ? Reply.ok(searchBody) : Reply.ok(GENRES);
    }

    private static Artist artist(String name) {
        return new Artist("sp1", name, List.of());
    }

    @Test
    void matchesTheArtistAndOrdersGenresByCountDescending() {
        serve(search("mbid-1", "Miles Davis", 100));

        assertEquals(List.of("jazz", "folk", "rock"), adapter().genresFor(artist("Miles Davis")));
    }

    @Test
    void sendsTheSearchAndGenreRequestsInTheDocumentedShape() {
        serve(search("mbid-1", "Miles Davis", 100));

        adapter().genresFor(artist("Miles Davis"));

        assertEquals(2, requests.size());
        Req find = requests.get(0);
        assertEquals("/ws/2/artist/", find.path());
        assertTrue(find.decodedQuery().contains("query=artist:\"Miles Davis\""), find.decodedQuery());
        assertTrue(find.decodedQuery().contains("limit=" + Config.of(Map.of()).musicBrainzResultLimit()));
        assertTrue(find.decodedQuery().contains("fmt=json"));
        Req genres = requests.get(1);
        assertEquals("/ws/2/artist/mbid-1", genres.path());
        assertTrue(genres.decodedQuery().contains("inc=genres") && genres.decodedQuery().contains("fmt=json"));
    }

    @Test
    void escapesQuotesInTheArtistNameSoTheQueryStaysOneTerm() {
        serve(search("mbid-1", "Say \\\"Hi\\\"", 100));

        adapter().genresFor(artist("Say \"Hi\""));

        assertTrue(requests.get(0).decodedQuery().contains("artist:\"Say \\\"Hi\\\"\""), requests.get(0).decodedQuery());
    }

    @Test
    void everyRequestCarriesTheConfiguredUserAgent() {
        serve(search("mbid-1", "Miles Davis", 100));

        adapter().genresFor(artist("Miles Davis"));

        String expected = Config.of(Map.of()).musicBrainzUserAgent();
        assertFalse(expected.isBlank());
        assertTrue(requests.stream().allMatch(r -> expected.equals(r.userAgent())), requests.toString());
    }

    @Test
    void aDifferentNameIsNotAMatchAndTheGenreLookupIsNeverMade() {
        serve(search("mbid-9", "Miles Davis Quintet", 100));

        assertTrue(adapter().genresFor(artist("Miles Davis")).isEmpty());
        assertEquals(1, requests.size());
    }

    @Test
    void aListedAliasMatches() {
        serve(search("mbid-1", "Shinichi Osawa", 100, "Mondo Grosso"));

        assertEquals(List.of("jazz", "folk", "rock"), adapter().genresFor(artist("Mondo Grosso")));
    }

    @Test
    void caseAndAccentsAreIgnoredWhenComparingNames() {
        serve(search("mbid-1", "Björk", 100));

        assertEquals(3, adapter().genresFor(artist("BJORK")).size());
    }

    @Test
    void aScoreBelowTheConfiguredMinimumIsNotAMatch() {
        int min = Config.of(Map.of()).musicBrainzMinScore();
        serve(search("mbid-1", "Miles Davis", min - 1));

        assertTrue(adapter().genresFor(artist("Miles Davis")).isEmpty());
        assertEquals(1, requests.size());
    }

    @Test
    void aScoreAtTheMinimumIsAMatch() {
        serve(search("mbid-1", "Miles Davis", Config.of(Map.of()).musicBrainzMinScore()));

        assertEquals(3, adapter().genresFor(artist("Miles Davis")).size());
    }

    @Test
    void aNoMatchIsAnAuthoritativeEmptyResultNotAFailure() {
        serve("{\"artists\":[]}");

        assertEquals(Optional.of(List.<String>of()), adapter().lookup(artist("Nobody")));
        assertTrue(adapter().notices().isEmpty());
    }

    @Test
    void anArtistWithoutANameIsNotLookedUp() {
        assertEquals(Optional.of(List.<String>of()), adapter().lookup(artist(" ")));
        assertTrue(requests.isEmpty());
    }

    @Test
    void a503IsRetriedOnceAfterHonouringRetryAfter() {
        int[] calls = {0};
        handler = r -> {
            if (r.path().endsWith("/artist/")) {
                return calls[0]++ == 0 ? new Reply(503, "", Map.of("Retry-After", "3"))
                        : Reply.ok(search("mbid-1", "Miles Davis", 100));
            }
            return Reply.ok(GENRES);
        };

        assertEquals(3, adapter().genresFor(artist("Miles Davis")).size());
        assertTrue(time.sleeps.contains(3000L), "slept " + time.sleeps);
        assertEquals(3, requests.size());
    }

    @Test
    void a503WithoutRetryAfterWaitsTheConfiguredTime() {
        int[] calls = {0};
        handler = r -> r.path().endsWith("/artist/") && calls[0]++ == 0
                ? Reply.status(503) : Reply.ok(r.path().endsWith("/artist/") ? search("m", "Miles Davis", 100) : GENRES);

        adapter().genresFor(artist("Miles Davis"));

        long expected = Config.of(Map.of()).musicBrainzRetryWaitSeconds() * 1000L;
        assertTrue(time.sleeps.contains(expected), "slept " + time.sleeps);
    }

    @Test
    void aSecond503GivesUpWithNoGenresAndANotice() {
        handler = r -> Reply.status(503);
        MusicBrainzAdapter adapter = adapter();

        assertEquals(Optional.empty(), adapter.lookup(artist("Miles Davis")));
        assertEquals(2, requests.size(), "one attempt and exactly one retry");
        assertEquals(1, adapter.notices().size());
        assertTrue(adapter.notices().get(0).contains("503"), adapter.notices().get(0));
    }

    @Test
    void aRetryAfterBeyondTheLimitIsNotWaitedFor() {
        long tooLong = Config.of(Map.of()).musicBrainzMaxWaitSeconds() + 1;
        handler = r -> new Reply(503, "", Map.of("Retry-After", String.valueOf(tooLong)));

        assertEquals(Optional.empty(), adapter().lookup(artist("Miles Davis")));
        assertEquals(1, requests.size());
    }

    @Test
    void anyOtherFailureReturnsNoGenresRecordsANoticeAndDoesNotThrow() {
        handler = r -> Reply.status(500);
        MusicBrainzAdapter adapter = adapter();

        assertTrue(adapter.genresFor(artist("Miles Davis")).isEmpty());
        assertEquals(Optional.empty(), adapter.lookup(artist("Miles Davis")));
        assertEquals(2, adapter.notices().size());
    }

    @Test
    void aMalformedResponseIsAFailureNotACrash() {
        handler = r -> Reply.ok("not json");
        MusicBrainzAdapter adapter = adapter();

        assertEquals(Optional.empty(), adapter.lookup(artist("Miles Davis")));
        assertEquals(1, adapter.notices().size());
    }

    @Test
    void anUnreachableServerIsAFailureNotACrash() {
        Config config = Config.of(Map.of()).withTunables(Map.of("musicbrainz.baseUrl", "http://127.0.0.1:1/ws/2"));
        MusicBrainzAdapter adapter = new MusicBrainzAdapter(config, time, time);

        assertEquals(Optional.empty(), adapter.lookup(artist("Miles Davis")));
        assertEquals(1, adapter.notices().size());
    }

    @Test
    void requestsAreSpacedByTheMinimumIntervalAcrossLookups() {
        serve(search("mbid-1", "Miles Davis", 100));
        MusicBrainzAdapter adapter = adapter();
        long interval = Config.of(Map.of()).musicBrainzMinIntervalMillis();

        List<Long> stamps = new ArrayList<>();
        handler = r -> {
            stamps.add(time.nowMillis);
            return r.path().endsWith("/artist/") ? Reply.ok(search("mbid-1", "Miles Davis", 100)) : Reply.ok(GENRES);
        };
        adapter.genresFor(artist("Miles Davis"));
        adapter.genresFor(artist("Miles Davis"));

        assertEquals(4, stamps.size());
        for (int i = 1; i < stamps.size(); i++) {
            assertTrue(stamps.get(i) - stamps.get(i - 1) >= interval,
                    "gap before request " + i + " was " + (stamps.get(i) - stamps.get(i - 1)));
        }
    }
}

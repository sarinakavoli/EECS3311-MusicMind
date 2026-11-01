package com.musicmind.music;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.musicmind.domain.Album;
import com.musicmind.domain.Artist;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.Track;
import com.musicmind.music.FakeSpotifyServer.Reply;
import com.musicmind.music.FakeSpotifyServer.Req;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives SpotifyAdapter against a local HttpServer and asserts on what goes over the wire. */
class SpotifyAdapterTest {

    @TempDir Path dir;
    FakeSpotifyServer api;
    SpotifyAdapter adapter;

    @BeforeEach
    void start() throws Exception {
        api = new FakeSpotifyServer();
        adapter = api.adapter(dir);
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private static String trackJson(int n) {
        return "{\"id\":\"t" + n + "\",\"uri\":\"spotify:track:t" + n + "\",\"name\":\"Song " + n
                + "\",\"duration_ms\":180000,\"explicit\":false,\"artists\":[{\"id\":\"a" + n + "\"}]}";
    }

    private static String decodedQuery(Req r) {
        return r.query() == null ? "" : URLDecoder.decode(r.query(), StandardCharsets.UTF_8);
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    // ---- listening data ----

    @Test
    void currentUserIdCallsMeWithBearerTokenAndCachesTheAnswer() {
        api.respond(r -> Reply.ok("{\"id\":\"sarina\"}"));

        assertEquals("sarina", adapter.currentUserId());
        assertEquals("sarina", adapter.currentUserId());

        assertEquals(1, api.requests.size());
        Req req = api.requests.get(0);
        assertEquals("GET", req.method());
        assertEquals("/v1/me", req.path());
        assertEquals("Bearer tok1", req.auth());
    }

    @Test
    void getTopItemsRequestsTopTracksForTheRange() {
        api.respond(r -> Reply.ok("{\"items\":[" + trackJson(1) + "]}"));

        List<Track> tracks = adapter.getTopItems("tracks", "short");

        Req req = api.requests.get(0);
        assertEquals("/v1/me/top/tracks", req.path());
        assertEquals("limit=20&time_range=short_term", req.query());
        assertEquals(1, tracks.size());
        assertEquals("spotify:track:t1", tracks.get(0).uri());
    }

    @Test
    void getTopItemsRejectsArtistsBecauseTheyHaveTheirOwnMethod() {
        assertThrows(IllegalArgumentException.class, () -> adapter.getTopItems("artists", "medium_term"));
        assertTrue(api.requests.isEmpty());
    }

    @Test
    void getTopArtistsReadsGenres() {
        api.respond(r -> Reply.ok("{\"items\":[{\"id\":\"a1\",\"name\":\"A\",\"genres\":[\"pop\",\"rock\"]},"
                + "{\"id\":\"a2\",\"name\":\"B\"}]}"));

        List<Artist> artists = adapter.getTopArtists("long_term");

        assertEquals("/v1/me/top/artists", api.requests.get(0).path());
        assertEquals("limit=20&time_range=long_term", api.requests.get(0).query());
        assertEquals(List.of("pop", "rock"), artists.get(0).genres());
        assertTrue(artists.get(1).genres().isEmpty());
    }

    @Test
    void getSavedTracksFollowsNextUntilItIsAbsentAndSkipsNullEntries() {
        api.respond(r -> {
            if (decodedQuery(r).contains("offset=50")) {
                return Reply.ok("{\"items\":[{\"track\":" + trackJson(3) + "}],\"next\":null}");
            }
            return Reply.ok("{\"items\":[{\"track\":" + trackJson(1) + "},{\"track\":null},{\"track\":"
                    + trackJson(2) + "}],\"next\":\"" + api.apiBase() + "/me/tracks?offset=50&limit=50\"}");
        });

        List<Track> tracks = adapter.getSavedTracks();

        assertEquals(List.of("t1", "t2", "t3"), tracks.stream().map(Track::id).toList());
        assertEquals(2, api.requests.size());
        assertEquals("/v1/me/tracks", api.requests.get(0).path());
        assertEquals("limit=50", api.requests.get(0).query());
    }

    private static String endlessPage(FakeSpotifyServer api, int items) {
        String body = IntStream.range(0, items).mapToObj(i -> "{\"track\":" + trackJson(i) + "}")
                .collect(Collectors.joining(","));
        return "{\"items\":[" + body + "],\"next\":\"" + api.apiBase() + "/me/tracks?offset=9&limit=50\"}";
    }

    private SpotifyAdapter adapterWith(com.musicmind.config.Config config) throws Exception {
        return new SpotifyAdapter(api.tokenStore(dir), api.apiBase(), config);
    }

    @Test
    void getSavedTracksStopsAtTheSavedTrackCapAndSaysItWasCut() throws Exception {
        com.musicmind.config.Config capped = com.musicmind.config.Config.defaults()
                .withTunables(java.util.Map.of("spotify.maxSavedTracks", "120"));
        api.respond(r -> Reply.ok(endlessPage(api, 50)));

        List<Track> tracks = adapterWith(capped).getSavedTracks();

        assertEquals(120, tracks.size());
        assertTrue(tracks instanceof TrackList list && list.isTruncated(), "the caller can tell tracks were left out");
        assertEquals(3, api.requests.size());
    }

    @Test
    void aPlaylistReadSaysWhereTheTracksReallyAreWhenEntriesAreSkipped() {
        api.respond(r -> Reply.ok("""
                {"items":[
                  {"item":{"id":null,"is_local":true,"uri":"spotify:local:x","name":"x"}},
                  {"item":{"id":"a","uri":"spotify:track:a","name":"A","duration_ms":1}},
                  {"item":null},
                  {"item":{"id":"b","uri":"spotify:track:b","name":"B","duration_ms":1}}
                ],"next":null}"""));

        List<Track> tracks = adapter.getPlaylistItems("p");

        assertEquals(2, tracks.size());
        assertEquals(1, TrackList.position(tracks, 0));
        assertEquals(3, TrackList.position(tracks, 1));
    }

    @Test
    void theSavedTrackCapDefaultsToFiveThousand() {
        api.respond(r -> Reply.ok(endlessPage(api, 50)));

        List<Track> tracks = adapter.getSavedTracks();

        assertEquals(5000, tracks.size());
        assertEquals(100, api.requests.size());
        assertTrue(((TrackList) tracks).isTruncated());
    }

    @Test
    void aLibraryThatEndsExactlyAtTheCapIsNotMarkedCut() throws Exception {
        com.musicmind.config.Config capped = com.musicmind.config.Config.defaults()
                .withTunables(java.util.Map.of("spotify.maxSavedTracks", "50"));
        api.respond(r -> Reply.ok(endlessPage(api, 50).replaceAll("\"next\":\"[^\"]*\"", "\"next\":null")));

        List<Track> tracks = adapterWith(capped).getSavedTracks();

        assertEquals(50, tracks.size());
        assertFalse(((TrackList) tracks).isTruncated());
    }

    @Test
    void getSavedTracksKeepsPagingWhenTheServerReducesThePageSize() {
        api.respond(r -> {
            if (r.query().contains("offset=")) {
                return Reply.ok("{\"items\":[{\"track\":" + trackJson(2) + "}],\"next\":null}");
            }
            return Reply.ok("{\"items\":[{\"track\":" + trackJson(1) + "}],\"next\":\""
                    + api.apiBase() + "/me/tracks?offset=1&limit=50\"}");
        });

        assertEquals(2, adapter.getSavedTracks().size());
    }

    @Test
    void getSavedTracksRefusesToSendTheTokenToAForeignNextUrl() {
        api.respond(r -> Reply.ok("{\"items\":[],\"next\":\"http://evil.example/steal\"}"));

        MusicServiceException e = assertThrows(MusicServiceException.class, adapter::getSavedTracks);
        assertTrue(e.getMessage().contains("unexpected"), e.getMessage());
    }

    @Test
    void getRecentlyPlayedReadsWrappedTracks() {
        api.respond(r -> Reply.ok("{\"items\":[{\"track\":" + trackJson(7) + ",\"played_at\":\"x\"}]}"));

        List<Track> tracks = adapter.getRecentlyPlayed();

        assertEquals("/v1/me/player/recently-played", api.requests.get(0).path());
        assertEquals("limit=50", api.requests.get(0).query());
        assertEquals("t7", tracks.get(0).id());
    }

    // ---- catalogue ----

    @Test
    void searchPagesInTensWithOffsetToSatisfyTheLimit() {
        api.respond(r -> {
            String q = decodedQuery(r);
            int offset = Integer.parseInt(q.replaceAll(".*offset=(\\d+).*", "$1"));
            int size = Integer.parseInt(q.replaceAll(".*limit=(\\d+).*", "$1"));
            String items = IntStream.range(offset, offset + size).mapToObj(SpotifyAdapterTest::trackJson)
                    .collect(Collectors.joining(","));
            return Reply.ok("{\"tracks\":{\"items\":[" + items + "],\"next\":\"more\"}}");
        });

        List<Track> tracks = adapter.search("jazz piano", 25);

        assertEquals(25, tracks.size());
        List<String> queries = api.requests.stream().map(SpotifyAdapterTest::decodedQuery).toList();
        assertEquals(List.of(
                "type=track&limit=10&offset=0&q=jazz piano",
                "type=track&limit=10&offset=10&q=jazz piano",
                "type=track&limit=5&offset=20&q=jazz piano"), queries);
        assertEquals("/v1/search", api.requests.get(0).path());
    }

    @Test
    void searchStopsWhenResultsRunOutAndSurvivesReducedPages() {
        api.respond(r -> {
            if (decodedQuery(r).contains("offset=0")) {
                return Reply.ok("{\"tracks\":{\"items\":[" + trackJson(1) + "," + trackJson(2) + "],\"next\":\"more\"}}");
            }
            return Reply.ok("{\"tracks\":{\"items\":[],\"next\":null}}");
        });

        List<Track> tracks = adapter.search("rare", 10);

        assertEquals(2, tracks.size());
        assertTrue(decodedQuery(api.requests.get(1)).contains("offset=2"),
                "the next offset follows the items actually returned");
    }

    @Test
    void searchWithSmallLimitMakesOneRequestOfThatSize() {
        api.respond(r -> Reply.ok("{\"tracks\":{\"items\":[" + trackJson(1) + "],\"next\":null}}"));

        adapter.search("x", 3);

        assertEquals(1, api.requests.size());
        assertTrue(decodedQuery(api.requests.get(0)).contains("limit=3&offset=0"));
    }

    @Test
    void getArtistAlbumsAndAlbumTracksUseTheirEndpoints() {
        api.respond(r -> r.path().endsWith("/albums")
                ? Reply.ok("{\"items\":[{\"id\":\"al1\",\"name\":\"Alb\",\"release_date\":\"2020\"}]}")
                : Reply.ok("{\"items\":[" + trackJson(1) + "]}"));

        List<Album> albums = adapter.getArtistAlbums("ar1");
        List<Track> tracks = adapter.getAlbumTracks("al1");

        assertEquals("/v1/artists/ar1/albums", api.requests.get(0).path());
        assertTrue(api.requests.get(0).query().contains("limit=10&"), api.requests.get(0).query());
        assertTrue(api.requests.get(1).query().contains("limit=50"), api.requests.get(1).query());
        assertEquals("al1", albums.get(0).id());
        assertEquals("/v1/albums/al1/tracks", api.requests.get(1).path());
        assertEquals(1, tracks.size());
    }

    // ---- playlists ----

    @Test
    void createPlaylistPostsToMeAndNotToTheRemovedUsersEndpoint() {
        api.respond(r -> Reply.ok("{\"id\":\"pl1\",\"name\":\"Road\"}"));

        Playlist created = adapter.createPlaylist("sarina", "Road");

        Req req = api.requests.get(0);
        assertEquals("POST", req.method());
        assertEquals("/v1/me/playlists", req.path());
        JsonObject body = json(req.body());
        assertEquals("Road", body.get("name").getAsString());
        assertEquals(false, body.get("public").getAsBoolean());
        assertEquals("pl1", created.spotifyId());
    }

    @Test
    void addItemsPostsUrisToTheItemsEndpointInBatchesOfAHundred() {
        api.respond(r -> Reply.ok("{\"snapshot_id\":\"s\"}"));
        List<String> uris = IntStream.range(0, 150).mapToObj(i -> "spotify:track:t" + i).toList();

        adapter.addItems("pl1", uris);

        assertEquals(2, api.requests.size());
        assertEquals("POST", api.requests.get(0).method());
        assertEquals("/v1/playlists/pl1/items", api.requests.get(0).path());
        assertEquals(100, json(api.requests.get(0).body()).getAsJsonArray("uris").size());
        assertEquals(50, json(api.requests.get(1).body()).getAsJsonArray("uris").size());
        assertEquals("spotify:track:t100",
                json(api.requests.get(1).body()).getAsJsonArray("uris").get(0).getAsString());
    }

    @Test
    void removeItemsDeletesWithAnItemsBody() {
        api.respond(r -> Reply.ok("{\"snapshot_id\":\"s\"}"));

        adapter.removeItems("pl1", List.of("spotify:track:a", "spotify:track:b"));

        Req req = api.requests.get(0);
        assertEquals("DELETE", req.method());
        assertEquals("/v1/playlists/pl1/items", req.path());
        assertEquals(json("{\"items\":[{\"uri\":\"spotify:track:a\"},{\"uri\":\"spotify:track:b\"}]}"),
                json(req.body()));
    }

    @Test
    void removeItemsDeletesInBatchesOfAHundred() {
        api.respond(r -> Reply.ok("{\"snapshot_id\":\"s\"}"));
        List<String> uris = IntStream.range(0, 250).mapToObj(i -> "spotify:track:t" + i).toList();

        adapter.removeItems("pl1", uris);

        assertEquals(3, api.requests.size());
        assertTrue(api.requests.stream().allMatch(r -> r.method().equals("DELETE")));
        assertEquals(List.of(100, 100, 50), api.requests.stream()
                .map(r -> json(r.body()).getAsJsonArray("items").size()).toList());
        assertEquals("spotify:track:t200", json(api.requests.get(2).body())
                .getAsJsonArray("items").get(0).getAsJsonObject().get("uri").getAsString());
    }

    @Test
    void reorderItemsPutsARangeMoveToTheItemsEndpoint() {
        api.respond(r -> Reply.ok("{\"snapshot_id\":\"s\"}"));

        adapter.reorderItems("pl1", 4, 1);

        Req req = api.requests.get(0);
        assertEquals("PUT", req.method());
        assertEquals("/v1/playlists/pl1/items", req.path());
        assertEquals(json("{\"range_start\":4,\"insert_before\":1,\"range_length\":1}"), json(req.body()));
    }

    @Test
    void getPlaylistItemsReadsItemWithTrackFallbackAndSkipsNullsAndLocalTracks() {
        String local = "{\"id\":null,\"uri\":\"spotify:local:x\",\"name\":\"Local\",\"is_local\":true,\"artists\":[]}";
        api.respond(r -> Reply.ok("{\"items\":[{\"item\":" + trackJson(1) + "},{\"track\":" + trackJson(2)
                + "},{\"item\":null},{\"item\":" + local + "},{\"item\":" + trackJson(3) + "}],\"next\":null}"));

        List<Track> tracks = adapter.getPlaylistItems("pl1");

        assertEquals(List.of("t1", "t2", "t3"), tracks.stream().map(Track::id).toList());
        assertEquals("/v1/playlists/pl1/items", api.requests.get(0).path());
        assertEquals("limit=100", api.requests.get(0).query());
    }

    @Test
    void getPlaylistItemsFollowsNext() {
        List<Integer> seen = new ArrayList<>();
        api.respond(r -> {
            seen.add(1);
            if (r.query().contains("offset=100")) {
                return Reply.ok("{\"items\":[{\"item\":" + trackJson(2) + "}],\"next\":null}");
            }
            return Reply.ok("{\"items\":[{\"item\":" + trackJson(1) + "}],\"next\":\""
                    + api.apiBase() + "/playlists/pl1/items?offset=100&limit=100\"}");
        });

        assertEquals(2, adapter.getPlaylistItems("pl1").size());
        assertEquals(2, seen.size());
    }

    @Test
    void getPlaylistItemsExplainsAForbiddenResponse() {
        api.respond(r -> Reply.status(403, "{\"error\":{\"status\":403}}", "X-Test", "1"));

        MusicServiceException e = assertThrows(MusicServiceException.class, () -> adapter.getPlaylistItems("pl1"));

        assertEquals(403, e.statusCode());
        assertTrue(e.getMessage().contains("owned or collaborative"), e.getMessage());
    }

    @Test
    void unfollowPlaylistDeletesFromTheLibraryByUri() {
        api.respond(r -> Reply.status(200));

        adapter.unfollowPlaylist("pl1");

        Req req = api.requests.get(0);
        assertEquals("DELETE", req.method());
        assertEquals("/v1/me/library", req.path());
        assertEquals("uris=spotify:playlist:pl1", decodedQuery(req));
    }

    // ---- errors and tokens ----

    @Test
    void a401ForcesATokenRefreshAndRetriesOnce() {
        api.respond(r -> "Bearer tok1".equals(r.auth()) ? Reply.status(401) : Reply.ok("{\"id\":\"sarina\"}"));

        assertEquals("sarina", adapter.currentUserId());

        assertEquals(List.of("Bearer tok1", "Bearer tok2"), api.requests.stream().map(Req::auth).toList());
        assertEquals(2, api.tokenRequests.size());
        assertTrue(api.tokenRequests.get(1).contains("grant_type=refresh_token"));
    }

    @Test
    void aSecond401RaisesAuthExpiredWithALoginMessage() {
        api.respond(r -> Reply.status(401));

        AuthExpiredException e = assertThrows(AuthExpiredException.class, adapter::currentUserId);

        assertEquals(2, api.requests.size(), "one attempt plus exactly one retry");
        assertTrue(e.getMessage().toLowerCase().contains("log in"), e.getMessage());
        assertEquals(401, e.statusCode());
    }

    @Test
    void a429CarriesTheRetryAfterHint() {
        api.respond(r -> Reply.status(429, "", "Retry-After", "7"));

        RateLimitException e = assertThrows(RateLimitException.class, adapter::currentUserId);

        assertEquals(7, e.retryAfterSeconds());
    }

    @Test
    void a5xxCarriesStatusAndRetryAfter() {
        api.respond(r -> Reply.status(503, "down", "Retry-After", "3"));

        MusicServiceException e = assertThrows(MusicServiceException.class, adapter::currentUserId);

        assertEquals(503, e.statusCode());
        assertEquals(3, e.retryAfterSeconds());
    }

    @Test
    void errorMessagesTruncateTheRawResponseBody() {
        api.respond(r -> new Reply(500, "z".repeat(5000), java.util.Map.of()));

        MusicServiceException e = assertThrows(MusicServiceException.class, () -> adapter.currentUserId());

        assertTrue(e.getMessage().contains("zzz"));
        assertTrue(e.getMessage().length() < 1000, "length " + e.getMessage().length());
    }
}

package com.musicmind.music;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.musicmind.config.Config;
import com.musicmind.domain.Album;
import com.musicmind.domain.Artist;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.Track;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter (GoF) that implements the internal {@link MusicService} over the
 * Spotify Web API. It translates between Spotify's JSON shapes and the app's
 * domain objects, so the rest of the system never depends on Spotify directly.
 * Authentication (PKCE) is delegated to {@link OAuthTokenStore}.
 *
 * <p>Dev-mode Spotify constraints are respected: discovery is built from the
 * user's own data (top items, saved, recently played, search, artist→albums→
 * tracks); {@code /recommendations} and audio-features are not used.
 */
public class SpotifyAdapter implements MusicService {

    private final int searchPage;
    private final int libraryPage;
    private final int maxItems;
    private final int maxSavedTracks;
    private final int playlistPage;
    private final int addBatch;
    private final int topLimit;
    private final int recentLimit;
    private final int artistAlbumPage;
    private final int albumTrackPage;
    private final Config config;
    private final HttpClient http = HttpClient.newHttpClient();
    private final OAuthTokenStore auth;
    private final String api;
    private final Gson gson = new Gson();
    private String cachedUserId;

    public SpotifyAdapter(String clientId, String redirectUri) {
        this(new OAuthTokenStore(clientId, redirectUri));
    }

    public SpotifyAdapter(OAuthTokenStore auth) {
        this(auth, Config.defaults());
    }

    /** The application's wiring: the same configuration also goes to {@link RetryingMusicService}. */
    public SpotifyAdapter(OAuthTokenStore auth, Config config) {
        this(auth, config.spotifyApiBaseUrl(), config);
    }

    /** API base URL seam, so tests can target a local server. */
    SpotifyAdapter(OAuthTokenStore auth, String apiBaseUrl) {
        this(auth, apiBaseUrl, Config.defaults());
    }

    /** As above, with explicit configuration (tests of tunable limits). */
    SpotifyAdapter(OAuthTokenStore auth, String apiBaseUrl, Config config) {
        this.auth = auth;
        this.api = apiBaseUrl;
        this.config = config;
        this.maxSavedTracks = config.spotifyMaxSavedTracks();
        this.searchPage = config.spotifySearchPageSize();
        this.libraryPage = config.spotifyLibraryPageSize();
        this.maxItems = config.spotifyMaxItems();
        this.playlistPage = config.spotifyPlaylistPageSize();
        this.addBatch = config.spotifyAddBatchSize();
        this.topLimit = config.spotifyTopItemsLimit();
        this.recentLimit = config.spotifyRecentlyPlayedLimit();
        this.artistAlbumPage = config.spotifyArtistAlbumPageSize();
        this.albumTrackPage = config.spotifyAlbumTrackPageSize();
    }

    // ---- read: listening data ----

    @Override
    public List<Artist> getTopArtists(String timeRange) {
        JsonObject body = get("/me/top/artists?limit=" + topLimit + "&time_range=" + range(timeRange));
        List<Artist> artists = new ArrayList<>();
        for (JsonElement e : array(body, "items")) {
            artists.add(toArtist(e.getAsJsonObject()));
        }
        return artists;
    }

    @Override
    public List<Track> getTopItems(String type, String timeRange) {
        if (type != null && !type.isBlank() && !type.equalsIgnoreCase("tracks")) {
            throw new IllegalArgumentException("getTopItems returns tracks only; use getTopArtists for artists.");
        }
        JsonObject body = get("/me/top/tracks?limit=" + topLimit + "&time_range=" + range(timeRange));
        return tracks(array(body, "items"));
    }

    @Override
    public List<Track> getSavedTracks() {
        return pagedTracks("/me/tracks?limit=" + libraryPage, null, maxSavedTracks);
    }

    @Override
    public List<Track> getRecentlyPlayed() {
        JsonObject body = get("/me/player/recently-played?limit=" + recentLimit);
        return tracksFromWrapped(array(body, "items"));
    }

    // ---- read: catalogue ----

    @Override
    public List<Track> search(String query, int limit) {
        int wanted = Math.min(limit, maxItems);
        List<Track> out = new ArrayList<>();
        int offset = 0;
        while (out.size() < wanted) {
            int size = Math.min(searchPage, wanted - out.size());
            JsonObject body = get("/search?type=track&limit=" + size + "&offset=" + offset + "&q=" + enc(query));
            JsonObject page = body.has("tracks") && body.get("tracks").isJsonObject()
                    ? body.getAsJsonObject("tracks") : new JsonObject();
            List<Track> found = tracks(array(page, "items"));
            int returned = array(page, "items").size();
            out.addAll(found);
            if (returned == 0 || !hasNext(page)) {
                break;
            }
            offset += returned;
        }
        return out;
    }

    @Override
    public List<Album> getArtistAlbums(String artistId) {
        JsonObject body = get("/artists/" + enc(artistId) + "/albums?limit=" + artistAlbumPage + "&include_groups=album,single");
        List<Album> albums = new ArrayList<>();
        for (JsonElement e : array(body, "items")) {
            JsonObject a = e.getAsJsonObject();
            albums.add(new Album(str(a, "id"), str(a, "name"), str(a, "release_date")));
        }
        return albums;
    }

    @Override
    public List<Track> getAlbumTracks(String albumId) {
        JsonObject body = get("/albums/" + enc(albumId) + "/tracks?limit=" + albumTrackPage);
        return tracks(array(body, "items"));
    }

    // ---- write: playlists ----

    @Override
    public Playlist createPlaylist(String userId, String name) {
        JsonObject req = new JsonObject();
        req.addProperty("name", name);
        req.addProperty("public", false);
        req.addProperty("description", "Created by MusicMind");
        JsonObject body = send("POST", "/me/playlists", req.toString());
        return new Playlist(str(body, "id"), str(body, "name"), List.of());
    }

    @Override
    public void addItems(String playlistId, List<String> uris) {
        for (int i = 0; i < uris.size(); i += addBatch) {
            JsonObject req = new JsonObject();
            req.add("uris", gson.toJsonTree(uris.subList(i, Math.min(i + addBatch, uris.size()))));
            send("POST", "/playlists/" + enc(playlistId) + "/items", req.toString());
        }
    }

    @Override
    public void removeItems(String playlistId, List<String> uris) {
        for (int i = 0; i < uris.size(); i += addBatch) {
            JsonArray items = new JsonArray();
            for (String uri : uris.subList(i, Math.min(i + addBatch, uris.size()))) {
                JsonObject t = new JsonObject();
                t.addProperty("uri", uri);
                items.add(t);
            }
            JsonObject req = new JsonObject();
            req.add("items", items);
            send("DELETE", "/playlists/" + enc(playlistId) + "/items", req.toString());
        }
    }

    @Override
    public void reorderItems(String playlistId, int from, int to) {
        JsonObject req = new JsonObject();
        req.addProperty("range_start", from);
        req.addProperty("insert_before", to);
        req.addProperty("range_length", 1);
        send("PUT", "/playlists/" + enc(playlistId) + "/items", req.toString());
    }

    @Override
    public List<Track> getPlaylistItems(String playlistId) {
        return pagedTracks("/playlists/" + enc(playlistId) + "/items?limit=" + playlistPage,
                "Permission denied: Spotify only lets an app read the items of playlists that are owned or "
                        + "collaborative, so this playlist cannot be read.", maxItems);
    }

    @Override
    public void unfollowPlaylist(String playlistId) {
        send("DELETE", "/me/library?uris=" + enc("spotify:playlist:" + playlistId), null);
    }

    @Override
    public String currentUserId() {
        if (cachedUserId == null) {
            cachedUserId = str(get("/me"), "id");
        }
        return cachedUserId;
    }

    // ---- HTTP plumbing ----

    private JsonObject get(String path) {
        return send("GET", path, null);
    }

    /**
     * Follows {@code next} links from {@code firstPath} until none remain or {@code limit}
     * entries are gathered; the result says whether it stopped at the limit with more left.
     * {@code forbiddenMessage}, when set, replaces the generic text of a 403.
     */
    private TrackList pagedTracks(String firstPath, String forbiddenMessage, int limit) {
        List<Track> out = new ArrayList<>();
        String url = api + firstPath;
        while (url != null && out.size() < limit) {
            JsonObject page;
            try {
                page = sendUrl("GET", url, null);
            } catch (MusicServiceException e) {
                if (e.statusCode() == 403 && forbiddenMessage != null) {
                    throw new MusicServiceException(forbiddenMessage, 403, e);
                }
                throw e;
            }
            out.addAll(tracksFromWrapped(array(page, "items")));
            url = hasNext(page) ? page.get("next").getAsString() : null;
            if (url != null && !url.startsWith(api)) {
                throw new MusicServiceException("Spotify returned an unexpected next-page address; stopped paging.");
            }
        }
        boolean truncated = url != null || out.size() > limit;
        return new TrackList(out.size() > limit ? out.subList(0, limit) : out, truncated);
    }

    private JsonObject send(String method, String path, String jsonBody) {
        return sendUrl(method, api + path, jsonBody);
    }

    /** One call; on 401 forces a token refresh and retries exactly once. */
    private JsonObject sendUrl(String method, String url, String jsonBody) {
        HttpResponse<String> response = execute(method, url, jsonBody, auth.accessToken());
        if (response.statusCode() == 401) {
            response = execute(method, url, jsonBody, auth.forceRefresh());
            if (response.statusCode() == 401) {
                throw new AuthExpiredException(OAuthTokenStore.LOGIN_MESSAGE);
            }
        }
        int code = response.statusCode();
        String where = method + " " + url.substring(api.length());
        if (code == 429) {
            throw new RateLimitException("Spotify rate limit hit on " + where, retryAfter(response));
        }
        if (code / 100 != 2) {
            throw new MusicServiceException("Spotify API error (HTTP " + code + ") on " + where
                    + ": " + config.truncateBody(response.body()), code, null, retryAfter(response));
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return new JsonObject(); // 204 No Content
        }
        try {
            JsonElement parsed = gson.fromJson(body, JsonElement.class);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            throw new MusicServiceException("Spotify returned an unreadable response on " + where, e);
        }
    }

    private HttpResponse<String> execute(String method, String url, String jsonBody, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token);
        if (jsonBody == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MusicServiceException("Interrupted during a Spotify request", e);
        } catch (Exception e) {
            throw new MusicServiceException("Spotify request failed on " + method + " " + url, e);
        }
    }

    private static int retryAfter(HttpResponse<String> response) {
        return parseInt(response.headers().firstValue("Retry-After").orElse("0"), 0);
    }

    private static boolean hasNext(JsonObject page) {
        return page.has("next") && !page.get("next").isJsonNull();
    }

    // ---- JSON -> domain mapping ----

    private List<Track> tracks(JsonArray items) {
        List<Track> out = new ArrayList<>();
        for (JsonElement e : items) {
            if (e.isJsonObject() && str(e.getAsJsonObject(), "id") != null) {
                out.add(toTrack(e.getAsJsonObject()));
            }
        }
        return out;
    }

    /**
     * For entries that wrap the track: playlist entries use {@code item} (Feb 2026),
     * with {@code track} accepted as the legacy key (saved and recent entries use it).
     * Null entries and local files without an id are skipped.
     */
    private List<Track> tracksFromWrapped(JsonArray items) {
        List<Track> out = new ArrayList<>();
        for (JsonElement e : items) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject entry = e.getAsJsonObject();
            JsonElement wrapped = entry.has("item") && !entry.get("item").isJsonNull()
                    ? entry.get("item") : entry.get("track");
            if (wrapped == null || !wrapped.isJsonObject()) {
                continue;
            }
            JsonObject t = wrapped.getAsJsonObject();
            if (str(t, "id") != null) {
                out.add(toTrack(t));
            }
        }
        return out;
    }

    private Track toTrack(JsonObject t) {
        List<String> artistIds = new ArrayList<>();
        if (t.has("artists")) {
            for (JsonElement a : t.getAsJsonArray("artists")) {
                artistIds.add(str(a.getAsJsonObject(), "id"));
            }
        }
        return new Track(
                str(t, "id"),
                str(t, "uri"),
                str(t, "name"),
                t.has("duration_ms") ? t.get("duration_ms").getAsInt() : 0,
                t.has("explicit") && t.get("explicit").getAsBoolean(),
                artistIds);
    }

    private Artist toArtist(JsonObject a) {
        List<String> genres = new ArrayList<>();
        if (a.has("genres")) {
            for (JsonElement g : a.getAsJsonArray("genres")) {
                genres.add(g.getAsString());
            }
        }
        return new Artist(str(a, "id"), str(a, "name"), genres);
    }

    // ---- small JSON helpers ----

    private static JsonArray array(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    }

    private static String str(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    /** Map a friendly range to Spotify's {@code time_range} values. */
    private static String range(String timeRange) {
        if (timeRange == null) return "medium_term";
        return switch (timeRange.toLowerCase()) {
            case "short", "short_term", "recent" -> "short_term";
            case "long", "long_term", "all", "all_time" -> "long_term";
            default -> "medium_term";
        };
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}

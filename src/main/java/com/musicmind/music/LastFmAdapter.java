package com.musicmind.music;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.musicmind.config.Config;
import com.musicmind.domain.TagTrack;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter (GoF) from Last.fm's {@code tag.gettoptracks} to {@link TagService}. With no API key it
 * returns an empty list without calling. The key is only ever in the request URL; it is never
 * logged or put into an exception message.
 */
public class LastFmAdapter implements TagService {

    private final Config config;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public LastFmAdapter(Config config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.lastfmTimeoutSeconds())).build();
    }

    @Override
    public List<TagTrack> topTracks(String tag, int limit) {
        if (!config.lastfmConfigured() || tag == null || tag.isBlank() || limit <= 0) {
            return List.of();
        }
        String url = config.lastfmApiBaseUrl() + "?method=tag.gettoptracks&tag=" + encode(tag.strip())
                + "&limit=" + limit + "&api_key=" + encode(config.lastfmApiKey()) + "&format=json";
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", config.lastfmUserAgent())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(config.lastfmTimeoutSeconds()))
                    .GET().build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MusicServiceException("Last.fm request was interrupted.");
        } catch (IOException | RuntimeException e) {
            // The exception text could echo the URL (and so the key); report only its type.
            throw new MusicServiceException("Last.fm could not be reached (" + e.getClass().getSimpleName() + ").");
        }
        if (response.statusCode() / 100 != 2) {
            throw new MusicServiceException("Last.fm returned HTTP " + response.statusCode() + ".",
                    response.statusCode());
        }
        return parse(response.body(), limit);
    }

    private List<TagTrack> parse(String body, int limit) {
        try {
            JsonElement parsed = gson.fromJson(body, JsonElement.class);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new MusicServiceException("Last.fm reply could not be read.");
            }
            JsonObject root = parsed.getAsJsonObject();
            if (root.has("error")) {
                String message = root.has("message") && root.get("message").isJsonPrimitive()
                        ? config.truncateBody(root.get("message").getAsString()) : "";
                throw new MusicServiceException("Last.fm reported an error (" + root.get("error").getAsString()
                        + ") " + message);
            }
            JsonObject tracks = root.has("tracks") && root.get("tracks").isJsonObject()
                    ? root.getAsJsonObject("tracks") : null;
            if (tracks == null) {
                throw new MusicServiceException("Last.fm reply has no tracks list.");
            }
            List<TagTrack> out = new ArrayList<>();
            JsonElement list = tracks.get("track");
            if (list == null) {
                return out; // an unknown or empty tag
            }
            // Last.fm sends an object, not an array, when there is exactly one result.
            JsonArray elements = list.isJsonArray() ? list.getAsJsonArray() : new JsonArray();
            if (list.isJsonObject()) {
                elements.add(list);
            }
            for (JsonElement e : elements) {
                TagTrack track = tagTrack(e);
                if (track != null) { // a malformed element is skipped, not fatal
                    out.add(track);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
            return out;
        } catch (MusicServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MusicServiceException("Last.fm reply could not be read.");
        }
    }

    private static TagTrack tagTrack(JsonElement e) {
        try {
            JsonObject t = e.getAsJsonObject();
            String title = t.get("name").getAsString().strip();
            JsonElement artist = t.get("artist");
            String artistName = (artist.isJsonObject() ? artist.getAsJsonObject().get("name") : artist)
                    .getAsString().strip();
            return title.isEmpty() || artistName.isEmpty() ? null : new TagTrack(title, artistName);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

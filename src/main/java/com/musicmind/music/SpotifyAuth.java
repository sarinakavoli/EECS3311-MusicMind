package com.musicmind.music;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Spotify OAuth using <b>Authorization Code with PKCE</b> (a public-client
 * flow, so no client secret is stored). It obtains and refreshes access tokens:
 *
 * <ol>
 *   <li>Generate a PKCE {@code code_verifier} and its SHA-256 {@code code_challenge}.</li>
 *   <li>Open the browser to Spotify's consent page.</li>
 *   <li>Catch the redirect to {@code http://127.0.0.1:8080/callback} with a tiny
 *       local HTTP server and read the {@code code}.</li>
 *   <li>Exchange the code for an access + refresh token.</li>
 * </ol>
 *
 * The refresh token is cached in a git-ignored file so later runs reconnect
 * silently (no browser), which matters when recording a demo.
 */
public class SpotifyAuth {

    private static final String AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String SCOPES = String.join(" ",
            "user-top-read",
            "user-library-read",
            "user-read-recently-played",
            "user-read-private",
            "playlist-read-private",
            "playlist-modify-public",
            "playlist-modify-private");

    private final String clientId;
    private final String redirectUri;
    private final Path tokenCache;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    private String accessToken;
    private String refreshToken;
    private Instant expiresAt = Instant.EPOCH;

    public SpotifyAuth(String clientId, String redirectUri) {
        this(clientId, redirectUri, Path.of(".spotify-tokens.json"));
    }

    public SpotifyAuth(String clientId, String redirectUri, Path tokenCache) {
        if (clientId == null || clientId.isBlank()) {
            throw new MusicServiceException("Spotify client id is not configured (set SPOTIFY_CLIENT_ID in .env).");
        }
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.tokenCache = tokenCache;
        loadCache();
    }

    /** A valid bearer access token, refreshing or running the full flow as needed. */
    public synchronized String accessToken() {
        if (accessToken != null && Instant.now().isBefore(expiresAt.minusSeconds(60))) {
            return accessToken;
        }
        if (refreshToken != null) {
            try {
                refresh();
                return accessToken;
            } catch (RuntimeException e) {
                // Refresh token expired/revoked — fall back to a full re-auth.
                refreshToken = null;
            }
        }
        authorize();
        return accessToken;
    }

    // ---- full authorization-code + PKCE flow ----

    private void authorize() {
        String verifier = randomUrlSafe(64);
        String challenge = s256Challenge(verifier);
        String state = randomUrlSafe(16);
        URI redirect = URI.create(redirectUri);

        String authUrl = AUTHORIZE_URL
                + "?client_id=" + enc(clientId)
                + "&response_type=code"
                + "&redirect_uri=" + enc(redirectUri)
                + "&code_challenge_method=S256"
                + "&code_challenge=" + enc(challenge)
                + "&state=" + enc(state)
                + "&scope=" + enc(SCOPES);

        String code = awaitAuthorizationCode(redirect, state, authUrl);
        exchangeCode(code, verifier);
    }

    /** Start the loopback server, open the browser, and block until the code arrives. */
    private String awaitAuthorizationCode(URI redirect, String expectedState, String authUrl) {
        int port = redirect.getPort() == -1 ? 80 : redirect.getPort();
        String path = redirect.getPath().isEmpty() ? "/" : redirect.getPath();
        CompletableFuture<String> codeFuture = new CompletableFuture<>();
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (Exception e) {
            throw new MusicServiceException("Could not start the local callback server on port " + port
                    + " (is something else using it?).", e);
        }
        server.createContext(path, exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String code = param(query, "code");
            String returnedState = param(query, "state");
            String error = param(query, "error");
            String body;
            boolean ok = error == null && code != null && expectedState.equals(returnedState);
            if (ok) {
                body = html("MusicMind is connected to Spotify.", "You can close this tab and return to the app.");
            } else {
                body = html("Spotify authorization failed.",
                        error != null ? error : "Missing or mismatched authorization code.");
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(ok ? 200 : 400, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
            if (ok) {
                codeFuture.complete(code);
            } else {
                codeFuture.completeExceptionally(new MusicServiceException(
                        "Spotify authorization failed: " + (error != null ? error : "bad callback")));
            }
        });
        server.start();
        try {
            openBrowser(authUrl);
            return codeFuture.get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            if (e.getCause() instanceof MusicServiceException mse) throw mse;
            throw new MusicServiceException("Timed out waiting for Spotify authorization.", e);
        } finally {
            server.stop(0);
        }
    }

    private void openBrowser(String authUrl) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(authUrl));
                System.out.println("[musicmind] Opened your browser to approve Spotify access.");
                return;
            }
        } catch (Exception ignored) {
            // fall through to manual instructions
        }
        System.out.println("[musicmind] Open this URL in your browser to approve Spotify access:\n  " + authUrl);
    }

    // ---- token endpoint calls ----

    private void exchangeCode(String code, String verifier) {
        String form = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(redirectUri)
                + "&client_id=" + enc(clientId)
                + "&code_verifier=" + enc(verifier);
        storeTokens(postToken(form), true);
    }

    private void refresh() {
        String form = "grant_type=refresh_token"
                + "&refresh_token=" + enc(refreshToken)
                + "&client_id=" + enc(clientId);
        storeTokens(postToken(form), false);
    }

    private JsonObject postToken(String form) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new MusicServiceException("Spotify token request failed (HTTP "
                        + response.statusCode() + "): " + response.body(), response.statusCode());
            }
            return gson.fromJson(response.body(), JsonObject.class);
        } catch (MusicServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new MusicServiceException("Spotify token request failed.", e);
        }
    }

    private void storeTokens(JsonObject json, boolean expectRefresh) {
        this.accessToken = json.get("access_token").getAsString();
        int expiresIn = json.has("expires_in") ? json.get("expires_in").getAsInt() : 3600;
        this.expiresAt = Instant.now().plusSeconds(expiresIn);
        if (json.has("refresh_token")) {
            this.refreshToken = json.get("refresh_token").getAsString();
        }
        saveCache();
    }

    // ---- token cache (git-ignored) ----

    private void loadCache() {
        try {
            if (Files.exists(tokenCache)) {
                JsonObject json = gson.fromJson(Files.readString(tokenCache), JsonObject.class);
                if (json != null && json.has("refresh_token")) {
                    this.refreshToken = json.get("refresh_token").getAsString();
                }
            }
        } catch (Exception ignored) {
            // a corrupt cache just means we re-authorize
        }
    }

    private void saveCache() {
        try {
            JsonObject json = new JsonObject();
            if (refreshToken != null) json.addProperty("refresh_token", refreshToken);
            Files.writeString(tokenCache, gson.toJson(json));
        } catch (Exception ignored) {
            // caching is best-effort
        }
    }

    // ---- PKCE / small helpers ----

    private static String randomUrlSafe(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String s256Challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new MusicServiceException("PKCE challenge generation failed.", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String param(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String html(String title, String subtitle) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>MusicMind</title></head>"
                + "<body style=\"font-family:system-ui;margin:0;display:flex;height:100vh;"
                + "align-items:center;justify-content:center;background:#121212;color:#fff\">"
                + "<div style=\"text-align:center\"><h1 style=\"color:#1DB954\">" + title + "</h1>"
                + "<p>" + subtitle + "</p></div></body></html>";
    }
}

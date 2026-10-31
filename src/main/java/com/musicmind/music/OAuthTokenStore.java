package com.musicmind.music;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.musicmind.config.Config;
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
import java.util.function.Consumer;

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
public class OAuthTokenStore {

    private static final String AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
    private static final String SCOPES = String.join(" ",
            "user-top-read",
            "user-library-read",
            "user-read-recently-played",
            "user-read-private",
            "playlist-read-private",
            "playlist-modify-public",
            "playlist-modify-private",
            "user-library-modify");

    static final String LOGIN_MESSAGE = "Your Spotify session has expired. Please log in to Spotify again.";

    private final String clientId;
    private final String redirectUri;
    private final Path tokenCache;
    private final String tokenUrl;
    private final Consumer<String> browser;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    private String accessToken;
    private String refreshToken;
    private Instant expiresAt = Instant.EPOCH;

    public OAuthTokenStore(String clientId, String redirectUri) {
        this(clientId, redirectUri, Path.of(".spotify-tokens.json"));
    }

    public OAuthTokenStore(String clientId, String redirectUri, Path tokenCache) {
        this(clientId, redirectUri, tokenCache, Config.defaults().spotifyTokenUrl());
    }

    /** Token endpoint seam, so tests can point refresh/exchange at a local server. */
    OAuthTokenStore(String clientId, String redirectUri, Path tokenCache, String tokenUrl) {
        this(clientId, redirectUri, tokenCache, tokenUrl, null);
    }

    /** As above, plus a seam for the "open the browser" step (null opens the real one). */
    OAuthTokenStore(String clientId, String redirectUri, Path tokenCache, String tokenUrl,
                    Consumer<String> browser) {
        if (clientId == null || clientId.isBlank()) {
            throw new MusicServiceException("Spotify client id is not configured (set SPOTIFY_CLIENT_ID in .env).");
        }
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.tokenCache = tokenCache;
        this.tokenUrl = tokenUrl;
        this.browser = browser != null ? browser : this::openBrowser;
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

    /**
     * Discards the current access token and gets a new one from the refresh
     * token, called when Spotify answers 401. Never opens a browser: if the
     * refresh token is missing or rejected the user must log in again.
     */
    public synchronized String forceRefresh() {
        if (refreshToken == null) {
            throw new AuthExpiredException(LOGIN_MESSAGE);
        }
        try {
            refresh();
        } catch (MusicServiceException e) {
            // Only a rejection of the refresh token itself means the user must log in
            // again; anything else (429, 5xx, network) is left for the retry decorator.
            if (e.statusCode() == 400 || e.statusCode() == 401) {
                refreshToken = null;
                saveCache();
                throw new AuthExpiredException(LOGIN_MESSAGE, e);
            }
            throw e;
        }
        return accessToken;
    }

    /** Forces the full browser (PKCE) flow, ignoring any cached tokens. */
    public synchronized void login() {
        accessToken = null;
        refreshToken = null;
        expiresAt = Instant.EPOCH;
        authorize();
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
            browser.accept(authUrl);
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new MusicServiceException("Spotify token request failed (HTTP "
                        + response.statusCode() + "): " + truncate(response.body()), response.statusCode());
            }
            return gson.fromJson(response.body(), JsonObject.class);
        } catch (MusicServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new MusicServiceException("Spotify token request failed.", e);
        }
    }

    private String truncate(String body) {
        int limit = Config.defaults().errorBodyLimit();
        return body == null || body.length() <= limit ? body : body.substring(0, limit) + "...";
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

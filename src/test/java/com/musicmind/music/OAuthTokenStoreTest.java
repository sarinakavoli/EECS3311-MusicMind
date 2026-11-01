package com.musicmind.music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OAuthTokenStoreTest {

    @TempDir Path dir;

    private static Map<String, String> form(String s) {
        Map<String, String> out = new HashMap<>();
        for (String pair : s.split("&")) {
            String[] kv = pair.split("=", 2);
            out.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void aBrowserLoginIsNeededOnlyWhenThereIsNoCachedRefreshToken() throws Exception {
        try (FakeSpotifyServer server = new FakeSpotifyServer()) {
            assertFalse(server.tokenStore(dir).needsBrowserLogin(), "a cached refresh token signs in silently");

            Path empty = dir.resolve("none.json");
            OAuthTokenStore fresh = new OAuthTokenStore("client", "http://127.0.0.1:8080/callback", empty, server.tokenUrl());
            assertTrue(fresh.needsBrowserLogin());
        }
    }

    @Test
    void forceRefreshUsesTheCachedRefreshTokenAndIssuesANewAccessToken() throws Exception {
        try (FakeSpotifyServer server = new FakeSpotifyServer()) {
            OAuthTokenStore store = server.tokenStore(dir);

            assertEquals("tok1", store.accessToken());
            assertEquals("tok1", store.accessToken(), "a fresh token is reused");
            assertEquals("tok2", store.forceRefresh());

            assertEquals(2, server.tokenRequests.size());
            assertEquals("refresh-1", form(server.tokenRequests.get(1)).get("refresh_token"));
        }
    }

    @Test
    void forceRefreshWithoutARefreshTokenRaisesAuthExpired() throws Exception {
        try (FakeSpotifyServer server = new FakeSpotifyServer()) {
            OAuthTokenStore store = new OAuthTokenStore("client", "http://127.0.0.1:8080/callback",
                    dir.resolve("none.json"), server.tokenUrl());

            AuthExpiredException e = assertThrows(AuthExpiredException.class, store::forceRefresh);
            assertTrue(e.getMessage().toLowerCase().contains("log in"), e.getMessage());
            assertTrue(server.tokenRequests.isEmpty());
        }
    }

    @Test
    void forceRefreshRejectedWith400Or401ClearsTheRefreshTokenAndRaisesAuthExpired() throws Exception {
        for (int status : new int[]{400, 401}) {
            try (FakeSpotifyServer server = new FakeSpotifyServer()) {
                OAuthTokenStore store = server.tokenStore(dir);
                server.failTokenEndpointWith(status);

                assertThrows(AuthExpiredException.class, store::forceRefresh);
                assertEquals(1, server.tokenRequests.size());

                // The refresh token is gone, so a second attempt does not even reach the endpoint.
                assertThrows(AuthExpiredException.class, store::forceRefresh);
                assertEquals(1, server.tokenRequests.size(), "HTTP " + status);
            }
        }
    }

    @Test
    void forceRefreshFailingWithAnotherStatusIsRethrownUnchangedAndKeepsTheRefreshToken() throws Exception {
        for (int status : new int[]{429, 500, 503}) {
            try (FakeSpotifyServer server = new FakeSpotifyServer()) {
                OAuthTokenStore store = server.tokenStore(dir);
                server.failTokenEndpointWith(status);

                MusicServiceException e = assertThrows(MusicServiceException.class, store::forceRefresh);
                assertFalse(e instanceof AuthExpiredException, "HTTP " + status);
                assertEquals(status, e.statusCode());

                // The decorator may retry: the refresh token survived, and recovery works.
                server.failTokenEndpointWith(200);
                assertEquals("tok1", store.forceRefresh());
                assertEquals(2, server.tokenRequests.size());
            }
        }
    }

    @Test
    void forceRefreshFailingWithoutAResponseIsRethrownAndKeepsTheRefreshToken() throws Exception {
        OAuthTokenStore store;
        try (FakeSpotifyServer server = new FakeSpotifyServer()) {
            store = server.tokenStore(dir);
        }
        MusicServiceException e = assertThrows(MusicServiceException.class, store::forceRefresh);
        assertFalse(e instanceof AuthExpiredException);
    }

    @Test
    void loginRunsTheFullPkceFlowEvenWhenARefreshTokenIsCached() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        String redirect = "http://127.0.0.1:" + port + "/callback";
        Path cache = dir.resolve("tokens.json");
        Files.writeString(cache, "{\"refresh_token\":\"stale\"}");
        String[] authUrl = new String[1];
        try (FakeSpotifyServer server = new FakeSpotifyServer()) {
            // The "browser": records the consent URL and follows it straight to the redirect.
            OAuthTokenStore store = new OAuthTokenStore("client", redirect, cache, server.tokenUrl(), url -> {
                authUrl[0] = url;
                Map<String, String> q = form(URI.create(url).getRawQuery());
                try {
                    HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                            redirect + "?code=abc&state=" + q.get("state"))).build(),
                            HttpResponse.BodyHandlers.discarding());
                } catch (IOException | InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });

            store.login();

            Map<String, String> consent = form(URI.create(authUrl[0]).getRawQuery());
            assertTrue(consent.get("scope").contains("user-library-modify"));
            assertEquals("S256", consent.get("code_challenge_method"));
            Map<String, String> exchange = form(server.tokenRequests.get(0));
            assertEquals("authorization_code", exchange.get("grant_type"));
            assertEquals("abc", exchange.get("code"));
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(exchange.get("code_verifier").getBytes(StandardCharsets.US_ASCII));
            assertEquals(consent.get("code_challenge"), Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
            assertEquals("tok1", store.accessToken());
            assertTrue(Files.readString(cache).contains("refresh-1"), "new refresh token is cached");
        }
    }

    // ---- accessToken: only a rejected refresh token falls back to the browser ----

    @Test
    void accessTokenRejectedWith400Or401ClearsTheRefreshTokenAndOpensTheBrowser() throws Exception {
        for (int status : new int[]{400, 401}) {
            try (FakeSpotifyServer server = new FakeSpotifyServer()) {
                AtomicBoolean opened = new AtomicBoolean();
                OAuthTokenStore store = storeWithCachedRefreshToken(server, url -> {
                    opened.set(true);
                    throw new IllegalStateException("no browser in tests");
                });
                server.failTokenEndpointWith(status);

                assertThrows(MusicServiceException.class, store::accessToken);

                assertTrue(opened.get(), "HTTP " + status + " means log in again");
                assertFalse(Files.readString(dir.resolve("tokens.json")).contains("refresh-1"), "refresh token cleared");
            }
        }
    }

    @Test
    void accessTokenFailingWithAnotherStatusIsRethrownWithoutOpeningTheBrowser() throws Exception {
        for (int status : new int[]{429, 500, 503}) {
            try (FakeSpotifyServer server = new FakeSpotifyServer()) {
                AtomicBoolean opened = new AtomicBoolean();
                OAuthTokenStore store = storeWithCachedRefreshToken(server, url -> opened.set(true));
                server.failTokenEndpointWith(status);

                MusicServiceException e = assertThrows(MusicServiceException.class, store::accessToken);

                assertEquals(status, e.statusCode());
                assertFalse(opened.get(), "HTTP " + status + " must not start a login");
                server.failTokenEndpointWith(200);
                assertEquals("tok1", store.accessToken(), "the refresh token survived");
            }
        }
    }

    private OAuthTokenStore storeWithCachedRefreshToken(FakeSpotifyServer server, java.util.function.Consumer<String> browser)
            throws IOException {
        Path cache = dir.resolve("tokens.json");
        Files.writeString(cache, "{\"refresh_token\":\"refresh-1\"}");
        return new OAuthTokenStore("client", "http://127.0.0.1:8080/callback", cache, server.tokenUrl(), browser);
    }

    // ---- token file permissions ----

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    @Test
    void theTokenFileIsCreatedReadableByTheOwnerOnly() throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        try (FakeSpotifyServer server = new FakeSpotifyServer()) {
            OAuthTokenStore store = server.tokenStore(dir);
            Files.delete(dir.resolve("tokens.json"));
            store.forceRefresh();

            assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(dir.resolve("tokens.json")));
            assertTrue(Files.readString(dir.resolve("tokens.json")).contains("refresh-1"));
        }
    }

    @Test
    void anExistingTokenFileIsTightenedWhenRewritten() throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        try (FakeSpotifyServer server = new FakeSpotifyServer()) {
            OAuthTokenStore store = server.tokenStore(dir);
            Files.setPosixFilePermissions(dir.resolve("tokens.json"), PosixFilePermissions.fromString("rw-r--r--"));

            store.forceRefresh();

            assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(dir.resolve("tokens.json")));
        }
    }

    // ---- the callback page ----

    private String redirect() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return "http://127.0.0.1:" + s.getLocalPort() + "/callback";
        }
    }

    private static HttpResponse<String> get(String url) {
        try {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void textInTheErrorParameterIsEscapedInTheCallbackPage() throws Exception {
        String redirect = redirect();
        String[] page = new String[1];
        try (FakeSpotifyServer server = new FakeSpotifyServer()) {
            OAuthTokenStore store = new OAuthTokenStore("client", redirect, dir.resolve("t.json"), server.tokenUrl(), url -> {
                String state = form(URI.create(url).getRawQuery()).get("state");
                page[0] = get(redirect + "?error=" + java.net.URLEncoder.encode("<script>alert(1)</script>", StandardCharsets.UTF_8)
                        + "&state=" + state).body();
            });

            assertThrows(MusicServiceException.class, store::login);

            assertFalse(page[0].contains("<script>"), page[0]);
            assertTrue(page[0].contains("&lt;script&gt;alert(1)&lt;/script&gt;"), page[0]);
        }
    }

    @Test
    void aCallbackWithTheWrongStateIsIgnoredAndTheLoginStillCompletes() throws Exception {
        String redirect = redirect();
        int[] wrongStatus = new int[1];
        try (FakeSpotifyServer server = new FakeSpotifyServer()) {
            OAuthTokenStore store = new OAuthTokenStore("client", redirect, dir.resolve("t.json"), server.tokenUrl(), url -> {
                String state = form(URI.create(url).getRawQuery()).get("state");
                wrongStatus[0] = get(redirect + "?code=evil&state=not-" + state).statusCode();
                get(redirect + "?error=access_denied&state=other");
                get(redirect + "?code=abc&state=" + state);
            });

            store.login();

            assertEquals(400, wrongStatus[0]);
            assertEquals("abc", form(server.tokenRequests.get(0)).get("code"), "only the genuine callback was used");
            assertEquals("tok1", store.accessToken());
        }
    }
}

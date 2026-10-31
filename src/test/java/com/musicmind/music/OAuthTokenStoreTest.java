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
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}

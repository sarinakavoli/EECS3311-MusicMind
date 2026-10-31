package com.musicmind.music;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Local stand-in for Spotify's API and token endpoint, so adapter tests assert
 * on the exact method, path, query, body and bearer token with no network.
 */
final class FakeSpotifyServer implements AutoCloseable {

    /** A request as received by the server. */
    record Req(String method, String path, String query, String body, String auth) {}

    /** A canned response. */
    record Reply(int status, String body, Map<String, String> headers) {
        static Reply ok(String body) { return new Reply(200, body, Map.of()); }
        static Reply status(int status) { return new Reply(status, "", Map.of()); }
        static Reply status(int status, String body, String header, String value) {
            return new Reply(status, body, Map.of(header, value));
        }
    }

    final List<Req> requests = new CopyOnWriteArrayList<>();
    final List<String> tokenRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokensIssued = new AtomicInteger();
    private final HttpServer server;
    private volatile Function<Req, Reply> handler = r -> Reply.status(404);
    private volatile int tokenStatus = 200;

    FakeSpotifyServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Req req = new Req(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(), body,
                    exchange.getRequestHeaders().getFirst("Authorization"));
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
        server.createContext("/token", exchange -> {
            tokenRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (tokenStatus != 200) {
                byte[] err = "{\"error\":\"token endpoint says no\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(tokenStatus, err.length);
                exchange.getResponseBody().write(err);
                exchange.close();
                return;
            }
            byte[] bytes = ("{\"access_token\":\"tok" + tokensIssued.incrementAndGet()
                    + "\",\"expires_in\":3600,\"refresh_token\":\"refresh-1\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    void respond(Function<Req, Reply> handler) {
        this.handler = handler;
    }

    /** Makes the token endpoint answer with this status instead of issuing tokens. */
    void failTokenEndpointWith(int status) {
        this.tokenStatus = status;
    }

    String apiBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    String tokenUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
    }

    /** A token store holding a cached refresh token, pointed at this server's token endpoint. */
    OAuthTokenStore tokenStore(Path dir) throws IOException {
        Path cache = dir.resolve("tokens.json");
        Files.writeString(cache, "{\"refresh_token\":\"refresh-1\"}");
        return new OAuthTokenStore("client", "http://127.0.0.1:8080/callback", cache, tokenUrl());
    }

    SpotifyAdapter adapter(Path dir) throws IOException {
        return new SpotifyAdapter(tokenStore(dir), apiBase());
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

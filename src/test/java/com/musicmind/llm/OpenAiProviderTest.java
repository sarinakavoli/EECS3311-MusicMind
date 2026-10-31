package com.musicmind.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiProviderTest {

    private static final String KEY = "sk-test-secret-key";

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "";
    private volatile long delayMillis = 0;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getQuery());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ignored) {
                    // the client gave up
                }
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenAiProvider provider() {
        return new OpenAiProvider(KEY, "gpt-4o", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 30);
    }

    private static String completion(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        JsonObject root = new JsonObject();
        root.add("choices", new com.google.gson.JsonArray());
        root.getAsJsonArray("choices").add(choice);
        return root.toString();
    }

    @Test
    void postsChatCompletionsWithModelAndUserMessageAndReturnsTheContent() {
        reply = completion("  Hello there  ");

        String out = provider().complete("Say hi");

        assertEquals("  Hello there  ", out);
        assertEquals("POST", method.get());
        assertEquals("/v1/chat/completions", path.get());
        JsonObject sent = JsonParser.parseString(body.get()).getAsJsonObject();
        assertEquals("gpt-4o", sent.get("model").getAsString());
        JsonObject message = sent.getAsJsonArray("messages").get(0).getAsJsonObject();
        assertEquals("user", message.get("role").getAsString());
        assertEquals("Say hi", message.get("content").getAsString());
        assertEquals(1, sent.getAsJsonArray("messages").size());
    }

    @Test
    void sendsTheKeyOnlyInTheAuthorizationHeader() {
        reply = completion("ok");

        provider().complete("prompt");

        assertEquals("Bearer " + KEY, auth.get());
        assertNull(query.get());
        assertFalse(path.get().contains(KEY));
        assertFalse(body.get().contains(KEY));
    }

    @Test
    void nonSuccessStatusRaisesLlmExceptionWithoutTheKey() {
        status = 401;
        reply = "{\"error\":{\"message\":\"Incorrect API key provided: " + KEY + ".\"}}";

        LLMException e = assertThrows(LLMException.class, () -> provider().complete("p"));

        assertTrue(e.getMessage().contains("401"), e.getMessage());
        assertFalse(e.getMessage().contains(KEY), e.getMessage());
    }

    @Test
    void longErrorBodiesAreTruncated() {
        status = 500;
        reply = "x".repeat(5000);

        LLMException e = assertThrows(LLMException.class, () -> provider().complete("p"));

        assertTrue(e.getMessage().length() < 1000, "length " + e.getMessage().length());
    }

    @Test
    void unexpectedResponseShapesRaiseLlmException() {
        for (String bad : new String[]{"not json", "{}", "{\"choices\":[]}",
                "{\"choices\":[{\"message\":{}}]}", "{\"choices\":[{\"message\":{\"content\":null}}]}"}) {
            reply = bad;
            LLMException e = assertThrows(LLMException.class, () -> provider().complete("p"), bad);
            assertFalse(e.getMessage().contains(KEY));
        }
    }

    @Test
    void aSlowServerTimesOutAsLlmException() {
        delayMillis = 3000;
        reply = completion("late");
        OpenAiProvider quick = new OpenAiProvider(KEY, "gpt-4o",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 1);

        LLMException e = assertThrows(LLMException.class, () -> quick.complete("p"));

        assertFalse(e.getMessage().contains(KEY));
    }

    @Test
    void unreachableServerRaisesLlmException() {
        server.stop(0);
        assertThrows(LLMException.class, () -> provider().complete("p"));
    }

    @Test
    void aMissingKeyIsRejectedAtConstruction() {
        assertThrows(LLMException.class, () -> new OpenAiProvider(" ", "gpt-4o"));
    }

    @Test
    void aBlankModelFallsBackToTheConfiguredDefault() {
        reply = completion("ok");
        new OpenAiProvider(KEY, null, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 30).complete("p");
        assertEquals("gpt-4o", JsonParser.parseString(body.get()).getAsJsonObject().get("model").getAsString());
    }
}

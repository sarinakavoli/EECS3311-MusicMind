package com.musicmind.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.musicmind.config.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * {@link LLMProvider} backed by OpenAI's chat completions API. The model comes
 * from {@code LLM_MODEL}, the key from {@code LLM_API_KEY}; the base URL and
 * timeout come from {@code musicmind.properties}. The key travels only in the
 * {@code Authorization} header and never appears in an exception message.
 */
public class OpenAiProvider implements LLMProvider {

    private static final String REDACTED = "[redacted]";

    private final String apiKey;
    private final String model;
    private final String endpoint;
    private final Duration timeout;
    private final Config config;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    public OpenAiProvider(String apiKey, String model) {
        this(apiKey, model, Config.defaults().openAiBaseUrl(), Config.defaults().openAiTimeoutSeconds());
    }

    /** Base URL and timeout seam, so tests can target a local server. */
    OpenAiProvider(String apiKey, String model, String baseUrl, int timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LLMException("LLM API key is not configured (set LLM_API_KEY in .env).");
        }
        this.config = Config.defaults();
        this.apiKey = apiKey;
        this.model = (model == null || model.isBlank()) ? config.llmModel() : model;
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public String complete(String prompt) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        JsonArray messages = new JsonArray();
        messages.add(message);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMException("The OpenAI request was interrupted.", e);
        } catch (Exception e) {
            throw new LLMException("The OpenAI request failed: " + safe(e.getMessage()), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new LLMException("OpenAI request failed (HTTP " + response.statusCode() + "): "
                    + safe(response.body()));
        }
        return extractContent(response.body());
    }

    /** Pull {@code choices[0].message.content} out of the response. */
    private String extractContent(String json) {
        try {
            JsonObject root = gson.fromJson(json, JsonObject.class);
            JsonElement content = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content");
            if (content == null || content.isJsonNull()) {
                throw new IllegalStateException("no content");
            }
            return content.getAsString();
        } catch (RuntimeException e) {
            throw new LLMException("Unexpected OpenAI response shape: " + safe(json), e);
        }
    }

    /** Text safe for an error message: key removed, length limited. */
    private String safe(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.replace(apiKey, REDACTED);
        return config.truncateBody(clean);
    }
}

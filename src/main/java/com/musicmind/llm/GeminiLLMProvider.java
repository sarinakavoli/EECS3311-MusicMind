package com.musicmind.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Real {@link LLMProvider} backed by Google's Gemini (Generative Language API).
 * The model name comes from configuration ({@code LLM_MODEL}); the key from
 * {@code LLM_API_KEY}. Used for the F1 taste-profile summary and, later, the
 * agent's planning and explanations.
 */
public class GeminiLLMProvider implements LLMProvider {

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final Gson gson = new Gson();

    public GeminiLLMProvider(String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LLMException("LLM API key is not configured (set LLM_API_KEY in .env).");
        }
        this.apiKey = apiKey;
        this.model = (model == null || model.isBlank()) ? "gemini-2.0-flash" : model;
    }

    @Override
    public String complete(String prompt) {
        String url = BASE + URLEncoder.encode(model, StandardCharsets.UTF_8)
                + ":generateContent?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);
        JsonObject body = new JsonObject();
        body.add("contents", contents);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new LLMException("Gemini request failed (HTTP " + response.statusCode() + "): " + response.body());
            }
            return extractText(response.body());
        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            throw new LLMException("Gemini request failed.", e);
        }
    }

    /** Pull {@code candidates[0].content.parts[0].text} out of the response. */
    private String extractText(String json) {
        try {
            JsonObject root = gson.fromJson(json, JsonObject.class);
            JsonObject candidate = root.getAsJsonArray("candidates").get(0).getAsJsonObject();
            JsonArray parts = candidate.getAsJsonObject("content").getAsJsonArray("parts");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                JsonObject p = parts.get(i).getAsJsonObject();
                if (p.has("text")) sb.append(p.get("text").getAsString());
            }
            return sb.toString().trim();
        } catch (Exception e) {
            throw new LLMException("Unexpected Gemini response shape: " + json, e);
        }
    }
}

package com.musicmind.llm;

/**
 * Offline LLM provider used for tests and for running the app without a real
 * API key. Returns deterministic, well-formed responses so the agent pipeline
 * can be exercised end-to-end without network access.
 */
public class MockLLMProvider implements LLMProvider {

    @Override
    public String complete(String prompt) {
        String p = prompt == null ? "" : prompt.toLowerCase();
        if (p.contains("playlistspec") || p.contains("create")) {
            // A minimal, valid PlaylistSpec JSON the ResponseParser can read.
            return "{\"targetMinutes\":60,\"allowExplicit\":false,"
                    + "\"familiarRatio\":0.7,\"blockedArtists\":[],\"name\":\"MusicMind Mix\"}";
        }
        if (p.contains("explain")) {
            return "Selected a balance of familiar favourites and a few new artists "
                    + "that match your top genres, with no explicit tracks.";
        }
        if (p.contains("summar") || p.contains("taste") || p.contains("listener")) {
            return "Your taste centres on a few favourite genres, mixing familiar "
                    + "go-to artists with room to explore new ones.";
        }
        return "{}";
    }
}

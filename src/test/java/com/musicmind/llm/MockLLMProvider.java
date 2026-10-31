package com.musicmind.llm;

/**
 * Test double for {@link LLMProvider}. Returns deterministic, well-formed
 * output chosen by the kind of prompt (plan, search, edit, explain, summary,
 * trends, options). It lives in the test tree only: the application never
 * creates one, so no canned response can reach a user.
 */
public class MockLLMProvider implements LLMProvider {

    @Override
    public String complete(String prompt) {
        String p = prompt == null ? "" : prompt.toLowerCase();
        if (p.contains("option") || p.contains("infeasible")) {
            return "{\"options\":[\"Relax the explicit-content filter\","
                    + "\"Shorten the target duration\",\"Allow more unfamiliar artists\"]}";
        }
        if (p.contains("trend")) {
            return "Your listening has shifted toward more upbeat genres since last time, "
                    + "while your long-time favourites remain steady.";
        }
        if (p.contains("modify") || p.contains("edit") || p.contains("instruction")) {
            return "{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}]}";
        }
        if (p.contains("search")) {
            return "{\"text\":\"upbeat songs\",\"genres\":[],\"mood\":\"upbeat\",\"limit\":10}";
        }
        if (p.contains("explain") || p.contains("decision")) {
            return "Selected a balance of familiar favourites and a few new artists "
                    + "that match your top genres, with no explicit tracks.";
        }
        if (p.contains("summar") || p.contains("taste") || p.contains("listener")) {
            return "Your taste centres on a few favourite genres, mixing familiar "
                    + "go-to artists with room to explore new ones.";
        }
        return "{\"targetMinutes\":60,\"allowExplicit\":false,\"familiarRatio\":0.7,"
                + "\"blockedArtists\":[],\"name\":\"MusicMind Mix\"}";
    }
}

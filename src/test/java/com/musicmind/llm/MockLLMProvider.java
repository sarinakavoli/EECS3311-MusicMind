package com.musicmind.llm;

import com.musicmind.agent.PromptBuilder;

/**
 * Test double for {@link LLMProvider}. Routes on the kind marker that
 * {@link PromptBuilder} puts at the start of every prompt, never on words in the
 * prompt body, and returns the exact shapes {@code ResponseParser} accepts. It lives
 * in the test tree only: the application never creates one, so no canned response
 * can reach a user.
 */
public class MockLLMProvider implements LLMProvider {

    @Override
    public String complete(String prompt) {
        PromptBuilder.Kind kind = PromptBuilder.kindOf(prompt)
                .orElseThrow(() -> new LLMException("MockLLMProvider: prompt has no kind marker"));
        return switch (kind) {
            case PLAN -> "{\"targetMinutes\":60,\"allowExplicit\":false,\"familiarRatio\":0.7,"
                    + "\"blockedArtists\":[],\"name\":\"MusicMind Mix\"}";
            case SEARCH -> "{\"keywords\":\"upbeat songs\",\"genre\":\"\",\"filters\":{},\"confidence\":0.8}";
            case EDIT -> "{\"commands\":[{\"type\":\"remove\",\"track\":\"1\"}]}";
            case OPTIONS -> "{\"options\":[\"Relax the explicit-content filter\","
                    + "\"Shorten the target duration\",\"Allow more unfamiliar artists\"]}";
            case TREND -> "Your listening has shifted toward more upbeat genres since last time, "
                    + "while your long-time favourites remain steady.";
            case EXPLAIN -> "Selected a balance of familiar favourites and a few new artists "
                    + "that match your top genres, with no explicit tracks.";
            case SUMMARY -> "Your taste centres on a few favourite genres, mixing familiar "
                    + "go-to artists with room to explore new ones.";
        };
    }
}

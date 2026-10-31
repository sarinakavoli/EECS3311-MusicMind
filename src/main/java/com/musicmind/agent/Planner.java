package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.SearchQuery;
import com.musicmind.llm.LLMProvider;

import java.util.Map;
import java.util.Objects;

/**
 * Turns the user's free text into something the agent can act on, using the LLM.
 * For F3 that is a {@link SearchQuery}; planning a playlist ({@code plan},
 * {@code parseGoal}) is added with F5.
 *
 * <p>The LLM only structures the text. If there is no provider, it fails, its reply is
 * malformed, or it is not confident enough, the original text is searched as typed
 * (the documented F3 fallback); callers detect that by the zero confidence.
 */
public class Planner {

    private final LLMProvider llm; // null when no key is configured
    private final PromptBuilder prompts;
    private final ResponseParser parser;
    private final Config config;

    public Planner(LLMProvider llm, PromptBuilder prompts, ResponseParser parser, Config config) {
        this.llm = llm;
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** R6: the structured query for {@code text}, or a raw keyword query when the parse cannot be trusted. */
    public SearchQuery parseSearchQuery(String text) {
        String original = text == null ? "" : text.strip();
        if (llm == null) {
            return rawKeywords(original);
        }
        try {
            SearchQuery parsed = parser.parseSearchQuery(llm.complete(prompts.buildSearchPrompt(original)));
            return parsed.confidence() >= config.searchMinConfidence() ? parsed : rawKeywords(original);
        } catch (RuntimeException e) {
            return rawKeywords(original);
        }
    }

    private static SearchQuery rawKeywords(String text) {
        return new SearchQuery(text, null, Map.of(), 0.0);
    }
}

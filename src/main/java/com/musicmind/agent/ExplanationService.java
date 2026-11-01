package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.llm.LLMProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Explains why a session chose its tracks (F10), from the recorded {@link DecisionTrace} only. The LLM words
 * it from a prompt that carries just those facts, and {@link ResponseParser#verifyExplanation} rejects any
 * text that names a track the trace does not contain. With no LLM, or if its text fails that check, a
 * sentence computed from the same trace is returned, ending with the "computed" label. A session with no
 * trace gets a general explanation that says so; track-level claims are never invented.
 */
public class ExplanationService {

    private final LLMProvider llm; // null when no key is configured
    private final PromptBuilder prompts;
    private final ResponseParser parser;
    private final Config config;

    public ExplanationService(LLMProvider llm, PromptBuilder prompts, ResponseParser parser, Config config) {
        this.llm = llm;
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** As {@link #explain(AgentSession, Map)} with no titles known, as when only stored data is available. */
    public String explain(AgentSession session) {
        return explain(session, Map.of());
    }

    /**
     * @param titles track titles by id, read for display only and never stored; the verified text shows a
     *               title where one is known and refers to the track generally otherwise, never an id
     */
    public String explain(AgentSession session, Map<String, String> titles) {
        DecisionTrace trace = session.trace();
        if (trace == null || trace.reasons().isEmpty()) {
            return config.wording("explainNoTrace");
        }
        if (llm != null) {
            try {
                String verified = parser.verifyExplanation(llm.complete(prompts.buildExplainPrompt(trace)), trace);
                return parser.replaceTrackIds(verified, id -> titles.containsKey(id)
                        ? "\"" + titles.get(id) + "\"" : config.wording("explainTrackGeneral"));
            } catch (RuntimeException e) {
                // fall back to the computed explanation below
            }
        }
        return computed(trace) + " " + config.computedLabel();
    }

    private String computed(DecisionTrace trace) {
        Map<String, Integer> byReason = new LinkedHashMap<>();
        trace.reasons().values().forEach(reason -> byReason.merge(reason, 1, Integer::sum));
        StringBuilder text = new StringBuilder(String.format(config.wording("explainComputed"),
                trace.strategyName(), trace.reasons().size()));
        byReason.forEach((reason, count) ->
                text.append(' ').append(String.format(config.wording("explainGroup"), count, reason)));
        if (!trace.constraintsSatisfied().isEmpty()) {
            text.append(' ').append(String.format(config.wording("explainConstraints"),
                    String.join("; ", trace.constraintsSatisfied())));
        }
        return text.toString();
    }
}

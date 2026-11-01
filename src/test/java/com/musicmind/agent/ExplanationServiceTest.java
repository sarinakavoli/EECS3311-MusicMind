package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.llm.LLMException;
import com.musicmind.llm.LLMProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F10: the explanation comes from the recorded trace only; anything else is replaced by a labelled computed one. */
class ExplanationServiceTest {

    private final Config config = Config.of(Map.of());
    private final PromptBuilder prompts = new PromptBuilder(config);
    private final ResponseParser parser = new ResponseParser(config);

    private static final DecisionTrace TRACE = new DecisionTrace("DiscoveryStrategy",
            Map.of("t1", "found by searching your \"jazz\" genre", "t2", "from your own listening history"),
            List.of("no duplicate tracks"));

    private ExplanationService service(LLMProvider llm) {
        return new ExplanationService(llm, prompts, parser, config);
    }

    private static AgentSession session(DecisionTrace trace) {
        AgentSession s = new AgentSession("s1", Instant.now(), null);
        s.setTrace(trace);
        return s;
    }

    @Test
    void usesTheLlmWordingWhenItStaysInsideTheTrace() {
        String reply = "[t1] came from your \"jazz\" genre and [t2] from your history.";
        assertEquals(reply, service(p -> reply).explain(session(TRACE)));
    }

    @Test
    void thePromptCarriesTheRecordedFactsAndNothingElse() {
        List<String> seen = new ArrayList<>();
        service(p -> {
            seen.add(p);
            return "Both were chosen from your own data.";
        }).explain(session(TRACE));
        String prompt = seen.get(0);
        assertTrue(prompt.contains("DiscoveryStrategy"));
        assertTrue(prompt.contains("[t1]: found by searching your \"jazz\" genre"));
        assertTrue(prompt.contains("no duplicate tracks"));
        assertEquals(PromptBuilder.Kind.EXPLAIN, PromptBuilder.kindOf(prompt).orElseThrow());
    }

    @Test
    void anInventedTrackReplacesTheLlmTextWithTheLabelledComputedOne() {
        String text = service(p -> "I picked [t9] because it is lovely.").explain(session(TRACE));
        assertFalse(text.contains("t9"));
        assertTrue(text.contains("DiscoveryStrategy"));
        assertTrue(text.endsWith(config.computedLabel()));
    }

    @Test
    void anInventedGenreNumberOrTitleIsReplacedToo() {
        for (String invented : List.of("These are all classical pieces.", "There are 14 tracks here.",
                "[t1] is \"Autumn Leaves\".")) {
            String text = service(p -> invented).explain(session(TRACE));
            assertTrue(text.endsWith(config.computedLabel()), invented);
        }
    }

    @Test
    void anLlmFailureOrNoLlmGivesTheComputedExplanationFromTheTrace() {
        String failed = service(p -> {
            throw new LLMException("down");
        }).explain(session(TRACE));
        String none = service(null).explain(session(TRACE));
        assertEquals(none, failed);
        assertTrue(none.contains("2 tracks"));
        assertTrue(none.contains("no duplicate tracks"));
        assertTrue(none.endsWith(config.computedLabel()));
    }

    @Test
    void aMissingOrEmptyTraceGivesTheGeneralExplanationThatSaysSoWithoutAskingTheLlm() {
        List<String> prompted = new ArrayList<>();
        ExplanationService svc = service(p -> {
            prompted.add(p);
            return "x";
        });
        String none = svc.explain(session(null));
        assertEquals(config.wording("explainNoTrace"), none);
        assertEquals(none, svc.explain(session(new DecisionTrace("Balanced", Map.of(), List.of()))));
        assertTrue(prompted.isEmpty());
        assertTrue(none.contains("only a general explanation"));
    }
}

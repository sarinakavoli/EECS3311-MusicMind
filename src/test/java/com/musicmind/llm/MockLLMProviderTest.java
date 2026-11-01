package com.musicmind.llm;

import com.musicmind.agent.PromptBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockLLMProviderTest {

    private final MockLLMProvider mock = new MockLLMProvider();

    @Test
    void routesOnTheLeadingMarkerForEveryKind() {
        for (PromptBuilder.Kind kind : PromptBuilder.Kind.values()) {
            String out = mock.complete(kind.marker() + "\nanything at all");
            assertFalse(out.isBlank(), kind.name());
            boolean json = kind == PromptBuilder.Kind.PLAN || kind == PromptBuilder.Kind.SEARCH
                    || kind == PromptBuilder.Kind.EDIT || kind == PromptBuilder.Kind.OPTIONS;
            assertEquals(json, out.startsWith("{"), kind.name());
        }
    }

    @Test
    void keywordsInThePromptBodyDoNotChangeTheRoute() {
        String plain = mock.complete(PromptBuilder.Kind.PLAN.marker() + "\nplan");
        String tricky = mock.complete(PromptBuilder.Kind.PLAN.marker() + "\nexplain trend search edit option");
        assertEquals(plain, tricky);
    }

    @Test
    void unmarkedOrNullPromptsAreRejected() {
        assertThrows(LLMException.class, () -> mock.complete("Describe the listening trends"));
        assertThrows(LLMException.class, () -> mock.complete(null));
    }

    @Test
    void responsesAreDeterministic() {
        String p = PromptBuilder.Kind.EXPLAIN.marker() + "\nx";
        assertEquals(mock.complete(p), mock.complete(p));
        assertTrue(mock.complete(p).length() > 0);
    }
}

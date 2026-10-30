package com.musicmind.llm;

/**
 * The single seam through which the system talks to a large language model.
 * Dependency inversion: Planner and ExplanationService depend on this
 * interface, so the concrete model is configurable and can be mocked in tests.
 */
public interface LLMProvider {
    /** Send a prompt and return the model's raw text completion. */
    String complete(String prompt);
}

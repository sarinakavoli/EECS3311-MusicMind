package com.musicmind.agent;

import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.Relaxation;

import java.util.List;
import java.util.Optional;

/**
 * F11: how the user answers a request that cannot be met. The CLI implements it as an interactive
 * prompt and the GUI as a dialog. {@code phrasedOptions} are the report's relaxations worded for the
 * user, in the same order; the handler returns the one the user picked, or empty to cancel.
 */
public interface NegotiationHandler {
    Optional<Relaxation> choose(FeasibilityReport report, List<String> phrasedOptions);
}

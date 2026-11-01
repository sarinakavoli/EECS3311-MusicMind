package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.AgentPlan;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Relaxation;
import com.musicmind.domain.RequestKind;
import com.musicmind.domain.SearchQuery;
import com.musicmind.domain.UserPreference;
import com.musicmind.llm.LLMException;
import com.musicmind.llm.LLMProvider;
import com.musicmind.llm.ParseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns the user's free text into something the agent can act on, using the LLM.
 * For F3 that is a {@link SearchQuery}; for F5 it is an {@link AgentPlan} ({@link #plan},
 * {@link #parseGoal}), and for F11 it words the relaxation options and regenerates the plan
 * once the user has chosen one.
 *
 * <p>The LLM only structures the text. For F3, if there is no provider, it fails, its reply is
 * malformed, or it is not confident enough, the original text is searched as typed
 * (the documented F3 fallback); callers detect that by the zero confidence. F5 has no such
 * fallback: without the LLM it says so, and a goal it cannot read confidently is sent back to the
 * user as a {@link ClarificationNeededException}. The LLM never names tracks or artists.
 */
public class Planner {

    private final LLMProvider llm; // null when no key is configured
    private final PromptBuilder prompts;
    private final ResponseParser parser;
    private final Config config;
    private final MoodGenreMap moods;

    public Planner(LLMProvider llm, PromptBuilder prompts, ResponseParser parser, Config config) {
        this(llm, prompts, parser, config, MoodGenreMap.load());
    }

    public Planner(LLMProvider llm, PromptBuilder prompts, ResponseParser parser, Config config, MoodGenreMap moods) {
        this.llm = llm;
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.config = Objects.requireNonNull(config, "config");
        this.moods = Objects.requireNonNull(moods, "moods");
    }

    // ---- F5 ----

    /** The plan for a request, without any stored preferences. */
    public AgentPlan plan(AgentRequest request) {
        return plan(request, List.of());
    }

    /**
     * Parses the goal with the LLM and merges, in this order of precedence for each setting: the
     * request's explicit constraints (R8), what the goal says, the stored preferences. Explicit content
     * can only be made stricter by a request or a preference; blocked artists are the request's plus the
     * stored ones. A mood in the goal and the stored preferred styles become the plan's seed genres.
     *
     * @throws ClarificationNeededException if the goal is blank, unclear or an explicit value is out of range
     * @throws IllegalStateException if there is no LLM to read the goal (R17)
     * @throws LLMException if the LLM could not be reached
     */
    public AgentPlan plan(AgentRequest request, List<UserPreference> preferences) {
        Objects.requireNonNull(request, "request");
        Interpretation parsed = interpret(request);
        PreferenceSettings prefs = PreferenceSettings.from(preferences);
        PlaylistSpec flags = request.constraints();
        PlaylistSpec fromGoal = parsed.spec();

        int minutes = flags != null && flags.targetMinutes() > 0 ? flags.targetMinutes() : fromGoal.targetMinutes();
        if (minutes < config.parserMinTargetMinutes() || minutes > config.parserMaxTargetMinutes()) {
            throw new ClarificationNeededException(String.format(config.wording("planBadMinutes"),
                    config.parserMinTargetMinutes(), config.parserMaxTargetMinutes()));
        }
        double ratio = familiarRatio(flags, parsed, prefs);
        boolean explicit = fromGoal.allowExplicit() && (flags == null || flags.allowExplicit());
        String name = flags != null && flags.name() != null && !flags.name().isBlank() ? flags.name() : fromGoal.name();
        List<String> blocked = flags == null ? List.of() : flags.blockedArtists();
        PlaylistSpec spec = prefs.applyTo(new PlaylistSpec(minutes, explicit, ratio, blocked, name));

        Set<String> seeds = new LinkedHashSet<>();
        moods.genresFor(parsed.extras().mood()).forEach(g -> seeds.add(g.toLowerCase(Locale.ROOT)));
        seeds.addAll(prefs.preferredStyles());
        List<String> seedGenres = seeds.stream().limit(config.planMaxSeedGenres()).toList();
        return new AgentPlan(spec, List.of(config.wording("planSteps").split("\\|")), Instant.now(), seedGenres);
    }

    /** The constraints the LLM reads out of a goal; low confidence or a malformed reply is a clarification. */
    public PlaylistSpec parseGoal(String text) {
        return interpret(new AgentRequest(text, RequestKind.CREATE_PLAYLIST)).spec();
    }

    /** F11: the plan again with the chosen relaxation applied; the steps record the change. */
    public AgentPlan regenerate(AgentPlan plan, Relaxation chosen) {
        List<String> steps = new ArrayList<>(plan.steps());
        steps.add(String.format(config.wording("planStepRelaxed"), chosen.description()));
        return new AgentPlan(chosen.applyTo(plan.spec()), steps, Instant.now(), plan.seedGenres());
    }

    /**
     * F11: the report's relaxations worded by the LLM. The wording is used only if it has one sentence
     * per relaxation; otherwise (or with no LLM, or on any failure) the computed descriptions are shown.
     */
    public List<String> phraseOptions(FeasibilityReport report) {
        List<String> computed = report.relaxationOptions();
        if (llm == null || computed.isEmpty()) {
            return computed;
        }
        try {
            List<String> phrased = parser.parseOptions(llm.complete(prompts.buildOptionsPrompt(report)));
            return phrased.size() == computed.size() ? phrased : computed;
        } catch (RuntimeException e) {
            return computed;
        }
    }

    private record Interpretation(PlaylistSpec spec, ResponseParser.PlanExtras extras) { }

    private Interpretation interpret(AgentRequest request) {
        String text = request.rawText() == null ? "" : request.rawText().strip();
        if (text.isEmpty()) {
            throw new ClarificationNeededException(config.wording("planEmptyGoal"));
        }
        if (llm == null) {
            throw new IllegalStateException(config.wording("planNeedsLlm"));
        }
        String reply;
        try {
            reply = llm.complete(prompts.buildPlanPrompt(request));
        } catch (LLMException e) {
            throw new LLMException(config.wording("planLlmFailed"), e);
        }
        try {
            PlaylistSpec parsed = parser.parsePlaylistSpec(reply);
            ResponseParser.PlanExtras extras = parser.parsePlanExtras(reply);
            if (extras.confidence() < config.planMinConfidence()) {
                throw unclear(text);
            }
            // The model cannot know artist ids, so any it returned are dropped; blocked artists come from the user.
            PlaylistSpec withoutIds = new PlaylistSpec(parsed.targetMinutes(), parsed.allowExplicit(),
                    parsed.familiarRatio(), List.of(), parsed.name());
            return new Interpretation(withoutIds, extras);
        } catch (ParseException e) {
            throw unclear(text);
        }
    }

    /** Explicit flag, else the goal's own share, else the stored bias, else the default. */
    private double familiarRatio(PlaylistSpec flags, Interpretation parsed, PreferenceSettings prefs) {
        if (flags != null && !Double.isNaN(flags.familiarRatio()) && flags.familiarRatio() >= 0) {
            if (flags.familiarRatio() > 1) {
                throw new ClarificationNeededException(config.wording("planBadFamiliar"));
            }
            return flags.familiarRatio();
        }
        if (parsed.extras().familiarStated()) {
            return parsed.spec().familiarRatio();
        }
        return prefs.familiarBias().orElse(parsed.spec().familiarRatio());
    }

    private ClarificationNeededException unclear(String text) {
        return new ClarificationNeededException(String.format(config.wording("planUnclear"), text));
    }

    // ---- F3 ----

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

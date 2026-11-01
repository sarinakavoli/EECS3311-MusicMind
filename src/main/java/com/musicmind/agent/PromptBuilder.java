package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.TrendReport;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Builds every prompt sent to the LLM. Each prompt starts with a stable kind marker
 * (see {@link Kind}) and carries only ids, genre names, numbers and the user's own
 * words, never a catalogue of tracks.
 */
public class PromptBuilder {

    /** The task a prompt asks for; its marker is the first line of the prompt. */
    public enum Kind {
        PLAN, EXPLAIN, SUMMARY, TREND, SEARCH, EDIT, OPTIONS;

        public String marker() {
            return "MUSICMIND_TASK: " + name();
        }
    }

    private final Config config;
    private final MoodGenreMap moods;

    public PromptBuilder(Config config) {
        this(config, MoodGenreMap.load());
    }

    public PromptBuilder(Config config, MoodGenreMap moods) {
        this.config = config;
        this.moods = moods;
    }

    /** The kind a prompt was built for, read from its leading marker. */
    public static Optional<Kind> kindOf(String prompt) {
        if (prompt == null) {
            return Optional.empty();
        }
        for (Kind kind : Kind.values()) {
            if (prompt.startsWith(kind.marker() + "\n")) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    public String buildPlanPrompt(AgentRequest request) {
        StringBuilder p = start(Kind.PLAN);
        p.append("Turn the playlist request below into constraints. ")
                .append("Do not name or choose any songs or artists.\n")
                .append("Request: ").append(request.rawText()).append('\n');
        PlaylistSpec c = request.constraints();
        if (c != null) {
            p.append("Explicit constraints that must be kept: targetMinutes=").append(c.targetMinutes())
                    .append(", allowExplicit=").append(c.allowExplicit())
                    .append(", familiarRatio=").append(c.familiarRatio());
            if (!c.blockedArtists().isEmpty()) {
                p.append(", blockedArtists=").append(c.blockedArtists());
            }
            p.append('\n');
        }
        p.append("Reply with JSON only: {\"targetMinutes\": whole number > 0, ")
                .append("\"allowExplicit\": false if the request asks for clean or no explicit content, otherwise true, ")
                .append("\"familiarRatio\": number from 0 (all new music) to 1 (all familiar), left out if the request does not say, ")
                .append("\"name\": short playlist name, ")
                .append("\"mood\": one of [").append(String.join(", ", moods.moods()))
                .append("] or \"\" if none fits, ")
                .append("\"confidence\": number from 0 to 1 for how sure you are what playlist is wanted}");
        return p.toString();
    }

    public String buildExplainPrompt(DecisionTrace trace) {
        StringBuilder p = start(Kind.EXPLAIN);
        p.append("Explain in a few friendly sentences why these tracks were chosen, using only the facts below. ")
                .append("Refer to a track only by its id in square brackets, like [id]. ")
                .append("Do not mention any other track, artist or id.\n")
                .append("Strategy: ").append(trace.strategyName()).append('\n');
        trace.reasons().forEach((id, reason) -> p.append('[').append(id).append("]: ").append(reason).append('\n'));
        p.append("Constraints satisfied: ").append(String.join("; ", trace.constraintsSatisfied()));
        return p.toString();
    }

    public String buildSummaryPrompt(ListeningProfile profile) {
        StringBuilder p = start(Kind.SUMMARY);
        p.append("In one friendly sentence (no lists, no quotes), summarise this listener's taste from the facts below.\n")
                .append("Genre weights: ").append(genres(profile.genreHistogram())).append('\n');
        if (profile.genreHistogram().isEmpty()) {
            p.append("No genre data is available. Do not make any claims about genres or genre variety.\n");
        }
        p.append("Top artists counted: ").append(profile.topArtists().size())
                .append(", top tracks counted: ").append(profile.topTracks().size());
        return p.toString();
    }

    public String buildTrendPrompt(TrendReport report) {
        StringBuilder p = start(Kind.TREND);
        p.append("In two or three friendly sentences, describe how this listener's taste has changed, ")
                .append("using only the facts below.\n");
        report.genresByRange().forEach((range, hist) ->
                p.append(range).append(" genres: ").append(genres(hist)).append('\n'));
        p.append("Genres added: ").append(limited(report.addedGenres())).append('\n')
                .append("Genres dropped: ").append(limited(report.droppedGenres())).append('\n')
                .append("Artists added: ").append(report.addedArtistIds().size())
                .append(", artists dropped: ").append(report.droppedArtistIds().size()).append('\n');
        if (!report.gaps().isEmpty()) {
            p.append("Missing data: ").append(String.join("; ", report.gaps()));
        }
        return p.toString();
    }

    public String buildSearchPrompt(String text) {
        return start(Kind.SEARCH)
                + "Turn this music search into Spotify search terms. Do not name specific songs. "
                + "Keep keywords short (a few words) and do not repeat the genre in the keywords.\n"
                + "Search: " + text + "\n"
                + "Reply with JSON only: {\"keywords\": free text, \"genre\": genre or \"\", "
                + "\"filters\": {\"year\": ..., \"artist\": ...} using only those two fields and only if you are sure of them, "
                + "\"confidence\": number from 0 to 1}";
    }

    /** @param trackRefs the playlist's track ids in order; the model refers to them by 1-based position */
    public String buildEditPrompt(String instruction, List<String> trackRefs) {
        StringBuilder p = start(Kind.EDIT);
        p.append("Turn the edit instruction into commands for a playlist. Do not name specific songs.\n")
                .append("Instruction: ").append(instruction).append('\n')
                .append("Tracks (position: id):\n");
        IntStream.range(0, trackRefs.size())
                .forEach(i -> p.append(i + 1).append(": ").append(trackRefs.get(i)).append('\n'));
        p.append("Reply with JSON only: {\"commands\": [{\"type\": \"remove\"|\"add\"|\"replace\"|\"reorder\", ")
                .append("\"track\": position or id (remove, replace, reorder), ")
                .append("\"query\": search text (add, replace), \"position\": new 0-based index (reorder)}]}");
        return p.toString();
    }

    public String buildOptionsPrompt(FeasibilityReport report) {
        return start(Kind.OPTIONS)
                + "A playlist request cannot be met as stated. Reword each possible relaxation below as one short "
                + "friendly sentence, using only the facts below. Give exactly one sentence per relaxation, in the same order.\n"
                + "Conflicts: " + String.join("; ", report.conflicts()) + "\n"
                + "Possible relaxations: " + String.join("; ", report.relaxationOptions()) + "\n"
                + "Reply with JSON only: {\"options\": [short sentences]}";
    }

    private static StringBuilder start(Kind kind) {
        return new StringBuilder(kind.marker()).append('\n');
    }

    private String genres(Map<String, Double> histogram) {
        if (histogram.isEmpty()) {
            return "none";
        }
        return histogram.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(config.promptMaxGenres())
                .map(e -> e.getKey() + " " + Math.round(e.getValue() * 100) / 100.0)
                .collect(Collectors.joining(", "));
    }

    private String limited(List<String> items) {
        return items.isEmpty() ? "none"
                : items.stream().limit(config.promptMaxGenres()).collect(Collectors.joining(", "));
    }
}

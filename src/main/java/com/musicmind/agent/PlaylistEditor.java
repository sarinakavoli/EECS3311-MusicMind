package com.musicmind.agent;

import com.musicmind.agent.command.AddItemsCommand;
import com.musicmind.agent.command.PlaylistCommand;
import com.musicmind.agent.command.RemoveTrackCommand;
import com.musicmind.domain.GenreNames;
import com.musicmind.agent.command.ReplaceTrackCommand;
import com.musicmind.config.Config;
import com.musicmind.domain.SongTags;
import com.musicmind.domain.Track;
import com.musicmind.llm.LLMException;
import com.musicmind.llm.LLMProvider;
import com.musicmind.llm.ParseException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an edit instruction into {@link PlaylistCommand}s (F6). The LLM reads the instruction against the
 * playlist's ids, explicit flags and lengths and answers with commands; {@link ResponseParser} validates
 * them and finds any track to add by searching Spotify, never from the model. An instruction that is only a
 * feeling ("calmer") has no command to express it: it is handled with the curated {@link MoodGenreMap} and
 * the result is flagged approximate. An ambiguous instruction is answered with the model's question, or with
 * a request to rephrase, and nothing is changed.
 */
public class PlaylistEditor {

    /**
     * The commands to run, whether they came from the mood heuristic (approximate) with the mood used, and the
     * length the instruction states: a signed change in minutes or a new total, 0 for neither.
     */
    public record EditPlan(List<PlaylistCommand> commands, boolean approximate, String mood,
                           int lengthChangeMinutes, int targetMinutes) {
        public EditPlan(List<PlaylistCommand> commands, boolean approximate, String mood) {
            this(commands, approximate, mood, 0, 0);
        }

        /** True if the instruction asked for a particular length. */
        public boolean statesLength() {
            return lengthChangeMinutes != 0 || targetMinutes != 0;
        }

        /** This plan's commands with the length another plan stated, when this one states none. */
        public EditPlan keepingLengthOf(EditPlan earlier) {
            return statesLength() ? this
                    : new EditPlan(commands, approximate, mood, earlier.lengthChangeMinutes, earlier.targetMinutes);
        }

        /** The same commands without the stated length, for when only part of the edit is applied. */
        public EditPlan withCommands(List<PlaylistCommand> kept) {
            return new EditPlan(kept, approximate, mood, 0, 0);
        }
    }

    private final LLMProvider llm; // null when no key is configured
    private final PromptBuilder prompts;
    private final ResponseParser parser;
    private final MoodGenreMap moods;
    private final Config config;

    public PlaylistEditor(LLMProvider llm, PromptBuilder prompts, ResponseParser parser, MoodGenreMap moods,
                          Config config) {
        this.llm = llm;
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.moods = Objects.requireNonNull(moods, "moods");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * @param artistGenres genres known for artist ids (from the stored taste profile); may be empty
     * @throws IllegalStateException if there is no LLM
     * @throws LLMException if the LLM could not be reached
     * @throws ClarificationNeededException if the instruction cannot be turned into a change
     */
    public EditPlan plan(PlaylistContext playlist, String instruction, Map<String, List<String>> artistGenres) {
        return plan(playlist, instruction, artistGenres, Map.of());
    }

    /** As above; {@code songTags} (by track id, may be empty) go into the prompt as the songs' Last.fm tags. */
    public EditPlan plan(PlaylistContext playlist, String instruction, Map<String, List<String>> artistGenres,
                         Map<String, SongTags> songTags) {
        requireLlm();
        EditPlan byName = removalByName(playlist, instruction);
        if (byName != null) {
            return byName;
        }
        String reply;
        try {
            reply = llm.complete(prompts.buildEditPrompt(instruction, playlist, songTags));
        } catch (LLMException e) {
            throw new LLMException(config.wording("modifyLlmFailed"), e);
        }
        ResponseParser.EditExtras extras;
        try {
            extras = parser.parseEditExtras(reply);
            if (extras.hasCommands()) {
                return new EditPlan(parser.forPlaylist(playlist).parseCommands(reply), false, "",
                        extras.lengthChangeMinutes(), extras.targetMinutes());
            }
        } catch (ParseException e) {
            throw unclear(instruction);
        }
        if (!extras.question().isEmpty()) {
            throw new ClarificationNeededException(extras.question());
        }
        if (extras.mood().isEmpty()) {
            throw unclear(instruction);
        }
        return moodEdit(playlist, extras.mood(), artistGenres);
    }

    private static final Pattern REMOVAL = Pattern.compile(
            "^\\s*(?:remove|delete|drop|take out|get rid of)\\s+((?:(?:all|the|songs by|tracks by|song|track|everything by)\\s+)*)(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NAME_SPLIT = Pattern.compile("\\s*,\\s*(?:and\\s+)?|\\s+and\\s+", Pattern.CASE_INSENSITIVE);

    /**
     * A plain "remove Madonna" or "remove Come Clean": matched here against the artists and titles of the
     * tracks, so no name ever goes to the model. Null unless every named part matches at least one track.
     */
    private EditPlan removalByName(PlaylistContext playlist, String instruction) {
        if (instruction == null) {
            return null;
        }
        Matcher m = REMOVAL.matcher(instruction);
        if (!m.matches()) {
            return null;
        }
        String rest = m.group(2).replaceAll("^[\\s\"'\u2018\u2019\u201c\u201d]+|[\\s,.!?;:\"'\u2018\u2019\u201c\u201d]+$", "");
        if (rest.isEmpty()) {
            return null;
        }
        Set<Track> matched = new LinkedHashSet<>(namedTracks(playlist, rest));
        if (matched.isEmpty()) {
            for (String part : NAME_SPLIT.split(rest)) {
                String name = part.replaceAll("^[\\s\"'\u2018\u2019\u201c\u201d]+|[\\s,.!?;:\"'\u2018\u2019\u201c\u201d]+$", "");
                List<Track> found = name.isEmpty() ? List.of() : namedTracks(playlist, name);
                if (found.isEmpty()) {
                    return null;
                }
                matched.addAll(found);
            }
        }
        if (matched.isEmpty()) {
            return null;
        }
        List<PlaylistCommand> commands = new ArrayList<>();
        for (Track t : matched) {
            commands.add(new RemoveTrackCommand(config, playlist.music(), playlist.playlistId(), t));
        }
        return new EditPlan(commands, false, "");
    }

    /** Tracks with an artist or a title (also without a " - ..." or "(...)" tail) equal to the name. */
    private static List<Track> namedTracks(PlaylistContext playlist, String name) {
        String wanted = SearchRanker.normalise(name);
        List<Track> found = new ArrayList<>();
        if (wanted.isEmpty()) {
            return found;
        }
        for (Track t : playlist.tracks()) {
            boolean hit = t.artistNames().stream().anyMatch(a -> SearchRanker.normalise(a).equals(wanted));
            String title = t.title() == null ? "" : t.title();
            String bare = title.replaceAll("\\s*\\(.*$", "").replaceAll("\\s+-\\s.*$", "");
            hit = hit || SearchRanker.normalise(title).equals(wanted) || SearchRanker.normalise(bare).equals(wanted);
            if (hit) {
                found.add(t);
            }
        }
        return found;
    }

    /** @throws IllegalStateException if there is no LLM, so callers can stop before reading anything */
    public void requireLlm() {
        if (llm == null) {
            throw new IllegalStateException(config.wording("modifyNeedsLlm"));
        }
    }

    private EditPlan moodEdit(PlaylistContext playlist, String mood, Map<String, List<String>> artistGenres) {
        List<String> genres = moods.genresFor(mood);
        if (genres.isEmpty()) {
            throw new ClarificationNeededException(String.format(config.wording("modifyUnknownMood"), mood));
        }
        Set<String> fitting = new LinkedHashSet<>();
        genres.forEach(g -> fitting.add(GenreNames.canonical(g)));
        Set<String> taken = new LinkedHashSet<>();
        playlist.tracks().forEach(t -> taken.add(t.id()));

        List<PlaylistCommand> commands = new ArrayList<>();
        int next = 0;
        for (Track track : playlist.tracks()) {
            if (commands.size() >= config.editMoodMaxChanges()) {
                break;
            }
            if (misfits(track, fitting, artistGenres)) {
                Track replacement = findNew(playlist, genres.get(next++ % genres.size()), taken);
                if (replacement != null) {
                    commands.add(new ReplaceTrackCommand(config, playlist.music(), playlist.playlistId(), track, replacement));
                }
            }
        }
        if (commands.isEmpty()) {
            for (int i = 0; i < config.editMoodAddCount(); i++) {
                Track added = findNew(playlist, genres.get(i % genres.size()), taken);
                if (added != null) {
                    commands.add(new AddItemsCommand(config, playlist.music(), playlist.playlistId(), List.of(added)));
                }
            }
        }
        if (commands.isEmpty()) {
            throw new ClarificationNeededException(String.format(config.wording("modifyNothingFound"), mood));
        }
        return new EditPlan(commands, true, mood);
    }

    /** True only when every artist of the track has known genres and none of them suits the mood. */
    private static boolean misfits(Track track, Set<String> fitting, Map<String, List<String>> artistGenres) {
        if (track.artistIds().isEmpty()) {
            return false;
        }
        for (String artist : track.artistIds()) {
            List<String> known = artistGenres.getOrDefault(artist, List.of());
            if (known.isEmpty()) {
                return false;
            }
            for (String g : known) {
                if (fitting.contains(GenreNames.canonical(g))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The first search result for the genre (as a plain keyword) not already in the playlist or chosen; null if none. */
    private Track findNew(PlaylistContext playlist, String genre, Set<String> taken) {
        for (Track t : playlist.music().search(genre, config.commandSearchLimit())) {
            if (playlist.allows(t) && taken.add(t.id())) {
                return t;
            }
        }
        return null;
    }

    private ClarificationNeededException unclear(String instruction) {
        return new ClarificationNeededException(String.format(config.wording("modifyUnclear"), instruction));
    }
}

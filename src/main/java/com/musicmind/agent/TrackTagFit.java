package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.TagService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Decides from Last.fm's tags for one song (artist + title) whether a familiar track fits a Create goal.
 * An artist's genres are too coarse (a pop star is also soul and r&amp;b), the song's own tags are not.
 *
 * <p>Deterministic: the song fits when one of its tags, compared as letters and digits only and ignoring case,
 * equals one of the plan's tags or a synonym listed for it in {@code musicmind.properties}
 * ({@code lastfm.synonyms.<word>}). A tag that merely contains a plan word ("slow" for "slow dance") does not
 * count. At most {@code lastfm.maxTrackTagLookups} songs are looked up per retrieval and each only once;
 * the answers live in memory for the run, are never stored and never sent to the LLM. After one failed lookup
 * no more are made. In every case where the answer is unknown (cap reached, failure, Last.fm does not know the
 * song) {@link #fits} is empty and the caller falls back to the artist-genre rule.
 */
final class TrackTagFit {

    private final TagService service;
    private final int maxLookups;
    private final Set<String> goalTags;
    private final Consumer<String> failures;
    private final Map<String, Optional<Boolean>> answers = new HashMap<>();
    private int lookups;
    private boolean failed;

    TrackTagFit(TagService service, Config config, List<String> planTags, List<String> seedGenres,
                Consumer<String> failures) {
        this.service = service;
        this.maxLookups = config.lastfmMaxTrackTagLookups();
        this.failures = failures;
        Set<String> wanted = new HashSet<>();
        // The genres the user asked for count as goal words too, so a song tagged "soul" is not vetoed for a "soul" request.
        List<String> goalWords = new java.util.ArrayList<>(planTags);
        goalWords.addAll(seedGenres);
        for (String tag : goalWords) {
            wanted.add(squash(tag));
            config.lastfmSynonyms(tag).forEach(s -> wanted.add(squash(s)));
        }
        wanted.remove("");
        this.goalTags = Set.copyOf(wanted);
    }

    /** True or false when Last.fm's tags for the song decide it; empty when they cannot. */
    synchronized Optional<Boolean> fits(Track track) {
        if (track.artistNames().isEmpty() || track.title() == null) {
            return Optional.empty();
        }
        String artist = track.artistNames().get(0);
        String key = squash(artist) + "|" + squash(track.title());
        Optional<Boolean> known = answers.get(key);
        if (known != null) {
            return known;
        }
        if (failed || lookups >= maxLookups) {
            return Optional.empty();
        }
        lookups++;
        Optional<Boolean> answer;
        try {
            List<String> tags = service.trackTags(artist, track.title());
            answer = tags.isEmpty() ? Optional.empty()
                    : Optional.of(tags.stream().anyMatch(t -> goalTags.contains(squash(t))));
        } catch (MusicServiceException e) {
            failed = true;
            failures.accept(e.getMessage());
            answer = Optional.empty();
        }
        answers.put(key, answer);
        return answer;
    }

    private static String squash(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}

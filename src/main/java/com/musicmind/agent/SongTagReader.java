package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.SongTags;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.TagService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Reads Last.fm's tags for the songs of a playlist so Modify can judge mood or style. Looks up at most
 * {@code lastfm.maxTrackTagLookups} songs, in playlist order, within {@code lastfm.trackTagBudgetSeconds} of the
 * first lookup, and stops after the first failure. A song without tags of its own gets the cached tags of its
 * first artist that has some; otherwise it has none. Artist names and titles go only to Last.fm; nothing is stored.
 */
public final class SongTagReader {

    private final TagService service;
    private final ArtistGenreCache artistTags;
    private final int maxLookups;
    private final int kept;
    private final long budgetNanos;
    private final LongSupplier nanos;

    public SongTagReader(TagService service, ArtistGenreCache artistTags, Config config, LongSupplier nanos) {
        this.service = service;
        this.artistTags = artistTags;
        this.maxLookups = config.lastfmMaxTrackTagLookups();
        this.kept = config.lastfmTrackTagsKept();
        this.budgetNanos = config.lastfmTrackTagBudgetSeconds() * 1_000_000_000L;
        this.nanos = nanos;
    }

    /** The tags per track id, for every given track. */
    public Map<String, SongTags> read(List<Track> tracks) {
        Map<String, SongTags> out = new LinkedHashMap<>();
        int lookups = 0;
        long startedAt = 0;
        boolean stopped = false;
        for (Track track : tracks) {
            List<String> own = List.of();
            if (!stopped && lookups < maxLookups && !track.artistNames().isEmpty() && track.title() != null) {
                long now = nanos.getAsLong();
                if (lookups == 0) {
                    startedAt = now;
                }
                if (now - startedAt > budgetNanos) {
                    stopped = true;
                } else {
                    lookups++;
                    try {
                        own = service.trackTags(track.artistNames().get(0), track.title()).stream()
                                .limit(kept).toList();
                    } catch (MusicServiceException e) {
                        stopped = true;
                    }
                }
            }
            out.put(track.id(), own.isEmpty() ? fromArtists(track) : new SongTags(own, SongTags.Kind.SONG));
        }
        return out;
    }

    private SongTags fromArtists(Track track) {
        for (String artistId : track.artistIds()) {
            Optional<List<String>> cached = artistTags.cached(artistId);
            if (cached.isPresent() && !cached.get().isEmpty()) {
                return new SongTags(cached.get().stream().limit(kept).toList(), SongTags.Kind.ARTIST);
            }
        }
        return new SongTags(List.of(), SongTags.Kind.NONE);
    }
}

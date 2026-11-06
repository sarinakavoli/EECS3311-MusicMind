package com.musicmind.music;

import com.musicmind.domain.TagTrack;

import java.util.List;

/** Looks up the top songs for a tag (a mood, genre or activity word). */
public interface TagService {

    /** A service that knows no tags. */
    TagService NONE = (tag, limit) -> List.of();

    /**
     * @return up to {@code limit} songs for the tag; empty when the service is not configured
     * @throws MusicServiceException on an HTTP error or an unreadable reply
     */
    List<TagTrack> topTracks(String tag, int limit);

    /**
     * The tags people give one song, most used first (Last.fm {@code track.gettoptags}).
     *
     * @return the tag names; empty when the service is not configured or does not know the song
     * @throws MusicServiceException on an HTTP error or an unreadable reply
     */
    default List<String> trackTags(String artist, String title) {
        return List.of();
    }

    /**
     * Artists Last.fm lists as similar to the given one, most similar first (Last.fm {@code artist.getsimilar}).
     *
     * @return up to {@code limit} names; empty when the service is not configured or does not know the artist
     * @throws MusicServiceException on an HTTP error or an unreadable reply
     */
    default List<String> similarArtists(String artistName, int limit) {
        return List.of();
    }
}

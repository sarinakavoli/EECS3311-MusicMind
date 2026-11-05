package com.musicmind.music;

import com.musicmind.domain.TagTrack;

import java.util.List;

/** Looks up the top songs for a tag (a mood, genre or activity word). */
public interface TagService {

    /**
     * @return up to {@code limit} songs for the tag; empty when the service is not configured
     * @throws MusicServiceException on an HTTP error or an unreadable reply
     */
    List<TagTrack> topTracks(String tag, int limit);
}

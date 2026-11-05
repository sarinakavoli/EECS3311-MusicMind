package com.musicmind.domain;

/** A song listed under a tag by Last.fm. Not Spotify content; never sent to the LLM. */
public record TagTrack(String title, String artist) {
}

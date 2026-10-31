package com.musicmind.controller;

import com.musicmind.agent.ProfileBuilder;
import com.musicmind.agent.ProfileSummarizer;
import com.google.gson.Gson;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.RequestKind;
import com.musicmind.domain.Track;
import com.musicmind.memory.MemoryManager;
import com.musicmind.music.MusicService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Facade (GoF) over the agent subsystem. It exposes one high-level method per
 * feature and hides the collaboration between the music service, the profile
 * builder, the LLM summary and the memory store. Both the GUI and the CLI go
 * through this (via {@link AppController}), so neither duplicates logic.
 */
public class MusicAgentFacade {

    private final MusicService music;
    private final MemoryManager memory;
    private final ProfileBuilder profileBuilder;
    private final ProfileSummarizer summarizer;

    public MusicAgentFacade(MusicService music, MemoryManager memory,
                            ProfileBuilder profileBuilder, ProfileSummarizer summarizer) {
        this.music = music;
        this.memory = memory;
        this.profileBuilder = profileBuilder;
        this.summarizer = summarizer;
    }

    /**
     * F1 — View Taste Profile. Fetches listening data, builds the profile, attaches
     * a one-line summary, and remembers the run as a session whose derived data
     * (genre histogram, ids, summary) is stored as JSON. No titles or artist names
     * are stored (Spotify terms).
     */
    public TasteProfileResult viewTasteProfile(String timeRange) {
        String userId = music.currentUserId();
        ListeningProfile profile = profileBuilder.build(timeRange);
        String summary = summarizer.summarize(profile);

        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("genreHistogram", profile.genreHistogram());
        derived.put("artistIds", profile.topArtists().stream().map(Artist::id).toList());
        derived.put("trackIds", profile.topTracks().stream().map(Track::id).toList());
        derived.put("summary", summary);
        derived.put("generatedAt", profile.generatedAt() == null ? null : profile.generatedAt().toString());

        AgentSession session = new AgentSession(UUID.randomUUID().toString(), Instant.now(), null);
        session.setStatus("done");
        session.setRequest(new AgentRequest("view taste profile (" + timeRange + ")", RequestKind.ANALYZE_TASTE));
        session.setDerivedData(new Gson().toJson(derived));
        memory.saveSession(session);
        return new TasteProfileResult(userId, profile, summary);
    }
}

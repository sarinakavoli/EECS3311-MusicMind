package com.musicmind.controller;

import com.musicmind.agent.ProfileBuilder;
import com.musicmind.agent.ProfileSummarizer;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.memory.MemoryManager;
import com.musicmind.music.MusicService;

import java.util.Optional;

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
     * F1 — View Taste Profile. Fetches listening data, builds the profile,
     * remembers it, and attaches a one-line summary.
     */
    public TasteProfileResult viewTasteProfile(String timeRange) {
        String userId = music.currentUserId();
        ListeningProfile profile = profileBuilder.build(timeRange);
        memory.saveProfile(userId, profile);
        String summary = summarizer.summarize(profile);
        return new TasteProfileResult(userId, profile, summary);
    }

    /** The last profile remembered for a user, if the agent has built one before. */
    public Optional<ListeningProfile> lastProfile(String userId) {
        return memory.loadProfile(userId);
    }
}

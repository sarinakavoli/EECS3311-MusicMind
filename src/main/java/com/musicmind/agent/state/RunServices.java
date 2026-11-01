package com.musicmind.agent.state;

import com.musicmind.agent.ConstraintChecker;
import com.musicmind.agent.ExplanationService;
import com.musicmind.agent.Planner;
import com.musicmind.agent.PlaylistValidator;
import com.musicmind.agent.RecommendationEngine;
import com.musicmind.agent.command.PlaylistManager;
import com.musicmind.config.Config;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.memory.MemoryManager;
import com.musicmind.music.MusicService;

import java.util.function.Supplier;

/**
 * The collaborators the states of one playlist-creation run use, so a state class holds its phase's
 * logic and takes everything else from here.
 *
 * @param profile         the listening profile to plan from (a fresh stored one, else a rebuilt one)
 * @param reauthenticator signs the user in to Spotify again; null where that is not possible
 */
public record RunServices(Config config, Planner planner, ConstraintChecker checker, PlaylistValidator validator,
                          RecommendationEngine engine, PlaylistManager playlists, MusicService music,
                          MemoryManager memory, ExplanationService explainer, Supplier<String> userId,
                          Supplier<ListeningProfile> profile, Runnable reauthenticator) {
}

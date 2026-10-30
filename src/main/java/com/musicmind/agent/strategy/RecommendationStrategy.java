package com.musicmind.agent.strategy;

import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Track;

import java.util.List;

/**
 * Strategy pattern: a policy for selecting candidate tracks from the retrieved
 * pools. Concrete strategies (Familiar, Discovery, Balanced) encapsulate one
 * selection policy each; the RecommendationEngine chooses one at runtime.
 */
public interface RecommendationStrategy {
    /**
     * @param pools candidate tracks grouped by source
     *              (e.g. "familiar" and "novel")
     * @param spec  the constraints to honour
     * @return the selected, ordered tracks
     */
    List<Track> selectCandidates(java.util.Map<String, List<Track>> pools, PlaylistSpec spec);
}

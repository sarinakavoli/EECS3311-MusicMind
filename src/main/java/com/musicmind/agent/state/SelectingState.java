package com.musicmind.agent.state;

import com.musicmind.agent.strategy.BalancedStrategy;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.PlaylistSpec;

/**
 * Has the recommender pick the tracks with the {@link BalancedStrategy}, from the pools already read.
 * The LLM takes no part in this: selection is deterministic and every pick has a recorded reason.
 */
public class SelectingState implements SessionState {

    @Override
    public String name() {
        return "selecting";
    }

    @Override
    public void handle(AgentSession session) {
        RunContext ctx = session.context();
        RunServices services = ctx.services;
        PlaylistSpec spec = ctx.spec();
        // Validation may have moved the length the recommender aims for; every other constraint is the spec's.
        PlaylistSpec asked = new PlaylistSpec(ctx.engineMinutes, spec.allowExplicit(), spec.familiarRatio(),
                spec.blockedArtists(), spec.name());
        synchronized (services.engine()) {
            services.engine().setStrategy(new BalancedStrategy(services.config()));
            ctx.recommendations = services.engine().recommend(ctx.retrieval, asked, ctx.feedback);
            ctx.trace = services.engine().lastTrace();
            services.engine().lastNotices().forEach(session::addNotice);
        }
        session.setState(new ValidatingState());
    }
}

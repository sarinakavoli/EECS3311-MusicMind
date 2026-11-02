package com.musicmind.agent.state;

import com.musicmind.domain.AgentSession;

/** Loads what the user has told MusicMind (preferences, feedback) and has the Planner turn the goal into a plan. */
public class PlanningState implements SessionState {

    @Override
    public String name() {
        return "planning";
    }

    @Override
    public void handle(AgentSession session) {
        RunContext ctx = session.context();
        RunServices services = ctx.services;
        String userId = services.userId().get();
        // Preferences and feedback load first because plan() merges the stored preferences into the spec.
        ctx.preferences = services.memory().loadPreferences(userId);
        ctx.feedback = services.memory().loadFeedback(userId);
        ctx.candidateIds = services.memory().loadCandidates(userId);
        ctx.plan = services.planner().plan(ctx.request, ctx.preferences);
        ctx.engineMinutes = ctx.spec().targetMinutes();
        session.setPlanSteps(ctx.plan.steps());
        session.setState(new RetrievingState());
    }
}

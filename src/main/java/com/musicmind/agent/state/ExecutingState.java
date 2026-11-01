package com.musicmind.agent.state;

import com.musicmind.agent.command.AddItemsCommand;
import com.musicmind.agent.command.CreatePlaylistCommand;
import com.musicmind.config.Config;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Recommendation;
import com.musicmind.domain.Track;

import java.util.List;

/**
 * The only state that writes to Spotify: a {@link CreatePlaylistCommand} and an {@link AddItemsCommand}
 * run as one batch by the PlaylistManager, which undoes the part already done if the other fails.
 */
public class ExecutingState implements SessionState {

    @Override
    public String name() {
        return "executing";
    }

    @Override
    public void handle(AgentSession session) {
        RunContext ctx = session.context();
        RunServices services = ctx.services;
        Config config = services.config();
        PlaylistSpec spec = ctx.spec();
        List<Track> tracks = ctx.recommendations.stream().map(Recommendation::track).toList();
        String name = spec.name() == null || spec.name().isBlank() ? config.parserDefaultPlaylistName() : spec.name();

        CreatePlaylistCommand create = new CreatePlaylistCommand(config, services.music(), services.userId().get(),
                name, List.of());
        AddItemsCommand add = new AddItemsCommand(config, services.music(), create::playlistId, tracks);
        services.playlists().executeAll(List.of(create, add));

        ctx.playlist = new Playlist(create.playlistId(), name, tracks);
        session.setPlaylistId(create.playlistId());
        session.setState(new DoneState());
    }
}

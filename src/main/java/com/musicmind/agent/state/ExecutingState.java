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
 * run as one batch by the PlaylistManager, which undoes the part already done if the other fails. If that
 * undo fails too (an expired token), the playlist id is kept on the run: a resume fills that playlist rather
 * than creating a second one, and a run that fails keeps the id in a notice (FailedState) so it is never invisible.
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

        String playlistId = ctx.leftoverPlaylistId;
        if (playlistId == null) {
            CreatePlaylistCommand create = new CreatePlaylistCommand(config, services.music(),
                    services.userId().get(), name, List.of());
            AddItemsCommand add = new AddItemsCommand(config, services.music(), create::playlistId, tracks);
            try {
                services.playlists().executeAll(List.of(create, add));
            } catch (RuntimeException e) {
                // The batch undoes what it did; if that undo failed too, the playlist is still on Spotify.
                if (create.playlistId() != null && e.getSuppressed().length > 0) {
                    ctx.leftoverPlaylistId = create.playlistId();
                }
                throw e;
            }
            playlistId = create.playlistId();
        } else {
            // Resuming after a re-login: the playlist from the interrupted attempt is filled, not duplicated.
            services.playlists().executeAll(List.of(new AddItemsCommand(config, services.music(), ctx.leftoverPlaylistId, tracks)));
        }

        ctx.playlist = new Playlist(playlistId, name, tracks);
        session.setPlaylistId(playlistId);
        session.setState(new DoneState());
    }
}

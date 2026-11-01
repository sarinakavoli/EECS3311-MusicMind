package com.musicmind.agent.command;

import com.musicmind.config.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Several commands from one instruction, kept as a single history entry so one undo reverts the
 * whole instruction. Undo runs in reverse order.
 */
class BatchCommand implements PlaylistCommand {

    private final Config config;
    private final List<PlaylistCommand> commands;

    BatchCommand(Config config, List<PlaylistCommand> commands) {
        this.config = config;
        this.commands = List.copyOf(commands);
    }

    /** Runs all in order; if one fails, the ones already applied are undone and the failure is rethrown. */
    @Override
    public void execute() {
        List<PlaylistCommand> applied = new ArrayList<>();
        for (PlaylistCommand c : commands) {
            try {
                c.execute();
            } catch (RuntimeException e) {
                rollBack(applied, e);
                throw e;
            }
            applied.add(c);
        }
    }

    /** Undoes in reverse order; if one fails, the ones already undone are applied again, so the undo can be repeated. */
    @Override
    public void undo() {
        List<PlaylistCommand> undone = new ArrayList<>();
        for (int i = commands.size() - 1; i >= 0; i--) {
            try {
                commands.get(i).undo();
            } catch (RuntimeException e) {
                rollForward(undone, e);
                throw e;
            }
            undone.add(commands.get(i));
        }
    }

    private static void rollForward(List<PlaylistCommand> undone, RuntimeException cause) {
        for (int i = undone.size() - 1; i >= 0; i--) {
            try {
                undone.get(i).execute();
            } catch (RuntimeException secondary) {
                if (secondary != cause) {
                    cause.addSuppressed(secondary);
                }
            }
        }
    }

    private static void rollBack(List<PlaylistCommand> applied, RuntimeException cause) {
        for (int i = applied.size() - 1; i >= 0; i--) {
            try {
                applied.get(i).undo();
            } catch (RuntimeException secondary) {
                if (secondary != cause) { // the same failure repeating is not a second one
                    cause.addSuppressed(secondary);
                }
            }
        }
    }

    @Override
    public String describe() {
        return String.join(config.wording("cmdBatchSeparator") + " ", commands.stream().map(PlaylistCommand::describe).toList());
    }

    @Override
    public String playlistId() {
        return commands.stream().map(PlaylistCommand::playlistId).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }
}

package com.musicmind.agent;

/**
 * A request that cannot be met and could not be negotiated: no relaxation helps, nobody was there to
 * choose one, or the agreed relaxations were not enough. The message explains why; nothing was changed.
 */
public class InfeasibleRequestException extends RuntimeException {
    public InfeasibleRequestException(String message) {
        super(message);
    }
}

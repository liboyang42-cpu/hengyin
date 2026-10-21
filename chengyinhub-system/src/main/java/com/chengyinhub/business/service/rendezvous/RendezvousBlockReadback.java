package com.chengyinhub.business.service.rendezvous;

/** Contact state read inside the block transaction. No place, clue or partner secret. */
public final class RendezvousBlockReadback {
    private final long actorId;
    private final long otherId;
    private final boolean blocked;
    private final boolean actorCurrent;
    private final boolean otherCurrent;
    private final String matchState;
    private final String actorTicketState;
    private final String otherTicketState;

    public RendezvousBlockReadback(long actorId, long otherId, boolean blocked, boolean actorCurrent, boolean otherCurrent,
            String matchState, String actorTicketState, String otherTicketState) {
        this.actorId = actorId;
        this.otherId = otherId;
        this.blocked = blocked;
        this.actorCurrent = actorCurrent;
        this.otherCurrent = otherCurrent;
        this.matchState = matchState;
        this.actorTicketState = actorTicketState;
        this.otherTicketState = otherTicketState;
    }

    public long getActorId() { return actorId; }
    public long getOtherId() { return otherId; }
    public boolean isBlocked() { return blocked; }
    public boolean isActorCurrent() { return actorCurrent; }
    public boolean isOtherCurrent() { return otherCurrent; }
    public String getMatchState() { return matchState; }
    public String getActorTicketState() { return actorTicketState; }
    public String getOtherTicketState() { return otherTicketState; }
}

package com.chengyinhub.business.service.rendezvous;

import java.util.List;

/** Pool read model for the scheduler. Not a Spring bean and not an HTTP route. */
public final class MyBatisRendezvousWaiting implements RendezvousPoolScheduler.Waiting, RendezvousPoolScheduler.Contacts {
    private final RendezvousProtocolMapper protocol;
    private final RendezvousContactLedger ledger;
    public MyBatisRendezvousWaiting(RendezvousProtocolMapper protocol, RendezvousContactLedger ledger) {
        this.protocol = protocol; this.ledger = ledger;
    }
    public List<String> waitingIds(String pool, long now, int limit) { return protocol.waitingIds(pool, now, limit); }
    public long actorOf(String ticketId) {
        Long actor = protocol.actorOf(ticketId);
        if (actor == null || actor <= 0) throw new RendezvousConsentService.Rejection("PAIR_UNAVAILABLE");
        return actor;
    }
    public void charge(String matchId, long first, long second, String pool, long now) {
        ledger.charge(matchId, first, second, pool, now);
    }
}

package com.chengyinhub.business.service.rendezvous;

import java.util.List;

/** Pool read model for the scheduler. Not a Spring bean and not an HTTP route. */
public final class MyBatisRendezvousWaiting implements RendezvousPoolScheduler.Waiting, RendezvousPoolScheduler.Contacts {
    private final RendezvousProtocolMapper protocol;
    private final MyBatisRendezvousCatalog catalog;
    public MyBatisRendezvousWaiting(RendezvousProtocolMapper protocol, MyBatisRendezvousCatalog catalog) {
        this.protocol = protocol; this.catalog = catalog;
    }
    public List<String> waitingIds(String pool, long now, int limit) { return protocol.waitingIds(pool, now, limit); }
    public long actorOf(String ticketId) {
        Long actor = protocol.actorOf(ticketId);
        if (actor == null || actor <= 0) throw new RendezvousConsentService.Rejection("PAIR_UNAVAILABLE");
        return actor;
    }
    public void record(long first, long second) { catalog.record(first); catalog.record(second); }
}

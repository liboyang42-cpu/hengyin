package com.chengyinhub.business.service.rendezvous;

/** Reads versioned policy rows. Callers must already be inside the consent guard transaction
 * when the answer is used to mutate a ticket. Not a Spring bean. */
public final class MyBatisRendezvousCatalog implements RendezvousVersionedPolicy.Catalog {
    private final RendezvousProtocolMapper mapper;
    public MyBatisRendezvousCatalog(RendezvousProtocolMapper mapper) { this.mapper = mapper; }
    public RendezvousVersionedPolicy.Row find(String dimension, String versionId) {
        return mapper.policy(dimension, versionId);
    }
    public int contacts(long actor) {
        Integer count = mapper.contacts(actor);
        return count == null ? 0 : count;
    }
    public void record(long actor) {
        if (mapper.bumpContact(actor) == 1) return;
        try { mapper.insertContact(actor); }
        catch (RuntimeException failed) {
            if (mapper.bumpContact(actor) != 1) throw failed;
        }
    }
}

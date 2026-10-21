package com.chengyinhub.business.service.rendezvous;

import org.apache.ibatis.annotations.Param;

/** Admission ledger. Not an authorization API and not a scanned mapper bean. */
public interface RendezvousContactMapper {
    Quota quota(@Param("pool") String pool);
    Integer eventExists(@Param("matchId") String matchId, @Param("actor") long actor);
    int countInWindow(@Param("actor") long actor, @Param("pool") String pool, @Param("scope") String scope,
                      @Param("since") long since, @Param("now") long now);
    int insertEvent(@Param("matchId") String matchId, @Param("actor") long actor, @Param("pool") String pool,
                    @Param("scope") String scope, @Param("chargedAt") long chargedAt);

    final class Quota {
        public String decision, scope;
        public Integer maxContacts;
        public Long windowMillis;
    }
}

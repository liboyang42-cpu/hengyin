package com.chengyinhub.business.service.rendezvous;

import org.apache.ibatis.annotations.Param;
import static com.chengyinhub.business.service.rendezvous.RendezvousRoleContentSource.Binding;
import static com.chengyinhub.business.service.rendezvous.RendezvousRoleContentSource.Grant;

/** Reads published role rows and inserts the caller's grant. Not a scanned mapper and not a publisher. */
public interface RendezvousRoleContentMapper {
    BindingRow binding(@Param("matchId") String matchId);
    String publicationState(@Param("goalId") String goalId, @Param("publishVersion") String publishVersion);
    String roleBody(@Param("goalId") String goalId, @Param("publishVersion") String publishVersion, @Param("roleKey") String roleKey);
    GrantRow grantByTicket(@Param("ticketId") String ticketId);
    int insertGrant(@Param("ticketId") String ticketId, @Param("actor") long actor, @Param("matchId") String matchId,
                    @Param("goalId") String goalId, @Param("publishVersion") String publishVersion,
                    @Param("roleKey") String roleKey, @Param("secretRef") String secretRef);

    final class BindingRow {
        public String matchId, goalId, publishVersion, firstRoleKey, secondRoleKey, state;
        Binding value() { return new Binding(matchId, goalId, publishVersion, firstRoleKey, secondRoleKey, state); }
    }

    final class GrantRow {
        public String ticketId, matchId, goalId, publishVersion, roleKey, secretRef;
        public long actor;
        Grant value() { return new Grant(ticketId, actor, matchId, goalId, publishVersion, roleKey, secretRef); }
    }
}

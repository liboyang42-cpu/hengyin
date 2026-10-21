package com.chengyinhub.business.service.rendezvous;

import org.apache.ibatis.annotations.Param;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;

/** Only used inside the store's guard-row transaction. Not an authorization API. */
public interface RendezvousConsentMapper {
    Integer lockGuard();
    TicketRow activeTicket(@Param("actor") long actor);
    TicketRow ticket(@Param("id") String id);
    MatchRow match(@Param("id") String id);
    int insertTicket(Ticket ticket);
    int updateTicket(Ticket ticket);
    int insertMatch(Match match);
    int updateMatch(Match match);
    String reservation(@Param("ticketId") String ticketId);
    int insertReservation(@Param("ticketId") String ticketId, @Param("matchId") String matchId);
    int deleteReservation(@Param("ticketId") String ticketId, @Param("matchId") String matchId);

    final class TicketRow {
        public String id, pool, eligibilityVersion, matchId, state;
        public long actor, expiresAt;
        Ticket value() { return new Ticket(id, actor, pool, eligibilityVersion, expiresAt, TicketState.valueOf(state), matchId); }
    }
    final class MatchRow {
        public String id, firstTicket, secondTicket, publicPlace, state;
        public long expiresAt, version;
        public Long firstAcceptedAt, secondAcceptedAt;
        Match value() { return new Match(id, firstTicket, secondTicket, publicPlace, expiresAt, version, firstAcceptedAt, secondAcceptedAt, MatchState.valueOf(state)); }
    }
}

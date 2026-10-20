package com.chengyinhub.business.city.read;

/**
 * Server-only published CITY read-model port. There is deliberately no production implementation.
 * An implementation must read one consistent, privacy-reviewed published snapshot and revalidate
 * the authenticated member/session plus current participation within that snapshot. It must use
 * actual publication, membership and property authorities, never draft previews, internal runtime
 * caller facts, topic/registration rows or existence of city_board as proof of publication.
 *
 * The snapshot's revision must change for any public projection or caller membership/ownership
 * change; it is not the raw runtime sequence (which can disclose opponents' activity).
 * Missing dependencies must throw Unavailable, never return an empty/NO_CURRENT_BOARD snapshot.
 * Reads must not create participation, write receipts, refresh tokens or mutate any game state.
 */
public interface CityPlayerReadRepository {
    CityPlayerReadModel.Snapshot read(long memberId, long sessionEpoch, String regionId);

    final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public Unavailable() { super("CITY_READ_UNAVAILABLE"); }
    }
}

package com.chengyinhub.business.marketplace.reservation;

import com.chengyinhub.business.marketplace.contract.BuyerPrincipal;
import com.chengyinhub.business.marketplace.contract.ModuleInstallAuthority;
import com.chengyinhub.business.marketplace.contract.ModuleInstallRequest;

/**
 * Server repository port, deliberately without bean registration. FreeClaimInstallationAuthority
 * implements only persisted free/individual/no-binding authority; other source paths remain absent.
 * All reads must use the same database/transaction as the reservation store. The adapter must
 * lock authoritative grant/evidence, immutable release approval, target owner/revision and binding
 * eligibility before validation; revocation/edit writers must take those same locks. Never accept
 * client authority flags or instantiate snapshots by deserializing HTTP input. Immutable release
 * bytes/metadata and frozen typed terms must come from their reviewed server repositories.
 */
public interface ReservationAuthority extends ModuleInstallAuthority {
    /**
     * Called by the service constructor before any transaction or write. Persisted adapters must
     * reject stores/managers outside their exact session/transaction boundary. Fixture-only ports
     * may retain this default; accepting arbitrary injected stores is not a production authority.
     */
    default void validateReservationPersistence(
            com.chengyinhub.business.marketplace.reservation.store.ModuleReservationStore store,
            org.springframework.transaction.PlatformTransactionManager manager) { }

    /**
     * Called inside a transaction after request and license mutexes, before reading source content.
     * Lock current authority rows in a consistent order and hold locks through commit. Check actor
     * scope before locking any content rows. Unsupported sources/buyer kinds must fail closed.
     * Usage reads include committed installs/run reservations, not this store's temporary holds.
     */
    void lockForReservation(long authenticatedMemberId, ModuleInstallRequest request);

    /** Lock current buyer/operator membership for cancellation; no active grant is required. */
    void lockBuyerForRelease(long authenticatedMemberId, BuyerPrincipal buyer);
}

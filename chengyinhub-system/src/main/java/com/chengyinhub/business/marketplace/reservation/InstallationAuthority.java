package com.chengyinhub.business.marketplace.reservation;

import com.chengyinhub.business.marketplace.contract.LicenseTermsSnapshot;
import com.chengyinhub.business.marketplace.contract.ModuleVersionSnapshot;

/**
 * Mandatory server repository seam, deliberately without registration. A bounded persisted free
 * implementation exists in FreeClaimInstallationAuthority; other sources remain unsupported. The inherited
 * lockForReservation must lock current buyer, grant, release-policy, target and bindings through commit;
 * their mutation/revocation writers must participate. Both byte loaders run AFTER those locks and
 * validation, in the same transaction/datasource. Return exact immutable reviewed bytes, never client
 * payloads or mutable latest templates. Hash verification is integrity, not approval or paid evidence.
 */
public interface InstallationAuthority extends ReservationAuthority {
    /** Validate both stores before any write; concrete persisted adapters must override. */
    default void validateInstallationPersistence(
            com.chengyinhub.business.marketplace.reservation.store.ModuleReservationStore reservations,
            com.chengyinhub.business.marketplace.reservation.store.ModuleInstallationStore installs,
            org.springframework.transaction.PlatformTransactionManager manager) {
        validateReservationPersistence(reservations, manager);
    }
    byte[] loadReviewedContent(ModuleVersionSnapshot exactVersion);
    byte[] loadFrozenTerms(LicenseTermsSnapshot exactTerms);
}

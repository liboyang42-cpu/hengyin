package com.chengyinhub.business.appleiap;

import java.util.Optional;
import java.util.UUID;

/** Internal contract: implementations lock the real workshop order in the caller's transaction. */
public interface WorkshopAppleOrderPort {
    Expectation requireOrder(Long buyerId, String orderReference);

    /** Match a server-issued account token and SKU, or an already bound original transaction. */
    Optional<Expectation> findOrder(VerifiedTransaction transaction);

    /** Must atomically bind transaction/original IDs; never restore a suspended/revoked license. */
    void activate(Expectation order, VerifiedTransaction transaction);

    /** Must also bind an unpaid order so a refund arriving first prevents subsequent activation. */
    void revoke(Expectation order, VerifiedTransaction transaction);

    /** Verified revocation only: lock and revoke an existing exact app/environment/original binding; never bind an unknown order. */
    void revokeBoundOriginal(String bundleId, String environment, String originalTransactionId, Long revocationDate);

    /** Return only this buyer's existing original-transaction license; never create another purchase. */
    Object restore(Expectation order, VerifiedTransaction transaction);

    record Expectation(String orderReference, Long buyerId, String bundleId, String environment,
                       String productId, UUID appAccountToken, String originalTransactionId,
                       Long priceMilliunits, String currency) { }

    record VerifiedTransaction(String transactionId, String originalTransactionId, String bundleId,
                               String environment, String productId, UUID appAccountToken,
                               String type, Long priceMilliunits, String currency,
                               Long purchaseDate, Long signedDate, Long revocationDate) { }
}

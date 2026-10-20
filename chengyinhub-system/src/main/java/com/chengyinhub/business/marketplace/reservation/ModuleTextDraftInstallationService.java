package com.chengyinhub.business.marketplace.reservation;

import com.chengyinhub.business.marketplace.contract.*;
import com.chengyinhub.business.marketplace.reservation.store.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import static com.chengyinhub.business.marketplace.reservation.ModuleReservationException.Reason.*;

/**
 * Unregistered atomic MEMBER_TEMPLATE text-subset installer. Consumes an existing hold, creates an
 * owned private W18 DRAFT/component and one durable usage receipt in ONE new transaction. No legacy
 * template/editor/publisher/payment calls. Stores and authority must share this transaction manager's
 * datasource; source revocation writers must honor its row-lock protocol before activation.
 */
public final class ModuleTextDraftInstallationService {
    private final ModuleReservationStore reservations;
    private final ModuleInstallationStore installs;
    private final InstallationAuthority authority;
    private final TransactionTemplate transactions;
    private final Clock clock;
    public ModuleTextDraftInstallationService(ModuleReservationStore reservations, ModuleInstallationStore installs,
            InstallationAuthority authority, PlatformTransactionManager transactionManager, Clock clock) {
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.installs = Objects.requireNonNull(installs, "installs");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.clock = Objects.requireNonNull(clock, "clock");
        transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.authority.validateInstallationPersistence(this.reservations, this.installs, transactionManager);
    }

    public ModuleInstallationReceipt consume(long actor, ModuleInstallRequest request, Instant requestedUntil) {
        if (actor <= 0) throw new IllegalArgumentException("authenticatedMemberId must be positive");
        Objects.requireNonNull(request, "request"); Objects.requireNonNull(requestedUntil, "requestedUntil");
        BuyerPrincipal buyer = request.getBuyer();
        String key = ReservationIdentity.key(buyer, request.getRequestId());
        String licenseKey = ReservationIdentity.license(request.getLicenseId());
        String payload = ReservationIdentity.payload(request, requestedUntil);
        return transactions.execute(status -> {
            requireBuyer(actor, buyer);
            lock(key); lock(licenseKey); // Identical canonical order as reserve/release.
            ModuleReservationRow row = reservations.findForUpdate(key);
            require(row != null, RESERVATION_NOT_FOUND);
            require(buyer.getKind().name().equals(row.getBuyerKind()) && buyer.getEntityId() == row.getBuyerId()
                    && request.getRequestId().equals(row.getRequestId()) && request.getLicenseId().equals(row.getLicenseId())
                    && licenseKey.equals(row.getLicenseKey()) && payload.equals(row.getPayloadHash()), IDEMPOTENCY_CONFLICT);
            if ("CONSUMED".equals(row.getState())) {
                // A lost-response retry returns historical IDs, even after grant expiry/revocation or
                // later draft edits. It is not another install, source/content read or renewed license.
                authority.lockBuyerForRelease(actor, buyer); requireBuyer(actor, buyer);
                return receipt(row, installs.findReceipt(key, buyer.getKind().name(), buyer.getEntityId()));
            }
            require("RESERVED".equals(row.getState()), RESERVATION_TERMINAL);
            require(clock.instant().isBefore(row.getExpiresAt().toInstant()), RESERVATION_EXPIRED);
            authority.lockForReservation(actor, request);
            DraftInstallPlan plan = new ModuleDraftInstallValidator(ModuleInstallReservationService.withReservedUsage(
                    authority, reservations, clock, licenseKey), clock).preview(actor, request);
            require(ReservationIdentity.authority(plan).equals(row.getAuthorityHash()), AUTHORITY_CHANGED);
            byte[] source = copy(authority.loadReviewedContent(plan.getSource()));
            byte[] terms = copy(authority.loadFrozenTerms(plan.getGrant().getOffer().getTerms()));
            ModuleOwnedDraftRow draft = new ModuleOwnedDraftRow();
            draft.setReservationKey(key); draft.setBuyerKind(buyer.getKind().name()); draft.setBuyerId(buyer.getEntityId());
            draft.setTargetDraftId(request.getDraftId()); draft.setTargetRevision(request.getExpectedDraftRevision());
            draft.setContentJson(TextModuleDraftCodec.cloneContent(source, plan));
            draft.setRightsJson(TextModuleDraftCodec.frozenRights(plan, terms));
            draft.setSourceContent(source); draft.setTermsDocument(terms); draft.setInstalledBy(actor);
            Instant now = clock.instant();
            requireLive(plan, row, now);
            draft.setInstalledAt(Timestamp.from(now.truncatedTo(ChronoUnit.MICROS)));
            require(installs.insertOwnedDraft(draft) == 1 && draft.getId() != null && draft.getId() > 0, STORAGE_INVARIANT);
            require(installs.insertTextComponent(draft) == 1, STORAGE_INVARIANT);
            require(installs.insertUsage(key, draft.getId(), draft.getInstalledAt()) == 1, STORAGE_INVARIANT);
            require(installs.consume(key, buyer.getKind().name(), buyer.getEntityId(), draft.getInstalledAt()) == 1, STORAGE_INVARIANT);
            // A late storage failure/deadline rolls back all draft/component/usage/state changes.
            ModuleInstallationReceipt result = receipt(row, installs.findReceipt(key, buyer.getKind().name(), buyer.getEntityId()));
            requireLive(plan, row, clock.instant());
            return result;
        });
    }
    private static byte[] copy(byte[] bytes) { return bytes == null ? null : bytes.clone(); }
    private static void requireLive(DraftInstallPlan plan, ModuleReservationRow row, Instant now) {
        require(now.isBefore(row.getExpiresAt().toInstant()) && plan.getGrant().isActiveAt(now), RESERVATION_EXPIRED);
        for (ModuleInstallAuthority.BindingApproval b : plan.getBindings().values())
            require(now.isBefore(b.getValidUntil()), RESERVATION_EXPIRED);
    }
    private void requireBuyer(long actor, BuyerPrincipal buyer) {
        if (!buyer.equals(authority.resolveBuyer(actor, buyer)))
            throw new ModuleInstallDeniedException(ModuleInstallDeniedException.Reason.BUYER_SCOPE_DENIED);
    }
    private void lock(String key) {
        reservations.ensureMutex(key); require(key.equals(reservations.lockMutex(key)), STORAGE_INVARIANT);
    }
    private static ModuleInstallationReceipt receipt(ModuleReservationRow row, ModuleOwnedDraftRow draft) {
        require(draft != null && row.getReservationKey().equals(draft.getReservationKey())
                && row.getBuyerKind().equals(draft.getBuyerKind()) && row.getBuyerId() == draft.getBuyerId()
                && row.getDraftId() == draft.getTargetDraftId() && row.getDraftRevision() == draft.getTargetRevision(), STORAGE_INVARIANT);
        return new ModuleInstallationReceipt(row.getReservationKey(), draft.getId(), draft.getInstalledAt().toInstant(),
                row.getVersionId(), row.getContentHash(), row.getTermsHash());
    }
    private static void require(boolean condition, ModuleReservationException.Reason reason) {
        if (!condition) throw new ModuleReservationException(reason);
    }
}

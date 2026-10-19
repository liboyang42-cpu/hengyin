package com.chengyinhub.business.marketplace.reservation;

import com.chengyinhub.business.marketplace.contract.*;
import com.chengyinhub.business.marketplace.reservation.store.ModuleReservationRow;
import com.chengyinhub.business.marketplace.reservation.store.ModuleReservationStore;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.chengyinhub.business.marketplace.reservation.ModuleReservationException.Reason.*;

/**
 * Internal, deliberately unregistered service. Creates bounded durable quota holds, never installs.
 * No default business limits or lease duration: the trusted caller supplies an explicit deadline.
 * Each call owns a new READ_COMMITTED transaction; a surrounding caller rollback cannot erase a
 * returned hold. Explicit mutex/source row locks, not an inherited repeatable-read snapshot, protect
 * invariants. In particular, release's nonlocking locator must see a first reservation that committed
 * while it waited for the request mutex after an earlier buyer-authority read.
 * The store, transaction manager and authority MUST share a database and transaction. The authority
 * port must lock real entitlement/release/draft/resource rows; a successful preview is not a lock.
 */
public final class ModuleInstallReservationService {
    public enum Release { CANCELLED, FAILED }
    private final ModuleReservationStore store;
    private final ReservationAuthority authority;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ModuleInstallReservationService(ModuleReservationStore store, ReservationAuthority authority,
                                           PlatformTransactionManager transactionManager, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.authority.validateReservationPersistence(this.store, transactionManager);
    }

    public ModuleReservationReceipt reserve(long authenticatedMemberId, ModuleInstallRequest request,
                                             Instant requestedUntil) {
        Objects.requireNonNull(request, "request"); Objects.requireNonNull(requestedUntil, "requestedUntil");
        requireActor(authenticatedMemberId);
        String key = ReservationIdentity.key(request.getBuyer(), request.getRequestId());
        String licenseKey = ReservationIdentity.license(request.getLicenseId());
        String payloadHash = ReservationIdentity.payload(request, requestedUntil);
        return transactions.execute(status -> {
            requireBuyer(authenticatedMemberId, request.getBuyer());
            lock(key); // Global order: request mutex -> license mutex -> request row -> authoritative rows.
            lock(licenseKey);
            ModuleReservationRow existing = store.findForUpdate(key);
            if (existing != null) {
                requireIdentity(existing, request.getBuyer(), request.getRequestId());
                require(payloadHash.equals(existing.getPayloadHash()) && request.getLicenseId().equals(existing.getLicenseId()),
                        IDEMPOTENCY_CONFLICT);
                require("RESERVED".equals(existing.getState()), RESERVATION_TERMINAL);
                require(clock.instant().isBefore(existing.getExpiresAt().toInstant()), RESERVATION_EXPIRED);
            }
            // No content load/preview before the server repository has acquired authoritative locks.
            authority.lockForReservation(authenticatedMemberId, request);
            DraftInstallPlan plan = new ModuleDraftInstallValidator(withReservedUsage(authority, store, clock, licenseKey), clock)
                    .preview(authenticatedMemberId, request);
            Instant expiresAt = minimum(requestedUntil, plan.getGrant().getValidUntil());
            for (ModuleInstallAuthority.BindingApproval binding : plan.getBindings().values()) {
                expiresAt = minimum(expiresAt, binding.getValidUntil());
            }
            expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS); // Never round a hold beyond authority expiry.
            Instant now = clock.instant();
            require(now.isBefore(expiresAt) && plan.getGrant().isActiveAt(now), RESERVATION_EXPIRED);
            String authorityHash = ReservationIdentity.authority(plan);
            if (existing != null) {
                require(authorityHash.equals(existing.getAuthorityHash()), AUTHORITY_CHANGED);
                // A replay never renews a lease or changes original actor/provenance.
                return receipt(existing);
            }
            store.expireLicense(licenseKey, Timestamp.from(now));
            ModuleReservationRow row = row(key, licenseKey, payloadHash, authorityHash, plan, now, expiresAt);
            require(store.insertReservation(row) == 1, STORAGE_INVARIANT);
            require(store.insertScope(key, "THEME", request.getDraftId()) == 1, STORAGE_INVARIANT);
            Set<Long> merchants = new TreeSet<Long>();
            if (request.getBuyer().getKind() == BuyerPrincipal.Kind.MERCHANT) merchants.add(request.getBuyer().getEntityId());
            for (ModuleInstallAuthority.BindingApproval binding : plan.getBindings().values()) {
                if (binding.getMerchantId() != null) merchants.add(binding.getMerchantId());
                require(store.insertBinding(key, ReservationIdentity.placeholder(binding.getPlaceholder()), binding.getPlaceholder(),
                        binding.getResource().getKind().name(), binding.getResource().getResourceId(), binding.getMerchantId(),
                        binding.getAuthorityReference(), binding.getRegion(), Timestamp.from(binding.getValidUntil())) == 1, STORAGE_INVARIANT);
            }
            for (Long merchant : merchants) require(store.insertScope(key, "MERCHANT", merchant) == 1, STORAGE_INVARIANT);
            // If slow database work crossed any authority/lease deadline, roll back EVERY insert and hold.
            require(clock.instant().isBefore(expiresAt), RESERVATION_EXPIRED);
            return receipt(row);
        });
    }

    /**
     * Release only this buyer/request hold after a cancellation or known failure; idempotent terminal
     * retries preserve the first outcome. Does not refund, revoke a license or mutate installed/running
     * content. A revoked/expired grant does not prevent its still-authorized buyer releasing a hold.
     */
    public ModuleReservationReceipt release(long authenticatedMemberId, BuyerPrincipal buyer, String requestId,
                                             Release reason) {
        requireActor(authenticatedMemberId); Objects.requireNonNull(buyer, "buyer"); Objects.requireNonNull(reason, "reason");
        String key = ReservationIdentity.key(buyer, requestId);
        return transactions.execute(status -> {
            requireBuyer(authenticatedMemberId, buyer);
            lock(key);
            // READ_COMMITTED sees a first reservation committed while we waited for this request
            // mutex, even after an earlier authority read. This locator MUST remain nonlocking:
            // take the immutable license's mutex BEFORE locking the row to avoid expiry deadlocks.
            ModuleReservationRow located = store.find(key);
            require(located != null, RESERVATION_NOT_FOUND);
            requireIdentity(located, buyer, requestId);
            lock(located.getLicenseKey());
            authority.lockBuyerForRelease(authenticatedMemberId, buyer);
            requireBuyer(authenticatedMemberId, buyer);
            ModuleReservationRow row = store.findForUpdate(key);
            require(row != null, STORAGE_INVARIANT);
            if ("RESERVED".equals(row.getState())) {
                Instant now = clock.instant();
                String state = now.isBefore(row.getExpiresAt().toInstant()) ? reason.name() : "EXPIRED";
                require(store.release(key, buyer.getKind().name(), buyer.getEntityId(), state, Timestamp.from(now)) == 1,
                        STORAGE_INVARIANT);
                row.setState(state); row.setTerminalAt(Timestamp.from(now));
            }
            return receipt(row);
        });
    }

    static ModuleInstallAuthority withReservedUsage(final ReservationAuthority authority,
            final ModuleReservationStore store, final Clock clock, final String licenseKey) {
        return new ModuleInstallAuthority() {
            public BuyerPrincipal resolveBuyer(long actor, BuyerPrincipal buyer) { return authority.resolveBuyer(actor, buyer); }
            public ModuleLicenseGrant findVerifiedGrant(String id) { return authority.findVerifiedGrant(id); }
            public ModuleVersionSnapshot findInstallableVersion(String id) { return authority.findInstallableVersion(id); }
            public OwnedDraft findDraft(long id) { return authority.findDraft(id); }
            public BindingApproval approveBinding(BuyerPrincipal buyer, long draftId, String placeholder,
                                                   ModuleInstallRequest.ResourceRef resource, Instant now) {
                return authority.approveBinding(buyer, draftId, placeholder, resource, now);
            }
            public UsageSnapshot findUsage(String id) {
                UsageSnapshot current = authority.findUsage(id);
                if (current == null) return null; // Existing validator rejects unavailable usage.
                Set<Long> themes = new LinkedHashSet<Long>(current.getThemeIds());
                Set<Long> merchants = new LinkedHashSet<Long>(current.getMerchantIds());
                Timestamp now = Timestamp.from(clock.instant());
                themes.addAll(store.activeScopes(licenseKey, "THEME", now));
                merchants.addAll(store.activeScopes(licenseKey, "MERCHANT", now));
                return new UsageSnapshot(current.getLicenseId(), current.getRevision(), themes, merchants,
                        current.getCompletedOrReservedRuns()); // Installs never reserve or admit a run.
            }
        };
    }

    private void requireBuyer(long actor, BuyerPrincipal buyer) {
        if (!buyer.equals(authority.resolveBuyer(actor, buyer))) {
            throw new ModuleInstallDeniedException(ModuleInstallDeniedException.Reason.BUYER_SCOPE_DENIED);
        }
    }
    private void lock(String key) {
        store.ensureMutex(key);
        require(key.equals(store.lockMutex(key)), STORAGE_INVARIANT);
    }
    private static void requireIdentity(ModuleReservationRow row, BuyerPrincipal buyer, String requestId) {
        require(buyer.getKind().name().equals(row.getBuyerKind()) && buyer.getEntityId() == row.getBuyerId()
                && requestId.equals(row.getRequestId()), IDEMPOTENCY_CONFLICT);
    }
    private static void requireActor(long actor) {
        if (actor <= 0) throw new IllegalArgumentException("authenticatedMemberId must be positive");
    }
    private static Instant minimum(Instant first, Instant second) {
        return second != null && second.isBefore(first) ? second : first;
    }
    private static void require(boolean condition, ModuleReservationException.Reason reason) {
        if (!condition) throw new ModuleReservationException(reason);
    }
    private static ModuleReservationReceipt receipt(ModuleReservationRow row) {
        return new ModuleReservationReceipt(row.getReservationKey(), row.getState(), row.getExpiresAt().toInstant());
    }
    private static ModuleReservationRow row(String key, String licenseKey, String payloadHash, String authorityHash,
                                              DraftInstallPlan p, Instant now, Instant expiresAt) {
        ModuleReservationRow r = new ModuleReservationRow(); ModuleInstallRequest q = p.getRequest();
        ModuleLicenseGrant g = p.getGrant();
        r.setReservationKey(key); r.setBuyerKind(q.getBuyer().getKind().name()); r.setBuyerId(q.getBuyer().getEntityId());
        r.setRequestId(q.getRequestId()); r.setLicenseKey(licenseKey); r.setLicenseId(g.getLicenseId());
        r.setPayloadHash(payloadHash); r.setAuthorityHash(authorityHash); r.setModuleId(p.getSource().getModuleId());
        r.setVersionId(p.getSource().getVersionId()); r.setContentHash(p.getSource().getContentHash());
        r.setOfferVersion(g.getOffer().getOfferVersion()); r.setTermsHash(g.getOffer().getTerms().getTermsHash());
        r.setSellerKind(g.getOffer().getSeller().getKind().name()); r.setSellerId(g.getOffer().getSeller().getEntityId());
        r.setSourceEventId(g.getSourceEventId()); r.setGrantEvidenceReference(g.getAuthorityReference());
        r.setApprovalReference(p.getSource().getApprovalReference()); r.setDraftId(q.getDraftId());
        r.setDraftRevision(p.getTarget().getRevision()); r.setActorId(p.getAuthenticatedMemberId());
        r.setState("RESERVED"); r.setReservedAt(Timestamp.from(now)); r.setExpiresAt(Timestamp.from(expiresAt));
        return r;
    }
}

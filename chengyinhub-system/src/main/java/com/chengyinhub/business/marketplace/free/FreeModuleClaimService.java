package com.chengyinhub.business.marketplace.free;

import com.chengyinhub.business.marketplace.contract.*;
import com.chengyinhub.business.marketplace.free.store.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Inactive real free-claim persistence slice, no Spring registration or controller.
 * Authenticated member comes only from server authentication, never request buyer fields.
 * Publication ingestion is deliberately unavailable until review/issuer authority is implemented.
 * No physical orders, payment, refund, editor writes, publishing or implicit rights defaults.
 */
public final class FreeModuleClaimService {
    private final FreeModuleClaimStore store;
    private final TransactionTemplate transactions;
    private final Clock clock;
    public FreeModuleClaimService(FreeModuleClaimStore store, PlatformTransactionManager manager, Clock clock) {
        this.store=Objects.requireNonNull(store); this.clock=Objects.requireNonNull(clock);
        transactions=new TransactionTemplate(Objects.requireNonNull(manager));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** Historical receipt only. Current revoked/suspended state is returned without renewal. */
    public Receipt claim(long authenticatedMember, String publicationId, String expectedDocumentHash, String requestKey) {
        positive(authenticatedMember); token(publicationId); token(requestKey); hash(expectedDocumentHash);
        return transactions.execute(status -> {
            lockBuyer(authenticatedMember);
            FreeClaimRow previous=store.findRequest(authenticatedMember,requestKey);
            if(previous!=null){
                require(publicationId.equals(previous.getPublicationId()) && expectedDocumentHash.equals(previous.getDocumentHash()),"IDEMPOTENCY_CONFLICT");
                return receipt(previous);
            }
            FreePublicationRow row=store.lockPublication(publicationId);
            require(row!=null,"PUBLICATION_NOT_FOUND");
            require(expectedDocumentHash.equals(row.getDocumentHash()),"PUBLICATION_CHANGED");
            FreeClaimRow owned=store.findOwned(authenticatedMember,publicationId);
            // A second key cannot multiply quotas, nor silently reuse a key that is not persisted.
            require(owned==null,"ALREADY_CLAIMED");
            require("LISTED".equals(row.getState()),"PUBLICATION_NOT_CLAIMABLE");
            require(row.getGrantValidUntil()!=null && clock.instant().isBefore(row.getGrantValidUntil().toInstant()),"PUBLICATION_EXPIRED");
            FreePublicationSnapshot snapshot=verified(row);
            FreeClaimRow claim=new FreeClaimRow();
            claim.setClaimId(UUID.randomUUID().toString()); claim.setBuyerMemberId(authenticatedMember);
            claim.setPublicationId(publicationId); claim.setDocumentHash(row.getDocumentHash());
            claim.setRequestKey(requestKey); claim.setState("ACTIVE"); claim.setValidUntil(row.getGrantValidUntil());
            claim.setCreatedAt(Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)));
            require(store.insertClaim(claim)==1,"STORAGE_INVARIANT");
            // Check persisted grant against the complete explicit policy before committing.
            asGrant(claim,snapshot);
            require(clock.instant().isBefore(claim.getValidUntil().toInstant()),"PUBLICATION_EXPIRED");
            return receipt(claim);
        });
    }
    /** Current entitlement read. Retirement preserves old free rights; safety withdrawal denies use. */
    public ModuleLicenseGrant findCurrentGrant(long authenticatedMember,String claimId) {
        positive(authenticatedMember); token(claimId);
        return transactions.execute(status -> {
            lockBuyer(authenticatedMember);
            FreeClaimRow claim=store.findClaim(authenticatedMember,claimId);
            require(claim!=null,"GRANT_SCOPE_DENIED");
            FreePublicationRow row=store.lockPublication(claim.getPublicationId());
            require(row!=null && ("LISTED".equals(row.getState())||"RETIRED".equals(row.getState())),"PUBLICATION_NOT_INSTALLABLE");
            require(claim.getDocumentHash().equals(row.getDocumentHash()),"PUBLICATION_CHANGED");
            require("ACTIVE".equals(claim.getState()) && claim.getCreatedAt()!=null && !clock.instant().isBefore(claim.getCreatedAt().toInstant()) && claim.getValidUntil()!=null && clock.instant().isBefore(claim.getValidUntil().toInstant()),"GRANT_INACTIVE");
            return asGrant(claim,verified(row));
        });
    }
    static ModuleLicenseGrant asGrant(FreeClaimRow row,FreePublicationSnapshot snapshot) {
        require(row.getValidUntil()!=null && snapshot.getValidUntil().equals(row.getValidUntil().toInstant()),"GRANT_CORRUPT");
        ModuleVersionSnapshot version=snapshot.getVersion();
        return new ModuleLicenseGrant(row.getClaimId(),new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,row.getBuyerMemberId()),
                snapshot.getOffer(),version.getVersionId(),Collections.singletonMap(version.getVersionId(),version.getContentHash()),
                ModuleLicenseGrant.Status.valueOf(row.getState()),ModuleLicenseGrant.EvidenceKind.VERIFIED_FREE_CLAIM,
                "w18-free-claim:"+row.getClaimId(),"w18-free-claim-issued:"+row.getClaimId(),row.getCreatedAt().toInstant(),row.getValidUntil().toInstant());
    }
    static FreePublicationSnapshot verified(FreePublicationRow row) {
        require(row.getSnapshotJson()!=null && row.getDocumentHash().equals(FreePublicationSnapshot.sha(row.getSnapshotJson().getBytes(StandardCharsets.UTF_8))),"PUBLICATION_CORRUPT");
        FreePublicationSnapshot snapshot=FreePublicationSnapshot.decode(row.getSnapshotJson(),row.getContent(),row.getTerms());
        require(snapshot.ownershipKey().equals(row.getOwnershipKey()),"PUBLICATION_CORRUPT");
        require(row.getGrantValidUntil()!=null && snapshot.getValidUntil().equals(row.getGrantValidUntil().toInstant()),"PUBLICATION_CORRUPT");
        require(Objects.equals(row.getSellerMemberId(),snapshot.getVersion().getCreator().getEntityId())
                && Objects.equals(row.getSourceTemplateId(),snapshot.getVersion().getSourceId()),"PUBLICATION_CORRUPT");
        return snapshot;
    }
    private void lockBuyer(long actor){require(Long.valueOf(actor).equals(store.lockActiveMember(actor)),"BUYER_SCOPE_DENIED");}
    private static Receipt receipt(FreeClaimRow row){return new Receipt(row.getClaimId(),row.getState(),row.getCreatedAt().toInstant());}
    private static void positive(long value){if(value<=0)throw new IllegalArgumentException("authenticated member");}
    private static void token(String value){if(value==null||!value.matches("[A-Za-z0-9:_-]{1,128}"))throw new IllegalArgumentException("invalid identifier");}
    private static void hash(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid hash");}
    private static void require(boolean value,String reason){if(!value)throw new IllegalStateException(reason);}
    public static final class Receipt {
        private final String claimId,state; private final Instant createdAt;
        private Receipt(String claimId,String state,Instant createdAt){this.claimId=claimId;this.state=state;this.createdAt=createdAt;}
        public String getClaimId(){return claimId;} public String getState(){return state;} public Instant getCreatedAt(){return createdAt;}
        public boolean isExecutable(){return false;} public boolean isPublished(){return false;}
    }
}

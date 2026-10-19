package com.chengyinhub.business.marketplace.free;

import com.chengyinhub.business.marketplace.contract.*;
import com.chengyinhub.business.marketplace.free.store.*;
import com.chengyinhub.business.marketplace.reservation.*;
import com.chengyinhub.business.marketplace.reservation.store.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;
import static com.chengyinhub.business.marketplace.free.FreeInstallationPersistence.require;

/**
 * Inactive persisted FREE/INDIVIDUAL, no-binding text adapter. It NEVER calls findCurrentGrant:
 * all authority locks and exact byte reads participate in reserve/consume's own transaction.
 * Source kind MEMBER_TEMPLATE describes the purchased text; target is an actual TOPIC/ACTIVITY
 * content_draft, not a source template or a second editor draft identity. Output remains a private
 * W18 component linked to that target. Reviewed ingress and editor materialization are still absent.
 */
public final class FreeClaimInstallationAuthority implements InstallationAuthority {
    private final FreeInstallationPersistence persistence;
    private final Clock clock;
    private final DataSourceTransactionManager manager;
    private final ModuleReservationStore reservations;
    private final ModuleInstallationStore installs;
    private final Object contextKey=new Object();
    public FreeClaimInstallationAuthority(SqlSessionTemplate session,DataSourceTransactionManager manager,Clock clock) {
        persistence=new FreeInstallationPersistence(session,manager);this.clock=Objects.requireNonNull(clock);
        this.manager=manager;reservations=session.getMapper(ModuleReservationStore.class);installs=session.getMapper(ModuleInstallationStore.class);
    }
    /** Closed composition: stores, authority and manager share the exact verified session boundary. */
    public ModuleInstallReservationService reservationService() {
        return new ModuleInstallReservationService(reservations,this,manager,clock);
    }
    public ModuleTextDraftInstallationService installationService() {
        return new ModuleTextDraftInstallationService(reservations,installs,this,manager,clock);
    }
    public void validateReservationPersistence(
            com.chengyinhub.business.marketplace.reservation.store.ModuleReservationStore store,
            org.springframework.transaction.PlatformTransactionManager manager) {
        persistence.requireMapper(store,reservations,manager);
    }
    public void validateInstallationPersistence(
            com.chengyinhub.business.marketplace.reservation.store.ModuleReservationStore reservations,
            com.chengyinhub.business.marketplace.reservation.store.ModuleInstallationStore installs,
            org.springframework.transaction.PlatformTransactionManager manager) {
        validateReservationPersistence(reservations,manager);
        persistence.requireMapper(installs,this.installs,manager);
    }
    /** Nonlocking preflight before request/license mutexes; lockForReservation rechecks under lock. */
    public BuyerPrincipal resolveBuyer(long actor,BuyerPrincipal buyer) {
        persistence.requireTransaction();
        if(actor<=0||buyer==null||buyer.getKind()!=BuyerPrincipal.Kind.INDIVIDUAL||buyer.getEntityId()!=actor)return null;
        return Long.valueOf(actor).equals(persistence.claims.findActiveMember(actor))?buyer:null;
    }
    public void lockBuyerForRelease(long actor,BuyerPrincipal buyer) {
        persistence.requireTransaction();requireBuyer(actor,buyer);persistence.lockBuyer(actor);
    }
    public void lockForReservation(long actor,ModuleInstallRequest request) {
        persistence.requireTransaction();requireBuyer(actor,request.getBuyer());
        require(TransactionSynchronizationManager.getResource(contextKey)==null,"INSTALL_CONTEXT_ALREADY_LOCKED");
        // Preceded by request mutex -> license mutex -> reservation row in both callers.
        // Never acquire any mutex after member -> claim -> publication/policy -> draft -> scope.
        persistence.lockBuyer(actor);
        FreeClaimRow claim=persistence.claims.findClaim(actor,request.getLicenseId());
        require(claim!=null,"GRANT_SCOPE_DENIED");
        FreePublicationRow publication=persistence.claims.lockPublication(claim.getPublicationId());
        require(publication!=null&&("LISTED".equals(publication.getState())||"RETIRED".equals(publication.getState())),"PUBLICATION_NOT_INSTALLABLE");
        require(claim.getDocumentHash().equals(publication.getDocumentHash()),"PUBLICATION_CHANGED");
        require("ACTIVE".equals(claim.getState())&&claim.getCreatedAt()!=null&&claim.getValidUntil()!=null
                &&!clock.instant().isBefore(claim.getCreatedAt().toInstant())&&clock.instant().isBefore(claim.getValidUntil().toInstant()),"GRANT_INACTIVE");
        FreePublicationSnapshot snapshot=FreeModuleClaimService.verified(publication);
        ModuleLicenseGrant grant=FreeModuleClaimService.asGrant(claim,snapshot);
        require(snapshot.getVersion().getRequiredBindings().isEmpty()&&request.getBindings().isEmpty(),"BINDING_AUTHORITY_UNAVAILABLE");
        FreeInstallTargetRow draft=persistence.targets.lockDraft(request.getDraftId(),actor);
        FreeContentDraftScopeService.requireEditable(draft,actor);
        require(draft.getVersion()==request.getExpectedDraftRevision(),"DRAFT_REVISION_MISMATCH");
        FreeInstallTargetRow scope=persistence.targets.lockScope(draft.getId(),draft.getVersion());
        require(FreeContentDraftScopeService.sameRevision(draft,scope),"DRAFT_SCOPE_UNAVAILABLE");
        OwnedDraft target=new OwnedDraft(draft.getId(),request.getBuyer(),draft.getVersion(),scope.getRegion(),OwnedDraft.State.DRAFT);
        Context context=new Context(grant,snapshot,target);
        TransactionSynchronizationManager.bindResource(contextKey,context);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            public void suspend(){TransactionSynchronizationManager.unbindResource(contextKey);}
            public void resume(){TransactionSynchronizationManager.bindResource(contextKey,context);}
            public void afterCompletion(int status){TransactionSynchronizationManager.unbindResourceIfPossible(contextKey);}
        });
    }
    public ModuleLicenseGrant findVerifiedGrant(String id) {
        Context context=context();return context.grant.getLicenseId().equals(id)?context.grant:null;
    }
    public ModuleVersionSnapshot findInstallableVersion(String id) {
        Context context=context();return context.snapshot.getVersion().getVersionId().equals(id)?context.snapshot.getVersion():null;
    }
    public OwnedDraft findDraft(long id){Context context=context();return context.target.getDraftId()==id?context.target:null;}
    public BindingApproval approveBinding(BuyerPrincipal buyer,long draftId,String placeholder,ModuleInstallRequest.ResourceRef resource,Instant now){
        context();return null; // No real resource authority exists for this bounded slice.
    }
    public UsageSnapshot findUsage(String id) {
        Context context=context();if(!context.grant.getLicenseId().equals(id))return null;
        Set<Long> themes=new LinkedHashSet<Long>(persistence.targets.consumedThemes(id,context.grant.getBuyer().getEntityId()));
        // No run admission exists for this inactive free slice; this adapter grants no run authority.
        return new UsageSnapshot(id,1,themes,Collections.<Long>emptySet(),0);
    }
    public byte[] loadReviewedContent(ModuleVersionSnapshot exactVersion) {
        Context context=context();require(exactVersion==context.snapshot.getVersion(),"PUBLICATION_CHANGED");return context.snapshot.getContent();
    }
    public byte[] loadFrozenTerms(LicenseTermsSnapshot exactTerms) {
        Context context=context();require(exactTerms==context.grant.getOffer().getTerms(),"PUBLICATION_CHANGED");return context.snapshot.getTerms();
    }
    private Context context(){
        persistence.requireTransaction();Context context=(Context)TransactionSynchronizationManager.getResource(contextKey);
        require(context!=null,"INSTALL_LOCKS_REQUIRED");return context;
    }
    private static void requireBuyer(long actor,BuyerPrincipal buyer){
        require(actor>0&&buyer!=null&&buyer.getKind()==BuyerPrincipal.Kind.INDIVIDUAL&&buyer.getEntityId()==actor,"BUYER_SCOPE_DENIED");
    }
    private static final class Context {
        final ModuleLicenseGrant grant;final FreePublicationSnapshot snapshot;final OwnedDraft target;
        Context(ModuleLicenseGrant grant,FreePublicationSnapshot snapshot,OwnedDraft target){this.grant=grant;this.snapshot=snapshot;this.target=target;}
    }
}

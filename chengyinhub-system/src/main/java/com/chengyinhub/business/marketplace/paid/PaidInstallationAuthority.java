package com.chengyinhub.business.marketplace.paid;

import com.chengyinhub.business.marketplace.contract.*;
import com.chengyinhub.business.marketplace.paid.store.PaidLicenseStore;
import com.chengyinhub.business.marketplace.free.store.*;
import com.chengyinhub.business.marketplace.reservation.*;
import com.chengyinhub.business.marketplace.reservation.store.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;

/** NEW, default-off paid authority for the existing no-resource MEMBER_TEMPLATE text installer.
 * No FREE claim/offer is read. The legacy-named target mapper addresses generic owner content_draft
 * and scope/usage tables only. Grant, frozen source, target and quota share the SAME fixed transaction.
 * Produces a private W18 component; never a professional template ID, release or run permission. */
public final class PaidInstallationAuthority implements InstallationAuthority {
    public enum Policy { DISABLED, OWNER_PAID_TEXT_INSTALL_V1 }
    private final Policy policy;private final PaidPersistenceBoundary persistence;private final PersistedPaidLicenseService licenses;
    private final PaidLicenseStore paid;private final FreeInstallTargetStore targets;
    private final ModuleReservationStore reservations;private final ModuleInstallationStore installs;private final Clock clock;
    private final Object contextKey=new Object();
    private final PaidInstallCommand expectation;
    public PaidInstallationAuthority(SqlSessionTemplate session,DataSourceTransactionManager manager,Clock clock){this(session,manager,clock,Policy.DISABLED);}
    public PaidInstallationAuthority(SqlSessionTemplate session,DataSourceTransactionManager manager,Clock clock,Policy policy){
        this(new PaidPersistenceBoundary(session,manager),clock,policy,null);
    }
    PaidInstallationAuthority(PaidPersistenceBoundary persistence,Clock clock,Policy policy,PaidInstallCommand expectation){
        this.policy=Objects.requireNonNull(policy);this.clock=Objects.requireNonNull(clock);this.persistence=Objects.requireNonNull(persistence);this.expectation=expectation;
        licenses=new PersistedPaidLicenseService(persistence,clock,PersistedPaidLicenseService.Policy.VERIFIED_WECHAT_PURCHASED_VERSION_V1);
        paid=persistence.mapper(PaidLicenseStore.class);targets=persistence.mapper(FreeInstallTargetStore.class);
        reservations=persistence.mapper(ModuleReservationStore.class);installs=persistence.mapper(ModuleInstallationStore.class);
    }
    private void enabled(){require(policy==Policy.OWNER_PAID_TEXT_INSTALL_V1,"PAID_INSTALL_NOT_ENABLED");persistence.verifyConfiguration();}
    public ModuleInstallReservationService reservationService(){enabled();return persistence.reservationService(reservations,this,clock);}
    public ModuleTextDraftInstallationService installationService(){enabled();return persistence.installationService(reservations,installs,this,clock);}
    public void validateReservationPersistence(ModuleReservationStore store,PlatformTransactionManager manager){enabled();persistence.requireStore(store,reservations,manager);}
    public void validateInstallationPersistence(ModuleReservationStore store,ModuleInstallationStore writer,PlatformTransactionManager manager){validateReservationPersistence(store,manager);persistence.requireStore(writer,installs,manager);}
    private void transaction(){enabled();persistence.fenceCurrentTransaction();}
    /** Explicit owner-selected planning region, not location proof, store consent or a mode inference. */
    public void prepareTarget(long actor,long target,long revision,String region){
        enabled();require(actor>0&&target>0&&revision>0&&region!=null&&!region.trim().isEmpty()&&region.equals(region.trim())&&region.length()<=128,"INVALID_TARGET");
        persistence.inTransaction(()->{
            require(paid.activeBuyer(actor)!=null,"BUYER_SCOPE_DENIED");FreeInstallTargetRow draft=targets.lockDraft(target,actor);editable(draft,actor);
            require(draft.getVersion()==revision,"DRAFT_REVISION_MISMATCH");FreeInstallTargetRow existing=targets.lockScope(target,revision);
            if(existing!=null)require(same(draft,existing)&&region.equals(existing.getRegion()),"DRAFT_SCOPE_CONFLICT");
            else{draft.setRegion(region);require(targets.insertScope(draft)==1,"STORAGE_INVARIANT");}return null;
        });
    }
    public BuyerPrincipal resolveBuyer(long actor,BuyerPrincipal buyer){
        transaction();if(actor<=0||buyer==null||buyer.getKind()!=BuyerPrincipal.Kind.INDIVIDUAL||buyer.getEntityId()!=actor)return null;
        // Nonlocking preflight before request/license mutexes. Lock only in the documented global order.
        return Long.valueOf(actor).equals(paid.findActiveBuyer(actor))?buyer:null;
    }
    public void lockBuyerForRelease(long actor,BuyerPrincipal buyer){transaction();buyer(actor,buyer);require(paid.activeBuyer(actor)!=null,"BUYER_SCOPE_DENIED");}
    public void lockForReservation(long actor,ModuleInstallRequest request){
        transaction();buyer(actor,request.getBuyer());require(!TransactionSynchronizationManager.hasResource(contextKey),"INSTALL_CONTEXT_ALREADY_LOCKED");
        // Caller owns request→license mutex→reservation row. Paid source then locks OMS order→line→snapshot→receipt→buyer.
        PersistedPaidLicenseService.LockedPurchase purchase=licenses.captureOwnedInCurrentTransaction(actor,request.getLicenseId());
        require(purchase.grant.isActiveAt(clock.instant()),"GRANT_INACTIVE");
        require(purchase.snapshot.getVersion().getSourceKind()==ModuleVersionSnapshot.SourceKind.MEMBER_TEMPLATE
                && purchase.snapshot.getVersion().getSchemaVersion()==1 && purchase.snapshot.getVersion().getRequiredBindings().isEmpty()
                && request.getBindings().isEmpty(),"PAID_INSTALL_ADAPTER_UNAVAILABLE");
        // Existing codec is streaming/flat and validates strict UTF-8/UTF-16, supported fields and source digest.
        TextModuleDraftCodec.validateReviewedSource(purchase.snapshot.getContent(),purchase.snapshot.getVersion());
        FreeInstallTargetRow draft=targets.lockDraft(request.getDraftId(),actor);editable(draft,actor);
        require(draft.getVersion()==request.getExpectedDraftRevision(),"DRAFT_REVISION_MISMATCH");
        if(expectation!=null)require(expectation.targetDraftId==draft.getId()&&expectation.targetRevision==draft.getVersion()
                && expectation.businessType.equals(draft.getBusinessType())&&expectation.targetPayloadHash.equals(draft.getPayloadHash())
                && expectation.coreRequestId().equals(request.getRequestId()),"CONFIRMED_TARGET_CHANGED");
        FreeInstallTargetRow scope=targets.lockScope(draft.getId(),draft.getVersion());require(same(draft,scope),"DRAFT_SCOPE_UNAVAILABLE");
        if(expectation!=null)require(expectation.planningRegion.equals(scope.getRegion()),"CONFIRMED_REGION_CHANGED");
        final Context context=new Context(purchase,new OwnedDraft(draft.getId(),request.getBuyer(),draft.getVersion(),scope.getRegion(),OwnedDraft.State.DRAFT));
        TransactionSynchronizationManager.bindResource(contextKey,context);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            public void suspend(){TransactionSynchronizationManager.unbindResourceIfPossible(contextKey);}
            public void resume(){TransactionSynchronizationManager.bindResource(contextKey,context);}
            public void afterCompletion(int status){TransactionSynchronizationManager.unbindResourceIfPossible(contextKey);}
        });
    }
    public ModuleLicenseGrant findVerifiedGrant(String id){Context c=context();return c.purchase.grant.getLicenseId().equals(id)?c.purchase.grant:null;}
    public ModuleVersionSnapshot findInstallableVersion(String id){Context c=context();return c.purchase.snapshot.getVersion().getVersionId().equals(id)?c.purchase.snapshot.getVersion():null;}
    public OwnedDraft findDraft(long id){Context c=context();return c.target.getDraftId()==id?c.target:null;}
    public BindingApproval approveBinding(BuyerPrincipal buyer,long draft,String slot,ModuleInstallRequest.ResourceRef ref,Instant now){context();return null;}
    public UsageSnapshot findUsage(String id){
        Context c=context();if(!c.purchase.grant.getLicenseId().equals(id))return null;
        Set<Long> consumed=new LinkedHashSet<Long>(targets.consumedThemes(id,c.purchase.grant.getBuyer().getEntityId()));
        // This bounded adapter installs drafts only; it neither reserves nor admits any run.
        return new UsageSnapshot(id,1,consumed,Collections.<Long>emptySet(),0);
    }
    public byte[] loadReviewedContent(ModuleVersionSnapshot exact){Context c=context();require(exact==c.purchase.snapshot.getVersion(),"SOURCE_CHANGED");return c.purchase.snapshot.getContent();}
    public byte[] loadFrozenTerms(LicenseTermsSnapshot exact){Context c=context();require(exact==c.purchase.grant.getOffer().getTerms(),"TERMS_CHANGED");return c.purchase.snapshot.getTerms();}
    private Context context(){transaction();Context c=(Context)TransactionSynchronizationManager.getResource(contextKey);require(c!=null,"INSTALL_LOCKS_REQUIRED");return c;}
    private static void editable(FreeInstallTargetRow d,long actor){
        require(d!=null&&Long.valueOf(actor).equals(d.getOwnerMemberId()),"DRAFT_SCOPE_DENIED");
        require("DRAFT".equals(d.getStatus())&&("TOPIC".equals(d.getBusinessType())||"ACTIVITY".equals(d.getBusinessType())),"DRAFT_NOT_EDITABLE");
        require(d.getVersion()!=null&&d.getVersion()>0&&d.getPayloadHash()!=null&&d.getPayloadHash().matches("[0-9a-f]{64}"),"DRAFT_CORRUPT");
    }
    private static boolean same(FreeInstallTargetRow a,FreeInstallTargetRow b){return b!=null&&a.getId().equals(b.getId())&&a.getOwnerMemberId().equals(b.getOwnerMemberId())&&a.getVersion().equals(b.getVersion())&&a.getPayloadHash().equals(b.getPayloadHash());}
    private static void buyer(long actor,BuyerPrincipal buyer){require(actor>0&&buyer!=null&&buyer.getKind()==BuyerPrincipal.Kind.INDIVIDUAL&&buyer.getEntityId()==actor,"BUYER_SCOPE_DENIED");}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static final class Context {final PersistedPaidLicenseService.LockedPurchase purchase;final OwnedDraft target;Context(PersistedPaidLicenseService.LockedPurchase purchase,OwnedDraft target){this.purchase=purchase;this.target=target;}}
}

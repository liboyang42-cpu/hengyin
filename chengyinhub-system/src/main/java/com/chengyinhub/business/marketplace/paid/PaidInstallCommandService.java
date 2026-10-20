package com.chengyinhub.business.marketplace.paid;

import com.chengyinhub.business.marketplace.contract.*;
import com.chengyinhub.business.marketplace.free.store.*;
import com.chengyinhub.business.marketplace.paid.store.PaidLicenseStore;
import com.chengyinhub.business.marketplace.reservation.*;
import com.chengyinhub.business.marketplace.reservation.store.ModuleReservationStore;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** NEW, unregistered command boundary over the real paid license and existing private text installer.
 * The immutable owner command commits BEFORE dispatch, preserving one UUID and deadline after a lost
 * response. Core reservation/consume retain their own atomic transactions. PREPARED is not installed.
 * No remote target edit, professional template materialization, publication or player admission. */
public final class PaidInstallCommandService implements PaidInstallCommandHandling {
    public enum Policy { DISABLED, OWNER_CONFIRMED_PAID_PRIVATE_TEXT_V1 }
    private final Policy policy;private final Clock clock;private final Duration holdDuration;
    private final PaidPersistenceBoundary persistence;private final PersistedPaidLicenseService licenses;
    private final PaidLicenseStore paid;private final PaidInstallCommandStore commands;
    private final ModuleReservationStore reservations;private final FreeInstallTargetStore drafts;
    public PaidInstallCommandService(SqlSessionTemplate session,DataSourceTransactionManager manager,Clock clock){
        this(session,manager,clock,Policy.DISABLED,null);
    }
    public PaidInstallCommandService(SqlSessionTemplate session,DataSourceTransactionManager manager,Clock clock,Policy policy,Duration holdDuration){
        this.clock=Objects.requireNonNull(clock);this.policy=Objects.requireNonNull(policy);this.holdDuration=holdDuration;
        if(policy!=Policy.DISABLED)require(holdDuration!=null&&!holdDuration.isNegative()&&!holdDuration.isZero()
                && holdDuration.getNano()==0&&holdDuration.compareTo(Duration.ofMinutes(10))<=0,"INVALID_TECHNICAL_HOLD_DURATION");
        persistence=new PaidPersistenceBoundary(session,manager);commands=persistence.mapper(PaidInstallCommandStore.class);
        paid=persistence.mapper(PaidLicenseStore.class);drafts=persistence.mapper(FreeInstallTargetStore.class);reservations=persistence.mapper(ModuleReservationStore.class);
        licenses=new PersistedPaidLicenseService(persistence,clock,PersistedPaidLicenseService.Policy.VERIFIED_WECHAT_PURCHASED_VERSION_V1);
    }
    private void enabled(long actor){require(policy==Policy.OWNER_CONFIRMED_PAID_PRIVATE_TEXT_V1&&actor>0,"PAID_INSTALL_COMMAND_NOT_ENABLED");persistence.verifyConfiguration();}
    @Override public Map<String,Object> targets(long actor,String licenseId,Long before){
        enabled(actor);require(before==null||before>0,"INVALID_CURSOR");
        return persistence.inTransaction(()->{
            PersistedPaidLicenseService.LockedPurchase purchase=licenses.captureOwnedInCurrentTransaction(actor,licenseId);
            require(purchase.grant.isActiveAt(clock.instant()),"GRANT_INACTIVE");
            List<FreeInstallTargetRow> found=commands.targets(actor,before);require(found!=null&&found.size()<=51,"TARGET_INDEX_CORRUPT");
            List<Map<String,Object>> items=new ArrayList<Map<String,Object>>();Long previous=before;
            for(FreeInstallTargetRow row:found){validTarget(row,actor);require(previous==null||row.getId()<previous,"TARGET_INDEX_CORRUPT");previous=row.getId();
                if(items.size()<50)items.add(map("targetDraftId",row.getId(),"businessType",row.getBusinessType(),"targetRevision",row.getVersion(),
                        "targetPayloadHash",row.getPayloadHash(),"mode","UNRESOLVED","destinationKind","W18_PRIVATE_COMPONENT_ONLY"));}
            boolean more=found.size()>50;return map("schema","w18-paid-install-targets-v1","scope","OWNER_DRAFT_METADATA_ONLY","licenseId",licenseId,
                    "checkedAt",clock.instant().toString(),"items",Collections.unmodifiableList(items),"hasMore",more,
                    "nextBeforeDraftId",more?found.get(49).getId():null);
        });
    }
    @Override public Map<String,Object> submit(long actor,PaidInstallCommand command){
        enabled(actor);Objects.requireNonNull(command);PaidInstallCommandStore.Row row=prepare(actor,command);
        // This immutable per-call expectation is checked while the REAL target row is locked at both
        // reserve and consume. A target hash/business type change without a revision change also fails.
        PaidInstallationAuthority authority=new PaidInstallationAuthority(persistence,clock,PaidInstallationAuthority.Policy.OWNER_PAID_TEXT_INSTALL_V1,command);
        ModuleInstallRequest request=request(actor,row);Instant deadline=row.getRequestedUntil().toInstant();
        ModuleTextDraftInstallationService installer=authority.installationService();
        try{installer.consume(actor,request,deadline);return status(actor,command.requestId);}
        catch(ModuleReservationException absent){if(absent.getReason()!=ModuleReservationException.Reason.RESERVATION_NOT_FOUND)throw absent;}
        require(clock.instant().isBefore(deadline),"COMMAND_EXPIRED");
        try{authority.reservationService().reserve(actor,request,deadline);}
        catch(ModuleReservationException concurrent){if(concurrent.getReason()!=ModuleReservationException.Reason.RESERVATION_TERMINAL)throw concurrent;}
        // Another identical request may have consumed the hold between the absent read and reserve.
        // consume validates the same payload and returns only its historical receipt in that case.
        installer.consume(actor,request,deadline);return status(actor,command.requestId);
    }
    private PaidInstallCommandStore.Row prepare(long actor,PaidInstallCommand command){
        return persistence.inTransaction(()->{
            require(Long.valueOf(actor).equals(paid.findActiveBuyer(actor)),"BUYER_SCOPE_DENIED");
            String key=PaidInstallCommand.key(actor,command.requestId);reservations.ensureMutex(key);require(key.equals(reservations.lockMutex(key)),"STORAGE_INVARIANT");
            PaidInstallCommandStore.Row old=commands.lock(actor,key);
            if(old!=null){validateRow(old,actor,command.requestId);require(command.digest().equals(old.getCommandHash()),"COMMAND_IDEMPOTENCY_CONFLICT");return old;}
            // Create both core coordination rows BEFORE publishing the immutable command. A status
            // reader can then wait on exactly these existing mutexes without creating any row.
            BuyerPrincipal owner=new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,actor);
            for(String mutex:Arrays.asList(ModuleReservationKeys.request(owner,command.coreRequestId()),ModuleReservationKeys.license(command.licenseId))){
                reservations.ensureMutex(mutex);require(mutex.equals(reservations.lockMutex(mutex)),"STORAGE_INVARIANT");
            }
            PersistedPaidLicenseService.LockedPurchase purchase=licenses.captureOwnedInCurrentTransaction(actor,command.licenseId);
            ModuleLicenseGrant grant=purchase.grant;require(grant.isActiveAt(clock.instant()),"GRANT_INACTIVE");
            require(command.moduleId.equals(purchase.snapshot.getVersion().getModuleId())&&command.versionId.equals(grant.getPurchasedVersionId())
                    && command.contentHash.equals(purchase.snapshot.getVersion().getContentHash())&&command.termsHash.equals(grant.getOffer().getTerms().getTermsHash()),"CONFIRMED_PURCHASE_CHANGED");
            require(grant.getOffer().getTerms().getAllowedRegions().contains(command.planningRegion)
                    && (!command.commercialUse||grant.getOffer().getTerms().getCommercialUse()==LicenseTermsSnapshot.Permission.ALLOWED),"CONFIRMED_USE_NOT_GRANTED");
            TextModuleDraftCodec.validateReviewedSource(purchase.snapshot.getContent(),purchase.snapshot.getVersion());
            FreeInstallTargetRow target=drafts.lockDraft(command.targetDraftId,actor);validTarget(target,actor);
            require(target.getVersion()==command.targetRevision&&command.businessType.equals(target.getBusinessType())&&command.targetPayloadHash.equals(target.getPayloadHash()),"CONFIRMED_TARGET_CHANGED");
            FreeInstallTargetRow scope=drafts.lockScope(command.targetDraftId,command.targetRevision);
            if(scope==null){target.setRegion(command.planningRegion);require(drafts.insertScope(target)==1,"STORAGE_INVARIANT");}
            else require(scope.getOwnerMemberId()==actor&&command.targetPayloadHash.equals(scope.getPayloadHash())&&command.planningRegion.equals(scope.getRegion()),"CONFIRMED_TARGET_SCOPE_CHANGED");
            PaidInstallCommandStore.Row row=new PaidInstallCommandStore.Row();row.setCommandKey(key);row.setCommandHash(command.digest());row.setBuyerMemberId(actor);
            row.setRequestId(command.requestId);row.setCoreRequestId(command.coreRequestId());row.setLicenseId(command.licenseId);row.setModuleId(command.moduleId);row.setVersionId(command.versionId);
            row.setContentHash(command.contentHash);row.setTermsHash(command.termsHash);row.setTargetDraftId(command.targetDraftId);row.setTargetRevision(command.targetRevision);
            row.setBusinessType(command.businessType);row.setTargetPayloadHash(command.targetPayloadHash);row.setPlanningRegion(command.planningRegion);row.setCommercialUse(command.commercialUse);
            row.setOfferVersion(grant.getOffer().getOfferVersion());Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
            row.setCreatedAt(Timestamp.from(now));row.setRequestedUntil(Timestamp.from(now.plus(holdDuration)));
            require(commands.insert(row)==1,"STORAGE_INVARIANT");return row;
        });
    }
    @Override public Map<String,Object> status(long actor,String requestId){
        enabled(actor);require(requestId!=null&&requestId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),"INVALID_REQUEST_ID");
        return persistence.inTransaction(()->{
            require(Long.valueOf(actor).equals(paid.findActiveBuyer(actor)),"BUYER_SCOPE_DENIED");
            PaidInstallCommandStore.Row row=commands.find(actor,PaidInstallCommand.key(actor,requestId));
            if(row==null)return map("schema","w18-paid-install-outcome-v1","requestId",requestId,"state","NOT_FOUND");
            lockOutcomes(actor,Collections.singletonList(row));return outcome(actor,row);
        });
    }
    private Map<String,Object> outcome(long actor,PaidInstallCommandStore.Row row){
            String requestId=row.getRequestId();validateRow(row,actor,requestId);List<PaidInstallCommandStore.Progress> values=commands.progress(actor,row.getCoreRequestId());
            require(values!=null&&values.size()<=1,"COMMAND_RESULT_CORRUPT");String state=clock.instant().isBefore(row.getRequestedUntil().toInstant())?"PREPARED":"EXPIRED";
            Long owned=null;String installedAt=null;
            if(!values.isEmpty()){
                PaidInstallCommandStore.Progress p=values.get(0);require(row.getLicenseId().equals(p.getLicenseId())&&row.getModuleId().equals(p.getModuleId())
                        &&row.getVersionId().equals(p.getVersionId())&&row.getContentHash().equals(p.getContentHash())&&row.getTermsHash().equals(p.getTermsHash())
                        &&row.getOfferVersion().equals(p.getOfferVersion())&&Long.valueOf(row.getTargetDraftId()).equals(p.getTargetDraftId())
                        &&Long.valueOf(row.getTargetRevision()).equals(p.getTargetRevision())&&Long.valueOf(actor).equals(p.getActorId())
                        &&row.getRequestedUntil().equals(p.getExpiresAt())&&Arrays.asList("RESERVED","CONSUMED","CANCELLED","FAILED","EXPIRED").contains(p.getState()),"COMMAND_RESULT_CORRUPT");
                state=p.getState();if("RESERVED".equals(state)&&!clock.instant().isBefore(row.getRequestedUntil().toInstant()))state="EXPIRED";
                if("CONSUMED".equals(state)){require(p.getOwnedDraftId()!=null&&p.getOwnedDraftId()>0&&p.getInstalledAt()!=null&&p.getOwnedDraftId().equals(p.getUsageDraftId())&&p.getInstalledAt().equals(p.getCommittedAt()),"COMMAND_RESULT_CORRUPT");state="INSTALLED_PRIVATE_DRAFT";owned=p.getOwnedDraftId();installedAt=p.getInstalledAt().toInstant().toString();}
                else require(p.getOwnedDraftId()==null&&p.getInstalledAt()==null&&p.getUsageDraftId()==null&&p.getCommittedAt()==null,"COMMAND_RESULT_CORRUPT");
            }
            return map("schema","w18-paid-install-outcome-v1","requestId",requestId,"state",state,"licenseId",row.getLicenseId(),"moduleId",row.getModuleId(),
                    "purchasedVersionId",row.getVersionId(),"contentHash",row.getContentHash(),"termsHash",row.getTermsHash(),"targetDraftId",row.getTargetDraftId(),
                    "targetRevision",row.getTargetRevision(),"targetPayloadHash",row.getTargetPayloadHash(),"businessType",row.getBusinessType(),"planningRegion",row.getPlanningRegion(),
                    "commercialUse",row.getCommercialUse(),"mode","UNRESOLVED","holdDeadline",row.getRequestedUntil().toInstant().toString(),
                    "ownedDraftId",owned,"installedAt",installedAt,"professionalTemplateStatus","MATERIALIZATION_REQUIRED","published",false,"executable",false);
    }
    @Override public Map<String,Object> history(long actor,String licenseId,String before){
        enabled(actor);require(licenseId!=null&&licenseId.matches("w18-paid-[1-9][0-9]{0,18}")&&(before==null||before.matches("[0-9a-f]{64}")),"INVALID_HISTORY_CURSOR");
        return persistence.inTransaction(()->{
            require(Long.valueOf(actor).equals(paid.findActiveBuyer(actor)),"BUYER_SCOPE_DENIED");
            List<PaidInstallCommandStore.Row> found=commands.history(actor,licenseId,before);require(found!=null&&found.size()<=51,"COMMAND_INDEX_CORRUPT");
            for(PaidInstallCommandStore.Row row:found){validateRow(row,actor,row.getRequestId());require(licenseId.equals(row.getLicenseId()),"COMMAND_INDEX_CORRUPT");}
            lockOutcomes(actor,found);
            List<Map<String,Object>> items=new ArrayList<Map<String,Object>>();String previous=before;
            for(PaidInstallCommandStore.Row row:found){validateRow(row,actor,row.getRequestId());require(licenseId.equals(row.getLicenseId())&&(previous==null||row.getCommandKey().compareTo(previous)<0),"COMMAND_INDEX_CORRUPT");previous=row.getCommandKey();if(items.size()<50)items.add(outcome(actor,row));}
            boolean more=found.size()>50;return map("schema","w18-paid-install-history-v1","scope","OWNER_COMMAND_RECEIPTS_ONLY","licenseId",licenseId,"checkedAt",clock.instant().toString(),"items",Collections.unmodifiableList(items),"hasMore",more,"nextBeforeCommandKey",more?found.get(49).getCommandKey():null);
        });
    }
    /** Status must wait for physical commits, not infer expiry from an older READ_COMMITTED row.
     * History first takes ALL canonical request mutexes in sorted order, then ALL license mutexes.
     * Locking a license while later waiting for another request would deadlock with core reserve.
     * Command prepare creates these mutexes atomically. A missing/corrupt mutex fails unavailable. */
    private void lockOutcomes(long actor,List<PaidInstallCommandStore.Row> rows){
        TreeSet<String> requests=new TreeSet<String>(),licenseKeys=new TreeSet<String>();
        BuyerPrincipal buyer=new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,actor);
        for(PaidInstallCommandStore.Row row:rows){validateRow(row,actor,row.getRequestId());requests.add(ModuleReservationKeys.request(buyer,row.getCoreRequestId()));licenseKeys.add(ModuleReservationKeys.license(row.getLicenseId()));}
        for(String key:requests)require(key.equals(reservations.lockMutex(key)),"COMMAND_COORDINATION_UNAVAILABLE");
        for(String key:licenseKeys)require(key.equals(reservations.lockMutex(key)),"COMMAND_COORDINATION_UNAVAILABLE");
    }
    private static ModuleInstallRequest request(long actor,PaidInstallCommandStore.Row row){return new ModuleInstallRequest(new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,actor),row.getCoreRequestId(),row.getLicenseId(),row.getModuleId(),row.getVersionId(),row.getContentHash(),row.getOfferVersion(),row.getTermsHash(),row.getTargetDraftId(),row.getTargetRevision(),row.getCommercialUse(),Collections.emptySet(),Collections.emptyMap());}
    private static void validateRow(PaidInstallCommandStore.Row row,long actor,String requestId){PaidInstallCommand c=row.command();require(row.getBuyerMemberId()==actor&&requestId.equals(c.requestId)&&c.coreRequestId().equals(row.getCoreRequestId())
            &&PaidInstallCommand.key(actor,requestId).equals(row.getCommandKey())&&c.digest().equals(row.getCommandHash())&&row.getOfferVersion()!=null&&!row.getOfferVersion().isEmpty()
            &&row.getCreatedAt()!=null&&row.getRequestedUntil()!=null&&row.getCreatedAt().before(row.getRequestedUntil())
            &&Duration.between(row.getCreatedAt().toInstant(),row.getRequestedUntil().toInstant()).compareTo(Duration.ofMinutes(10))<=0,"COMMAND_CORRUPT");}
    private static void validTarget(FreeInstallTargetRow row,long actor){require(row!=null&&Long.valueOf(actor).equals(row.getOwnerMemberId())&&row.getId()!=null&&row.getId()>0&&row.getVersion()!=null&&row.getVersion()>0
            &&"DRAFT".equals(row.getStatus())&&Arrays.asList("TOPIC","ACTIVITY").contains(row.getBusinessType())&&row.getPayloadHash()!=null&&row.getPayloadHash().matches("[0-9a-f]{64}"),"TARGET_UNAVAILABLE");}
    private static void require(boolean value,String code){if(!value)throw new IllegalArgumentException(code);}
    private static Map<String,Object> map(Object... pairs){Map<String,Object> result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return Collections.unmodifiableMap(result);}
}

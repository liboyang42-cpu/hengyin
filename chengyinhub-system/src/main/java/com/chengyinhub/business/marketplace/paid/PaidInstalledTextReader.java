package com.chengyinhub.business.marketplace.paid;

import com.chengyinhub.business.marketplace.contract.*;
import com.chengyinhub.business.marketplace.reservation.*;
import com.chengyinhub.business.marketplace.reservation.store.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** NEW protected-body producer, independently default off and unregistered. Reads only an existing
 * committed PAID individual text installation. Current frozen grant/source/terms, canonical command,
 * reservation, owned row, single component and usage must all agree in one fixed transaction.
 * No CMS row, editor write, fresh install, quota mutation, checkout or publication authority. */
public final class PaidInstalledTextReader {
    public enum Policy { DISABLED, OWNER_PAID_COMMITTED_TEXT_READ_V1 }
    private final Policy policy;private final Clock clock;private final PaidPersistenceBoundary persistence;
    private final PersistedPaidLicenseService licenses;private final PaidInstallCommandStore commands;
    private final ModuleReservationStore reservations;private final PaidInstalledTextStore installed;
    public PaidInstalledTextReader(SqlSessionTemplate session,DataSourceTransactionManager manager,Clock clock){this(session,manager,clock,Policy.DISABLED);}
    public PaidInstalledTextReader(SqlSessionTemplate session,DataSourceTransactionManager manager,Clock clock,Policy policy){
        this(new PaidPersistenceBoundary(session,manager),clock,policy);
    }
    PaidInstalledTextReader(PaidPersistenceBoundary persistence,Clock clock,Policy policy){
        this.policy=Objects.requireNonNull(policy);this.clock=Objects.requireNonNull(clock);this.persistence=Objects.requireNonNull(persistence);
        licenses=new PersistedPaidLicenseService(persistence,clock,PersistedPaidLicenseService.Policy.VERIFIED_WECHAT_PURCHASED_VERSION_V1);
        commands=persistence.mapper(PaidInstallCommandStore.class);reservations=persistence.mapper(ModuleReservationStore.class);installed=persistence.mapper(PaidInstalledTextStore.class);
    }
    public Map<String,Object> detail(long actor,String requestId,long ownedDraftId){
        enabled(actor,requestId,ownedDraftId);
        return persistence.inTransaction(()->project(captureInCurrentTransaction(actor,requestId,ownedDraftId)));
    }
    /** NEW internal materializer seam. No detached API DTO is accepted as authorization: the same
     * fixed session/manager must own the transaction and all source locks until caller commit. */
    Captured captureInCurrentTransaction(long actor,String requestId,long ownedDraftId){
        enabled(actor,requestId,ownedDraftId);persistence.fenceCurrentTransaction();
            PaidInstallCommandStore.Row command=commands.find(actor,PaidInstallCommand.key(actor,requestId));require(command!=null);
            PaidInstallCommand intent=command.command();require(command.getBuyerMemberId()==actor&&requestId.equals(intent.requestId)
                    &&PaidInstallCommand.key(actor,requestId).equals(command.getCommandKey())&&intent.digest().equals(command.getCommandHash())
                    &&intent.coreRequestId().equals(command.getCoreRequestId())&&command.getCreatedAt()!=null&&command.getRequestedUntil()!=null
                    &&command.getCreatedAt().before(command.getRequestedUntil())
                    &&Duration.between(command.getCreatedAt().toInstant(),command.getRequestedUntil().toInstant()).compareTo(Duration.ofMinutes(10))<=0);
            BuyerPrincipal buyer=new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,actor);
            String reservationKey=ModuleReservationKeys.request(buyer,command.getCoreRequestId());
            // Identical request → license → reservation → paid source lock order. Existing mutexes
            // only: a protected read never repairs or resumes an installation.
            for(String mutex:Arrays.asList(reservationKey,ModuleReservationKeys.license(command.getLicenseId())))require(mutex.equals(reservations.lockMutex(mutex)));
            ModuleReservationRow reservation=reservations.findForUpdate(reservationKey);
            PersistedPaidLicenseService.LockedPurchase purchase=licenses.captureOwnedInCurrentTransaction(actor,command.getLicenseId());
            ModuleLicenseGrant grant=purchase.grant;require(grant.isActiveAt(clock.instant())&&grant.getValidUntil()==null
                    &&grant.getEvidenceKind()==ModuleLicenseGrant.EvidenceKind.VERIFIED_PAID_ORDER_LINE
                    &&grant.getOffer().getTerms().getUseDuration()==LicenseTermsSnapshot.UseDuration.PERPETUAL_PURCHASED_VERSION);
            ModuleInstallRequest request=new ModuleInstallRequest(buyer,command.getCoreRequestId(),command.getLicenseId(),command.getModuleId(),command.getVersionId(),command.getContentHash(),command.getOfferVersion(),command.getTermsHash(),command.getTargetDraftId(),command.getTargetRevision(),command.getCommercialUse(),Collections.emptySet(),Collections.emptyMap());
            CommittedTextInstallationVerifier.requireMatches(reservation,request,command.getRequestedUntil().toInstant(),grant,purchase.snapshot.getVersion(),command.getPlanningRegion());
            PaidInstalledTextStore.OwnedRow row=installed.lockOwned(actor,ownedDraftId,reservationKey);
            require(row!=null&&Long.valueOf(ownedDraftId).equals(row.getId())&&Long.valueOf(actor).equals(row.getBuyerId())&&"INDIVIDUAL".equals(row.getBuyerKind())
                    &&reservationKey.equals(row.getReservationKey())&&Long.valueOf(command.getTargetDraftId()).equals(row.getTargetDraftId())&&Long.valueOf(command.getTargetRevision()).equals(row.getTargetRevision())
                    &&Long.valueOf(1).equals(row.getRevision())&&"DRAFT".equals(row.getState())&&Integer.valueOf(0).equals(row.getReviewStatus())
                    &&Integer.valueOf(0).equals(row.getPublishStatus())&&Integer.valueOf(0).equals(row.getIsSync())&&Long.valueOf(actor).equals(row.getInstalledBy())
                    &&row.getInstalledAt()!=null&&row.getInstalledAt().equals(row.getCommittedAt())&&row.getInstalledAt().equals(reservation.getTerminalAt())
                    &&row.getId().equals(row.getUsageOwnedDraftId())&&reservationKey.equals(row.getUsageReservationKey()));
            requireBytes(row.getSourceContent(),row.getSourceLength(),262144);requireBytes(row.getTermsDocument(),row.getTermsLength(),65535);
            requireString(row.getRightsJson(),row.getRightsCharacterCount(),65536);
            require(Arrays.equals(row.getSourceContent(),purchase.snapshot.getContent())&&Arrays.equals(row.getTermsDocument(),purchase.snapshot.getTerms()));
            List<PaidInstalledTextStore.ComponentRow> components=installed.lockComponents(ownedDraftId);require(components!=null&&components.size()==1);
            PaidInstalledTextStore.ComponentRow component=components.get(0);require(component!=null&&row.getId().equals(component.getDraftId())&&"template".equals(component.getLocalKey())
                    &&"TEXT_TEMPLATE".equals(component.getComponentKind())&&Integer.valueOf(1).equals(component.getSchemaVersion())&&component.getContentJson()!=null);
            requireString(component.getContentJson(),component.getContentCharacterCount(),266240);
            TextModuleDraftCodec.verifyInstalledClone(grant,purchase.snapshot.getVersion(),request,command.getPlanningRegion(),row.getSourceContent(),row.getTermsDocument(),component.getContentJson(),row.getRightsJson());
            require(grant.isActiveAt(clock.instant()));
            return new Captured(persistence,actor,purchase,command,row,component);
    }
    private Map<String,Object> project(Captured captured){
            captured.requireCurrent(persistence,clock.instant());
            long ownedDraftId=captured.ownedDraftId;String requestId=captured.intent.requestId;
            PersistedPaidLicenseService.LockedPurchase purchase=captured.purchase;ModuleLicenseGrant grant=purchase.grant;
            PaidInstallCommandStore.Row command=captured.command;PaidInstalledTextStore.OwnedRow row=captured.row;
            PaidInstalledTextStore.ComponentRow component=captured.component;
            // A consumed installation's technical hold deadline is historical. Do not apply it as
            // a license expiry. Later generic draft edits also cannot erase this purchased copy;
            // a future materializer still requires its own CURRENT target CAS before any write.
            LicenseTermsSnapshot terms=grant.getOffer().getTerms();
            return map("schema","w18-paid-installed-text-v1","scope","OWNER_PAID_INSTALLED_TEXT_PROTECTED_READ_ONLY","requestId",requestId,
                    "licenseId",grant.getLicenseId(),"moduleId",command.getModuleId(),"purchasedVersionId",grant.getPurchasedVersionId(),"version",purchase.snapshot.getVersion().getVersion(),
                    "contentHash",command.getContentHash(),"componentHash",PaidPurchaseSnapshot.sha(component.getContentJson().getBytes(StandardCharsets.UTF_8)),
                    "ownedDraftId",ownedDraftId,"ownedDraftRevision",row.getRevision(),"installedAt",row.getInstalledAt().toInstant().toString(),"checkedAt",clock.instant().toString(),
                    "originalTargetDraftId",command.getTargetDraftId(),"originalTargetRevision",command.getTargetRevision(),"originalTargetPayloadHash",command.getTargetPayloadHash(),"originalBusinessType",command.getBusinessType(),
                    "mode","UNRESOLVED","sourceJson",new String(row.getSourceContent(),StandardCharsets.UTF_8),"componentJson",component.getContentJson(),
                    "termsVersion",terms.getTermsVersion(),"termsHash",terms.getTermsHash(),"termsDocumentHash",terms.getTermsDocumentHash(),"termsDocumentBase64",Base64.getEncoder().encodeToString(row.getTermsDocument()),
                    "rights",map("useDuration",terms.getUseDuration().name(),"commercialUse",terms.getCommercialUse().name(),"adaptation",terms.getAdaptation().name(),"translation",terms.getTranslation().name(),"updates",terms.getUpdates().name(),"redistribution",terms.getRedistribution().name(),"allowedRegions",Collections.unmodifiableList(new ArrayList<String>(new TreeSet<String>(terms.getAllowedRegions()))),"themeLimit",limit(terms.getThemeLimit()),"merchantLimit",limit(terms.getMerchantLimit()),"runLimit",limit(terms.getRunLimit())),
                    "professionalTemplateStatus","MATERIALIZATION_REQUIRED","published",false,"executable",false);
    }
    private void enabled(long actor,String requestId,long ownedDraftId){
        require(policy==Policy.OWNER_PAID_COMMITTED_TEXT_READ_V1&&actor>0&&ownedDraftId>0
                &&requestId!=null&&requestId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        persistence.verifyConfiguration();
    }
    /** Immutable references/copies of verified stored material. Constructor is private and this
     * type is package-private. It is not a reusable entitlement or a public transport response. */
    static final class Captured {
        final long actor,ownedDraftId,ownedDraftRevision;
        final PaidInstallCommand intent;
        final ModuleLicenseGrant grant;
        final ModuleVersionSnapshot source;
        final String componentHash,frozenRights,installedAt;
        private final PaidPersistenceBoundary sourcePersistence;
        private final Object transactionIdentity;
        private final PersistedPaidLicenseService.LockedPurchase purchase;
        private final PaidInstallCommandStore.Row command;
        private final PaidInstalledTextStore.OwnedRow row;
        private final PaidInstalledTextStore.ComponentRow component;
        private Captured(PaidPersistenceBoundary persistence,long actor,PersistedPaidLicenseService.LockedPurchase purchase,PaidInstallCommandStore.Row command,
                PaidInstalledTextStore.OwnedRow row,PaidInstalledTextStore.ComponentRow component){
            sourcePersistence=persistence;transactionIdentity=persistence.currentTransactionIdentity();
            this.actor=actor;this.purchase=purchase;this.command=command;this.row=row;this.component=component;
            ownedDraftId=row.getId();ownedDraftRevision=row.getRevision();intent=command.command();grant=purchase.grant;source=purchase.snapshot.getVersion();
            componentHash=PaidPurchaseSnapshot.sha(component.getContentJson().getBytes(StandardCharsets.UTF_8));frozenRights=row.getRightsJson();installedAt=row.getInstalledAt().toInstant().toString();
        }
        void requireCurrent(PaidPersistenceBoundary persistence,Instant now){require(sourcePersistence==persistence);persistence.requireTransactionIdentity(transactionIdentity);require(grant.isActiveAt(now));}
        byte[] originalSource(){return row.getSourceContent().clone();}
        byte[] termsDocument(){return row.getTermsDocument().clone();}
        byte[] originalComponent(){return component.getContentJson().getBytes(StandardCharsets.UTF_8);}
    }
    private static Map<String,Object> limit(LicenseTermsSnapshot.Limit limit){return map("unlimited",limit.isUnlimited(),"maximum",limit.getMaximum());}
    private static void requireBytes(byte[] value,Long length,int maximum){require(value!=null&&length!=null&&length>0&&length<=maximum&&length==value.length);}
    // SQL bounds whole strings by characters; Java enforces UTF-8 bytes. This avoids database
    // OCTET_LENGTH encoding differences and never accepts a truncated substring as complete text.
    private static void requireString(String value,Long characters,int maximum){require(value!=null&&characters!=null&&characters>0&&characters<=maximum&&value.getBytes(StandardCharsets.UTF_8).length<=maximum);}
    private static void require(boolean condition){if(!condition)throw new IllegalArgumentException("PAID_INSTALLED_TEXT_UNAVAILABLE");}
    private static Map<String,Object> map(Object... pairs){Map<String,Object> value=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return Collections.unmodifiableMap(value);}
}

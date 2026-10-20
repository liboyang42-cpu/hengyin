package com.chengyinhub.business.marketplace.paid;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.marketplace.contract.LicenseTermsSnapshot;
import com.chengyinhub.business.marketplace.paid.materialization.PaidTemplateMaterializationFields;
import com.chengyinhub.business.service.support.TemplateAuditSnapshot;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** NEW proposed transactional adapter, unregistered and default disabled. Activation is dependent
 * on the independently reviewed legacy CMS dual-sentinel guards; this policy enum is configuration,
 * not proof that arbitrary existing code is protected. No checkout, license issuance, role change,
 * generic save, topic node insertion, publication, marketplace or run is enabled by this adapter.
 * A consumed generic TOPIC draft is explicitly bound to one actual CMS topic and confirmed mode.
 * The binding is not a claim that generic payload_json represents a professional topic graph. */
public final class PaidProfessionalMaterializationService implements PaidProfessionalMaterializationHandling {
    public enum Policy { DISABLED, REVIEWED_GUARDED_CMS_TEXT_V1 }
    public static final String SCHEMA="w18-paid-cms-text-provenance-v1";
    private final Policy policy;private final Clock clock;private final PaidPersistenceBoundary persistence;
    private final PaidInstalledTextReader source;private final PaidProfessionalMaterializationCapture targets;
    private final PaidProfessionalTemplateStore store;
    private final com.chengyinhub.business.marketplace.paid.store.PaidLicenseStore ownerIndex;
    public PaidProfessionalMaterializationService(SqlSessionTemplate session,DataSourceTransactionManager manager,Clock clock){this(session,manager,clock,Policy.DISABLED);}
    public PaidProfessionalMaterializationService(SqlSessionTemplate session,DataSourceTransactionManager manager,Clock clock,Policy policy){
        this.policy=Objects.requireNonNull(policy);this.clock=Objects.requireNonNull(clock);persistence=new PaidPersistenceBoundary(session,manager);
        source=new PaidInstalledTextReader(persistence,clock,PaidInstalledTextReader.Policy.OWNER_PAID_COMMITTED_TEXT_READ_V1);
        targets=new PaidProfessionalMaterializationCapture(persistence,clock);store=persistence.mapper(PaidProfessionalTemplateStore.class);
        ownerIndex=persistence.mapper(com.chengyinhub.business.marketplace.paid.store.PaidLicenseStore.class);
    }
    /** Legacy callers also use the operation coordinator: there is no public bypass around
     * retained cancellation/rejection tombstones. The valid create receipt remains compatible. */
    public Map<String,Object> materialize(long actor,PaidProfessionalMaterializationCommand command){
        return new PaidProfessionalOperationService(this,clock,policy).legacyMaterialize(actor,command);
    }
    PaidPersistenceBoundary operationBoundary(){return persistence;}
    void requireOperationEnabled(long actor){require(policy==Policy.REVIEWED_GUARDED_CMS_TEXT_V1&&actor>0);}
    /** Package-private kernel, called only while the coordinator holds the matching operation row.
     * It shares the fixed transaction; never invoke this in a separate REQUIRES_NEW transaction. */
    Map<String,Object> createInCurrentTransaction(long actor,PaidProfessionalMaterializationCommand command,PaidProfessionalOperationService.Permit operation){
        requireOperationEnabled(actor);require(command!=null&&operation!=null);persistence.fenceCurrentTransaction();operation.validate(persistence,actor,command);
            PaidProfessionalMaterializationCapture.Topic topic=targets.lockTopic(actor,command);
            PaidInstalledTextReader.Captured captured=source.captureInCurrentTransaction(actor,command.installationRequestId,command.ownedDraftId);
            PaidProfessionalMaterializationCapture.Draft draft=targets.lockCurrentDraft(actor,command,captured);
            require(captured.grant.getOffer().getTerms().getAdaptation()==LicenseTermsSnapshot.Adaptation.LOCAL_ADAPTATION);
            PaidTemplateMaterializationFields fields=PaidTemplateMaterializationFields.decode(captured.originalComponent());
            requirePhysicalTextBounds(fields);
            PaidProfessionalProvenanceRow origin=origin(actor,command,captured,topic,draft);
            // Existing license mutex serializes a consumed license/theme binding. Unique keys also
            // reject cross-license UUID reuse and ambiguous/corrupt duplicates; never overwrite.
            PaidProfessionalProvenanceRow old=store.findRequest(command.ownerRequestKey(actor));
            if(old!=null){require(old.sameOrigin(origin));return receipt(verifiedTemplate(old,fields,actor),old,fields);}
            operation.requireCreation();require(store.findBinding(origin.getBindingKey())==null);
            CmsMemberTemplate row=persistableDraft(fields,actor,Date.from(clock.instant()));
            require(store.insertMarkedTemplate(row)==1&&row.getId()!=null&&row.getId()>0);
            origin.setTemplateId(row.getId());origin.setTemplateContentHash(templateHash(row));origin.setCreatedAt(row.getCreateTime());
            // The marker is part of INSERT itself. No committed unmarked interval, async sidecar,
            // provisional entitlement or generic editor save can occur between these operations.
            require(store.insertProvenance(origin)==1);
            PaidProfessionalProvenanceRow saved=store.findRequest(origin.getRequestKey());
            require(saved!=null&&saved.sameOrigin(origin)&&origin.getTemplateId().equals(saved.getTemplateId())
                    &&origin.getTemplateContentHash().equals(saved.getTemplateContentHash())&&origin.getCreatedAt().equals(saved.getCreatedAt()));
            CmsMemberTemplate readback=verifiedTemplate(saved,fields,actor);
            captured.requireCurrent(persistence,clock.instant());
            return receipt(readback,saved,fields);
    }
    /** Immutable operation history only. Unlike a fresh materialize command, recovery must survive
     * later target CAS/mode/role changes or refund. It exposes no protected body, current editability,
     * publication/run approval or assertion about the current CMS row. Actor ownership still gates
     * every lookup; malformed provenance is unavailable, never treated as a new request. */
    @Override public Map<String,Object> status(long actor,String requestId){
        requireOperationEnabled(actor);
        return persistence.inTransaction(()->historyInCurrentTransaction(actor,requestId,null));
    }
    Map<String,Object> historyInCurrentTransaction(long actor,String requestId,PaidProfessionalMaterializationCommand expected){
        requireOperationEnabled(actor);persistence.fenceCurrentTransaction();
        String key=PaidProfessionalMaterializationCommand.ownerRequestKey(actor,requestId);
            require(ownerIndex.activeBuyer(actor)!=null);
            PaidProfessionalProvenanceRow row=store.findRequest(key);
            if(row!=null&&expected!=null)require(expected.digest().equals(row.getCommandHash()));
            Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("schema","w18-paid-professional-materialization-status-v1");
            result.put("scope","OWNER_HISTORICAL_CREATION_REFERENCE_ONLY");result.put("requestId",requestId);
            if(row==null){result.put("state","NOT_FOUND");return Collections.unmodifiableMap(result);}
            require(Long.valueOf(actor).equals(row.getOwnerMemberId())&&SCHEMA.equals(row.getSchemaVersion())
                    &&"W18_PAID_INSTALLED_TEXT".equals(row.getSourceKind())&&row.getTemplateId()!=null&&row.getTemplateId()>0
                    &&row.getOwnedDraftRevision()!=null&&row.getOwnedDraftRevision()==1&&row.getCreatedAt()!=null
                    &&row.getOriginalTargetDraftId()!=null&&row.getOriginalTargetDraftId()>0&&row.getOriginalTargetRevision()!=null&&row.getOriginalTargetRevision()>0
                    &&row.getOriginalTargetPayloadHash()!=null&&row.getOriginalTargetPayloadHash().matches("[0-9a-f]{64}")
                    &&row.getContentRoleId()!=null&&row.getContentRoleId()>0&&(row.getRouteConfigVersion()==null||row.getRouteConfigVersion()>=0)
                    &&row.getTemplateContentHash()!=null&&row.getTemplateContentHash().matches("[0-9a-f]{64}"));
            PaidProfessionalMaterializationCommand stored=new PaidProfessionalMaterializationCommand(requestId,row.getInstallationRequestId(),row.getOwnedDraftId(),
                    row.getLicenseId(),row.getModuleId(),row.getPurchasedVersionId(),row.getContentHash(),row.getTermsHash(),row.getComponentHash(),
                    row.getConfirmedDraftRevision(),row.getConfirmedDraftPayloadHash(),row.getTargetTopicId(),row.getOwnerModeFingerprint(),
                    PaidProfessionalMaterializationCommand.Mode.valueOf(row.getConfirmedMode()));
            require(key.equals(row.getRequestKey())&&stored.digest().equals(row.getCommandHash())
                    &&stored.consumedThemeBindingKey(row.getOriginalTargetDraftId()).equals(row.getBindingKey()));
            result.put("state","CREATION_COMMITTED");result.put("templateId",row.getTemplateId());result.put("targetTopicId",row.getTargetTopicId());
            result.put("confirmedMode",row.getConfirmedMode());result.put("ownedDraftId",row.getOwnedDraftId());result.put("licenseId",row.getLicenseId());
            result.put("purchasedVersionId",row.getPurchasedVersionId());result.put("templateContentHash",row.getTemplateContentHash());
            result.put("createdAt",row.getCreatedAt().toInstant().toString());result.put("currentTemplateState","NOT_CHECKED");
            result.put("currentUseAuthority","NOT_GRANTED_BY_HISTORY");return Collections.unmodifiableMap(result);
    }
    private CmsMemberTemplate verifiedTemplate(PaidProfessionalProvenanceRow origin,PaidTemplateMaterializationFields fields,long actor){
        require(origin.getTemplateId()!=null&&origin.getTemplateId()>0&&origin.getCreatedAt()!=null
                &&origin.getTemplateContentHash()!=null&&origin.getTemplateContentHash().matches("[0-9a-f]{64}"));
        PaidProfessionalTemplateStore.TemplateGuard guard=store.lockTemplateGuard(actor,origin.getTemplateId());
        require(guard!=null&&"W18_MANAGED".equals(guard.getMarker())&&guard.getCharacters()!=null&&guard.getCharacters()>=0&&guard.getCharacters()<=1048576);
        CmsMemberTemplate row=store.readTemplate(actor,origin.getTemplateId());fields.requireExactReadback(row,actor);
        CmsMemberTemplate expected=persistableDraft(fields,actor,origin.getCreatedAt());expected.setId(origin.getTemplateId());
        require(origin.getTemplateContentHash().equals(templateHash(expected))&&origin.getTemplateContentHash().equals(templateHash(row)));
        return row;
    }
    private static PaidProfessionalProvenanceRow origin(long actor,PaidProfessionalMaterializationCommand c,PaidInstalledTextReader.Captured source,
            PaidProfessionalMaterializationCapture.Topic topic,PaidProfessionalMaterializationCapture.Draft draft){
        PaidProfessionalProvenanceRow r=new PaidProfessionalProvenanceRow();r.setSchemaVersion(SCHEMA);r.setOwnerMemberId(actor);
        r.setRequestKey(c.ownerRequestKey(actor));r.setCommandHash(c.digest());r.setBindingKey(c.consumedThemeBindingKey(source.intent.targetDraftId));
        r.setSourceKind("W18_PAID_INSTALLED_TEXT");r.setOwnedDraftId(source.ownedDraftId);r.setOwnedDraftRevision(source.ownedDraftRevision);
        r.setInstallationRequestId(c.installationRequestId);r.setLicenseId(c.licenseId);r.setModuleId(c.moduleId);r.setPurchasedVersionId(c.purchasedVersionId);
        r.setContentHash(c.contentHash);r.setComponentHash(source.componentHash);r.setTermsHash(c.termsHash);
        r.setOriginalTargetDraftId(source.intent.targetDraftId);r.setOriginalTargetRevision(source.intent.targetRevision);r.setOriginalTargetPayloadHash(source.intent.targetPayloadHash);
        r.setConfirmedDraftRevision(draft.revision);r.setConfirmedDraftPayloadHash(draft.payloadHash);r.setTargetTopicId(topic.id);r.setConfirmedMode(topic.mode.name());
        r.setContentRoleId(topic.contentRoleId);r.setRouteConfigVersion(topic.routeConfigVersion);r.setOwnerModeFingerprint(topic.ownerModeFingerprint);return r;
    }
    private static CmsMemberTemplate persistableDraft(PaidTemplateMaterializationFields fields,long actor,Date at){
        CmsMemberTemplate row=fields.newPrivateDraft(actor,at);
        // Exact 7aa base_schema_20260818: photo_review NOT NULL DEFAULT 0 and medal_style
        // NOT NULL DEFAULT glow. These inert storage defaults grant no photo/medal capability:
        // validation remains text-only, medal ID/name/image and all reward fields remain absent.
        row.setPhotoReview(0);row.setMedalStyle("glow");return row;
    }
    private static void requirePhysicalTextBounds(PaidTemplateMaterializationFields fields){
        Map<String,Integer> columns=new LinkedHashMap<String,Integer>();columns.put("title",255);columns.put("questionName",255);
        columns.put("hint1",255);columns.put("hint2",255);columns.put("answerReveal",500);columns.put("merchantGuide",1000);
        for(Map.Entry<String,Integer> column:columns.entrySet()){
            String value=fields.textFields().get(column.getKey());if(value!=null&&value.codePointCount(0,value.length())>column.getValue())
                throw new IllegalArgumentException("W18_MATERIALIZATION_FIELD_REJECTED:"+column.getKey()+":cms-column-limit");
        }
        for(String field:Arrays.asList("description","ruleInstructions","questionAnswer")){
            String value=fields.textFields().get(field);if(value!=null&&value.getBytes(StandardCharsets.UTF_8).length>65535)
                throw new IllegalArgumentException("W18_MATERIALIZATION_FIELD_REJECTED:"+field+":cms-text-byte-limit");
        }
    }
    private static String templateHash(CmsMemberTemplate row){return PaidPurchaseSnapshot.sha(TemplateAuditSnapshot.personal(row).getBytes(StandardCharsets.UTF_8));}
    private static Map<String,Object> receipt(CmsMemberTemplate row,PaidProfessionalProvenanceRow origin,PaidTemplateMaterializationFields fields){
        Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("schema","w18-paid-professional-materialization-receipt-v1");
        result.put("templateId",row.getId());result.put("targetTopicId",origin.getTargetTopicId());result.put("confirmedMode",origin.getConfirmedMode());
        result.put("ownedDraftId",origin.getOwnedDraftId());result.put("licenseId",origin.getLicenseId());result.put("purchasedVersionId",origin.getPurchasedVersionId());
        result.put("templateContentHash",origin.getTemplateContentHash());result.put("createdAt",origin.getCreatedAt().toInstant().toString());
        result.put("state","GUARDED_PRIVATE_TEMPLATE_CREATED");result.put("missingProfessionalInputs",fields.missingProfessionalInputs());
        result.put("nodeAttached",false);result.put("published",false);result.put("executable",false);return Collections.unmodifiableMap(result);
    }
    private static void require(boolean value){if(!value)throw new IllegalArgumentException("PAID_PROFESSIONAL_MATERIALIZATION_UNAVAILABLE_OR_CONFLICT");}
}

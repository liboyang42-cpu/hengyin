package com.chengyinhub.business.service.ai.npc;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Date;
import java.util.Objects;

/**
 * Internal authenticated-server service. No HTTP endpoint or approved-source adapter is provided.
 * All content stays an unapproved draft, even after the review of a candidate. No legacy import.
 */
@Service
public class MerchantNpcKnowledgeDraftService {
    public static final String BIZ_TYPE="MERCHANT_NPC_KNOWLEDGE_DRAFT";
    public static final String SOURCE_TYPE="merchant_npc_public_facts_draft_v1";
    @Value("${questify.merchant-npc-knowledge-drafts.enabled:false}") private boolean enabled;
    @Autowired(required=false) private MerchantNpcKnowledgeReviewAuthority reviewAuthority;
    private final MmsMerchantMapper merchants;
    private final MerchantNpcKnowledgeDraftMapper drafts;
    private final BusinessAuditEventMapper events;
    private final AuditTaskMapper tasks;
    private final AuditRecordMapper records;
    public MerchantNpcKnowledgeDraftService(MmsMerchantMapper merchants, MerchantNpcKnowledgeDraftMapper drafts,
            BusinessAuditEventMapper events, AuditTaskMapper tasks, AuditRecordMapper records) {
        this.merchants=merchants; this.drafts=drafts; this.events=events; this.tasks=tasks; this.records=records;
    }

    /** memberId must come from authenticated member context; never accept an owner flag from a payload. */
    @Transactional(rollbackFor=Exception.class)
    public MerchantNpcKnowledgeDraft save(long merchantId,long memberId,long expectedVersion,String requestId,String publicFactsJson) {
        requireTransaction(); validIds(merchantId,memberId);
        if(expectedVersion<0 || expectedVersion==Long.MAX_VALUE || requestId==null
            || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) throw conflict();
        MerchantNpcPublicFactsDraft facts=new MerchantNpcPublicFactsDraft(publicFactsJson);
        lockOwner(merchantId,memberId);
        MerchantNpcKnowledgeDraft prior=drafts.selectRequest(merchantId,memberId,requestId);
        if(prior!=null) {
            verify(prior,merchantId,memberId,expectedVersion+1,facts.hash);
            if(!facts.json.equals(prior.getContentJson()) || !requestId.equals(prior.getRequestId())) throw conflict();
            return prior;
        }
        MerchantNpcKnowledgeDraft head=drafts.selectHead(merchantId);
        if(head==null ? expectedVersion!=0 : !Long.valueOf(expectedVersion).equals(head.getVersion())
            || !Long.valueOf(memberId).equals(head.getOwnerMemberId())) throw conflict();
        MerchantNpcKnowledgeDraft row=new MerchantNpcKnowledgeDraft();
        row.setMerchantId(merchantId); row.setOwnerMemberId(memberId); row.setVersion(expectedVersion+1);
        row.setRequestId(requestId); row.setContentJson(facts.json); row.setContentHash(facts.hash);
        if(drafts.insertRevision(row)!=1 || row.getId()==null || row.getId()<=0) throw conflict();
        if((head==null ? drafts.insertHead(row) : drafts.advanceHead(row,expectedVersion))!=1) throw conflict();
        MerchantNpcKnowledgeDraft saved=drafts.selectHead(merchantId);
        exact(saved,row);
        appendEvent(saved,"NPC_KNOWLEDGE_DRAFT_SAVE",requestId,expectedVersion);
        return saved;
    }

    @Transactional(rollbackFor=Exception.class)
    public MerchantNpcKnowledgeDraft read(long merchantId,long memberId,long version,String expectedHash) {
        requireTransaction(); validIds(merchantId,memberId); lockOwner(merchantId,memberId);
        MerchantNpcKnowledgeDraft row=drafts.selectVersion(merchantId,memberId,version);
        verify(row,merchantId,memberId,version,expectedHash); return row;
    }

    /**
     * Concrete handoff to the existing audit storage. Disabled without a separately installed review
     * authority/route adapter; shared audit dispatch/RBAC is intentionally not modified by this slice.
     * One immutable revision gets one review task; a resubmission requires a new saved revision.
     */
    @Transactional(rollbackFor=Exception.class)
    public MerchantNpcKnowledgeDraft submitForReview(long merchantId,long memberId,long version,String expectedHash) {
        requireTransaction(); validIds(merchantId,memberId); lockOwner(merchantId,memberId);
        requireReviewRoute(merchantId,memberId);
        MerchantNpcKnowledgeDraft row=drafts.selectVersion(merchantId,memberId,version);
        verify(row,merchantId,memberId,version,expectedHash);
        MerchantNpcKnowledgeDraft head=drafts.selectHead(merchantId);
        exact(head,row); // stale drafts cannot be newly submitted or replayed after supersession
        if(row.getAuditTaskId()!=null) { validateTask(row,tasks.selectAuditTaskByIdForUpdate(row.getAuditTaskId())); return row; }
        AuditTask task=new AuditTask(); task.setBizType(BIZ_TYPE); task.setBizId(row.getId());
        task.setSubmitterId(memberId); task.setSourceType(SOURCE_TYPE); task.setRiskLevel("NORMAL");
        task.setStatus(AuditTask.STATUS_PENDING); task.setVersion(0); task.setSubmittedAt(new Date());
        task.setCreateTime(task.getSubmittedAt()); task.setSnapshotJson(envelope(row));
        if(tasks.insertAuditTask(task)!=1 || task.getId()==null || task.getId()<=0) throw conflict();
        row.setAuditTaskId(task.getId()); if(drafts.bindReview(row)!=1) throw conflict();
        MerchantNpcKnowledgeDraft saved=drafts.selectVersion(merchantId,memberId,version);
        exact(saved,row); if(!task.getId().equals(saved.getAuditTaskId())) throw conflict();
        validateTask(saved,tasks.selectAuditTaskByIdForUpdate(task.getId()));
        AuditRecord record=new AuditRecord(); record.setTaskId(task.getId()); record.setAction(AuditRecord.ACTION_SUBMIT);
        record.setOperatorId(memberId); record.setSnapshotJson(task.getSnapshotJson()); record.setCreateTime(task.getSubmittedAt());
        if(records.insertAuditRecord(record)!=1) throw conflict();
        appendEvent(saved,"NPC_KNOWLEDGE_DRAFT_SUBMIT","review:"+row.getId(),version);
        return saved;
    }

    /** Required future audit pre-CAS/replay hook; never establishes approved-public-source authority. */
    @Transactional(rollbackFor=Exception.class)
    public void validateReview(long merchantId,long memberId,long version,String expectedHash,long taskId,long systemUserId) {
        requireTransaction(); validIds(merchantId,memberId); if(taskId<=0 || systemUserId<=0) throw conflict();
        lockOwner(merchantId,memberId); requireReviewRoute(merchantId,memberId);
        reviewAuthority.requireCurrentReviewer(systemUserId,merchantId);
        MerchantNpcKnowledgeDraft row=drafts.selectVersion(merchantId,memberId,version);
        verify(row,merchantId,memberId,version,expectedHash); exact(drafts.selectHead(merchantId),row);
        if(!Long.valueOf(taskId).equals(row.getAuditTaskId())) throw conflict();
        validateTask(row,tasks.selectAuditTaskByIdForUpdate(taskId));
    }
    /**
     * New internal read projection, not an HTTP route or an approval capability.
     * Caller supplies the exact candidate binding and authenticated system actor. The transaction
     * remains write-capable solely for existing owner/task row locks; this method performs no writes.
     * A later review must revalidate independently; this DTO conveys no durable authority.
     */
    @Transactional(rollbackFor=Exception.class)
    public MerchantNpcKnowledgePreview previewForReview(long merchantId,long memberId,long version,
            String expectedHash,long taskId,int expectedTaskVersion,long systemUserId) {
        requireTransaction(); validIds(merchantId,memberId);
        if(taskId<=0 || systemUserId<=0 || expectedTaskVersion<0) throw conflict();
        lockOwner(merchantId,memberId); requireReviewRoute(merchantId,memberId);
        reviewAuthority.requireCurrentReviewer(systemUserId,merchantId);
        MerchantNpcKnowledgeDraft row=drafts.selectVersion(merchantId,memberId,version);
        verify(row,merchantId,memberId,version,expectedHash); exact(drafts.selectHead(merchantId),row);
        if(!Long.valueOf(taskId).equals(row.getAuditTaskId())) throw conflict();
        AuditTask task=tasks.selectAuditTaskByIdForUpdate(taskId);
        validateTask(row,task);
        if(!AuditTask.STATUS_PENDING.equals(task.getStatus())
            || !Integer.valueOf(expectedTaskVersion).equals(task.getVersion())) throw conflict();
        return new MerchantNpcKnowledgePreview(taskId,expectedTaskVersion,merchantId,row.getId(),
                version,row.getContentHash(),row.getContentJson());
    }

    private void requireReviewRoute(long merchantId,long memberId) {
        if(reviewAuthority==null) throw new IllegalStateException("Merchant knowledge review authority/route is not installed");
        reviewAuthority.requireInstalledReviewRoute(merchantId,memberId);
    }
    private void lockOwner(long merchantId,long memberId) {
        MmsMerchant m=merchants.selectMmsMerchantByIdForUpdate(merchantId);
        if(m==null || !Long.valueOf(merchantId).equals(m.getId()) || !Long.valueOf(memberId).equals(m.getMemberId())
            || !Long.valueOf(1).equals(m.getStatus()) || !Long.valueOf(1).equals(m.getAccountStatus())
            || !Integer.valueOf(0).equals(m.getDelFlag())) throw conflict();
    }
    private void requireTransaction() {
        if(!enabled || !TransactionSynchronizationManager.isActualTransactionActive()
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Merchant knowledge drafts require explicit enablement and a write transaction");
    }
    private void validIds(long merchantId,long memberId) { if(merchantId<=0 || memberId<=0) throw conflict(); }
    private void verify(MerchantNpcKnowledgeDraft row,long merchantId,long memberId,long version,String expectedHash) {
        if(row==null || row.getId()==null || row.getId()<=0 || version<=0 || expectedHash==null
            || !expectedHash.matches("[0-9a-f]{64}") || !Long.valueOf(merchantId).equals(row.getMerchantId())
            || !Long.valueOf(memberId).equals(row.getOwnerMemberId()) || !Long.valueOf(version).equals(row.getVersion())
            || !expectedHash.equals(row.getContentHash())) throw conflict();
        MerchantNpcPublicFactsDraft stored=new MerchantNpcPublicFactsDraft(row.getContentJson());
        if(!stored.json.equals(row.getContentJson()) || !stored.hash.equals(row.getContentHash())) throw conflict();
    }
    private void exact(MerchantNpcKnowledgeDraft actual,MerchantNpcKnowledgeDraft expected) {
        verify(actual,expected.getMerchantId(),expected.getOwnerMemberId(),expected.getVersion(),expected.getContentHash());
        if(!Objects.equals(actual.getId(),expected.getId()) || !Objects.equals(actual.getRequestId(),expected.getRequestId())
            || !Objects.equals(actual.getContentJson(),expected.getContentJson())) throw conflict();
    }
    private String envelope(MerchantNpcKnowledgeDraft row) {
        ObjectNode n=new ObjectMapper().createObjectNode(); n.put("schema",SOURCE_TYPE);
        n.put("merchantId",row.getMerchantId()); n.put("ownerMemberId",row.getOwnerMemberId());
        n.put("revisionId",row.getId()); n.put("version",row.getVersion()); n.put("contentHash",row.getContentHash());
        n.put("publicFactsDraftJson",row.getContentJson()); n.put("runtimePublication",false); return n.toString();
    }
    private void validateTask(MerchantNpcKnowledgeDraft row,AuditTask task) {
        if(task==null || !Objects.equals(row.getAuditTaskId(),task.getId()) || !BIZ_TYPE.equals(task.getBizType())
            || !SOURCE_TYPE.equals(task.getSourceType()) || !row.getId().equals(task.getBizId())
            || !row.getOwnerMemberId().equals(task.getSubmitterId()) || !envelope(row).equals(task.getSnapshotJson())
            || task.getVersion()==null || task.getVersion()<0 || task.getStatus()==null
            || task.getStatus()<0 || task.getStatus()>4) throw conflict();
    }
    private void appendEvent(MerchantNpcKnowledgeDraft row,String action,String request,long beforeVersion) {
        BusinessAuditEvent e=new BusinessAuditEvent(); e.setDomainType("MERCHANT"); e.setDomainId(row.getMerchantId());
        e.setOwnerMemberId(row.getOwnerMemberId()); e.setActorType(BusinessAuditEvent.ACTOR_TYPE_MEMBER);
        e.setActorMemberId(row.getOwnerMemberId()); e.setActorRoleCode("merchant"); e.setAction(action);
        e.setTargetType(BIZ_TYPE); e.setTargetId(row.getId()); e.setRelationId(row.getAuditTaskId());
        e.setRequestId(request); e.setBeforeJson("{\"version\":"+beforeVersion+"}");
        // Audit stores identity/hash only, never duplicates untrusted text into generic audit feeds.
        e.setAfterJson("{\"version\":"+row.getVersion()+",\"contentHash\":\""+row.getContentHash()+"\"}");
        e.setCreateTime(new Date()); if(events.insertBusinessAuditEvent(e)!=1) throw conflict();
    }
    private IllegalStateException conflict() { return new IllegalStateException("Merchant knowledge draft conflict"); }
}

package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.domain.vo.ContentCaptureReviewStatusVO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.constant.ProductSlot;
import com.chengyinhub.common.constant.RoleEntityType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Default-off candidate review submission. APPROVE means review of an incomplete candidate only.
 * There is deliberately no release allocation, publication, rights grant or runtime binding here.
 * The existing authenticated audit controller remains the sole reviewer entry point.
 */
@Service
public class ContentCaptureReviewService {
    public static final String BIZ_TYPE = "CONTENT_CAPTURE_REVIEW";
    public static final String SOURCE_TYPE = "content_capture_review_v1";
    @Value("${questify.content-capture-review.enabled:false}")
    private boolean enabled;
    @org.springframework.beans.factory.annotation.Autowired
    private ContentCaptureReviewSchemaPreflight schemaPreflight;
    private final ContentCaptureReviewMapper mapper;
    private final AuditTaskMapper tasks;
    private final AuditRecordMapper records;

    public ContentCaptureReviewService(ContentCaptureReviewMapper mapper, AuditTaskMapper tasks, AuditRecordMapper records) {
        this.mapper=mapper; this.tasks=tasks; this.records=records;
    }

    /** Internal server caller only: memberId must be the authenticated member, never a request owner flag. */
    @Transactional(rollbackFor=Exception.class)
    public long submit(long topicId, long memberId, long snapshotId, long expectedVersion, String expectedHash, String requestId) {
        if (topicId<=0 || memberId<=0 || snapshotId<=0 || expectedVersion<0
                || expectedHash==null || !expectedHash.matches("[0-9a-f]{64}")
                || requestId==null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) throw conflict();
        requireEnabledTransaction();
        lockOwner(topicId,memberId);
        ContentCaptureReceipt candidate=loadSnapshot(snapshotId,topicId,memberId,expectedVersion,expectedHash);
        ContentCaptureReviewBinding prior=mapper.selectRequest(topicId,memberId,requestId);
        if (prior!=null) {
            verifyBinding(prior,candidate,memberId);
            if (!requestId.equals(prior.getRequestId())) throw conflict();
            return prior.getAuditTaskId();
        }
        // One immutable candidate gets one review history. Resubmission requires a new captured identity.
        if (mapper.selectSnapshot(snapshotId)!=null) throw conflict();
        AuditTask task=new AuditTask();
        task.setBizType(BIZ_TYPE); task.setBizId(topicId); task.setSubmitterId(memberId);
        task.setSourceType(SOURCE_TYPE); task.setRiskLevel("NORMAL"); task.setStatus(AuditTask.STATUS_PENDING);
        task.setVersion(0); task.setSubmittedAt(new Date()); task.setCreateTime(task.getSubmittedAt());
        task.setSnapshotJson(envelope(candidate,memberId));
        if (tasks.insertAuditTask(task)!=1 || task.getId()==null || task.getId()<=0) throw conflict();
        ContentCaptureReviewBinding binding=new ContentCaptureReviewBinding();
        binding.setSnapshotId(snapshotId); binding.setTopicId(topicId); binding.setMemberId(memberId);
        binding.setRequestId(requestId); binding.setAuditTaskId(task.getId());
        binding.setSourceConfigVersion(expectedVersion); binding.setContentHash(expectedHash);
        if (mapper.insertBinding(binding)!=1 || binding.getId()==null || binding.getId()<=0) throw conflict();
        ContentCaptureReviewBinding saved=mapper.selectRequest(topicId,memberId,requestId);
        verifyBinding(saved,candidate,memberId);
        if (!binding.getId().equals(saved.getId()) || !task.getId().equals(saved.getAuditTaskId())) throw conflict();
        AuditRecord record=new AuditRecord(); record.setTaskId(task.getId()); record.setAction(AuditRecord.ACTION_SUBMIT);
        record.setOperatorId(memberId); record.setSnapshotJson(task.getSnapshotJson()); record.setCreateTime(task.getSubmittedAt());
        if (records.insertAuditRecord(record)!=1) throw conflict();
        return task.getId();
    }

    /** Authenticated member transport. Submission and response validation share one transaction. */
    @Transactional(rollbackFor=Exception.class)
    public ContentCaptureReviewStatusVO submitForMember(long topicId, long memberId, long snapshotId,
            long expectedVersion, String expectedHash, String requestId) {
        submit(topicId, memberId, snapshotId, expectedVersion, expectedHash, requestId);
        return statusForMember(topicId, memberId, snapshotId, expectedVersion, expectedHash, requestId);
    }

    /** Exact submission lookup, with current ownership on every read. Does not create or reopen a task. */
    @Transactional(rollbackFor=Exception.class)
    public ContentCaptureReviewStatusVO statusForMember(long topicId, long memberId, long snapshotId,
            long expectedVersion, String expectedHash, String requestId) {
        if (topicId <= 0 || memberId <= 0 || snapshotId <= 0 || expectedVersion < 0
                || expectedHash == null || !expectedHash.matches("[0-9a-f]{64}")
                || requestId == null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) throw conflict();
        requireEnabledTransaction();
        lockOwner(topicId, memberId);
        ContentCaptureReceipt candidate = loadSnapshot(snapshotId, topicId, memberId, expectedVersion, expectedHash);
        ContentCaptureReviewBinding binding = mapper.selectRequest(topicId, memberId, requestId);
        verifyBinding(binding, candidate, memberId);
        if (!requestId.equals(binding.getRequestId())) throw conflict();
        ReviewEvidence evidence = inspect(binding.getAuditTaskId());
        AuditTask task = tasks.selectAuditTaskByIdForUpdate(binding.getAuditTaskId());
        if (task == null || task.getVersion() == null || task.getVersion() < 0) throw conflict();
        String status;
        if (AuditTask.STATUS_PENDING.equals(task.getStatus())) status = "PENDING";
        else if (AuditTask.STATUS_APPROVED.equals(task.getStatus())) status = "APPROVED";
        else if (AuditTask.STATUS_REJECTED.equals(task.getStatus())) status = "REJECTED";
        else if (AuditTask.STATUS_ESCALATED.equals(task.getStatus())) status = "ESCALATED";
        else if (AuditTask.STATUS_CANCELLED.equals(task.getStatus())) status = "CANCELLED";
        else throw conflict();
        return new ContentCaptureReviewStatusVO(topicId, snapshotId, expectedVersion, expectedHash,
                requestId, evidence.auditTaskId(), evidence.auditTaskVersion(), status, evidence.candidateReviewed());
    }

    /** Called inside existing review transaction before CAS/replay. operatorId comes from getUserId(). */
    public AuditTask lockAndValidateReview(long taskId, long operatorId, AuditReviewDTO request) {
        if (taskId<=0 || operatorId<=0 || request==null || request.getExpectedVersion()==null) throw conflict();
        requireEnabledTransaction();
        AuditTask task=tasks.selectAuditTaskByIdForUpdate(taskId);
        ContentCaptureReviewBinding binding=validateTask(task);
        int expected=Objects.equals(task.getStatus(),request.getStatus()) ? task.getVersion()-1 : task.getVersion();
        if (expected<0 || request.getExpectedVersion()!=expected) throw conflict();
        // Member IDs and platform reviewer IDs are different identity spaces. Never compare them for self-review.
        if (binding.getAuditTaskId()!=taskId) throw conflict();
        return task;
    }

    public AuditTask lockAndValidateRevert(long taskId) {
        if(taskId<=0)throw conflict();
        requireEnabledTransaction();
        AuditTask task=tasks.selectAuditTaskByIdForUpdate(taskId);
        validateTask(task);
        return task;
    }

    /** Current decision evidence. A reverted/rejected task never yields an approved observation. */
    @Transactional(rollbackFor=Exception.class)
    public ReviewEvidence inspect(long taskId) {
        if(taskId<=0)throw conflict();
        requireEnabledTransaction();
        AuditTask task=tasks.selectAuditTaskByIdForUpdate(taskId);
        ContentCaptureReviewBinding binding=validateTask(task);
        boolean reviewed=AuditTask.STATUS_APPROVED.equals(task.getStatus());
        if (reviewed) {
            AuditRecord decision=mapper.selectLatestDecision(taskId);
            if (task.getCurrentAssignee()==null || task.getCurrentAssignee()<=0 || decision==null
                    || !AuditRecord.ACTION_APPROVE.equals(decision.getAction())
                    || !task.getId().equals(decision.getTaskId())
                    || !task.getCurrentAssignee().equals(decision.getOperatorId())
                    || !task.getSnapshotJson().equals(decision.getSnapshotJson())) throw conflict();
        }
        return new ReviewEvidence(binding.getSnapshotId(),taskId,task.getVersion(),binding.getContentHash(),reviewed);
    }

    /** Package-private server contract; caller must establish current platform reviewer scope first. */
    PreviewEvidence lockPreview(long taskId, int expectedTaskVersion, long snapshotId,
            long sourceConfigVersion, String contentHash) {
        requireEnabledTransaction();
        AuditTask task=tasks.selectAuditTaskByIdForUpdate(taskId);
        if (task==null || !Long.valueOf(taskId).equals(task.getId()) || !Integer.valueOf(expectedTaskVersion).equals(task.getVersion())
                || task.getStatus()==null || task.getStatus()<0 || task.getStatus()>4)
            throw new ContentCapturePreviewService.ConflictException();
        try {
            ContentCaptureReviewBinding binding=validateTask(task);
            if (!Long.valueOf(snapshotId).equals(binding.getSnapshotId())
                    || !Long.valueOf(sourceConfigVersion).equals(binding.getSourceConfigVersion())
                    || !Objects.equals(contentHash,binding.getContentHash())) throw conflict();
            ContentCaptureReceipt candidate=loadSnapshot(snapshotId,task.getBizId(),task.getSubmitterId(),
                    sourceConfigVersion,contentHash);
            return new PreviewEvidence(task,candidate);
        } catch (ContentCaptureReviewConflictException invalidBoundSnapshot) {
            // Only the typed immutable binding failure is a conflict; infrastructure is never relabelled.
            throw new ContentCapturePreviewService.ConflictException();
        }
    }
    static final class PreviewEvidence {
        final AuditTask task;
        final ContentCaptureReceipt candidate;
        PreviewEvidence(AuditTask task, ContentCaptureReceipt candidate) { this.task=task; this.candidate=candidate; }
    }

    private ContentCaptureReviewBinding validateTask(AuditTask task) {
        if (task==null || !BIZ_TYPE.equals(task.getBizType()) || !SOURCE_TYPE.equals(task.getSourceType())
                || task.getId()==null || task.getBizId()==null || task.getSubmitterId()==null || task.getVersion()==null) throw conflict();
        lockOwner(task.getBizId(),task.getSubmitterId());
        ContentCaptureReviewBinding binding=mapper.selectTask(task.getId());
        if (binding==null || binding.getSnapshotId()==null || binding.getSourceConfigVersion()==null) throw conflict();
        ContentCaptureReceipt candidate=loadSnapshot(binding.getSnapshotId(),task.getBizId(),task.getSubmitterId(),
                binding.getSourceConfigVersion(),binding.getContentHash());
        verifyBinding(binding,candidate,task.getSubmitterId());
        if (!task.getId().equals(binding.getAuditTaskId()) || !envelope(candidate,task.getSubmitterId()).equals(task.getSnapshotJson())) throw conflict();
        return binding;
    }

    private ContentCaptureReceipt loadSnapshot(long id,long topic,long member,long version,String hash) {
        ContentCaptureSnapshot row=mapper.lockSnapshot(id);
        if (row==null || !Long.valueOf(id).equals(row.getId()) || row.getRequestId()==null || hash==null) throw conflict();
        // Only pure stored-receipt validation is classified here. Mapper/schema/transaction failures remain unavailable.
        try {
            return ContentCaptureReceipt.verify(row,topic,member,row.getRequestId(),version,hash);
        } catch (ContentCaptureReceipt.InvalidReceiptException | IllegalArgumentException invalid) {
            throw conflict();
        }
    }
    private void lockOwner(long topicId,long memberId) {
        CmsTopic topic=mapper.lockTopic(topicId);
        if (topic==null || !Long.valueOf(topicId).equals(topic.getId()) || Integer.valueOf(2).equals(topic.getDelFlag())) throw conflict();
        List<ProductRole> owners=mapper.lockContentOwners(topicId);
        if (owners==null || owners.size()!=1) throw conflict();
        ProductRole owner=owners.get(0);
        if (owner==null || !Long.valueOf(topicId).equals(owner.getTopicId()) || !ProductSlot.CONTENT.equals(owner.getSlot())
                || !Integer.valueOf(1).equals(owner.getStatus()) || !RoleEntityType.MEMBER.equals(owner.getEntityType())
                || !Long.valueOf(memberId).equals(owner.getEntityId())) throw conflict();
    }
    private static void verifyBinding(ContentCaptureReviewBinding b,ContentCaptureReceipt c,long member) {
        if (b==null || b.getId()==null || b.getId()<=0 || b.getAuditTaskId()==null || b.getAuditTaskId()<=0
                || !Long.valueOf(c.snapshotId()).equals(b.getSnapshotId()) || !Long.valueOf(c.topicId()).equals(b.getTopicId())
                || !Long.valueOf(member).equals(b.getMemberId()) || !Long.valueOf(c.draftConfigVersion()).equals(b.getSourceConfigVersion())
                || !c.contentHash().equals(b.getContentHash())) throw conflict();
    }
    static String envelope(ContentCaptureReceipt candidate,long member) {
        ObjectNode value=new ObjectMapper().createObjectNode();
        value.put("schemaVersion",1); value.put("contract",SOURCE_TYPE); value.put("snapshotId",candidate.snapshotId());
        value.put("topicId",candidate.topicId()); value.put("submitterMemberId",member);
        value.put("sourceConfigVersion",candidate.draftConfigVersion()); value.put("contentHash",candidate.contentHash());
        value.put("candidateContract","questify.content-capture-candidate.v1");
        value.put("canonicalizationVersion","questify.canonical-content-json.v1"); value.put("completeRelease",false);
        value.put("reviewScope","INCOMPLETE_CANDIDATE_ONLY");
        return CanonicalContentJson.write(value);
    }
    private void requireEnabledTransaction() {
        if (!enabled) throw new UnavailableException();
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new UnavailableException();
        if (schemaPreflight==null) throw new UnavailableException();
        try { schemaPreflight.verifyCurrentTransaction(); }
        catch (RuntimeException e) { throw new UnavailableException(e); }
    }
    private static ContentCaptureReviewConflictException conflict() {return new ContentCaptureReviewConflictException();}

    /** Stable transport classification without exposing schema or database diagnostics. */
    public static final class UnavailableException extends IllegalStateException {
        public UnavailableException() { super("Candidate review is unavailable"); }
        public UnavailableException(Throwable cause) { super("Candidate review is unavailable", cause); }
    }

    public static final class ReviewEvidence {
        private final long snapshotId,taskId; private final int taskVersion; private final String hash; private final boolean reviewed;
        private ReviewEvidence(long snapshotId,long taskId,int taskVersion,String hash,boolean reviewed) {
            this.snapshotId=snapshotId;this.taskId=taskId;this.taskVersion=taskVersion;this.hash=hash;this.reviewed=reviewed;
        }
        public long snapshotId(){return snapshotId;} public long auditTaskId(){return taskId;}
        public int auditTaskVersion(){return taskVersion;} public String contentHash(){return hash;}
        public boolean candidateReviewed(){return reviewed;}
        public boolean publicationEligible(){return false;}
        public void requirePublicationEligible(){throw new IllegalStateException("Complete dependency validation and release-scoped rights evidence are not established");}
    }
}

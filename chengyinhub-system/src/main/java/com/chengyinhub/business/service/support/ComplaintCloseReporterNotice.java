package com.chengyinhub.business.service.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Only the original complaint-close CAS winner submits; rejected input never rewrites delivery history. */
@Service
public class ComplaintCloseReporterNotice {
    public static final String SUBMITTED = "SUBMITTED";
    public static final String REJECTED = "REJECTED_INVALID_RECIPIENT";
    private static final Logger log = LoggerFactory.getLogger(ComplaintCloseReporterNotice.class);
    private final CommittedOfficialNoticeService notices;
    private final BusinessAuditEventMapper rejections;
    private final AlarmService alarm;
    private final ScheduledExecutorService executor;

    public ComplaintCloseReporterNotice(CommittedOfficialNoticeService notices, BusinessAuditEventMapper rejections,
            AlarmService alarm, @Qualifier("scheduledExecutorService") ScheduledExecutorService executor) {
        this.notices=notices; this.rejections=rejections; this.alarm=alarm; this.executor=executor;
    }

    /** A submission receipt, never evidence that either channel reached a person. */
    public record Submission(String state, Long referenceId) { }

    @Transactional(propagation=Propagation.MANDATORY, rollbackFor=Exception.class)
    public Submission submitReporter(Long complaintId, Long recipient, Long operatorId, String title, String body) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("关闭通知必须在原业务事务内登记");
        if (complaintId==null || complaintId<=0) throw new IllegalArgumentException("缺少原投诉身份");
        if (title==null || body==null) throw new IllegalArgumentException("缺少原通知内容");
        String key="complaint-close-reporter:"+complaintId;
        String hash=CanonicalDigest.sha256Hex(JSON.toJSONString(Arrays.asList(
                key,recipient,operatorId,title,body)));
        if (recipient!=null && recipient>0) {
            // Never turn a prior immutable rejection into a new recipient through an internal retry.
            if (rejections.selectByDomainActionRequestForUpdate("PLATFORM",complaintId,"COMPLAINT_CLOSE_NOTICE_REJECTED",key)!=null)
                throw new ServiceException("原关闭通知提交记录冲突，须人工核实",409);
            return new Submission(SUBMITTED,notices.enqueueOfficial(key,recipient,title,body));
        }

        String kind=recipient==null ? "MISSING" : "NON_POSITIVE";
        JSONObject before=new JSONObject(); before.put("eventKey",key); before.put("requestHash",hash);
        JSONObject after=new JSONObject(); after.put("schemaVersion",1); after.put("submission",REJECTED);
        after.put("recipientKind",kind); after.put("eventKey",key); after.put("requestHash",hash);
        BusinessAuditEvent event=new BusinessAuditEvent(); event.setDomainId(complaintId); event.setTargetId(complaintId);
        boolean knownOperator=operatorId!=null && operatorId>0;
        event.setActorType(knownOperator ? BusinessAuditEvent.ACTOR_TYPE_SYS_USER : BusinessAuditEvent.ACTOR_TYPE_UNKNOWN);
        event.setActorMemberId(knownOperator ? operatorId : 0L);
        event.setActorRoleCode(knownOperator ? "SYS_USER" : "UNKNOWN");
        event.setRequestId(key); event.setBeforeJson(before.toJSONString()); event.setAfterJson(after.toJSONString());
        rejections.insertComplaintCloseNoticeRejectionIgnore(event);
        BusinessAuditEvent stored=rejections.selectByDomainActionRequestForUpdate("PLATFORM",complaintId,"COMPLAINT_CLOSE_NOTICE_REJECTED",key);
        verifyRejection(stored,event,key,hash,kind);
        Long auditId=stored.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { alert(complaintId,auditId); }
        });
        // This is a known input rejection, not UNKNOWN and not a new financial veto.
        // An original accepted/UNKNOWN notice, if present, is left completely untouched.
        return new Submission(REJECTED,auditId);
    }

    private static void verifyRejection(BusinessAuditEvent stored,BusinessAuditEvent requested,
            String key,String hash,String kind) {
        if (stored==null || stored.getId()==null || stored.getId()<=0)
            throw new IllegalStateException("不可投递通知审计未持久登记");
        JSONObject before=JSON.parseObject(stored.getBeforeJson()),after=JSON.parseObject(stored.getAfterJson());
        if (before==null || after==null || !key.equals(before.getString("eventKey"))
                || !hash.equals(before.getString("requestHash")) || !key.equals(after.getString("eventKey"))
                || !hash.equals(after.getString("requestHash")) || !REJECTED.equals(after.getString("submission"))
                || !Integer.valueOf(1).equals(after.getInteger("schemaVersion"))
                || !kind.equals(after.getString("recipientKind"))
                || !Objects.equals(requested.getActorType(),stored.getActorType())
                || !Objects.equals(requested.getActorMemberId(),stored.getActorMemberId()))
            throw new ServiceException("原关闭通知拒绝记录冲突，须人工核实",409);
    }

    private void alert(Long complaintId,Long auditId) {
        try {
            executor.schedule(() -> {
                try { alarm.alarm("投诉关闭通知不可投递","complaintId="+complaintId+" auditId="+auditId+" reason=INVALID_RECIPIENT"); }
                catch (Throwable failure) { alertFailure(auditId,failure); }
            },10,TimeUnit.MILLISECONDS);
        } catch (Throwable rejected) { alertFailure(auditId,rejected); }
    }

    private static void alertFailure(Long auditId,Throwable failure) {
        log.warn("Complaint notice rejection is durable; alert requires attention auditId={} type={}",
                auditId,failure.getClass().getSimpleName());
    }
}

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Typed application events: delivery uses the original permanent round; unknown legacy history stays explicit. */
@Service
public class ChapterApplicationInviteNotice {
    private static final Logger log=LoggerFactory.getLogger(ChapterApplicationInviteNotice.class);
    public enum Outcome {
        WITHDRAW("withdraw"), REVIEW("review"), INVITE_DECISION("invite-decision"), INVITE_REVOKE("invite-revoke");
        public final String code;
        Outcome(String code) { this.code=code; }
    }
    private final CommittedOfficialNoticeService notices;
    @Autowired private BusinessAuditEventMapper audit;
    @Autowired private AlarmService alarm;
    @Autowired @Qualifier("scheduledExecutorService") private ScheduledExecutorService executor;
    public ChapterApplicationInviteNotice(CommittedOfficialNoticeService notices) { this.notices=notices; }

    @Transactional(propagation=Propagation.MANDATORY, rollbackFor=Exception.class)
    public Long submit(Long topicId, Long applicationId, Long round, Long recipient) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("邀请通知必须与原申请事务一起登记");
        if (topicId==null || topicId<=0 || applicationId==null || applicationId<=0 || round==null || round<=0)
            throw new IllegalStateException("缺少可核实的原邀请轮次");
        // Domain scope + original application + persisted attempt + event; never a clock or a random request key.
        String key="chapter-app:"+topicId+":"+applicationId+":"+round+":invite";
        return notices.enqueueOfficial(key,recipient,"章节承接邀请","请在主题承接进度中接受或拒绝邀请");
    }

    /** Only the original successful decision/withdrawal CAS calls this method, in its original transaction. */
    @Transactional(propagation=Propagation.MANDATORY, rollbackFor=Exception.class)
    public void submitOutcome(Long topicId,Long applicationId,Long round,Outcome event,Long recipient,String title,String body) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("申请通知必须与原状态事务一起登记");
        if (applicationId==null || applicationId<=0 || event==null || title==null || body==null)
            throw new IllegalArgumentException("缺少原申请通知事实");
        String reason=round==null ? "UNVERIFIABLE_LEGACY_ROUND"
                : round<=0 ? "UNVERIFIABLE_ROUND"
                : topicId==null || topicId<=0 ? "UNVERIFIABLE_SCOPE"
                : recipient==null || recipient<=0 ? "REJECTED_INVALID_RECIPIENT" : null;
        if (reason==null) {
            notices.enqueueOfficial("chapter-app:"+topicId+":"+applicationId+":"+round+":"+event.code,
                    recipient,title,body);
            return;
        }
        // No invented round, local message or WX attempt for unprovable legacy input.
        // This append records only an unsubmitted notification, not a domain/delivery terminal state.
        String descriptor=JSON.toJSONString(Arrays.asList(topicId,applicationId,round,event.code));
        String requestId=CanonicalDigest.sha256Hex(descriptor);
        String hash=CanonicalDigest.sha256Hex(JSON.toJSONString(Arrays.asList(descriptor,recipient,title,body)));
        JSONObject before=new JSONObject(); before.put("requestHash",hash);
        JSONObject after=new JSONObject(); after.put("schemaVersion",1); after.put("submission",reason);
        boolean knownRound=round!=null && round>0;
        after.put("event",event.code); after.put("requestHash",hash); after.put("roundKnown",knownRound);
        BusinessAuditEvent requested=new BusinessAuditEvent(); requested.setDomainType("PLATFORM");
        requested.setDomainId(applicationId); requested.setOwnerMemberId(0L);
        requested.setActorType(BusinessAuditEvent.ACTOR_TYPE_UNKNOWN); requested.setActorMemberId(0L);
        requested.setActorRoleCode("SYSTEM_NOTICE"); requested.setAction("CHAPTER_APP_NOTICE_UNSUBMITTED");
        requested.setTargetType("MERCHANT_CHAPTER_APPLICATION"); requested.setTargetId(applicationId);
        requested.setCreateTime(new java.util.Date());
        requested.setRequestId(requestId); requested.setReason(reason);
        requested.setBeforeJson(before.toJSONString()); requested.setAfterJson(after.toJSONString());
        boolean inserted=true;
        try {
            if (audit.insertBusinessAuditEvent(requested)!=1)
                throw new IllegalStateException("申请通知不可核实事实未持久登记");
        } catch (DuplicateKeyException duplicate) {
            // Only an exact immutable receipt below can turn a duplicate INSERT into a replay.
            // Every other SQL failure propagates through the original business transaction.
            inserted=false;
        }
        BusinessAuditEvent stored=audit.selectByDomainActionRequestForUpdate("PLATFORM",applicationId,"CHAPTER_APP_NOTICE_UNSUBMITTED",requestId);
        verifyUnsubmitted(stored,applicationId,requestId,hash,reason,event,knownRound);
        if (inserted) {
            Long auditId=stored.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { alert(applicationId,auditId,reason); }
            });
        }
    }

    private static void verifyUnsubmitted(BusinessAuditEvent stored,Long applicationId,String requestId,
            String hash,String reason,Outcome event,boolean knownRound) {
        if (stored==null || stored.getId()==null || stored.getId()<=0)
            throw new IllegalStateException("申请通知不可核实事实未持久登记");
        JSONObject before=JSON.parseObject(stored.getBeforeJson()),after=JSON.parseObject(stored.getAfterJson());
        if (!"PLATFORM".equals(stored.getDomainType()) || !Objects.equals(applicationId,stored.getDomainId())
                || !Long.valueOf(0).equals(stored.getOwnerMemberId()) || !"UNKNOWN".equals(stored.getActorType())
                || !Long.valueOf(0).equals(stored.getActorMemberId()) || !"SYSTEM_NOTICE".equals(stored.getActorRoleCode())
                || !"CHAPTER_APP_NOTICE_UNSUBMITTED".equals(stored.getAction())
                || !"MERCHANT_CHAPTER_APPLICATION".equals(stored.getTargetType())
                || !Objects.equals(applicationId,stored.getTargetId()) || !requestId.equals(stored.getRequestId())
                || !reason.equals(stored.getReason()) || before==null || after==null
                || !hash.equals(before.getString("requestHash")) || !hash.equals(after.getString("requestHash"))
                || !reason.equals(after.getString("submission")) || !event.code.equals(after.getString("event"))
                || !Boolean.valueOf(knownRound).equals(after.getBoolean("roundKnown"))
                || !Integer.valueOf(1).equals(after.getInteger("schemaVersion")))
            throw new ServiceException("原申请通知事实冲突，须人工核实",409);
    }

    private void alert(Long applicationId,Long auditId,String reason) {
        try {
            executor.schedule(() -> {
                try { alarm.alarm("申请通知未提交","applicationId="+applicationId+" auditId="+auditId+" reason="+reason); }
                catch (Throwable failure) { log.warn("Application notice alert requires verification auditId={} type={}",auditId,failure.getClass().getSimpleName()); }
            },10,TimeUnit.MILLISECONDS);
        } catch (Throwable failure) { log.warn("Application notice diagnostic is durable auditId={} type={}",auditId,failure.getClass().getSimpleName()); }
    }
}

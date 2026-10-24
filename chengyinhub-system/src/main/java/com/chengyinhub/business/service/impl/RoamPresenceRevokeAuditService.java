package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.domain.RoamPresenceRevokeIntent;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.mapper.RoamPresenceRevokeIntentMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.common.utils.LogCorrelation;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Date;
import java.util.UUID;

@Service
public class RoamPresenceRevokeAuditService {
    private static final Logger log = LoggerFactory.getLogger(RoamPresenceRevokeAuditService.class);
    private final RoamPresenceRevokeIntentMapper intents;
    private final ConsentRecordMapper consents;
    private final UmsMemberMapper members;
    private final AlarmService alarm;
    private final TransactionTemplate transaction;

    public RoamPresenceRevokeAuditService(RoamPresenceRevokeIntentMapper intents, ConsentRecordMapper consents,
            UmsMemberMapper members, AlarmService alarm, PlatformTransactionManager manager) {
        this.intents = intents; this.consents = consents; this.members = members; this.alarm = alarm;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Caller holds the sharing transaction; both AGREE and close acquire this owner lock first. */
    public void lockPreference(Long memberId) {
        if (members.lockMemberById(memberId) == null) throw new IllegalStateException("账号状态已变化");
    }

    /** No consent_record writes here: audit-table failure cannot roll back sharing closed in this transaction. */
    public void remember(Long memberId, String requestId, Date occurredAt) {
        Long previous = intents.selectPreviousConsentIdForUpdate(memberId);
        RoamPresenceRevokeIntent row = new RoamPresenceRevokeIntent();
        row.setMemberId(memberId); row.setRequestId(requestId); row.setOccurredAt(occurredAt);
        row.setEventType("REVOKE"); row.setState(0);
        row.setPreviousConsentId(previous == null ? 0L : previous);
        if (intents.insertIntent(row) != 1) throw new IllegalStateException("位置分享关闭未保存，请重试");
    }

    public void rememberAgreement(Long memberId, String requestId, Date occurredAt) {
        ConsentRecord record = consents.selectByRequestId(memberId, requestId);
        if (record == null || !"AGREE".equals(record.getEventType()) || !"roam_presence".equals(record.getScene())
                || !"privacy_policy".equals(record.getDocType()) || !"roam-presence-v1".equals(record.getDocVersion()))
            throw new IllegalStateException("位置分享同意未保存，请重试");
        RoamPresenceRevokeIntent row = new RoamPresenceRevokeIntent();
        row.setMemberId(memberId); row.setRequestId(requestId); row.setOccurredAt(occurredAt);
        row.setPreviousConsentId(record.getId()); row.setEventType("AGREE"); row.setState(2);
        if (intents.insertIntent(row) != 1) throw new IllegalStateException("位置分享同意事实未保存，请重试");
    }

    public void retryDue() {
        for (String requestId : intents.selectDue(50)) deliver(requestId);
    }

    public boolean deliver(String requestId) {
        // Durable business command, not the HTTP request ID or the lease owner capability.
        try (LogCorrelation.Scope ignored = LogCorrelation.with(Map.of(
                "commandId", LogCorrelation.safe("commandId", requestId), "event", "roam.presence.audit"))) {
            log.info("event=roam.presence.audit.attempt commandId={} jobRunId={}",
                    LogCorrelation.value("commandId"), LogCorrelation.value(LogCorrelation.JOB_RUN_ID));
            boolean delivered = deliverAudit(requestId);
            log.info("event=roam.presence.audit.result commandId={} jobRunId={} observedResult={}",
                    LogCorrelation.value("commandId"), LogCorrelation.value(LogCorrelation.JOB_RUN_ID),
                    delivered ? "DELIVERED" : "NOT_CONFIRMED");
            return delivered;
        }
    }

    private boolean deliverAudit(String requestId) {
        String owner = UUID.randomUUID().toString();
        RoamPresenceRevokeIntent row;
        java.util.concurrent.atomic.AtomicBoolean escalated = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            row = transaction.execute(status -> {
                if (intents.claim(requestId, owner) != 1) {
                    escalated.set(intents.escalateExpired(requestId) == 1);
                    return null;
                }
                return intents.selectByRequestId(requestId);
            });
            if (escalated.get()) reportFailure(requestId);
            if (row == null) return false;
            return Boolean.TRUE.equals(transaction.execute(status -> {
                ConsentRecord existing = consents.selectByRequestId(row.getMemberId(), requestId);
                if (existing == null) {
                    ConsentRecord record = new ConsentRecord();
                    record.setMemberId(row.getMemberId()); record.setDocType("privacy_policy");
                    record.setDocVersion("roam-presence-v1"); record.setScene("roam_presence");
                    record.setEventType("REVOKE"); record.setRequestId(requestId); record.setIpAddress("");
                    record.setOccurredAt(row.getOccurredAt()); record.setCreateTime(new Date());
                    if (consents.insertConsentRecord(record) != 1) throw new IllegalStateException("撤回记录未保存");
                    existing = consents.selectByRequestId(row.getMemberId(), requestId);
                }
                if (!matches(existing, row) || intents.complete(requestId, owner) != 1)
                    throw new IllegalStateException("撤回记录状态已变化");
                return true;
            }));
        } catch (RuntimeException failure) {
            log.warn("位置分享关闭审计待恢复 type={}", failure.getClass().getSimpleName());
            try { transaction.execute(status -> intents.fail(requestId, owner)); }
            catch (RuntimeException retryFailure) { log.warn("位置分享审计退避未保存 type={}", retryFailure.getClass().getSimpleName()); }
            reportFailure(requestId);
            return false;
        }
    }

    private boolean matches(ConsentRecord record, RoamPresenceRevokeIntent row) {
        return record != null && row.getRequestId().equals(record.getRequestId())
                && row.getMemberId().equals(record.getMemberId()) && "privacy_policy".equals(record.getDocType())
                && "roam_presence".equals(record.getScene()) && "roam-presence-v1".equals(record.getDocVersion())
                && "REVOKE".equals(record.getEventType());
    }

    private void reportFailure(String requestId) {
        try { alarm.alarm("位置分享撤回记录失败", "关闭事实已保存，撤回审计需恢复 commandId="
                + LogCorrelation.safe("commandId", requestId) + " jobRunId=" + LogCorrelation.value(LogCorrelation.JOB_RUN_ID)); }
        catch (RuntimeException failure) { log.warn("位置分享审计告警未送达 type={}", failure.getClass().getSimpleName()); }
    }
}

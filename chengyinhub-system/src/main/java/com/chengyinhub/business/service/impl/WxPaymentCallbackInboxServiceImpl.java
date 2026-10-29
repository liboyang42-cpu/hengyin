package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.observability.CriticalPathCode;
import com.chengyinhub.common.observability.CriticalPathEvents;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.chengyinhub.business.domain.WxPaymentCallbackClaim;
import com.chengyinhub.business.domain.WxPaymentCallbackInbox;
import com.chengyinhub.business.mapper.WxPaymentCallbackInboxMapper;
import com.chengyinhub.business.service.IWxPaymentCallbackInboxService;
import com.chengyinhub.common.utils.StringUtils;

/**
 * 支付/退款/转账回调的落库去重与 fencing 权威实现(表 wx_payment_callback_inbox)。
 *
 * <p>只负责「同一条回调不重复处理」这一层:notifyId 只以 SHA-256 落库,原值不落盘;
 * 业务状态推进由调用方通过 {@link Mutation} 传入,在目标行 FOR UPDATE 的同一事务内完成。</p>
 *
 * <p>状态语义与内容回调 inbox 一致:
 * DONE 的重复投递返回 true(幂等成功,消费方应 ACK);IGNORED_STALE 表示事件已过期、
 * 被有意终态化,同样返回 true 让微信停止重投;BUSY(别的 worker 持租约)与 MISSING(未登记)
 * 返回 false,由调用方回 FAIL 让微信按重试策略稍后重投。</p>
 */
@Service
public class WxPaymentCallbackInboxServiceImpl implements IWxPaymentCallbackInboxService
{
    private static final Logger log = LoggerFactory.getLogger(WxPaymentCallbackInboxServiceImpl.class);

    /** event_type 列 VARCHAR(32);超长按列上限截断(纯信息字段,不影响幂等)。 */
    private static final int MAX_EVENT_TYPE_LENGTH = 32;

    // Bound to this bean and the actual transaction; not a process-wide or account/thread cache.
    private final Object transactionWorkKey = new Object();
    private record InboxIdentity(Long id, Integer bizType, Long bizId, String eventType, String owner, Long fence) { }
    private static final class TransactionWork {
        final java.util.Map<String, Invocation> notifications = new java.util.HashMap<>();
    }
    private static final class Invocation {
        final com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference;
        InboxIdentity identity;
        boolean finished;
        boolean completed;
        Invocation(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference) { this.reference=reference; }
    }

    @Autowired
    private WxPaymentCallbackInboxMapper mapper;
    @Autowired
    private WxPaymentCallbackClaimService claimService;
    @Autowired
    private WxPaymentCallbackMutationService mutationService;
    @Autowired
    private AlarmService alarmService;
    @Autowired private WechatVerifiedPaymentFactService verifiedFacts;

    @Override
    @Transactional(noRollbackFor = IdentityConflictException.class)
    public boolean register(String notifyId, int bizType, Long bizId, String eventType)
    {
        if (StringUtils.isEmpty(notifyId) || bizId == null || bizId <= 0 || bizType <= 0)
        {
            return false;
        }
        WxPaymentCallbackInbox candidate = new WxPaymentCallbackInbox();
        candidate.setNotifyHash(sha256Hex(notifyId));
        candidate.setBizType(bizType);
        candidate.setBizId(bizId);
        candidate.setEventType(truncateEventType(eventType));
        candidate.setState(WxPaymentCallbackInbox.STATE_WAITING);
        if (mapper.insertIgnore(candidate) == 1)
        {
            return true;
        }
        // 重复投递只认同一笔业务;同 hash 换了业务身份是冲突,拒收并告警,不能当作重复成功。
        WxPaymentCallbackInbox original = mapper.selectByNotifyHash(candidate.getNotifyHash());
        if (original == null || !Objects.equals(original.getBizType(), candidate.getBizType())
                || !Objects.equals(original.getBizId(), candidate.getBizId())
                || !Objects.equals(original.getEventType(), candidate.getEventType()))
        {
            // 只记 hash 前缀与业务 id,不记 notifyId 原文。
            String detail = "hash=" + candidate.getNotifyHash().substring(0, 12)
                    + " bizType=" + bizType + " bizId=" + bizId
                    + " original=" + (original == null ? "missing"
                            : original.getBizType() + "/" + original.getBizId())
                    + " result=FAIL";
            log.error("[支付回调] inbox 业务身份冲突 {}", detail);
            alarmService.alarmThrottled("payment.callback.inbox_identity_conflict",
                    "支付回调幂等键业务身份冲突", detail);
            throw new IdentityConflictException("支付回调幂等键对应的原业务身份冲突");
        }
        return false;
    }

    @Override
    @Transactional(noRollbackFor = IdentityConflictException.class)
    public boolean registerVerified(String notifyId, int bizType, Long bizId, String eventType,
            com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference) {
        var fact = verifiedFacts.requireVerified(reference).facts();
        int expectedType = fact.kind() == com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.PAYMENT
                ? WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT : WxPaymentCallbackInbox.BIZ_TYPE_REFUND;
        if (StringUtils.isEmpty(notifyId) || bizId == null || bizId <= 0 || bizType != expectedType
                || !"SUCCESS".equals(eventType)) throw new IdentityConflictException("Verified success inbox identity invalid");
        boolean created = register(notifyId,bizType,bizId,eventType);
        WxPaymentCallbackInbox inbox = mapper.selectForUpdate(sha256Hex(notifyId));
        if (inbox == null || inbox.getId() == null || !Objects.equals(inbox.getBizType(),bizType)
                || !Objects.equals(inbox.getBizId(),bizId) || !Objects.equals(inbox.getEventType(),eventType)) {
            throw new IdentityConflictException("Verified inbox durable readback conflict");
        }
        try {
            verifiedFacts.bindNewInbox(inbox.getId(),sha256Hex(notifyId),reference,created);
        } catch (WechatVerifiedPaymentFactService.ConflictException rejected) {
            verifiedFacts.recordRejectedBinding(sha256Hex(notifyId),reference,null);
            throw new IdentityConflictException("Verified inbox fact binding conflict");
        }
        return created;
    }

    @Override
    public boolean processVerified(String notifyId,
            com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference, Mutation mutation) {
        if (StringUtils.isEmpty(notifyId) || mutation == null) return false;
        // Avoid the caller's old RR view and do not hold an inbox lock before the independent claim.
        if (!verifiedFacts.hasInboxLinkForNotify(sha256Hex(notifyId),reference)) return false;
        return processInTransaction(notifyId,new Mutation() {
            @Override public boolean isCurrent(WxPaymentCallbackInbox lockedInbox) {
                // Executed by the original mutation service after inbox and business target locks,
                // in the same transaction that owns the business mutation and DONE.
                if (lockedInbox.getId()==null || !verifiedFacts.hasInboxLink(lockedInbox.getId(),sha256Hex(notifyId),reference)) {
                    throw new IdentityConflictException("Verified inbox reference changed inside mutation transaction");
                }
                return mutation.isCurrent(lockedInbox);
            }
            @Override public boolean apply(WxPaymentCallbackInbox lockedInbox) { return mutation.apply(lockedInbox); }
        },reference);
    }

    @Override
    public boolean isRegistered(String notifyId)
    {
        return StringUtils.isNotEmpty(notifyId) && mapper.selectByNotifyHash(sha256Hex(notifyId)) != null;
    }

    @Override
    public boolean process(String notifyId, Mutation mutation)
    {
        return processInTransaction(notifyId,mutation,null);
    }

    private boolean processInTransaction(String notifyId, Mutation mutation,
            com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference)
    {
        if (StringUtils.isEmpty(notifyId) || mutation == null)
        {
            return false;
        }
        // A participating mutation can retain its inbox X lock until the outer transaction ends.
        // Refuse an unmanaged transaction before claiming: it has no safe completion callback.
        boolean outerTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        if (outerTransaction && !TransactionSynchronizationManager.isSynchronizationActive())
        {
            log.error("[支付回调] 外层事务未提供完成同步，未领取回调");
            return false;
        }
        String notifyHash = sha256Hex(notifyId);
        TransactionWork work = outerTransaction ? transactionWork() : null;
        Invocation invocation = new Invocation(reference);
        if (work != null)
        {
            Invocation previous = work.notifications.putIfAbsent(notifyHash, invocation);
            if (previous != null) return repeatedInTransaction(notifyHash,reference,previous);
        }
        WxPaymentCallbackClaim claim = claimService.claim(notifyHash);
        if (claim.getState() == WxPaymentCallbackClaim.State.DONE
                || claim.getState() == WxPaymentCallbackClaim.State.STALE)
        {
            if (work != null) invocation.identity=identity(mapper.selectForUpdate(notifyHash));
            invocation.finished=true;
            invocation.completed=true;
            return true;
        }
        if (claim.getState() != WxPaymentCallbackClaim.State.ACQUIRED)
        {
            invocation.finished=true;
            return false;
        }
        final boolean[] completed = {false};
        if (outerTransaction)
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCompletion(int status)
                {
                    // A successful participating mutation/DONE also disappears on an outer rollback.
                    if (!completed[0] || status != TransactionSynchronization.STATUS_COMMITTED)
                    {
                        releaseClaim(claim);
                    }
                }
            });
        }
        try
        {
            completed[0] = mutationService.apply(claim, new Mutation() {
                @Override public boolean isCurrent(WxPaymentCallbackInbox row) {
                    invocation.identity=identity(row);
                    return mutation.isCurrent(row);
                }
                @Override public boolean apply(WxPaymentCallbackInbox row) { return mutation.apply(row); }
            });
            if (!completed[0] && !outerTransaction)
            {
                releaseClaim(claim);
            }
            return completed[0];
        }
        catch (RuntimeException e)
        {
            CriticalPathEvents.failure(CriticalPathCode.CP_PAYMENT_INBOX_FAILED, "process", e);
            if (!outerTransaction)
            {
                releaseClaim(claim);
            }
            // An outer owner still holds its locks here. Never suspend it to release the same row.
            log.error("[支付回调] inbox 事务失败，释放遵循原事务完成及 owner/fence；未记录回调内容");
            return false;
        }
        finally
        {
            invocation.completed=completed[0];
            invocation.finished=true;
        }
    }

    private TransactionWork transactionWork()
    {
        Object existing=TransactionSynchronizationManager.getResource(transactionWorkKey);
        if (existing instanceof TransactionWork work) return work;
        TransactionWork work=new TransactionWork();
        TransactionSynchronizationManager.bindResource(transactionWorkKey,work);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void suspend() {
                if (TransactionSynchronizationManager.unbindResource(transactionWorkKey)!=work)
                    throw new IllegalStateException("Payment inbox transaction scope changed");
            }
            @Override public void resume() {
                TransactionSynchronizationManager.bindResource(transactionWorkKey,work);
            }
            @Override public void afterCompletion(int status) {
                if (TransactionSynchronizationManager.getResource(transactionWorkKey)==work)
                    TransactionSynchronizationManager.unbindResource(transactionWorkKey);
                work.notifications.clear();
            }
        });
        return work;
    }

    private boolean repeatedInTransaction(String notifyHash,
            com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference, Invocation previous)
    {
        // A legacy/unbound call cannot prove the immutable economic identity for an uncommitted replay.
        if (reference==null || !reference.equals(previous.reference) || !previous.finished || !previous.completed
                || previous.identity==null) return false;
        WxPaymentCallbackInbox current=mapper.selectForUpdate(notifyHash); // same transaction, never a new claim
        if (!previous.identity.equals(identity(current))
                || !verifiedFacts.hasInboxLink(current.getId(),notifyHash,reference)) return false;
        return Integer.valueOf(WxPaymentCallbackInbox.STATE_DONE).equals(current.getState())
                || Integer.valueOf(WxPaymentCallbackInbox.STATE_IGNORED_STALE).equals(current.getState());
    }

    private static InboxIdentity identity(WxPaymentCallbackInbox row)
    {
        return row==null ? null : new InboxIdentity(row.getId(),row.getBizType(),row.getBizId(),
                row.getEventType(),row.getOwnerToken(),row.getFenceToken());
    }

    private void releaseClaim(WxPaymentCallbackClaim claim)
    {
        try
        {
            claimService.release(claim.getNotifyHash(), claim.getOwnerToken(), claim.getFenceToken());
        }
        catch (RuntimeException unavailable)
        {
            // Keep the original claim/lease for recovery; never invent DONE or release another owner's fence.
            CriticalPathEvents.failure(CriticalPathCode.CP_PAYMENT_INBOX_FAILED, "release", unavailable);
            log.error("[支付回调] 原 owner/fence 释放暂未确认，保留原回调身份等待恢复");
        }
    }

    public static String sha256Hex(String value)
    {
        try
        {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash)
            {
                String part = Integer.toHexString(b & 0xff);
                if (part.length() == 1) out.append('0');
                out.append(part);
            }
            return out.toString();
        }
        catch (Exception e)
        {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String truncateEventType(String eventType)
    {
        if (StringUtils.isEmpty(eventType))
        {
            return null;
        }
        String trimmed = eventType.trim();
        return trimmed.length() <= MAX_EVENT_TYPE_LENGTH
                ? trimmed : trimmed.substring(0, MAX_EVENT_TYPE_LENGTH);
    }
}

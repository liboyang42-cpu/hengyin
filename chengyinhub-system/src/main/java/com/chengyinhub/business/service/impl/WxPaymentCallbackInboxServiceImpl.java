package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.observability.CriticalPathCode;
import com.chengyinhub.common.observability.CriticalPathEvents;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Autowired
    private WxPaymentCallbackInboxMapper mapper;
    @Autowired
    private WxPaymentCallbackClaimService claimService;
    @Autowired
    private WxPaymentCallbackMutationService mutationService;

    @Override
    @Transactional
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
        return mapper.insertIgnore(candidate) == 1;
    }

    @Override
    public boolean isRegistered(String notifyId)
    {
        return StringUtils.isNotEmpty(notifyId) && mapper.selectByNotifyHash(sha256Hex(notifyId)) != null;
    }

    @Override
    public boolean process(String notifyId, Mutation mutation)
    {
        if (StringUtils.isEmpty(notifyId) || mutation == null)
        {
            return false;
        }
        String notifyHash = sha256Hex(notifyId);
        WxPaymentCallbackClaim claim = claimService.claim(notifyHash);
        if (claim.getState() == WxPaymentCallbackClaim.State.DONE
                || claim.getState() == WxPaymentCallbackClaim.State.STALE)
        {
            return true;
        }
        if (claim.getState() != WxPaymentCallbackClaim.State.ACQUIRED)
        {
            return false;
        }
        try
        {
            boolean completed = mutationService.apply(claim, mutation);
            if (!completed)
            {
                claimService.release(notifyHash, claim.getOwnerToken(), claim.getFenceToken());
            }
            return completed;
        }
        catch (RuntimeException e)
        {
            CriticalPathEvents.failure(CriticalPathCode.CP_PAYMENT_INBOX_FAILED, "process", e);
            claimService.release(notifyHash, claim.getOwnerToken(), claim.getFenceToken());
            // 不记录 notifyId 原文与回调体,避免微信密文/单号进入日志。
            log.error("[支付回调] inbox 事务失败,已按 owner/fence 释放;未记录 notifyId 或回调内容");
            return false;
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

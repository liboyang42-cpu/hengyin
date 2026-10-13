package com.chengyinhub.business.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.WxContentCallbackClaim;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;
import com.chengyinhub.common.utils.StringUtils;

@Service
public class WxContentCallbackInboxServiceImpl implements IWxContentCallbackInboxService
{
    private static final Logger log = LoggerFactory.getLogger(WxContentCallbackInboxServiceImpl.class);

    @Autowired
    private WxContentCallbackInboxMapper mapper;
    @Autowired
    private WxContentCallbackClaimService claimService;
    @Autowired
    private WxContentCallbackMutationService mutationService;

    @Override
    @Transactional
    public boolean register(String traceId, int bizType, Long bizId, String mediaUrl)
    {
        if (StringUtils.isEmpty(traceId) || bizId == null || bizId <= 0 || bizType <= 0)
        {
            return false;
        }
        WxContentCallbackInbox candidate = new WxContentCallbackInbox();
        candidate.setTraceHash(sha256Hex(traceId));
        candidate.setBizType(bizType);
        candidate.setBizId(bizId);
        candidate.setMediaHash(StringUtils.isEmpty(mediaUrl) ? null : sha256Hex(mediaUrl.trim()));
        candidate.setState(WxContentCallbackInbox.STATE_WAITING);
        if (mapper.insertIgnore(candidate) == 1)
        {
            return true;
        }
        WxContentCallbackInbox existing = mapper.selectByTraceHash(candidate.getTraceHash());
        return existing != null
                && Integer.valueOf(bizType).equals(existing.getBizType())
                && bizId.equals(existing.getBizId())
                && same(candidate.getMediaHash(), existing.getMediaHash());
    }

    @Override
    public boolean isRegistered(String traceId)
    {
        return StringUtils.isNotEmpty(traceId) && mapper.selectByTraceHash(sha256Hex(traceId)) != null;
    }

    @Override
    public boolean process(String traceId, boolean risky, Mutation mutation)
    {
        if (StringUtils.isEmpty(traceId) || mutation == null)
        {
            return false;
        }
        String traceHash = sha256Hex(traceId);
        WxContentCallbackClaim claim = claimService.claim(traceHash, risky);
        if (claim.getState() == WxContentCallbackClaim.State.DONE)
        {
            return true;
        }
        if (claim.getState() != WxContentCallbackClaim.State.ACQUIRED)
        {
            return false;
        }
        try
        {
            boolean completed = mutationService.apply(claim, risky, mutation);
            if (!completed)
            {
                claimService.release(traceHash, claim.getOwnerToken(), claim.getFenceToken());
            }
            return completed;
        }
        catch (RuntimeException e)
        {
            claimService.release(traceHash, claim.getOwnerToken(), claim.getFenceToken());
            log.error("[内容安全回调] inbox 事务失败，已按 owner/fence 释放；未记录 trace 或媒体内容");
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

    private static boolean same(Object left, Object right)
    {
        return left == null ? right == null : left.equals(right);
    }
}

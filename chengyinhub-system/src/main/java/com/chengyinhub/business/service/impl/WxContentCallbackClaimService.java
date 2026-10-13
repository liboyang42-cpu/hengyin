package com.chengyinhub.business.service.impl;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.WxContentCallbackClaim;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;

/** 领取单独提交，使 owner/fence/lease 对后续 worker 可见。 */
@Service
public class WxContentCallbackClaimService
{
    @Autowired
    private WxContentCallbackInboxMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WxContentCallbackClaim claim(String traceHash, boolean risky)
    {
        WxContentCallbackInbox row = mapper.selectForUpdate(traceHash);
        if (row == null)
        {
            return WxContentCallbackClaim.of(WxContentCallbackClaim.State.MISSING, traceHash);
        }
        int verdict = risky ? 1 : 0;
        if (Integer.valueOf(WxContentCallbackInbox.STATE_DONE).equals(row.getState())
                || Integer.valueOf(WxContentCallbackInbox.STATE_IGNORED_STALE).equals(row.getState())
                || Integer.valueOf(WxContentCallbackInbox.STATE_MANUAL_REVIEW).equals(row.getState()))
        {
            return row.getVerdict() != null && row.getVerdict().intValue() == verdict
                    ? WxContentCallbackClaim.done(traceHash)
                    : WxContentCallbackClaim.of(WxContentCallbackClaim.State.REJECTED, traceHash);
        }
        if (row.getVerdict() != null && row.getVerdict().intValue() != verdict)
        {
            // 同一 trace 的微信判定应稳定；冲突结果不覆盖已有事实，更不能让 pass 复活 risky。
            return WxContentCallbackClaim.of(WxContentCallbackClaim.State.REJECTED, traceHash);
        }
        String owner = UUID.randomUUID().toString().replace("-", "");
        if (mapper.claim(traceHash, owner, verdict) != 1)
        {
            return WxContentCallbackClaim.of(WxContentCallbackClaim.State.BUSY, traceHash);
        }
        WxContentCallbackInbox claimed = mapper.selectForUpdate(traceHash);
        if (claimed == null || claimed.getOwnerToken() == null
                || !owner.equalsIgnoreCase(claimed.getOwnerToken()) || claimed.getFenceToken() == null)
        {
            throw new IllegalStateException("callback inbox claim was not observable");
        }
        return WxContentCallbackClaim.acquired(traceHash, owner, claimed.getFenceToken());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean release(String traceHash, String ownerToken, long fenceToken)
    {
        return mapper.release(traceHash, ownerToken, fenceToken) == 1;
    }
}

package com.chengyinhub.business.service.impl;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.WxPaymentCallbackClaim;
import com.chengyinhub.business.domain.WxPaymentCallbackInbox;
import com.chengyinhub.business.mapper.WxPaymentCallbackInboxMapper;

/** 领取单独提交,使 owner/fence/lease 对后续 worker 可见。 */
@Service
public class WxPaymentCallbackClaimService
{
    @Autowired
    private WxPaymentCallbackInboxMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WxPaymentCallbackClaim claim(String notifyHash)
    {
        WxPaymentCallbackInbox row = mapper.selectForUpdate(notifyHash);
        if (row == null)
        {
            return WxPaymentCallbackClaim.of(WxPaymentCallbackClaim.State.MISSING, notifyHash);
        }
        if (Integer.valueOf(WxPaymentCallbackInbox.STATE_DONE).equals(row.getState()))
        {
            return WxPaymentCallbackClaim.done(notifyHash);
        }
        if (Integer.valueOf(WxPaymentCallbackInbox.STATE_IGNORED_STALE).equals(row.getState()))
        {
            return WxPaymentCallbackClaim.stale(notifyHash);
        }
        String owner = UUID.randomUUID().toString().replace("-", "");
        if (mapper.claim(notifyHash, owner) != 1)
        {
            return WxPaymentCallbackClaim.of(WxPaymentCallbackClaim.State.BUSY, notifyHash);
        }
        WxPaymentCallbackInbox claimed = mapper.selectForUpdate(notifyHash);
        if (claimed == null || claimed.getOwnerToken() == null
                || !owner.equalsIgnoreCase(claimed.getOwnerToken()) || claimed.getFenceToken() == null)
        {
            throw new IllegalStateException("payment callback inbox claim was not observable");
        }
        return WxPaymentCallbackClaim.acquired(notifyHash, owner, claimed.getFenceToken());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean release(String notifyHash, String ownerToken, long fenceToken)
    {
        return mapper.release(notifyHash, ownerToken, fenceToken) == 1;
    }
}

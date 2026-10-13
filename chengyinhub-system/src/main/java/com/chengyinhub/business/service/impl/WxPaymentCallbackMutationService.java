package com.chengyinhub.business.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.WxPaymentCallbackClaim;
import com.chengyinhub.business.domain.WxPaymentCallbackInbox;
import com.chengyinhub.business.mapper.WxPaymentCallbackInboxMapper;
import com.chengyinhub.business.service.IWxPaymentCallbackInboxService;

/** 目标业务 mutation 与 inbox 终态写入必须在同一个 REQUIRED InnoDB 事务中。 */
@Service
public class WxPaymentCallbackMutationService
{
    static final String ERROR_STALE_EVENT = "STALE_EVENT";
    static final String ERROR_TARGET_MISSING = "CALLBACK_TARGET_MISSING";

    @Autowired
    private WxPaymentCallbackInboxMapper mapper;

    @Transactional
    public boolean apply(WxPaymentCallbackClaim claim, IWxPaymentCallbackInboxService.Mutation mutation)
    {
        WxPaymentCallbackInbox row = mapper.selectOwnedForUpdate(
                claim.getNotifyHash(), claim.getOwnerToken(), claim.getFenceToken());
        if (row == null)
        {
            return false;
        }
        if (row.getBizId() == null || row.getBizType() == null
                || mapper.lockBusinessTarget(row.getBizType(), row.getBizId()) == null)
        {
            throw new PaymentCallbackTransactionException(ERROR_TARGET_MISSING);
        }
        if (!mutation.isCurrent(row))
        {
            if (mapper.markIgnoredStale(claim.getNotifyHash(), claim.getOwnerToken(),
                    claim.getFenceToken(), ERROR_STALE_EVENT) != 1)
            {
                throw new PaymentCallbackTransactionException("stale callback terminal write lost ownership");
            }
            return true;
        }
        if (!mutation.apply(row))
        {
            throw new PaymentCallbackTransactionException("callback mutation has no success evidence");
        }
        if (mapper.markDone(claim.getNotifyHash(), claim.getOwnerToken(), claim.getFenceToken()) != 1)
        {
            throw new PaymentCallbackTransactionException("callback DONE write lost ownership");
        }
        return true;
    }

    static final class PaymentCallbackTransactionException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
        PaymentCallbackTransactionException(String message) { super(message); }
    }
}

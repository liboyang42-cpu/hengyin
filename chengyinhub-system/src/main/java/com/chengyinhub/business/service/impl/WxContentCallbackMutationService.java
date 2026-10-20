package com.chengyinhub.business.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.WxContentCallbackClaim;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;

/** 目标业务 mutation 与 inbox 终态写入必须在同一个 REQUIRED InnoDB 事务中。 */
@Service
public class WxContentCallbackMutationService
{
    static final String ERROR_LEGACY_MEDIA_HASH_MISSING = "LEGACY_MEDIA_HASH_MISSING";
    static final String ERROR_UNSUPPORTED_AUTO_MUTATION = "UNSUPPORTED_AUTO_MUTATION";

    @Autowired
    private WxContentCallbackInboxMapper mapper;

    @Transactional
    public boolean apply(WxContentCallbackClaim claim, boolean risky,
                         IWxContentCallbackInboxService.Mutation mutation)
    {
        WxContentCallbackInbox row = mapper.selectOwnedForUpdate(
                claim.getTraceHash(), claim.getOwnerToken(), claim.getFenceToken());
        if (row == null)
        {
            return false;
        }
        // 7=据点互动模板仍无自动处置,走人工;8=官方活动已有下线分支(AUDIT-G1-4),照常锁行 + 当前媒体校验。
        if (row.getBizType() != null && row.getBizType() == 7)
        {
            return markManualReview(claim, risky, ERROR_UNSUPPORTED_AUTO_MUTATION);
        }
        if (row.getMediaHash() == null || row.getMediaHash().trim().length() == 0)
        {
            return markManualReview(claim, risky, ERROR_LEGACY_MEDIA_HASH_MISSING);
        }
        if (row.getBizId() == null || mapper.lockBusinessTarget(row.getBizType(), row.getBizId()) == null)
        {
            throw new CallbackTransactionException("callback target missing");
        }
        if (!mutation.isCurrent(row))
        {
            if (mapper.markIgnoredStale(claim.getTraceHash(), claim.getOwnerToken(),
                    claim.getFenceToken(), risky ? 1 : 0) != 1)
            {
                throw new CallbackTransactionException("stale callback terminal write lost ownership");
            }
            return true;
        }
        if (!mutation.apply(row, risky))
        {
            throw new CallbackTransactionException("callback mutation has no success evidence");
        }
        if (mapper.markDone(claim.getTraceHash(), claim.getOwnerToken(), claim.getFenceToken(), risky ? 1 : 0) != 1)
        {
            throw new CallbackTransactionException("callback DONE write lost ownership");
        }
        return true;
    }

    private boolean markManualReview(WxContentCallbackClaim claim, boolean risky, String errorCode)
    {
        if (mapper.markManualReview(claim.getTraceHash(), claim.getOwnerToken(), claim.getFenceToken(),
                risky ? 1 : 0, errorCode) != 1)
        {
            throw new CallbackTransactionException("callback manual review write lost ownership");
        }
        return true;
    }

    static final class CallbackTransactionException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
        CallbackTransactionException(String message) { super(message); }
    }
}

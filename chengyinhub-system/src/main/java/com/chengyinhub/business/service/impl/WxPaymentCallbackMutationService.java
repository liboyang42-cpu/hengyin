package com.chengyinhub.business.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.WxPaymentCallbackClaim;
import com.chengyinhub.business.domain.WxPaymentCallbackInbox;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
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
    @Autowired private CmsRegistrationMapper registrationMapper;
    @Autowired private CmsActivityMapper activityMapper;
    @Autowired private CmsTopicMapper topicMapper;
    @Autowired private RefundApplicationMapper refundMapper;

    @Transactional
    public boolean apply(WxPaymentCallbackClaim claim, IWxPaymentCallbackInboxService.Mutation mutation)
    {
        WxPaymentCallbackInbox row = mapper.selectOwnedForUpdate(
                claim.getNotifyHash(), claim.getOwnerToken(), claim.getFenceToken());
        if (row == null)
        {
            return false;
        }
        lockOriginalRegistration(row);
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

    private void lockOriginalRegistration(WxPaymentCallbackInbox row)
    {
        if (Integer.valueOf(WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT).equals(row.getBizType()))
        {
            RegistrationTopicFence.lock(registrationMapper.selectCmsRegistrationById(row.getBizId()),
                    registrationMapper, activityMapper, topicMapper);
        }
        else if (Integer.valueOf(WxPaymentCallbackInbox.BIZ_TYPE_REFUND).equals(row.getBizType()))
        {
            RefundApplication observed = refundMapper.selectRefundApplicationById(row.getBizId());
            if (observed == null || !"registration".equals(observed.getSourceType()) || observed.getSourceId() == null)
                throw new PaymentCallbackTransactionException(ERROR_TARGET_MISSING);
            CmsRegistration registration = RegistrationTopicFence.lock(
                    registrationMapper.selectCmsRegistrationById(observed.getSourceId()),
                    registrationMapper, activityMapper, topicMapper).registration();
            RefundApplication current = refundMapper.selectRegistrationHistoryForUpdate(registration.getId()).stream()
                    .filter(candidate -> observed.getId().equals(candidate.getId())).findFirst()
                    .orElseThrow(() -> new PaymentCallbackTransactionException(ERROR_TARGET_MISSING));
            RefundServiceImpl.requireSameRefundIdentity(observed, current);
        }
    }

    static final class PaymentCallbackTransactionException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
        PaymentCallbackTransactionException(String message) { super(message); }
    }
}

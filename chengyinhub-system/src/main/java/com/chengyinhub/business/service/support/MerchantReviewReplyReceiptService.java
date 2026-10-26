package com.chengyinhub.business.service.support;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.mapper.MerchantReviewReplyReceiptMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Set;

@Service
public class MerchantReviewReplyReceiptService {
    @Autowired private IMerchantAccessService accessService;
    @Autowired private MerchantReviewReplyReceiptMapper mapper;
    private static final Set<String> ACTIONS = Set.of("MERCHANT_REVIEW_REPLIED",
            "MERCHANT_REVIEW_REPLY_UPDATED", "MERCHANT_REVIEW_REPLY_DELETED");

    public Map<String, Object> receipt(Long principal, Long reviewId, String action, String requestId) {
        var access = accessService.require(principal, MerchantPermission.MARKETING_WRITE);
        if (reviewId == null || reviewId <= 0 || !ACTIONS.contains(action == null ? "" : action)
                || requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new ServiceException("回复回读参数无效", 400);
        }
        boolean applied = mapper.countApplied(access.getMerchantId(), access.getOwnerMemberId(),
                access.getPrincipalMemberId(), reviewId, action, requestId) > 0;
        // Absence may mean an in-flight transaction, old no-op delete, or inaccessible history.
        // It is never proof that a new command is safe.
        return Map.of("reviewId", reviewId.toString(), "action", action, "requestId", requestId,
                "state", applied ? "APPLIED" : "UNKNOWN");
    }
}

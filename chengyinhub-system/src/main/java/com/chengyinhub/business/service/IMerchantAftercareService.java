package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.MerchantAftercareRespondRequest;
import com.chengyinhub.business.domain.vo.MerchantAftercareDetailVO;
import com.chengyinhub.business.domain.vo.MerchantAftercareResponseVO;
import com.chengyinhub.business.domain.vo.MerchantAftercareListVO;

public interface IMerchantAftercareService {
    MerchantAftercareListVO list(Long principalMemberId, String bucket, Integer pageNum, Integer pageSize);

    MerchantAftercareDetailVO detail(Long principalMemberId, Long refundId);

    MerchantAftercareResponseVO respond(Long principalMemberId, Long refundId,
                                        MerchantAftercareRespondRequest request);

    /** 售后凭证上传前:校验退款单属于当前店员所在门店、仍可回应且岗位可提交,返回要写入凭证元数据的门店 id。 */
    Long requireEvidenceUploadTarget(Long principalMemberId, Long refundId);

    /**
     * 商家售后超时巡检:商家 24 小时未回应(无同意/驳回意见)的待审退款单,自动转平台客服处理并通知玩家。
     * 幂等:已转过(platform_takeover_at 非空)的不重复转、不重复通知。返回本轮真正转出的单数。
     */
    int escalateStaleMerchantResponses();
}

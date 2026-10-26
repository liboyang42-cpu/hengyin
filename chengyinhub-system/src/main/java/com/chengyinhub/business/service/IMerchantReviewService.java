package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.MerchantReviewCreateRequest;
import com.chengyinhub.business.domain.dto.MerchantReviewReplyDeleteRequest;
import com.chengyinhub.business.domain.dto.MerchantReviewReplyRequest;
import com.chengyinhub.business.domain.dto.MerchantReviewReportRequest;
import com.chengyinhub.business.domain.vo.MerchantReviewPageVO;
import com.chengyinhub.business.domain.vo.MerchantReviewWriteResultVO;

public interface IMerchantReviewService {
    MerchantReviewPageVO publicList(Long viewerMemberId, Long merchantRowId,
                                    Integer pageNum, Integer pageSize);

    /** Specified order never falls back to another order; null retains the legacy latest-order hint. */
    MerchantReviewPageVO publicList(Long viewerMemberId, Long merchantRowId,
                                    Integer pageNum, Integer pageSize, Long registrationId);

    default MerchantReviewPageVO manageList(Long principalMemberId, Integer pageNum, Integer pageSize) {
        return manageList(principalMemberId, null, pageNum, pageSize);
    }

    /**
     * 商家管理列表(服务端筛选)。filter 取 all/pending/low/photo/platform,null 或空串按 all;
     * 其余值 400。total/hasMore 按筛选后的全量计算,filterCounts 恒为本店全量计数。
     */
    MerchantReviewPageVO manageList(Long principalMemberId, String filter, Integer pageNum, Integer pageSize);

    /** 创建后为 PENDING_REVIEW；统一 AuditTask 通过后才进入公开 VISIBLE。 */
    MerchantReviewWriteResultVO create(Long authorMemberId, MerchantReviewCreateRequest request);

    MerchantReviewWriteResultVO reply(Long principalMemberId, MerchantReviewReplyRequest request);

    /** 改已有公开回复;没有回复时拒绝(第一次回复走 {@link #reply})。 */
    MerchantReviewWriteResultVO updateReply(Long principalMemberId, MerchantReviewReplyRequest request);

    /** 删已有公开回复;删后该评价回到待回复,可重新回复。 */
    MerchantReviewWriteResultVO deleteReply(Long principalMemberId, MerchantReviewReplyDeleteRequest request);

    MerchantReviewWriteResultVO report(Long reporterMemberId, MerchantReviewReportRequest request);

    MerchantReviewWriteResultVO reportAsMerchant(Long principalMemberId, MerchantReviewReportRequest request);

    /** 仅由统一 AuditTask 的已 CAS 复核事务调用；预期状态与 version 共同构成领域 CAS。 */
    int moderateFromAudit(Long reviewId, String expectedStatus, String targetStatus, String reasonText);
}

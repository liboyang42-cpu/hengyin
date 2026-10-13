package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ActivityCancelManualRefundCase;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundAssessmentRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundHandleRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundReopenRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundReworkRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundVerifyRequest;
import com.chengyinhub.business.domain.vo.ActivityCancelManualRefundCaseVO;

import java.util.List;

public interface IActivityCancelManualRefundCaseService {
    ActivityCancelManualRefundCaseVO recordCase(ActivityCancelManualRefundCase draft);

    /** FX-02:按 (活动, 父订单) 查已冻结的工单;没有返回 null。同一单只记一次工单时先查它。 */
    ActivityCancelManualRefundCaseVO findCase(Long activityId, String parentOrderNo);

    /** FX-05:按 (主题, 父订单) 查主题级票的已冻结工单;没有返回 null。 */
    ActivityCancelManualRefundCaseVO findTopicCase(Long topicId, String parentOrderNo);

    List<ActivityCancelManualRefundCaseVO> list(Long clubId, String status);

    ActivityCancelManualRefundCaseVO detail(Long caseId);

    /** 返回已有幂等上传的 objectKey；新请求返回 null，Controller 才执行 PUT。 */
    String prepareEvidenceUpload(Long uploaderUserId, Long caseId, Long expectedVersion,
                                 String requestId);

    /** 固定 OSS versionId 回读后追加上传审计；返回可交给 handle 的 objectKey。 */
    String recordEvidenceUpload(Long uploaderUserId, Long caseId, Long expectedVersion,
                                String requestId, String objectKey, String versionId);

    ActivityCancelManualRefundCaseVO assess(Long assessorUserId,
                                             ActivityCancelManualRefundAssessmentRequest request);

    ActivityCancelManualRefundCaseVO handle(Long handlerUserId,
                                             ActivityCancelManualRefundHandleRequest request);

    ActivityCancelManualRefundCaseVO verify(Long verifierUserId,
                                             ActivityCancelManualRefundVerifyRequest request);

    ActivityCancelManualRefundCaseVO rework(Long reviewerUserId,
                                             ActivityCancelManualRefundReworkRequest request);

    ActivityCancelManualRefundCaseVO reopenAssessment(Long reviewerUserId,
                                                       ActivityCancelManualRefundReopenRequest request);
}

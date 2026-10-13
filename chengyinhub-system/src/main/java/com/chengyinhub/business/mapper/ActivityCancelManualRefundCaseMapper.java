package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ActivityCancelManualRefundCase;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface ActivityCancelManualRefundCaseMapper {
    /** 仅投影本人报名且在案件冻结范围内的处理状态，不返回内部备注或凭证。 */
    String selectPlayerCaseStatus(@Param("registrationId") Long registrationId,
                                 @Param("memberId") Long memberId);

    int insertCase(ActivityCancelManualRefundCase row);

    ActivityCancelManualRefundCase selectById(@Param("caseId") Long caseId);

    ActivityCancelManualRefundCase selectByIdForUpdate(@Param("caseId") Long caseId);

    ActivityCancelManualRefundCase selectByActivityAndParent(@Param("activityId") Long activityId,
                                                              @Param("parentOrderNo") String parentOrderNo);

    /** FX-05:主题级票工单按 (主题, 父订单) 加锁读。 */
    ActivityCancelManualRefundCase selectByTopicAndParent(@Param("topicId") Long topicId,
                                                           @Param("parentOrderNo") String parentOrderNo);

    ActivityCancelManualRefundCase selectByAssessmentRequestId(@Param("requestId") String requestId);

    ActivityCancelManualRefundCase selectByHandleRequestId(@Param("requestId") String requestId);

    ActivityCancelManualRefundCase selectByVerifyRequestId(@Param("requestId") String requestId);

    List<ActivityCancelManualRefundCase> selectList(@Param("clubId") Long clubId,
                                                     @Param("status") String status);

    int assessCase(@Param("caseId") Long caseId,
                   @Param("expectedVersion") Long expectedVersion,
                   @Param("decision") String decision,
                   @Param("amountFen") Long amountFen,
                   @Param("note") String note,
                   @Param("assessorUserId") Long assessorUserId,
                   @Param("requestId") String requestId,
                   @Param("assessedAt") Date assessedAt);

    int handleCase(@Param("caseId") Long caseId,
                   @Param("expectedVersion") Long expectedVersion,
                   @Param("actualAmountFen") Long actualAmountFen,
                   @Param("channel") String channel,
                   @Param("evidenceObjectKey") String evidenceObjectKey,
                   @Param("evidenceSha256") String evidenceSha256,
                   @Param("note") String note,
                   @Param("handlerUserId") Long handlerUserId,
                   @Param("requestId") String requestId,
                   @Param("handledAt") Date handledAt);

    int verifyCase(@Param("caseId") Long caseId,
                   @Param("expectedVersion") Long expectedVersion,
                   @Param("evidenceSha256") String evidenceSha256,
                   @Param("note") String note,
                   @Param("verifierUserId") Long verifierUserId,
                   @Param("requestId") String requestId,
                   @Param("verifiedAt") Date verifiedAt);

    int reworkCase(@Param("caseId") Long caseId,
                   @Param("expectedVersion") Long expectedVersion,
                   @Param("reworkedAt") Date reworkedAt);

    int reopenAssessment(@Param("caseId") Long caseId,
                         @Param("expectedVersion") Long expectedVersion,
                         @Param("reopenedAt") Date reopenedAt);
}

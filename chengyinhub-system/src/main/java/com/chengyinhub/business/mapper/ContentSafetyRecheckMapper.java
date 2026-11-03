package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ContentSafetyRecheck;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** Round5C 机审未完成内容的自动复检队列。所有状态推进都带 state=0 + attempt_count CAS,重放/并发只有一方生效。 */
public interface ContentSafetyRecheckMapper {

    ContentSafetyRecheck selectPendingByBiz(@Param("bizType") String bizType, @Param("bizId") Long bizId);

    int insertRecheck(ContentSafetyRecheck row);

    /** 同一内容再次进队(作者又改了一版):换成最新文本/图片,次数清零,立即可跑。 */
    int refreshPending(@Param("id") Long id, @Param("memberId") Long memberId, @Param("textBody") String textBody,
                       @Param("mediaJson") String mediaJson, @Param("businessReview") Integer businessReview,
                       @Param("now") Date now);

    List<ContentSafetyRecheck> selectDue(@Param("now") Date now, @Param("limit") int limit);

    int reschedule(@Param("id") Long id, @Param("expectedAttempt") Integer expectedAttempt,
                   @Param("attemptCount") Integer attemptCount, @Param("nextAttemptAt") Date nextAttemptAt,
                   @Param("errorCode") String errorCode, @Param("now") Date now);

    int finish(@Param("id") Long id, @Param("expectedAttempt") Integer expectedAttempt,
               @Param("state") Integer state, @Param("errorCode") String errorCode, @Param("now") Date now);
}

package com.chengyinhub.business.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** qx-c1 #7: post-game feedback facts (play_run_feedback). */
public interface PlayRunFeedbackMapper {
    Map<String, Object> selectByRequest(@Param("memberId") long memberId, @Param("requestId") String requestId);

    int insertFeedback(Map<String, Object> row);

    Long selectRevisionIdBySnapshot(@Param("topicId") long topicId, @Param("contentSha256") String contentSha256);
}

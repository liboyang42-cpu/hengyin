package com.chengyinhub.business.appgaps.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 已批准主题发行：读主题内容与 BIZ_TOPIC 审核事实；送审捕获与发行分配只插入、只读。 */
public interface ApprovedTopicReleaseMapper {
    Map<String, Object> topic(@Param("id") long id, @Param("lock") boolean lock);
    List<Map<String, Object>> chapters(@Param("topicId") long topicId);
    List<Map<String, Object>> nodes(@Param("topicId") long topicId);
    List<Map<String, Object>> templates(@Param("ids") Collection<Long> ids);

    Map<String, Object> task(@Param("id") long id, @Param("lock") boolean lock);
    Long openTopicTask(@Param("topicId") long topicId);
    Long latestApproveRecord(@Param("taskId") long taskId);

    int insertSubmission(Map<String, Object> row);
    Map<String, Object> submissionByRequest(@Param("memberId") long memberId, @Param("requestId") String requestId);
    Map<String, Object> latestSubmissionForTask(@Param("taskId") long taskId);

    long headRevision(@Param("topicId") long topicId);
    int insertRelease(Map<String, Object> row);
    Map<String, Object> releaseByRequest(@Param("topicId") long topicId, @Param("requestId") String requestId);
}

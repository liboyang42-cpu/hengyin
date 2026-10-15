package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.AuditTask;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * P3 统一审核任务 Mapper。
 */
public interface AuditTaskMapper {
    AuditTask selectAuditTaskById(Long id);

    AuditTask selectAuditTaskByIdForUpdate(Long id);
    List<AuditTask> selectTopicPublicationTasksForUpdate(@Param("topicId") Long topicId);
    AuditTask selectLatestTopicPublicationTask(@Param("topicId") Long topicId);
    int cancelTopicPublicationTaskCas(@Param("id") Long id, @Param("version") Integer version);
    int cancelOpenTemplateTasks(@Param("bizType") String bizType, @Param("bizId") Long bizId);

    List<AuditTask> selectAuditTaskList(AuditTask auditTask);

    int insertAuditTask(AuditTask auditTask);

    int updateAuditTaskReviewCas(@Param("id") Long id,
                                 @Param("expectedStatus") Integer expectedStatus,
                                 @Param("status") Integer status,
                                 @Param("assignee") Long assignee,
                                 @Param("version") Integer version,
                                 @Param("updateTime") Date updateTime);

    int revertAuditTaskDecisionCas(@Param("id") Long id,
                                   @Param("expectedStatus") Integer expectedStatus,
                                   @Param("expectedVersion") Integer expectedVersion,
                                   @Param("cutoff") Date cutoff,
                                   @Param("updateTime") Date updateTime);

    int refreshPendingSnapshot(@Param("id") Long id,
                               @Param("snapshotJson") String snapshotJson,
                               @Param("submitterId") Long submitterId,
                               @Param("submittedAt") Date submittedAt);

    AuditTask selectOpenAuditTaskForUpdate(@Param("bizType") String bizType, @Param("bizId") Long bizId);

    int refreshOpenAuditSnapshot(@Param("id") Long id,
                                 @Param("bizType") String bizType,
                                 @Param("sourceType") String sourceType,
                                 @Param("snapshotJson") String snapshotJson,
                                 @Param("submitterId") Long submitterId,
                                 @Param("submittedAt") Date submittedAt);

    /** 待审(status=0)且提交时间早于 cutoff 的积压条数(SLA 监控)。 */
    int countPendingBacklog(@Param("cutoff") Date cutoff);

    /** 待审(status=0)且已过审核时限(deadline_at &lt; now)的超期条数。 */
    int countPendingOverdue(@Param("now") Date now);

    List<Long> samplePendingBacklogIds(@Param("cutoff") Date cutoff);
    List<Long> samplePendingOverdueIds(@Param("now") Date now);

    /** 某提交人在 since 之后新建的举报单(source_type=report)条数,用于 submitterId 举报限频。 */
    int countRecentReportsBySubmitter(@Param("submitterId") Long submitterId, @Param("since") Date since);
}

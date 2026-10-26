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

    /** 按 submitted_at desc 取历史(get(0)=最新一单),供内部状态源读取。 */
    List<AuditTask> selectAuditTaskList(AuditTask auditTask);

    /** 工作台队列:deadline_at 升序(最紧急在前),无 deadline 垫底。 */
    List<AuditTask> selectAuditTaskQueue(AuditTask auditTask);

    List<AuditTask> selectWorkbenchQueueWithGuardScope(@Param("filter") AuditTask filter,
                                                       @Param("guardTopicIds") List<Long> guardTopicIds);

    int insertAuditTask(AuditTask auditTask);

    /** O5b-3:未结单(PENDING/ESCALATED)的 deadline 只收紧到 #{deadline},已更早或已结单不动。 */
    int tightenOpenDeadline(@Param("id") Long id, @Param("deadline") java.util.Date deadline);

    int updateAuditTaskReviewCas(@Param("id") Long id,
                                 @Param("expectedStatus") Integer expectedStatus,
                                 @Param("status") Integer status,
                                 @Param("assignee") Long assignee,
                                 @Param("version") Integer version,
                                 @Param("updateTime") Date updateTime);

    /** 认领待审单:仅 status=0 且未被认领时生效,返回影响行数。 */
    int claimAuditTask(@Param("id") Long id, @Param("assignee") Long assignee,
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

    /** O-05 #2:该内容所有开放审核单上的去重举报人数。 */
    int countOpenReportersByBiz(@Param("bizType") String bizType, @Param("bizId") Long bizId);

    /** O-05 #2:开放单 risk_level 按旧值 CAS 升级(只升不降由调用方保证),不改 version。 */
    int escalateOpenRiskLevel(@Param("id") Long id, @Param("expectedRiskLevel") String expectedRiskLevel,
                              @Param("riskLevel") String riskLevel);

    /** 改写审核单快照(不看状态);举报成立下架前把下架前公开态并入举报单快照(AUDIT-INT-4)。 */
    int updateTaskSnapshot(@Param("id") Long id, @Param("snapshotJson") String snapshotJson);

    /** 按版本 CAS 撤销一张未结审核单(status → 4);用于作者改内容时撤回申诉单。 */
    int cancelOpenAuditTaskCas(@Param("id") Long id, @Param("version") Integer version);

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

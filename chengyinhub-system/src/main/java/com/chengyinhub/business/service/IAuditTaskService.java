package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.domain.dto.AuditRevertDTO;
import com.chengyinhub.business.domain.vo.AuditRevertResultVO;
import com.chengyinhub.business.domain.vo.AuditTaskDetailVO;

import java.util.List;

/**
 * P3 统一审核任务服务。
 */
public interface IAuditTaskService {
    Long submit(String bizType, Long bizId, Long submitterId, String sourceType, String snapshotJson);

    /** 商业主题分阶段提审；同阶段未结单幂等复用，终态后重提创建新任务并追加 SUBMIT 记录。 */
    Long submitCommercialReview(String bizType, Long topicId, Long submitterId, String snapshotJson);

    /** 专业主题 V2 组合提审；锁定并刷新唯一开放 TOPIC 任务，终态后新建。 */
    Long submitOrRefreshTopicBundle(Long topicId, Long submitterId, String snapshotJson);

    /** 模板广场独立提审；刷新开放的 library 审核任务，不复用个人玩法审核。 */
    Long submitOrRefreshTemplate(Long templateId, Long submitterId, String snapshotJson);

    Long submitOrRefreshTemplateLibrary(Long libraryId, Long submitterId, String snapshotJson);

    /**
     * 活动编辑重提 / 抽检打标:锁定并刷新唯一开放 BIZ_ACTIVITY 内容审核单,没有则新建。
     * 活动的内容类来源(user_publish / user_republish / post_review_flag / auto_gate)互相可刷新;
     * 举报单等其他来源仍拒绝。post_review_flag 不会把已开着的阻断单降级成抽检单。
     */
    Long submitOrRefreshActivity(Long activityId, Long submitterId, String sourceType, String snapshotJson);

    /** V2 编辑事务在写主题前统一先锁开放审核任务，保持审核与重提锁序一致。 */
    boolean lockOpenTopicBundleTask(Long topicId);

    /** 内容举报 → 生成审核任务(sourceType=report)。四处举报统一调它,汇入同一队列。 */
    Long reportContent(String bizType, Long bizId, Long reporterId, String reasonText);

    /**
     * 用户端举报入口(主题/模板广场/个人玩法):同一举报人对同一轮未结单只记一次,其余同 {@link #reportContent}。
     *
     * @return true=本次已记录;false=该举报人本轮已举报过,未重复记录
     * @throws IllegalArgumentException 举报原因为空或超长
     */
    boolean reportContentOnce(String bizType, Long bizId, Long reporterId, String reasonText);

    /**
     * 安全门禁 HOLD → 生成待审任务(sourceType=auto_gate),幂等:同 bizType+bizId 已有未结单(PENDING/ESCALATED)则复用不重复建。
     * 返回审核单 id。供 ContentSafetyGate 在 HOLD 时调用,复用现有审核工作台,不新建第二套审核系统。
     */
    Long submitSafetyHold(String bizType, Long bizId, Long submitterId, String riskLevel, String snapshotJson);

    int review(Long taskId, Long operatorId, AuditReviewDTO request);

    AuditRevertResultVO revert(Long taskId, Long operatorId, boolean platformAdmin, AuditRevertDTO request);

    AuditTaskDetailVO detail(Long taskId, Long viewerId, boolean platformAdmin);

    java.util.Map<String, Object> itemProblemReceipt(Long taskId, Long viewerId, String requestId);

    /** 列出某审核单的处置/举报记录(含 action=REPORT 的举报留痕),供工作台 detail 展示举报数与处置历史。 */
    List<AuditRecord> listAuditRecords(Long taskId);

    AuditTask selectAuditTaskById(Long id);

    List<AuditTask> selectAuditTaskList(AuditTask auditTask);

    /**
     * 撤回该内容未结的申诉单:任务撤销 + 申诉记录 WITHDRAWN + 通知作者(reasonText 即通知原因)。
     * 开着的不是申诉单或没有未结单时不动并返回 false。
     */
    boolean withdrawOpenAppeal(String bizType, Long bizId, String reasonText);

    /** 认领待审单(仅未被认领的 status=0 单);已被认领或已处理抛 ServiceException。 */
    void claim(Long taskId, Long operatorId);

    /** 作者对自己被驳回/下架的活动、主题、玩法、模板广场条目申诉；每内容每人一次，生成 source=appeal 审核任务。 */
    com.chengyinhub.business.domain.AuditAppeal submitAppeal(String bizType, Long bizId, Long memberId,
                                                             String reason, String evidenceJson);

    /**
     * O5b-2:同上,另带申诉附件(媒体引用,须本人上传且已过内容安全)。开关关闭时带附件会被拒。
     * 默认实现只在不带附件时委托旧方法,避免旧实现静默丢附件。
     */
    default com.chengyinhub.business.domain.AuditAppeal submitAppeal(String bizType, Long bizId, Long memberId,
                                                                     String reason, String evidenceJson,
                                                                     java.util.List<String> attachments) {
        if (attachments != null && attachments.stream().anyMatch(a -> a != null && !a.trim().isEmpty())) {
            throw new UnsupportedOperationException("申诉附件未实现");
        }
        return submitAppeal(bizType, bizId, memberId, reason, evidenceJson);
    }

    /** Bound request identity is mandatory for this overload; legacy overloads retain once-only semantics. */
    com.chengyinhub.business.domain.AuditAppeal submitAppeal(String bizType, Long bizId, Long memberId,
            String reason, String evidenceJson, java.util.List<String> attachments, String requestId);

    java.util.Map<String, Object> appealReceipt(String bizType, Long bizId, Long memberId, String requestId);

    /** 作者只读：自己内容的审核轮次与时间线、申诉状态、是否可申诉。非作者抛 403。 */
    java.util.Map<String, Object> contentTimeline(String bizType, Long bizId, Long memberId);
    com.chengyinhub.business.domain.AuditAppeal submitGuardAppeal(Long memberId,
            com.chengyinhub.business.domain.dto.GuardAppealDTO request);
    java.util.Map<String, Object> guardAppealReceipt(Long memberId, Long guardLogId, String requestId);
    java.util.Map<String, Object> guardAppealReference(Long memberId, Long guardLogId, String arrivalRequestId);
    boolean canReadGuardTask(AuditTask task);
    List<AuditTask> selectWorkbenchQueueWithGuardScope(AuditTask filter, List<Long> guardTopicIds);
    java.util.Map<String, Object> guardReviewReceipt(Long taskId, Long reviewerId, String requestId);

}

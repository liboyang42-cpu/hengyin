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

    /** V2 编辑事务在写主题前统一先锁开放审核任务，保持审核与重提锁序一致。 */
    boolean lockOpenTopicBundleTask(Long topicId);

    /** 内容举报 → 生成审核任务(sourceType=report)。四处举报统一调它,汇入同一队列。 */
    Long reportContent(String bizType, Long bizId, Long reporterId, String reasonText);

    /**
     * 安全门禁 HOLD → 生成待审任务(sourceType=auto_gate),幂等:同 bizType+bizId 已有未结单(PENDING/ESCALATED)则复用不重复建。
     * 返回审核单 id。供 ContentSafetyGate 在 HOLD 时调用,复用现有审核工作台,不新建第二套审核系统。
     */
    Long submitSafetyHold(String bizType, Long bizId, Long submitterId, String riskLevel, String snapshotJson);

    int review(Long taskId, Long operatorId, AuditReviewDTO request);

    AuditRevertResultVO revert(Long taskId, Long operatorId, boolean platformAdmin, AuditRevertDTO request);

    AuditTaskDetailVO detail(Long taskId, Long viewerId, boolean platformAdmin);

    /** 列出某审核单的处置/举报记录(含 action=REPORT 的举报留痕),供工作台 detail 展示举报数与处置历史。 */
    List<AuditRecord> listAuditRecords(Long taskId);

    AuditTask selectAuditTaskById(Long id);

    List<AuditTask> selectAuditTaskList(AuditTask auditTask);

    /** 认领待审单(仅未被认领的 status=0 单);已被认领或已处理抛 ServiceException。 */
    void claim(Long taskId, Long operatorId);
}

package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/** audit_task 是两阶段审核状态真源；任何空态、未知态或快照不一致都 fail closed。 */
@Component
public class CommercialReviewStatusSourceImpl implements CommercialReviewStatusSource {

    private final AuditTaskMapper taskMapper;
    private final CmsTopicMapper topicMapper;
    private final CommercialTopicPolicy commercialTopicPolicy;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CommercialReviewStatusSourceImpl(AuditTaskMapper taskMapper, CmsTopicMapper topicMapper,
                                            CommercialTopicPolicy commercialTopicPolicy) {
        this.taskMapper = taskMapper;
        this.topicMapper = topicMapper;
        this.commercialTopicPolicy = commercialTopicPolicy;
    }

    @Override
    public void assertProjectApproved(Long topicId, TopicLifecycleState requestedTarget) {
        AuditTask task = approvedProjectTask(topicId, requestedTarget);
        assertProjectSnapshotCurrent(task, topicId, true);
    }

    @Override
    public void assertProjectApprovedReadOnly(Long topicId, TopicLifecycleState requestedTarget) {
        AuditTask task = approvedProjectTask(topicId, requestedTarget);
        assertProjectSnapshotCurrent(task, topicId, false);
    }

    private AuditTask approvedProjectTask(Long topicId, TopicLifecycleState requestedTarget) {
        AuditTask task = latest(AuditTask.BIZ_TOPIC_PROJECT_REVIEW, topicId);
        assertKnownStatus(task);
        if (!AuditTask.STATUS_APPROVED.equals(task.getStatus())) {
            throw new IllegalStateException("立项审核未通过，拒绝离开 PENDING_REVIEW");
        }
        String frozenTarget = snapshotTarget(task);
        if (requestedTarget == null || !requestedTarget.name().equals(frozenTarget)) {
            throw new IllegalStateException("立项审核通过的目标状态与本次迁移不一致");
        }
        return task;
    }

    @Override
    public void assertProjectRejected(Long topicId) {
        AuditTask task = latest(AuditTask.BIZ_TOPIC_PROJECT_REVIEW, topicId);
        assertKnownStatus(task);
        if (!AuditTask.STATUS_REJECTED.equals(task.getStatus())) {
            throw new IllegalStateException("立项审核未驳回，拒绝回退 DRAFT");
        }
    }

    @Override
    public void assertSaleApproved(Long topicId) {
        AuditTask task = latest(AuditTask.BIZ_TOPIC_SALE_REVIEW, topicId);
        assertKnownStatus(task);
        if (!AuditTask.STATUS_APPROVED.equals(task.getStatus())) {
            throw new IllegalStateException("开售审核未通过，拒绝进入 SELLING");
        }
        assertSaleSnapshotCurrent(task, topicId);
    }

    private AuditTask latest(String bizType, Long topicId) {
        AuditTask query = new AuditTask();
        query.setBizType(bizType);
        query.setBizId(topicId);
        List<AuditTask> tasks = taskMapper.selectAuditTaskList(query);
        if (tasks == null || tasks.isEmpty() || tasks.get(0) == null) {
            throw new IllegalStateException("缺少" + bizType + "审核任务，拒绝继续");
        }
        return tasks.get(0);
    }

    private static void assertKnownStatus(AuditTask task) {
        Integer status = task.getStatus();
        if (!AuditTask.STATUS_PENDING.equals(status) && !AuditTask.STATUS_APPROVED.equals(status)
                && !AuditTask.STATUS_REJECTED.equals(status) && !AuditTask.STATUS_ESCALATED.equals(status)
                && !AuditTask.STATUS_CANCELLED.equals(status)) {
            throw new IllegalStateException("未知审核状态:" + status);
        }
    }

    private String snapshotTarget(AuditTask task) {
        try {
            JsonNode root = objectMapper.readTree(task.getSnapshotJson());
            JsonNode target = root == null ? null : root.get("requestedTarget");
            if (target == null || target.asText().isEmpty()) throw new IllegalStateException("立项审核快照缺目标状态");
            return target.asText();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("立项审核快照不可解析，拒绝迁移", e);
        }
    }

    private void assertSaleSnapshotCurrent(AuditTask task, Long topicId) {
        try {
            JsonNode root = objectMapper.readTree(task.getSnapshotJson());
            JsonNode approved = root == null ? null : root.get("saleParameters");
            if (approved == null || approved.isNull()) {
                throw new IllegalStateException("开售审核快照缺少完整商业参数");
            }
            CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
            if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) {
                throw new IllegalStateException("开售审核主题不存在或已删除");
            }
            CommercialTopicPolicy.Evaluation current = commercialTopicPolicy.evaluate(topicId);
            // 两边都走 JSON 文本解析，消除 LongNode/IntNode 仅宽度不同造成的假“参数变化”。
            JsonNode currentNode = objectMapper.readTree(objectMapper.writeValueAsString(
                    commercialTopicPolicy.saleParameters(topic, current)));
            if (!approved.equals(currentNode)) {
                throw new IllegalStateException("开售审核通过后商业参数已变化，请重新提审");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("开售审核快照不可解析，拒绝进入 SELLING", e);
        }
    }

    private void assertProjectSnapshotCurrent(AuditTask task, Long topicId, boolean lockTopic) {
        try {
            JsonNode root = objectMapper.readTree(task.getSnapshotJson());
            JsonNode approved = root == null ? null : root.get("projectParameters");
            if (approved == null || approved.isNull()) {
                throw new IllegalStateException("立项审核快照缺少商圈、四店或履约窗参数");
            }
            CmsTopic topic = lockTopic
                    ? topicMapper.selectCmsTopicByIdForUpdate(topicId)
                    : topicMapper.selectCmsTopicById(topicId);
            if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) {
                throw new IllegalStateException("立项审核主题不存在或已删除");
            }
            CommercialTopicPolicy.Evaluation current = commercialTopicPolicy.evaluate(topicId);
            JsonNode currentNode = objectMapper.readTree(objectMapper.writeValueAsString(
                    commercialTopicPolicy.projectParameters(topic, current)));
            if (!approved.equals(currentNode)) {
                throw new IllegalStateException("立项审核通过后商圈、四店或履约窗已变化，请重新提审");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("立项审核快照不可解析，拒绝迁移", e);
        }
    }
}

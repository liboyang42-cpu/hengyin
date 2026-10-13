package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;

/** 供给变更与平台审核共用 task → topic → chapter 锁序。 */
@Service
public class TopicSupplyReviewService {
    private final AuditTaskMapper tasks;
    private final AuditRecordMapper records;
    private final CmsTopicMapper topics;
    private final CmsTopicChapterMapper chapters;

    public TopicSupplyReviewService(AuditTaskMapper tasks, AuditRecordMapper records,
                                   CmsTopicMapper topics, CmsTopicChapterMapper chapters) {
        this.tasks = tasks; this.records = records; this.topics = topics; this.chapters = chapters;
    }

    public void lockBeforeChapter(Long chapterId) {
        CmsTopicChapter locator = chapterId == null ? null : chapters.selectCmsTopicChapterById(chapterId);
        if (locator == null || locator.getTopicId() == null) throw new IllegalStateException("章节不存在");
        lockTopic(locator.getTopicId());
    }

    public CmsTopic lockTopic(Long topicId) {
        tasks.selectTopicPublicationTasksForUpdate(topicId);
        CmsTopic topic = topics.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) throw new IllegalStateException("主题不存在或已删除");
        return topic;
    }

    public boolean invalidate(Long topicId, Long actorId) {
        List<AuditTask> open = tasks.selectTopicPublicationTasksForUpdate(topicId);
        if (open == null || open.isEmpty()) return false;
        for (AuditTask task : open) {
            if (tasks.cancelTopicPublicationTaskCas(task.getId(), task.getVersion()) != 1)
                throw new IllegalStateException("旧审核版本作废失败，请刷新后重试");
            AuditRecord record = new AuditRecord();
            record.setTaskId(task.getId()); record.setOperatorId(actorId);
            record.setAction("CANCEL"); record.setReasonText("SUPPLY_REPLACEMENT：章节供给退出，补位后须重新提交审核");
            record.setSnapshotJson(task.getSnapshotJson()); record.setCreateTime(DateUtils.getNowDate());
            if (records.insertAuditRecord(record) != 1) throw new IllegalStateException("供给变更审核记录保存失败");
        }
        return true;
    }

    public AuditTask cancelledReview(Long topicId, String bizType) {
        AuditTask query = new AuditTask(); query.setBizId(topicId); query.setBizType(bizType);
        List<AuditTask> history = tasks.selectAuditTaskList(query);
        if (history == null || history.isEmpty()) return null;
        AuditTask latest = history.get(0);
        return AuditTask.STATUS_CANCELLED.equals(latest.getStatus()) && "SUPPLY_REPLACEMENT".equals(latest.getRiskLevel())
                ? latest : null;
    }

    public boolean canResubmit(CmsTopic topic) {
        if (topic == null || topic.getMemberId() == null || topic.getMemberId() <= 0L
                || !Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())
                || !"pro".equals(topic.getPublishMode()) || !Integer.valueOf(0).equals(topic.getUserStatus())
                || topic.getLifecycle() == null || (topic.getLifecycle() != 7 && topic.getLifecycle() != 2)
                || !Integer.valueOf(0).equals(topic.getDelFlag())
                || (topic.getCircleThemeCode() != null && !topic.getCircleThemeCode().trim().isEmpty())) return false;
        AuditTask latest = tasks.selectLatestTopicPublicationTask(topic.getId());
        return latest != null && AuditTask.STATUS_CANCELLED.equals(latest.getStatus())
                && "SUPPLY_REPLACEMENT".equals(latest.getRiskLevel())
                && Objects.equals(topic.getMemberId(), latest.getSubmitterId());
    }
}

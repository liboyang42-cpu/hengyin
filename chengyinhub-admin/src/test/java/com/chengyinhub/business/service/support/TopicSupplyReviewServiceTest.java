package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TopicSupplyReviewServiceTest {
    private final AuditTaskMapper tasks = mock(AuditTaskMapper.class);
    private final AuditRecordMapper records = mock(AuditRecordMapper.class);
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
    private final TopicSupplyReviewService service = new TopicSupplyReviewService(tasks, records, topics, chapters);

    @Test
    void locksPublicationTasksBeforeTopicAndInvalidatesAllThreeVersionsWithAudit() {
        CmsTopicChapter chapter = new CmsTopicChapter(); chapter.setId(20L); chapter.setTopicId(10L);
        CmsTopic topic = new CmsTopic(); topic.setId(10L); topic.setDelFlag(0);
        when(chapters.selectCmsTopicChapterById(20L)).thenReturn(chapter);
        when(topics.selectCmsTopicByIdForUpdate(10L)).thenReturn(topic);
        AuditTask content = task(1L, AuditTask.BIZ_TOPIC), project = task(2L, AuditTask.BIZ_TOPIC_PROJECT_REVIEW), sale = task(3L, AuditTask.BIZ_TOPIC_SALE_REVIEW);
        when(tasks.selectTopicPublicationTasksForUpdate(10L)).thenReturn(Arrays.asList(content, project, sale));
        when(tasks.cancelTopicPublicationTaskCas(anyLong(), eq(5))).thenReturn(1);
        when(records.insertAuditRecord(any())).thenReturn(1);
        service.lockBeforeChapter(20L);
        org.mockito.InOrder order = inOrder(tasks, topics);
        order.verify(tasks).selectTopicPublicationTasksForUpdate(10L);
        order.verify(topics).selectCmsTopicByIdForUpdate(10L);
        service.invalidate(10L, 88L);
        verify(tasks).cancelTopicPublicationTaskCas(1L, 5);
        verify(tasks).cancelTopicPublicationTaskCas(2L, 5);
        verify(tasks).cancelTopicPublicationTaskCas(3L, 5);
        verify(records, times(3)).insertAuditRecord(argThat(r -> r.getOperatorId().equals(88L) && r.getReasonText().startsWith("SUPPLY_REPLACEMENT")));
        when(tasks.cancelTopicPublicationTaskCas(2L, 5)).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.invalidate(10L, 88L));
    }

    @Test
    void resubmissionRequiresLatestActualCancelledReviewOwnerAndClosedSignup() {
        CmsTopic topic = new CmsTopic(); topic.setId(10L); topic.setMemberId(7L); topic.setDelFlag(0);
        topic.setProductType(ProductType.FREE_EXPLORE); topic.setPublishMode("pro"); topic.setLifecycle(7); topic.setUserStatus(0);
        AuditTask cancelled = task(1L, AuditTask.BIZ_TOPIC); cancelled.setSubmitterId(7L); cancelled.setStatus(4); cancelled.setRiskLevel("SUPPLY_REPLACEMENT");
        when(tasks.selectLatestTopicPublicationTask(10L)).thenReturn(cancelled);
        assertTrue(service.canResubmit(topic));
        cancelled.setStatus(2); assertFalse(service.canResubmit(topic));
        cancelled.setStatus(4); cancelled.setSubmitterId(8L); assertFalse(service.canResubmit(topic));
        cancelled.setSubmitterId(7L); topic.setUserStatus(1); assertFalse(service.canResubmit(topic));
        topic.setUserStatus(0); topic.setPublishMode("simple"); assertFalse(service.canResubmit(topic));
        topic.setPublishMode("pro"); topic.setLifecycle(6); assertFalse(service.canResubmit(topic));
    }

    private AuditTask task(Long id, String type) {
        AuditTask task = new AuditTask(); task.setId(id); task.setBizType(type); task.setBizId(10L); task.setVersion(5); task.setStatus(0); task.setSnapshotJson("{}"); return task;
    }
}

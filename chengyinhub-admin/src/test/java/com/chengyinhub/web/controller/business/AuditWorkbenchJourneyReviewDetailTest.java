package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.support.JourneyReviewTextService;
import com.chengyinhub.business.util.JourneyReviewTexts;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R12:审核详情必须让审核员看得到旅程新叙事 —— journey_rules_json / journey_story_json
 * 与节点块叙事段的可读文本,且不能动原有字段。
 */
class AuditWorkbenchJourneyReviewDetailTest {

    @Test
    void topicDetailAppendsRulesStoryAndReadableNarrativeTexts() {
        IAuditTaskService auditTaskService = mock(IAuditTaskService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        JourneyReviewTextService narrativeService = mock(JourneyReviewTextService.class);
        AuditWorkbenchController controller = controller(auditTaskService, topicService, null, narrativeService);
        AuditTask task = task(AuditTask.BIZ_TOPIC, 20L);
        when(auditTaskService.selectAuditTaskById(30L)).thenReturn(task);
        when(auditTaskService.listAuditRecords(30L)).thenReturn(Collections.emptyList());
        CmsTopic topic = new CmsTopic();
        topic.setId(20L);
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true}");
        topic.setJourneyStoryJson("{\"schemaVersion\":1,\"call\":{\"opener\":\"开场\"}}");
        when(topicService.selectCmsTopicById(20L)).thenReturn(topic);
        List<JourneyReviewTexts.Entry> texts = Collections.singletonList(
                new JourneyReviewTexts.Entry("journeyStory.call.opener", "开场"));
        when(narrativeService.topicTexts(topic)).thenReturn(texts);

        AjaxResult result = controller.detail(30L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(task, result.get("task"), "原有字段不动");
        assertEquals(topic.getJourneyRulesJson(), result.get("journeyRulesJson"));
        assertEquals(topic.getJourneyStoryJson(), result.get("journeyStoryJson"));
        assertEquals(texts, result.get("narrativeTexts"));
    }

    @Test
    void templateDetailAppendsReadableBlockNarrativeTexts() {
        IAuditTaskService auditTaskService = mock(IAuditTaskService.class);
        ICmsMemberTemplateService templateService = mock(ICmsMemberTemplateService.class);
        JourneyReviewTextService narrativeService = mock(JourneyReviewTextService.class);
        AuditWorkbenchController controller = controller(auditTaskService, null, templateService, narrativeService);
        AuditTask task = task(AuditTask.BIZ_TEMPLATE, 91L);
        when(auditTaskService.selectAuditTaskById(31L)).thenReturn(task);
        when(auditTaskService.listAuditRecords(31L)).thenReturn(Collections.emptyList());
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(91L);
        when(templateService.selectCmsMemberTemplateById(91L)).thenReturn(template);
        List<JourneyReviewTexts.Entry> texts = Collections.singletonList(
                new JourneyReviewTexts.Entry("blockConfig.enter.aside", "旁白"));
        when(narrativeService.templateTexts(template)).thenReturn(texts);

        AjaxResult result = controller.detail(31L);

        assertEquals(texts, result.get("narrativeTexts"));
    }

    @Test
    void marketAuditDetailUsesTheFrozenTaskSnapshotInsteadOfLiveProjection() {
        IAuditTaskService auditTaskService = mock(IAuditTaskService.class);
        AuditWorkbenchController controller = controller(auditTaskService, null, null,
                mock(JourneyReviewTextService.class));
        AuditTask task = task(AuditTask.BIZ_TEMPLATE_LIBRARY, 191L);
        task.setSnapshotJson("{\"title\":\"提交版本\"}");
        when(auditTaskService.selectAuditTaskById(32L)).thenReturn(task);
        when(auditTaskService.listAuditRecords(32L)).thenReturn(Collections.emptyList());
        AjaxResult result = controller.detail(32L);

        assertEquals(task, result.get("task"));
        assertEquals(null, result.get("templateLibrary"));
    }

    private static AuditWorkbenchController controller(IAuditTaskService auditTaskService,
                                                       ICmsTopicService topicService,
                                                       ICmsMemberTemplateService templateService,
                                                       JourneyReviewTextService narrativeService) {
        AuditWorkbenchController controller = new TestableAuditWorkbenchController(100L);
        ReflectionTestUtils.setField(controller, "auditTaskService", auditTaskService);
        if (topicService != null) {
            ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        }
        if (templateService != null) {
            ReflectionTestUtils.setField(controller, "cmsMemberTemplateService", templateService);
        }
        ReflectionTestUtils.setField(controller, "journeyReviewTextService", narrativeService);
        return controller;
    }

    private static AuditTask task(String bizType, Long bizId) {
        AuditTask task = new AuditTask();
        task.setId(30L);
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setVersion(1);
        task.setBizType(bizType);
        task.setBizId(bizId);
        return task;
    }

    private static class TestableAuditWorkbenchController extends AuditWorkbenchController {
        private final Long userId;

        private TestableAuditWorkbenchController(Long userId) {
            this.userId = userId;
        }

        @Override
        public Long getUserId() {
            return userId;
        }
    }
}

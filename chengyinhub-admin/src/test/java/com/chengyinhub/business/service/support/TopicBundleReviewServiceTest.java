package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicBundleReviewServiceTest {
    private CmsTopicMapper topicMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicNodeMapper nodeMapper;
    private CmsMemberTemplateMapper templateMapper;
    private TopicBundleReviewService service;
    private CmsTopic topic;
    private CmsMemberTemplate template;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        service = new TopicBundleReviewService(topicMapper, chapterMapper, nodeMapper, templateMapper);

        topic = new CmsTopic();
        topic.setId(10L);
        topic.setMemberId(7L);
        topic.setProductType(1);
        topic.setPublishMode("pro");
        topic.setConfigVersion(3L);
        topic.setRouteMode("LINEAR");
        topic.setName("夜行谜题");
        topic.setDelFlag(0);
        when(topicMapper.selectCmsTopicById(10L)).thenReturn(topic);

        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(20L);
        chapter.setTopicId(10L);
        chapter.setName("第一章");
        chapter.setSortId(1L);
        chapter.setBlocksJson("[{\"type\":\"node\",\"nodeId\":30}]");
        when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));

        CmsTopicNode node = new CmsTopicNode();
        node.setId(30L);
        node.setTopicId(10L);
        node.setChapterId(20L);
        node.setName("旧码头");
        node.setAddress("中山东一路");
        node.setLatitude("31.240100");
        node.setLongitude("121.490100");
        node.setTemplateId(100L);
        node.setStatus(1L);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));

        template = new CmsMemberTemplate();
        template.setId(100L);
        template.setMemberId(7L);
        template.setTitle("码头暗号");
        template.setQuestionName("找到门牌号");
        template.setQuestionAnswer("17");
        template.setDraftStatus(0);
        template.setStatus(0L);
        template.setCheckStatus(0);
        template.setPublishStatus(0L);
        template.setDelFlag(0);
        when(templateMapper.selectCmsMemberTemplateById(100L)).thenReturn(template);
    }

    @Test
    void rejectsMissingChapterNodeAndReferencedGame() {
        CmsTopicChapter chapter = chapterMapper.selectCmsTopicChapterList(new CmsTopicChapter()).get(0);
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeList(new CmsTopicNode()).get(0);
        when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
        assertThrows(IllegalArgumentException.class, () -> service.freezeForSubmission(10L, 7L));
        when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        assertThrows(IllegalArgumentException.class, () -> service.freezeForSubmission(10L, 7L));
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        node.setTemplateId(null);
        assertThrows(IllegalArgumentException.class, () -> service.freezeForSubmission(10L, 7L));
        verify(templateMapper, never()).approveBundledDraft(any(), any());
    }

    @Test
    void storyGameWithoutLocationCanBeSubmittedAndReviewed() {
        CmsTopicChapter chapter = chapterMapper.selectCmsTopicChapterList(new CmsTopicChapter()).get(0);
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeList(new CmsTopicNode()).get(0);
        chapter.setBlocksJson("[{\"type\":\"node\",\"nodeId\":30,\"locationRequired\":false}]");
        node.setAddress(null);
        node.setLatitude(null);
        node.setLongitude(null);
        TopicBundleReviewService.Submission submission = service.freezeForSubmission(10L, 7L);
        stubLockedRows();
        when(templateMapper.approveBundledDraft(100L, 7L)).thenReturn(1);
        service.applyDecision(10L, 7L, submission.getSnapshotJson(), true);
        verify(templateMapper).approveBundledDraft(100L, 7L);

        node.setLatitude("31.240100");
        assertThrows(IllegalArgumentException.class, () -> service.freezeForSubmission(10L, 7L));
        node.setLatitude(null);
        node.setTemplateId(null);
        assertThrows(IllegalArgumentException.class, () -> service.freezeForSubmission(10L, 7L));
        node.setTemplateId(100L);
        chapter.setBlocksJson("[{\"type\":\"node\",\"nodeId\":30}]");
        assertThrows(IllegalArgumentException.class, () -> service.freezeForSubmission(10L, 7L));
        chapter.setBlocksJson("[{\"type\":\"node\",\"nodeId\":31,\"locationRequired\":false}]");
        assertThrows(IllegalArgumentException.class, () -> service.freezeForSubmission(10L, 7L));
    }

    @Test
    void freezesStableVersionedSnapshotWithReferencedDraftOnly() throws Exception {
        TopicBundleReviewService.Submission first = service.freezeForSubmission(10L, 7L);
        TopicBundleReviewService.Submission second = service.freezeForSubmission(10L, 7L);

        assertEquals(first.getSnapshotJson(), second.getSnapshotJson());
        assertEquals(Collections.singletonList(100L), first.getBundledTemplateIds());
        JsonNode snapshot = new ObjectMapper().readTree(first.getSnapshotJson());
        assertEquals(2, snapshot.path("schemaVersion").asInt());
        assertEquals("TOPIC", snapshot.path("bizType").asText());
        assertEquals(3L, snapshot.path("topic").path("configVersion").asLong());
        assertTrue(snapshot.path("topic").path("contentHash").asText().startsWith("sha256:"));
        assertTrue(snapshot.path("bundledTemplates").get(0).path("contentHash").asText().startsWith("sha256:"));
        assertEquals("17", snapshot.path("bundledTemplates").get(0).path("content").path("questionAnswer").asText());
    }

    @Test
    void acceptsApprovedPersonalTemplateEvenWhenItsPersonalListingIsDown() {
        template.setDraftStatus(1);
        template.setStatus(1L);
        template.setCheckStatus(1);
        template.setPublishStatus(0L);

        TopicBundleReviewService.Submission submission = service.freezeForSubmission(10L, 7L);

        assertEquals(Collections.singletonList(100L), submission.getBundledTemplateIds());
    }

    @Test
    void rejectsOtherOwnerDeletedInvalidAndFakeCoordinates() {
        template.setMemberId(8L);
        assertThrows(IllegalStateException.class, () -> service.freezeForSubmission(10L, 7L));

        template.setMemberId(7L);
        template.setDelFlag(2);
        assertThrows(IllegalStateException.class, () -> service.freezeForSubmission(10L, 7L));

        template.setDelFlag(0);
        template.setAdvancedConfigJson("{");
        assertThrows(IllegalArgumentException.class, () -> service.freezeForSubmission(10L, 7L));

        template.setAdvancedConfigJson(null);
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeList(new CmsTopicNode()).get(0);
        node.setLatitude("31.2304");
        node.setLongitude("121.4737");
        assertThrows(IllegalArgumentException.class, () -> service.freezeForSubmission(10L, 7L));
    }

    @Test
    void decisionLocksAndApprovesOnlyTheDraftReferencedByTheSnapshot() {
        TopicBundleReviewService.Submission submission = service.freezeForSubmission(10L, 7L);
        stubLockedRows();
        when(templateMapper.approveBundledDraft(100L, 7L)).thenReturn(1);

        service.applyDecision(10L, 7L, submission.getSnapshotJson(), true);

        verify(templateMapper).selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(100L));
        verify(templateMapper).approveBundledDraft(100L, 7L);
    }

    @Test
    void rejectionVerifiesTheBundleButLeavesItsDraftPrivate() {
        TopicBundleReviewService.Submission submission = service.freezeForSubmission(10L, 7L);
        stubLockedRows();

        service.applyDecision(10L, 7L, submission.getSnapshotJson(), false);

        verify(templateMapper).selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(100L));
        verify(templateMapper, never()).approveBundledDraft(100L, 7L);
    }

    @Test
    void decisionRejectsTamperedTemplateAndForgedUnreferencedDraft() throws Exception {
        TopicBundleReviewService.Submission submission = service.freezeForSubmission(10L, 7L);
        stubLockedRows();
        template.setQuestionAnswer("18");
        assertThrows(IllegalStateException.class,
                () -> service.applyDecision(10L, 7L, submission.getSnapshotJson(), true));
        verify(templateMapper, never()).approveBundledDraft(100L, 7L);

        template.setQuestionAnswer("17");
        JsonNode original = new ObjectMapper().readTree(submission.getSnapshotJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) original.path("bundledTemplates").get(0)).put("id", 101L);
        assertThrows(IllegalStateException.class,
                () -> service.applyDecision(10L, 7L, original.toString(), false));
    }

    @Test
    void decisionRejectsSnapshotTemplateContentTamperingEvenWhenStoredHashIsUnchanged() throws Exception {
        TopicBundleReviewService.Submission submission = service.freezeForSubmission(10L, 7L);
        stubLockedRows();
        when(templateMapper.approveBundledDraft(100L, 7L)).thenReturn(1);
        JsonNode tampered = new ObjectMapper().readTree(submission.getSnapshotJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tampered.path("bundledTemplates").get(0)
                .path("content")).put("questionAnswer", "伪造答案");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.applyDecision(10L, 7L, tampered.toString(), true));

        assertTrue(error.getMessage().contains("提交内容已变化"), error.getMessage());
        verify(topicMapper, never()).selectCmsTopicByIdForUpdate(any());
        verify(templateMapper, never()).approveBundledDraft(100L, 7L);
    }

    @Test
    void decisionRejectsTamperedReviewerDisplayFields() throws Exception {
        TopicBundleReviewService.Submission submission = service.freezeForSubmission(10L, 7L);
        stubLockedRows();
        when(templateMapper.approveBundledDraft(100L, 7L)).thenReturn(1);
        JsonNode tampered = new ObjectMapper().readTree(submission.getSnapshotJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tampered.path("bundledTemplates").get(0))
                .put("title", "伪造标题");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.applyDecision(10L, 7L, tampered.toString(), true));

        assertTrue(error.getMessage().contains("提交内容已变化"), error.getMessage());
        verify(templateMapper, never()).approveBundledDraft(100L, 7L);
    }

    @Test
    void decisionRejectsTamperedBundledDraftFlag() throws Exception {
        TopicBundleReviewService.Submission submission = service.freezeForSubmission(10L, 7L);
        stubLockedRows();
        when(templateMapper.approveBundledDraft(100L, 7L)).thenReturn(1);
        JsonNode tampered = new ObjectMapper().readTree(submission.getSnapshotJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tampered.path("bundledTemplates").get(0))
                .put("bundledDraft", false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.applyDecision(10L, 7L, tampered.toString(), true));

        assertTrue(error.getMessage().contains("提交内容已变化"), error.getMessage());
        verify(templateMapper, never()).approveBundledDraft(100L, 7L);
    }

    @Test
    void decisionRejectsAChangedTopicVersionBeforeApprovingDrafts() {
        TopicBundleReviewService.Submission submission = service.freezeForSubmission(10L, 7L);
        stubLockedRows();
        topic.setConfigVersion(4L);

        assertThrows(IllegalStateException.class,
                () -> service.applyDecision(10L, 7L, submission.getSnapshotJson(), true));
        verify(templateMapper, never()).approveBundledDraft(100L, 7L);
    }

    @Test
    void decisionIgnoresLifecycleChangesMadeByIndependentCommercialReview() {
        TopicBundleReviewService.Submission submission = service.freezeForSubmission(10L, 7L);
        stubLockedRows();
        topic.setLifecycle(1);
        when(templateMapper.approveBundledDraft(100L, 7L)).thenReturn(1);

        service.applyDecision(10L, 7L, submission.getSnapshotJson(), true);

        verify(templateMapper).approveBundledDraft(100L, 7L);
    }

    private void stubLockedRows() {
        when(topicMapper.selectCmsTopicByIdForUpdate(10L)).thenReturn(topic);
        CmsTopicChapter chapter = chapterMapper.selectCmsTopicChapterList(new CmsTopicChapter()).get(0);
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeList(new CmsTopicNode()).get(0);
        when(chapterMapper.selectByTopicForUpdate(10L)).thenReturn(Collections.singletonList(chapter));
        when(nodeMapper.selectByTopicForUpdate(10L)).thenReturn(Collections.singletonList(node));
        when(templateMapper.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(100L)))
                .thenReturn(Collections.singletonList(template));
    }
}

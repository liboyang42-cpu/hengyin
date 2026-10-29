package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IMerchantChapterApplicationService;
import com.chengyinhub.business.util.TemplateStatus;
import com.chengyinhub.system.service.ISysCategoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real extracted query/projection owner; mapper doubles do not establish real SQL authorization. */
class TopicQueryServiceTest {
    private final TopicQueryService service = new TopicQueryService();
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
    private final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
    private final CmsTopicTemplateMetaMapper metadata = mock(CmsTopicTemplateMetaMapper.class);
    private final CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
    private final ISysCategoryService categories = mock(ISysCategoryService.class);
    private final IMerchantChapterApplicationService applications = mock(IMerchantChapterApplicationService.class);

    TopicQueryServiceTest() {
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapters);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodes);
        ReflectionTestUtils.setField(service, "cmsTopicTemplateMetaMapper", metadata);
        ReflectionTestUtils.setField(service, "cmsMemberTemplateMapper", templates);
        ReflectionTestUtils.setField(service, "sysCategoryService", categories);
        ReflectionTestUtils.setField(service, "merchantChapterApplicationService", applications);
    }

    @Test void deniedSnapshotsNeverReadCountsChildrenOrPartnerMetadata() {
        assertNull(service.selectPublicCmsTopicById(41L));
        assertNull(service.selectCmsTopicDetailById(41L, 9L));
        assertNull(service.selectMerchantVisibleCmsTopicById(41L));
        assertNull(service.selectMerchantManagedCmsTopicById(41L, 17L));
        assertNull(service.buildPublicTopicTemplateDetail(41L, 9L, true, 8));
        verify(topics).selectPublicCmsTopicById(41L);
        verify(topics).selectCmsTopicDetailById(41L, 9L);
        verify(topics).selectMerchantVisibleCmsTopicById(41L);
        verify(topics).selectMerchantManagedCmsTopicById(41L, 17L);
        verify(topics).selectPublicTopicTemplateById(41L);
        verifyNoMoreInteractions(topics);
        verifyNoInteractions(chapters, nodes, metadata, templates, categories, applications);
    }

    @Test void publicPageKeepsPageMetadataIdentityOrderAndOneAdmittedCountBatch() {
        CmsTopic first = topic(42L), second = topic(43L);
        Page<CmsTopic> page = new Page<>();
        page.setPageNum(3); page.setPageSize(3); page.setTotal(71);
        page.addAll(Arrays.asList(first, second, topic(42L)));
        CmsTopic probe = new CmsTopic();
        when(topics.selectPublicCmsTopicList(probe)).thenReturn(page);
        CmsTopic count = topic(42L); count.setLocationCount(6L); count.setTemplateCount(2L);
        when(topics.selectPublicNodeCountsByTopicIds(Arrays.asList(42L, 43L)))
                .thenReturn(Collections.singletonList(count));
        assertSame(page, service.selectPublicCmsTopicList(probe));
        assertEquals(Arrays.asList(42L, 43L, 42L), page.stream().map(CmsTopic::getId).toList());
        assertEquals(Arrays.asList(6L, 0L, 6L), page.stream().map(CmsTopic::getLocationCount).toList());
        assertEquals(3, page.getPageNum()); assertEquals(3, page.getPageSize()); assertEquals(71L, page.getTotal());
        var order = inOrder(topics);
        order.verify(topics).selectPublicCmsTopicList(probe);
        order.verify(topics).selectPublicNodeCountsByTopicIds(Arrays.asList(42L, 43L));
        verifyNoMoreInteractions(topics);
        verifyNoInteractions(chapters, nodes, metadata, templates, categories, applications);
    }

    @Test void whitelistProjectionDropsExactCoordinatesAnswersHintsAndAnonymousPartnerFields() throws Exception {
        CmsTopic topic = topic(42L); topic.setMemberId(9L); topic.setProductType(1);
        when(topics.selectPublicTopicTemplateById(42L)).thenReturn(topic);
        CmsTopicChapter chapter = new CmsTopicChapter(); chapter.setId(101L);
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
        CmsTopicNode node = new CmsTopicNode(); node.setName("公开节点"); node.setTemplateId(201L);
        node.setAddress("公开地址"); node.setLatitude("30.123456"); node.setLongitude("120.654321");
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        CmsMemberTemplate template = new CmsMemberTemplate(); template.setId(201L); template.setTitle("公开玩法");
        template.setQuestionAnswer("private-answer"); template.setHint1("private-hint");
        when(templates.selectCmsMemberTemplateByIds(Collections.singletonList(201L)))
                .thenReturn(Collections.singletonList(template));
        CmsTopicChapterTemplateMeta cm = new CmsTopicChapterTemplateMeta(); cm.setChapterId(101L);
        cm.setExclusivityReason("private-partner-reason"); cm.setEffortMaterials("private-partner-material");
        when(metadata.selectChapterMetaByTopicId(42L)).thenReturn(Collections.singletonList(cm));
        var detail = service.buildPublicTopicTemplateDetail(42L, null, false, null);
        assertFalse(detail.getViewerIsPublisher()); assertFalse(detail.getViewerIsMerchant());
        assertEquals("公开地址", detail.getChapters().get(0).getNodes().get(0).getAddressName());
        assertEquals("公开玩法", detail.getGames().get(0).getTitle());
        assertNull(detail.getChapters().get(0).getRecruitStatus());
        assertNull(detail.getChapters().get(0).getExclusivity()); assertNull(detail.getChapters().get(0).getEffort());
        String json = new ObjectMapper().writeValueAsString(detail);
        for (String forbidden : Arrays.asList("private-answer", "private-hint", "private-partner-reason",
                "private-partner-material", "longitude", "latitude", "questionAnswer", "hint1"))
            assertFalse(json.contains(forbidden), forbidden);
        verifyNoInteractions(categories, applications);
        verify(topics, never()).selectCmsTopicById(any());
    }

    @Test void shelfRetainsOfficialStableOrderingAndSeparateVerifiedRecommendationGate() {
        when(topics.selectTopicTemplateList()).thenReturn(Arrays.asList(topic(44L), topic(43L), topic(42L)));
        CmsTopicTemplateMeta first = meta(44L, 0, TemplateStatus.VERIFIED);
        CmsTopicTemplateMeta second = meta(43L, 1, TemplateStatus.VERIFIED);
        CmsTopicTemplateMeta third = meta(42L, 1, null);
        when(metadata.selectByTopicIds(Arrays.asList(44L, 43L, 42L))).thenReturn(Arrays.asList(first, second, third));
        assertEquals(Arrays.asList(43L, 42L, 44L), service.buildTopicTemplateListVO(false).stream().map(v -> v.getId()).toList());
        assertEquals(Arrays.asList(43L, 44L), service.buildTopicTemplateListVO(true).stream().map(v -> v.getId()).toList());
        verify(metadata, times(2)).selectByTopicIds(Arrays.asList(44L, 43L, 42L));
        verifyNoInteractions(templates, categories, applications);
    }

    @Test void databaseFailureIsNotConvertedToMissingOrEmptyProjection() {
        IllegalStateException failure = new IllegalStateException("controlled storage failure");
        when(topics.selectCmsTopicDetailById(42L, 9L)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.selectCmsTopicDetailById(42L, 9L)));
        verifyNoMoreInteractions(chapters, nodes, metadata, templates, categories, applications);
        verify(topics, never()).selectPublicNodeCountsByTopicIds(any());
    }

    @Test void extractedPublicSnapshotEntryRetainsRepeatableReadContract() throws Exception {
        Transactional tx = TopicQueryService.class.getMethod("buildPublicTopicTemplateDetail",
                Long.class, Long.class, boolean.class, Integer.class).getAnnotation(Transactional.class);
        assertNotNull(tx); assertTrue(tx.readOnly()); assertEquals(Isolation.REPEATABLE_READ, tx.isolation());
    }

    private static CmsTopic topic(Long id) { CmsTopic row = new CmsTopic(); row.setId(id); return row; }
    private static CmsTopicTemplateMeta meta(Long id, int official, String status) {
        CmsTopicTemplateMeta row = new CmsTopicTemplateMeta(); row.setTopicId(id);
        row.setIsOfficial(official); row.setTemplateStatus(status); return row;
    }
}

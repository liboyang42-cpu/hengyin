package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.vo.TopicTemplateDetailVO;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper;
import com.chengyinhub.business.service.impl.TopicQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 匿名主题模板详情的单查询 eligibility 与同快照组装合同。 */
class TopicTemplatePublicReadSecurityTest
{
    private TopicQueryService service;
    private CmsTopicMapper topicMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicTemplateMetaMapper metaMapper;
    private CmsTopicNodeMapper nodeMapper;

    @BeforeEach
    void setUp()
    {
        service = new TopicQueryService();
        topicMapper = mock(CmsTopicMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        metaMapper = mock(CmsTopicTemplateMetaMapper.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "cmsTopicTemplateMetaMapper", metaMapper);
        when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
    }

    @Test
    void eligibleDetailUsesOnlyFailClosedTopicSnapshot()
    {
        CmsTopic snapshot = new CmsTopic();
        snapshot.setId(42L);
        snapshot.setMemberId(9L);
        snapshot.setName("公开模板");
        snapshot.setProductType(1);
        when(topicMapper.selectPublicTopicTemplateById(42L)).thenReturn(snapshot);

        TopicTemplateDetailVO detail = service.buildPublicTopicTemplateDetail(42L, 9L, false, null);

        assertEquals(42L, detail.getId());
        assertEquals("公开模板", detail.getName());
        assertTrue(detail.getViewerIsPublisher());
        assertFalse(detail.getViewerIsMerchant());
        verify(topicMapper).selectPublicTopicTemplateById(42L);
        verify(topicMapper, never()).selectCmsTopicById(42L);
        verify(topicMapper, never()).selectTopicIsTemplate(42L);
    }

    @Test
    void ineligibleSnapshotFailsClosedBeforeReadingDetailChildren()
    {
        when(topicMapper.selectPublicTopicTemplateById(42L)).thenReturn(null);

        assertNull(service.buildPublicTopicTemplateDetail(42L, null, false, null));

        verify(chapterMapper, never()).selectCmsTopicChapterList(any());
        org.mockito.Mockito.verifyNoInteractions(nodeMapper);
        verify(metaMapper, never()).selectByTopicId(any());
    }

    @Test
    void publicDetailUsesAuthoritativeTotalIncludingZeroWithoutChangingPrivateDetail() {
        for (long count : new long[]{8L, 0L}) {
            CmsTopic snapshot = new CmsTopic(); snapshot.setId(42L); snapshot.setProductType(1);
            snapshot.setLocationCount(99L);
            when(topicMapper.selectPublicTopicTemplateById(42L)).thenReturn(snapshot);
            CmsTopic currentCount = new CmsTopic(); currentCount.setId(42L);
            currentCount.setLocationCount(count); currentCount.setTemplateCount(0L);
            when(topicMapper.selectPublicNodeCountsByTopicIds(Collections.singletonList(42L)))
                    .thenReturn(count == 0L ? Collections.emptyList() : Collections.singletonList(currentCount));
            TopicTemplateDetailVO detail = service.buildPublicTopicTemplateDetail(42L, null, false, null);
            assertEquals(count, detail.getLocationCount());
            assertTrue(detail.getChapters().isEmpty());
            assertFalse(detail.getViewerIsPublisher());
            assertFalse(detail.getViewerIsMerchant());
        }
        org.mockito.Mockito.clearInvocations(nodeMapper);
        CmsTopic privateSnapshot = new CmsTopic(); privateSnapshot.setId(43L);
        privateSnapshot.setProductType(1); privateSnapshot.setLocationCount(99L);
        when(topicMapper.selectCmsTopicById(43L)).thenReturn(privateSnapshot);
        assertEquals(99L, service.buildTopicTemplateDetail(43L, false).getLocationCount());
        org.mockito.Mockito.verifyNoInteractions(nodeMapper);
    }

    @Test
    void publicDetailPinsRepeatableReadSnapshot() throws Exception
    {
        Transactional tx = TopicQueryService.class.getDeclaredMethod(
                        "buildPublicTopicTemplateDetail", Long.class, Long.class,
                        boolean.class, Integer.class)
                .getAnnotation(Transactional.class);

        assertTrue(tx != null && tx.readOnly(), "公共详情必须在只读事务中组装完整白名单子树");
        assertEquals(Isolation.REPEATABLE_READ, tx.isolation(),
                "必须显式固定可重复读，不能依赖部署库的默认隔离级别");
    }
}

package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.exception.ChapterFlowValidationException;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** owner 2026-10-23 D8(U-170):每个主题最多 30 章、300 个节点,保存与发布都拦,常量只有一处。 */
class TopicSizeLimitsTest {

    private static List<ChapterDTO> chapters(int chapterCount, int nodesPerChapter) {
        List<ChapterDTO> chapters = new ArrayList<>();
        for (int c = 0; c < chapterCount; c++) {
            ChapterDTO chapter = new ChapterDTO();
            List<NodeDTO> nodes = new ArrayList<>();
            for (int n = 0; n < nodesPerChapter; n++) nodes.add(new NodeDTO());
            chapter.setNodes(nodes);
            chapters.add(chapter);
        }
        return chapters;
    }

    @Test
    void limitsAreThirtyChaptersAndThreeHundredNodes() {
        assertEquals(30, TopicSizeLimits.MAX_CHAPTERS);
        assertEquals(300, TopicSizeLimits.MAX_NODES);
        assertNull(TopicSizeLimits.violation(30, 300));
        assertEquals("主题最多 30 章,当前 31 章,请合并或删减章节", TopicSizeLimits.violation(31, 10));
        assertEquals("主题最多 300 个节点,当前 301 个,请删减节点", TopicSizeLimits.violation(30, 301));
    }

    @Test
    void draftPayloadAtTheBoundaryPassesAndOneMoreIsRejected() {
        TopicSizeLimits.requireWithinLimits(chapters(30, 10));          // 30 章 300 节点
        TopicSizeLimits.requireWithinLimits(null);
        assertThrows(ChapterFlowValidationException.class, () -> TopicSizeLimits.requireWithinLimits(chapters(31, 1)));
        List<ChapterDTO> oneNodeTooMany = chapters(30, 10);
        oneNodeTooMany.get(0).getNodes().add(new NodeDTO());
        ChapterFlowValidationException e = assertThrows(ChapterFlowValidationException.class,
                () -> TopicSizeLimits.requireWithinLimits(oneNodeTooMany));
        assertEquals("主题最多 300 个节点,当前 301 个,请删减节点", e.getMessage());
    }

    @Test
    void bothSavePathsRejectBeforeTouchingAnyMapper() {
        // 未注入任何 mapper:若校验不是第一步,这里会是 NPE 而不是 400 语义的校验异常
        var service = new com.chengyinhub.business.service.impl.TopicContentGraphAuthoringServiceImpl();
        assertThrows(ChapterFlowValidationException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "saveChaptersAndNodes", chapters(31, 1), 1L, "author", 7L, false));
        assertThrows(ChapterFlowValidationException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "compileChapterFlow", chapters(1, 301), 1L, "author", 7L, true, false));
    }

    @Test
    void publishCountsRowsAlreadyInTheDatabase() {
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        var topicMapper = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        when(topicMapper.lockTopicContentBudget(5L)).thenReturn(5L);
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        List<CmsTopicChapter> thirtyOne = new ArrayList<>();
        for (int i = 0; i < 31; i++) thirtyOne.add(new CmsTopicChapter());
        when(chapterMapper.countContentByTopicForUpdate(5L)).thenReturn((long) thirtyOne.size());
        when(nodeMapper.countContentByTopicForUpdate(5L)).thenReturn(0L);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(com.chengyinhub.business.service.support.TopicPublicationTestWiring.owner(service, "assertTopicSizeWithinLimits"), "assertTopicSizeWithinLimits", 5L));
        assertEquals("发布前请先调整:主题最多 30 章,当前 31 章,请合并或删减章节", e.getMessage());

        when(chapterMapper.countContentByTopicForUpdate(5L)).thenReturn((long) thirtyOne.subList(0, 30).size());
        ReflectionTestUtils.invokeMethod(com.chengyinhub.business.service.support.TopicPublicationTestWiring.owner(service, "assertTopicSizeWithinLimits"), "assertTopicSizeWithinLimits", 5L);
    }
}

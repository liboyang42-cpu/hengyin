package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TopicContentBudgetGuardTest {
    private static final long TOPIC = 7L;

    @Test
    void parentFencePrecedesBothCurrentReadCounts() {
        Fixture f = new Fixture(30L, 300L);
        f.guard.assertTopicSizeWithinLimits(TOPIC);
        var order = inOrder(f.topics, f.chapters, f.nodes);
        order.verify(f.topics).lockTopicContentBudget(TOPIC);
        order.verify(f.chapters).countContentByTopicForUpdate(TOPIC);
        order.verify(f.nodes).countContentByTopicForUpdate(TOPIC);
        order.verifyNoMoreInteractions();
    }

    @Test
    void thirtyChaptersAndThreeHundredPhysicalNodesStillPass() {
        Fixture f = new Fixture(30L, 300L);
        assertDoesNotThrow(() -> f.guard.assertTopicSizeWithinLimits(TOPIC));
    }

    @Test
    void thirtyOneChaptersRetainTheExistingLimitMessage() {
        Fixture f = new Fixture(31L, 1L);
        assertEquals("发布前请先调整:主题最多 30 章,当前 31 章,请合并或删减章节",
                assertThrows(IllegalStateException.class,
                        () -> f.guard.assertTopicSizeWithinLimits(TOPIC)).getMessage());
    }

    @Test
    void hiddenMerchantNodesCannotBeExcludedByPlayerVisibility() {
        Fixture f = new Fixture(30L, 301L);
        when(f.nodes.selectCmsTopicNodeList(any())).thenReturn(java.util.Collections.emptyList());
        assertEquals("发布前请先调整:主题最多 300 个节点,当前 301 个,请删减节点",
                assertThrows(IllegalStateException.class,
                        () -> f.guard.assertTopicSizeWithinLimits(TOPIC)).getMessage());
        verify(f.nodes, never()).selectCmsTopicNodeList(any());
        verify(f.chapters, never()).selectCmsTopicChapterList(any());
    }

    @Test
    void missingParentFailsBeforeLookingAtChildren() {
        Fixture f = new Fixture(0L, 0L);
        when(f.topics.lockTopicContentBudget(TOPIC)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> f.guard.assertTopicSizeWithinLimits(TOPIC));
        verifyNoInteractions(f.chapters, f.nodes);
    }

    @Test
    void invalidParentIdNeverBecomesAnAllTopicsQuery() {
        Fixture f = new Fixture(0L, 0L);
        assertThrows(IllegalStateException.class, () -> f.guard.assertTopicSizeWithinLimits(null));
        assertThrows(IllegalStateException.class, () -> f.guard.assertTopicSizeWithinLimits(0L));
        verifyNoInteractions(f.topics, f.chapters, f.nodes);
    }

    @Test
    void unprovableCountsFailClosed() {
        Fixture f = new Fixture(null, 0L);
        assertThrows(IllegalStateException.class, () -> f.guard.assertTopicSizeWithinLimits(TOPIC));
        when(f.chapters.countContentByTopicForUpdate(TOPIC)).thenReturn(0L);
        when(f.nodes.countContentByTopicForUpdate(TOPIC)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> f.guard.assertTopicSizeWithinLimits(TOPIC));
    }

    @Test
    void negativeOrOverflowedCountsNeverWrapIntoTheAllowedBudget() {
        Fixture f = new Fixture(-1L, 0L);
        assertThrows(IllegalStateException.class, () -> f.guard.assertTopicSizeWithinLimits(TOPIC));
        when(f.chapters.countContentByTopicForUpdate(TOPIC)).thenReturn(1L);
        when(f.nodes.countContentByTopicForUpdate(TOPIC)).thenReturn(Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> f.guard.assertTopicSizeWithinLimits(TOPIC));
    }

    private static final class Fixture {
        final CmsTopicMapper topics = mock(CmsTopicMapper.class);
        final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
        final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        final TopicContentWriteGuard guard = new TopicContentWriteGuard(topics, chapters, null, null, null);
        Fixture(Long chapterCount, Long nodeCount) {
            ReflectionTestUtils.setField(guard, "cmsTopicNodeMapper", nodes);
            when(topics.lockTopicContentBudget(TOPIC)).thenReturn(TOPIC);
            when(chapters.countContentByTopicForUpdate(TOPIC)).thenReturn(chapterCount);
            when(nodes.countContentByTopicForUpdate(TOPIC)).thenReturn(nodeCount);
        }
    }
}

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.business.service.support.TopicContentWriteGuard;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TopicContentCrudBudgetTest {
    @Test void chapterUpdateLocksParentBeforeCurrentChildAndKeepsScopeChecks() {
        Fixture f = new Fixture();
        CmsTopicChapter current = chapter(10, 7);
        when(f.chapters.selectCmsTopicChapterById(10L)).thenReturn(current);
        when(f.chapters.selectContentByIdForUpdate(10L)).thenReturn(current);
        CmsTopicChapter patch = new CmsTopicChapter(); patch.setId(10L); patch.setName("changed");
        f.chapterService.updateCmsTopicChapter(patch);
        var order = inOrder(f.scope, f.topics, f.chapters);
        order.verify(f.chapters).selectCmsTopicChapterById(10L);
        order.verify(f.scope).canViewTopic(7L);
        order.verify(f.topics).lockTopicContentBudget(7L);
        order.verify(f.chapters).selectContentByIdForUpdate(10L);
        order.verify(f.chapters).updateCmsTopicChapter(patch);
    }

    @Test void changedChapterParentRejectsBeforeAnyWrite() {
        Fixture f = new Fixture();
        when(f.chapters.selectCmsTopicChapterById(10L)).thenReturn(chapter(10, 7));
        when(f.chapters.selectContentByIdForUpdate(10L)).thenReturn(chapter(10, 8));
        CmsTopicChapter patch = new CmsTopicChapter(); patch.setId(10L);
        assertThrows(IllegalStateException.class, () -> f.chapterService.updateCmsTopicChapter(patch));
        verify(f.chapters, never()).updateCmsTopicChapter(any());
        verifyNoInteractions(f.canonical);
    }

    @Test void batchChapterDeleteLocksEveryParentBeforeAnyCurrentChild() {
        Fixture f = new Fixture();
        for (long id : List.of(10L, 20L)) {
            CmsTopicChapter row = chapter(id, id == 10 ? 99 : 1);
            when(f.chapters.selectCmsTopicChapterById(id)).thenReturn(row);
            when(f.chapters.selectContentByIdForUpdate(id)).thenReturn(row);
        }
        when(f.chapters.deleteCmsTopicChapterByIds(any())).thenReturn(2);
        assertEquals(2, f.chapterService.deleteCmsTopicChapterByIds(new Long[]{20L, 10L}));
        var order = inOrder(f.topics, f.chapters);
        order.verify(f.topics).lockTopicContentBudget(1L);
        order.verify(f.topics).lockTopicContentBudget(99L);
        order.verify(f.chapters).selectContentByIdForUpdate(10L);
        order.verify(f.chapters).selectContentByIdForUpdate(20L);
        order.verify(f.chapters).deleteCmsTopicChapterByIds(any());
    }

    @Test void chapterInsertOverflowRejectsBeforeCanonicalEffects() {
        Fixture f = new Fixture();
        when(f.chapters.countContentByTopicForUpdate(7L)).thenReturn(31L);
        when(f.chapters.insertCmsTopicChapter(any())).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> f.chapterService.insertCmsTopicChapter(chapter(10, 7)));
        verifyNoInteractions(f.canonical);
    }

    @Test void scopeRejectionNeverAcquiresParentOrChildWriteLocks() {
        Fixture f = new Fixture(); when(f.scope.canViewTopic(7L)).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> f.chapterService.insertCmsTopicChapter(chapter(10, 7)));
        verifyNoInteractions(f.topics, f.canonical);
        verify(f.chapters, never()).insertCmsTopicChapter(any());
        verify(f.chapters, never()).selectContentByIdForUpdate(anyLong());
    }

    @Test void nodeInsertUsesParentFenceAndPhysicalBudgetBeforeCanonicalEffects() {
        Fixture f = new Fixture();
        when(f.nodes.insertCmsTopicNode(any())).thenReturn(1);
        when(f.nodes.countContentByTopicForUpdate(7L)).thenReturn(301L);
        assertThrows(IllegalStateException.class, () -> f.nodeService.insertCmsTopicNode(node(10, 7)));
        var order = inOrder(f.topics, f.nodes);
        order.verify(f.topics).lockTopicContentBudget(7L);
        order.verify(f.nodes).insertCmsTopicNode(any());
        order.verify(f.nodes).countContentByTopicForUpdate(7L);
        verifyNoInteractions(f.canonical);
    }

    @Test void nodeRelocationLocksOldAndNewParentsInAscendingOrder() {
        Fixture f = new Fixture(); CmsTopicNode old = node(10, 99);
        when(f.nodes.selectCmsTopicNodeById(10L)).thenReturn(old);
        when(f.nodes.selectContentByIdForUpdate(10L)).thenReturn(old);
        when(f.nodes.updateCmsTopicNode(any())).thenReturn(1);
        CmsTopicNode patch = node(10, 1);
        assertEquals(1, f.nodeService.updateCmsTopicNode(patch));
        var order = inOrder(f.topics, f.nodes);
        order.verify(f.topics).lockTopicContentBudget(1L);
        order.verify(f.topics).lockTopicContentBudget(99L);
        order.verify(f.nodes).selectContentByIdForUpdate(10L);
        order.verify(f.nodes).updateCmsTopicNode(patch);
        order.verify(f.nodes).countContentByTopicForUpdate(1L);
    }

    @Test void changedNodeParentRejectsUnderTheFenceWithoutUpdating() {
        Fixture f = new Fixture();
        when(f.nodes.selectCmsTopicNodeById(10L)).thenReturn(node(10, 7));
        when(f.nodes.selectContentByIdForUpdate(10L)).thenReturn(node(10, 8));
        assertThrows(IllegalStateException.class, () -> f.nodeService.updateCmsTopicNode(node(10, 7)));
        verify(f.nodes, never()).updateCmsTopicNode(any());
        verifyNoInteractions(f.canonical);
    }

    @Test void bulkNodeDeleteLocksAllParentsThenRechecksOnlyLocatedIds() {
        Fixture f = new Fixture();
        for (long id : List.of(10L, 20L)) {
            CmsTopicNode row = node(id, id == 10 ? 99 : 1);
            when(f.nodes.selectCmsTopicNodeById(id)).thenReturn(row);
            when(f.nodes.selectContentByIdForUpdate(id)).thenReturn(row);
        }
        when(f.nodes.deleteCmsTopicNodeByIds(any())).thenReturn(2);
        assertEquals(2, f.nodeService.deleteCmsTopicNodeByIds(new Long[]{20L, 10L, 999L}));
        var order = inOrder(f.topics, f.nodes);
        order.verify(f.topics).lockTopicContentBudget(1L);
        order.verify(f.topics).lockTopicContentBudget(99L);
        order.verify(f.nodes).selectContentByIdForUpdate(10L);
        order.verify(f.nodes).selectContentByIdForUpdate(20L);
        order.verify(f.nodes).deleteCmsTopicNodeByIds(argThat(ids -> java.util.Arrays.equals(ids,new Long[]{10L,20L})));
    }

    @Test void missingNodeRetainsZeroRowResultsWithoutUnfencedWrites() {
        Fixture f = new Fixture();
        assertEquals(0, f.nodeService.updateCmsTopicNode(node(10, 7)));
        assertEquals(0, f.nodeService.deleteCmsTopicNodeById(10L));
        assertEquals(0, f.nodeService.deleteCmsTopicNodeByIds(new Long[]{10L}));
        verify(f.nodes, never()).updateCmsTopicNode(any());
        verify(f.nodes, never()).deleteCmsTopicNodeById(anyLong());
        verify(f.nodes, never()).deleteCmsTopicNodeByIds(any());
        verifyNoInteractions(f.topics, f.canonical);
    }

    private static CmsTopicChapter chapter(long id, long topic) {
        CmsTopicChapter row = new CmsTopicChapter(); row.setId(id); row.setTopicId(topic);
        row.setTermsMode("PERK"); row.setRecruitEnabled(0); return row;
    }
    private static CmsTopicNode node(long id, long topic) {
        CmsTopicNode row = new CmsTopicNode(); row.setId(id); row.setTopicId(topic); row.setChapterId(10L);
        row.setName("node"); row.setCheckinCode("existing-code"); return row;
    }
    private static final class Fixture {
        final CmsTopicMapper topics = mock(CmsTopicMapper.class);
        final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
        final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        final ITopicOperationsScopeAuthorizer scope = mock(ITopicOperationsScopeAuthorizer.class);
        final ITopicSaleCanonicalMutationService canonical = mock(ITopicSaleCanonicalMutationService.class);
        final CmsTopicChapterServiceImpl chapterService = new CmsTopicChapterServiceImpl();
        final CmsTopicNodeServiceImpl nodeService = new CmsTopicNodeServiceImpl();
        Fixture() {
            when(topics.lockTopicContentBudget(anyLong())).thenAnswer(call -> call.getArgument(0));
            when(topics.selectCmsTopicByIdForUpdate(anyLong())).thenAnswer(call -> {
                CmsTopic row = new CmsTopic(); row.setId(call.getArgument(0)); row.setDelFlag(0);
                row.setLifecycle(TopicLifecycleState.DRAFT.getCode()); return row;
            });
            when(scope.canViewTopic(anyLong())).thenReturn(true);
            TopicContentWriteGuard guard = new TopicContentWriteGuard(topics, chapters, null, null, null);
            ReflectionTestUtils.setField(guard, "cmsTopicNodeMapper", nodes);
            ReflectionTestUtils.setField(chapterService, "cmsTopicMapper", topics);
            ReflectionTestUtils.setField(chapterService, "cmsTopicChapterMapper", chapters);
            ReflectionTestUtils.setField(chapterService, "topicOperationsScopeAuthorizer", scope);
            ReflectionTestUtils.setField(chapterService, "contentWriteGuard", guard);
            ReflectionTestUtils.setField(chapterService, "merchantChapterApplicationMapper", mock(MerchantChapterApplicationMapper.class));
            ReflectionTestUtils.setField(chapterService, "merchantChapterOfferMapper", mock(MerchantChapterOfferMapper.class));
            ReflectionTestUtils.setField(chapterService, "commercialTopicPolicy", mock(CommercialTopicPolicy.class));
            ReflectionTestUtils.setField(chapterService, "productRoleService", mock(IProductRoleService.class));
            ReflectionTestUtils.setField(chapterService, "canonicalMutationService", canonical);
            ReflectionTestUtils.setField(nodeService, "cmsTopicNodeMapper", nodes);
            ReflectionTestUtils.setField(nodeService, "contentWriteGuard", guard);
            ReflectionTestUtils.setField(nodeService, "canonicalMutationService", canonical);
        }
    }
}

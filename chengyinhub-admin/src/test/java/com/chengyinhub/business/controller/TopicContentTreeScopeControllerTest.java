package com.chengyinhub.business.controller;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CmsTopicLike;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicLikeService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.business.CmsTopicChapterController;
import com.chengyinhub.web.controller.business.CmsTopicNodeController;
import com.chengyinhub.web.controller.business.CmsTopicLikeController;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 章节和节点必须继承父主题的运营范围，不能靠子资源 ID 绕过。 */
class TopicContentTreeScopeControllerTest {

    @Test
    void chapterInfoAndBatchDeleteFailClosedOnTheParentTopicScope() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ICmsTopicChapterService chapterService = mock(ICmsTopicChapterService.class);
        CmsTopicChapter hidden = new CmsTopicChapter();
        hidden.setId(11L);
        hidden.setTopicId(71L);
        when(chapterService.selectCmsTopicChapterById(11L)).thenReturn(hidden);
        when(scopeAuthorizer.canViewTopic(71L)).thenReturn(false);
        CmsTopicChapterController controller = new CmsTopicChapterController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", chapterService);

        AjaxResult detail = controller.getInfo(11L);
        AjaxResult deleted = controller.remove(new Long[]{11L});

        assertEquals(500, detail.get(AjaxResult.CODE_TAG));
        assertEquals(500, deleted.get(AjaxResult.CODE_TAG));
        verify(chapterService, never()).deleteCmsTopicChapterByIds(new Long[]{11L});
    }

    @Test
    void chapterListRequiresAnInScopeParentTopicBeforeQueryingRows() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ICmsTopicChapterService chapterService = mock(ICmsTopicChapterService.class);
        CmsTopicChapter query = new CmsTopicChapter();
        query.setTopicId(72L);
        when(scopeAuthorizer.canViewTopic(72L)).thenReturn(false);
        CmsTopicChapterController controller = new CmsTopicChapterController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", chapterService);

        controller.list(query);

        verify(chapterService, never()).selectCmsTopicChapterList(query);
    }

    @Test
    void chapterAddAndEditFailClosedOnTheParentTopicScope() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ICmsTopicChapterService chapterService = mock(ICmsTopicChapterService.class);
        CmsTopicChapter stored = new CmsTopicChapter();
        stored.setId(11L);
        stored.setTopicId(71L);
        when(chapterService.selectCmsTopicChapterById(11L)).thenReturn(stored);
        when(scopeAuthorizer.canViewTopic(71L)).thenReturn(false);
        CmsTopicChapterController controller = new CmsTopicChapterController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", chapterService);

        CmsTopicChapter create = new CmsTopicChapter();
        create.setTopicId(71L);
        CmsTopicChapter patch = new CmsTopicChapter();
        patch.setId(11L);
        AjaxResult added = controller.add(create);
        AjaxResult edited = controller.edit(patch);

        assertEquals(500, added.get(AjaxResult.CODE_TAG));
        assertEquals(500, edited.get(AjaxResult.CODE_TAG));
        verify(chapterService, never()).insertCmsTopicChapter(create);
        verify(chapterService, never()).updateCmsTopicChapter(patch);
    }

    @Test
    void nodeEditAndListFailClosedOnTheStoredParentTopicScope() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        CmsTopicNode stored = new CmsTopicNode();
        stored.setId(21L);
        stored.setTopicId(73L);
        stored.setChapterId(11L);
        CmsTopicNode request = new CmsTopicNode();
        request.setId(21L);
        request.setTopicId(999L);
        when(nodeService.selectCmsTopicNodeById(21L)).thenReturn(stored);
        when(scopeAuthorizer.canViewTopic(73L)).thenReturn(false);
        CmsTopicNodeController controller = new CmsTopicNodeController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodeService);

        AjaxResult result = controller.edit(request);
        CmsTopicNode listQuery = new CmsTopicNode();
        listQuery.setTopicId(73L);
        controller.list(listQuery);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(nodeService, never()).updateCmsTopicNode(request);
        verify(nodeService, never()).selectCmsTopicNodeList(listQuery);
    }

    @Test
    void nodeAddMustMatchTheStoredChapterTopicBeforeInsert() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        ICmsTopicChapterService chapterService = mock(ICmsTopicChapterService.class);
        CmsTopicNode request = new CmsTopicNode();
        request.setTopicId(73L);
        request.setChapterId(11L);
        CmsTopicChapter storedChapter = new CmsTopicChapter();
        storedChapter.setId(11L);
        storedChapter.setTopicId(74L);
        when(chapterService.selectCmsTopicChapterById(11L)).thenReturn(storedChapter);
        CmsTopicNodeController controller = new CmsTopicNodeController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", chapterService);

        AjaxResult result = controller.add(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(nodeService, never()).insertCmsTopicNode(request);
    }


    @Test
    void likeRecordsCannotBypassTheTopicScopeThroughTheirOwnIds() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ICmsTopicLikeService likeService = mock(ICmsTopicLikeService.class);
        CmsTopicLike hidden = new CmsTopicLike();
        hidden.setId(31L);
        hidden.setDataId(74L);
        when(likeService.selectCmsTopicLikeById(31L)).thenReturn(hidden);
        when(scopeAuthorizer.canViewTopic(74L)).thenReturn(false);
        CmsTopicLikeController controller = new CmsTopicLikeController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "cmsTopicLikeService", likeService);

        AjaxResult detail = controller.getInfo(31L);
        AjaxResult deleted = controller.remove(new Long[]{31L});

        assertEquals(500, detail.get(AjaxResult.CODE_TAG));
        assertEquals(500, deleted.get(AjaxResult.CODE_TAG));
        verify(likeService, never()).deleteCmsTopicLikeByIds(new Long[]{31L});
    }
}

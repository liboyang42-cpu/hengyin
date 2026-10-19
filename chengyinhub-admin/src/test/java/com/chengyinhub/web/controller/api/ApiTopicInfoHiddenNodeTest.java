package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.vo.TopicChapterVO;
import com.chengyinhub.business.domain.vo.TopicInfoVO;
import com.chengyinhub.business.domain.vo.TopicNodeVO;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IViewCollaboratorsService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * SC-UI-DATE-COUNT:后台「是否显示=隐藏」(cms_topic_node.status=0)的节点不进玩家侧主题详情。
 *
 * <p>站数口径(主题卡 locationCount、topic-manage-stats.nodeCount、/api/play/nodes)都只认
 * status=1;info-to-user 按章节查节点时原来不带 status,主题页章节行就把隐藏节点也数进「N 个节点」。
 *
 * <p>edit-detail 不收窄:作者保存是整体替换,回填少一个节点 = 保存时把它删掉。隐藏节点随回填带回,
 * 保存时按旧 id 保留隐藏态(TopicUpdateByOwnerTest#fullRebuild_keepsBackOfficeHiddenNodeHidden...),
 * 向导站数只数 status=1。
 */
class ApiTopicInfoHiddenNodeTest {

    private static final long VIEWER = 901L;
    private static final long TOPIC_ID = 7001L;
    private static final long CHAPTER_ID = 12L;

    private static CmsTopic topic() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(VIEWER);
        topic.setDelFlag(0);
        topic.setStatus(0);
        topic.setUserStatus(0);
        topic.setProductType(1);
        topic.setPublishMode("pro");
        topic.setLifecycle(0);
        topic.setName("测试主题");
        return topic;
    }

    private static CmsTopicNode node(long id, long status) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setTopicId(TOPIC_ID);
        n.setChapterId(CHAPTER_ID);
        n.setName("站" + id);
        n.setTemplateId(0L);
        n.setStatus(status);
        return n;
    }

    /** 与 CmsTopicNodeMapper.selectCmsTopicNodeList 同一语义:status 为 null 不过滤,非 null 按值过滤。 */
    private static ICmsTopicNodeService nodeServiceLikeMapper(List<CmsTopicNode> rows) {
        ICmsTopicNodeService service = mock(ICmsTopicNodeService.class);
        when(service.selectCmsTopicNodeList(any())).thenAnswer(inv -> {
            CmsTopicNode q = inv.getArgument(0);
            List<CmsTopicNode> out = new ArrayList<>();
            for (CmsTopicNode n : rows) {
                if (q.getChapterId() != null && !q.getChapterId().equals(n.getChapterId())) continue;
                if (q.getStatus() != null && !q.getStatus().equals(n.getStatus())) continue;
                out.add(n);
            }
            return out;
        });
        return service;
    }

    private static ICmsTopicChapterService oneChapter() {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER_ID);
        chapter.setTopicId(TOPIC_ID);
        chapter.setName("第二章");
        ICmsTopicChapterService service = mock(ICmsTopicChapterService.class);
        when(service.selectCmsTopicChapterList(any())).thenReturn(new ArrayList<>(Collections.singletonList(chapter)));
        return service;
    }

    private static final List<CmsTopicNode> ROWS = Arrays.asList(node(103L, 1L), node(104L, 0L));

    @Test
    void playerTopicInfo_chapterNodesExcludeHiddenNodes() {
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicDetailById(TOPIC_ID, VIEWER)).thenReturn(topic());
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic());
        ApiTopicController controller = spy(new ApiTopicController());
        ReflectionTestUtils.setField(controller, "merchantBusinessStatusService",
                mock(com.chengyinhub.business.service.IMerchantBusinessStatusService.class));
        doReturn(VIEWER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", oneChapter());
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodeServiceLikeMapper(ROWS));
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "omsTicketService", ticketService);
        ReflectionTestUtils.setField(controller, "viewCollaboratorsService", mock(IViewCollaboratorsService.class));
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService",
                mock(com.chengyinhub.business.service.ICmsRegistrationMerchantService.class));
        ReflectionTestUtils.setField(controller, "umsCommentService",
                mock(com.chengyinhub.business.service.IUmsCommentService.class));
        ReflectionTestUtils.setField(controller, "commentService",
                mock(com.chengyinhub.business.service.IViewCommentService.class));
        ReflectionTestUtils.setField(controller, "viewRegistrationService",
                mock(com.chengyinhub.business.service.IViewRegistrationService.class));
        ReflectionTestUtils.setField(controller, "cmsActivityService",
                mock(com.chengyinhub.business.service.ICmsActivityService.class));
        ReflectionTestUtils.setField(controller, "cmsRegistrationMapper",
                mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(controller, "playerNodeProgressService",
                mock(com.chengyinhub.business.service.IPlayerNodeProgressService.class));
        ReflectionTestUtils.setField(controller, "topicRouteService",
                mock(com.chengyinhub.business.service.ITopicRouteService.class));

        AjaxResult result = controller.topicInfo(String.valueOf(TOPIC_ID));

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        List<TopicChapterVO> chapters = ((TopicInfoVO) result.get("data")).getChaptersList();
        assertEquals(1, chapters.size());
        List<Long> ids = chapters.get(0).getNodes().stream().map(TopicNodeVO::getId).collect(Collectors.toList());
        assertEquals(Collections.singletonList(103L), ids, "status=0 的隐藏节点不能进玩家侧章节节点(主题页按它数「N 个节点」)");
    }

    @Test
    void editDetail_stillReturnsHiddenNodesForFullReplaceSave() {
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic());
        when(topicService.selectTopicIsDraft(TOPIC_ID)).thenReturn(1);
        ApiTopicController controller = spy(new ApiTopicController());
        doReturn(VIEWER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", oneChapter());
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodeServiceLikeMapper(ROWS));
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "omsTicketService", ticketService);
        IViewCollaboratorsService collaboratorService = mock(IViewCollaboratorsService.class);
        when(collaboratorService.selectViewCollaboratorsList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "viewCollaboratorsService", collaboratorService);

        AjaxResult result = controller.topicEditDetail(String.valueOf(TOPIC_ID), null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        @SuppressWarnings("unchecked")
        List<CmsTopicChapter> chapters = (List<CmsTopicChapter>) data.get("chapters");
        List<Long> ids = chapters.get(0).getCmsTopicNodeList().stream().map(CmsTopicNode::getId).collect(Collectors.toList());
        assertEquals(Arrays.asList(103L, 104L), ids, "作者回填仍带隐藏节点:保存是整体替换,少回一个就会在重存时删掉它");
    }
}

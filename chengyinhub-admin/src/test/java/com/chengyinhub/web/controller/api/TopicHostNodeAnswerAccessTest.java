package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPuzzlePlayService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /api/topic/host-node-answer 的准入契约(2026-11-01 业主:商家打开主题卡也能看剧情、玩法与答案)。
 * 只放行主题发布者本人与这一站已中标的承接商家;每道闸没过时答案读取链路一次都不能被碰到。
 */
class TopicHostNodeAnswerAccessTest {

    private static final Long TOPIC = 77L;
    private static final Long NODE = 501L;
    private static final Long ME = 7L;

    private ApiTopicController controller;
    private ICmsTopicService topicService;
    private ICmsTopicNodeService nodeService;
    private ICmsRegistrationMerchantService registrationMerchantService;
    private IPuzzlePlayService puzzlePlayService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiTopicController());
        topicService = mock(ICmsTopicService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        registrationMerchantService = mock(ICmsRegistrationMerchantService.class);
        puzzlePlayService = mock(IPuzzlePlayService.class);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", registrationMerchantService);
        ReflectionTestUtils.setField(controller, "puzzlePlayService", puzzlePlayService);
        doReturn(ME).when(controller).getAppUserId();
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(registrationMerchantService.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.emptyList());
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setTitle("门牌线索");
        template.setQuestionName("这条街最常出现的树是什么?");
        when(puzzlePlayService.selectPuzzleTemplate(any())).thenReturn(template);
        when(puzzlePlayService.revealText(any())).thenReturn("梧桐");
    }

    private Map<String, Object> request() {
        Map<String, Object> body = new HashMap<>();
        body.put("topicId", TOPIC);
        body.put("nodeId", NODE);
        return body;
    }

    private void topicOwnedBy(Long owner) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(owner);
        topic.setDelFlag(0);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
    }

    @Test
    void publisherSeesAnswer() {
        topicOwnedBy(ME);
        AjaxResult result = controller.hostNodeAnswer(request());
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertNotNull(data);
        assertEquals("梧桐", data.get("answerReveal"));
    }

    @Test
    void approvedUndertakerOfThatNodeSeesAnswer() {
        topicOwnedBy(9L);
        CmsRegistrationMerchant mine = new CmsRegistrationMerchant();
        mine.setNodeId(NODE);
        when(registrationMerchantService.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.singletonList(mine));
        assertEquals(200, controller.hostNodeAnswer(request()).get(AjaxResult.CODE_TAG));
    }

    @Test
    void undertakerOfAnotherNodeIsRejectedBeforeReadingAnswer() {
        topicOwnedBy(9L);
        CmsRegistrationMerchant other = new CmsRegistrationMerchant();
        other.setNodeId(999L);
        when(registrationMerchantService.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.singletonList(other));
        assertEquals(500, controller.hostNodeAnswer(request()).get(AjaxResult.CODE_TAG));
        verify(puzzlePlayService, never()).selectPuzzleTemplate(any());
    }

    @Test
    void strangerIsRejectedBeforeReadingAnswer() {
        topicOwnedBy(9L);
        assertEquals(500, controller.hostNodeAnswer(request()).get(AjaxResult.CODE_TAG));
        verify(puzzlePlayService, never()).selectPuzzleTemplate(any());
    }

    @Test
    void nodeFromAnotherTopicIsRejected() {
        topicOwnedBy(ME);
        CmsTopicNode foreign = new CmsTopicNode();
        foreign.setId(NODE);
        foreign.setTopicId(88L);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(foreign);
        assertEquals(500, controller.hostNodeAnswer(request()).get(AjaxResult.CODE_TAG));
        verify(puzzlePlayService, never()).selectPuzzleTemplate(any());
    }
}

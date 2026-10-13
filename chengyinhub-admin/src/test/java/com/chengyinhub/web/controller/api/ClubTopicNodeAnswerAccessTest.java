package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPuzzlePlayService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /api/club/topic-node-answer 的准入契约。
 *
 * <p>这条端点是**唯一**允许把 answerReveal/hint 下发到俱乐部端的出口 —— 玩家端的
 * /api/play/nodes 不承担这件事(它的揭示判据是玩家自己写的 member_spoiler_reveal)。
 * 所以这里每一道闸都要有一条负控:闸没过时,答案读取链路一次都不能被碰到。
 */
class ClubTopicNodeAnswerAccessTest {

    private static final Long CLUB = 42L;
    private static final Long TOPIC = 77L;
    private static final Long NODE = 501L;
    private static final Long ME = 7L;

    private ApiClubController controller;
    private IClubService clubService;
    private IClubMemberService memberService;
    private ICmsTopicService topicService;
    private ICmsTopicNodeService nodeService;
    private IPuzzlePlayService puzzlePlayService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiClubController());
        clubService = mock(IClubService.class);
        memberService = mock(IClubMemberService.class);
        topicService = mock(ICmsTopicService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        puzzlePlayService = mock(IPuzzlePlayService.class);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "clubMemberService", memberService);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(controller, "puzzlePlayService", puzzlePlayService);
        doReturn(ME).when(controller).getAppUserId();
    }

    private Map<String, Object> request() {
        Map<String, Object> body = new HashMap<>();
        body.put("clubId", CLUB);
        body.put("topicId", TOPIC);
        body.put("nodeId", NODE);
        return body;
    }

    private Club club() {
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(9L);
        return club;
    }

    private CmsTopic clubTopic(Long ownerClubId) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setClubId(ownerClubId);
        topic.setMemberId(9999L);   // 内容槽仍挂在原创作者名下(transfer-to-club 之后不变)
        return topic;
    }

    private CmsTopicNode node(Long ownerTopicId) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(ownerTopicId);
        node.setTemplateId(88L);
        return node;
    }

    private CmsMemberTemplate template() {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(88L);
        template.setTitle("门牌线索");
        template.setValidationMethod(1);
        template.setQuestionName("这条街最常出现的树是什么?");
        template.setAnswerReveal("梧桐");
        template.setHint1("抬头看树冠");
        template.setHint2("这条路的名字里就有");
        template.setFeedbackText("答案还没咬合,再看看四周……");
        return template;
    }

    @Test
    void 普通成员读不到答案且不触碰答案链路() {
        when(clubService.selectClubById(CLUB)).thenReturn(club());
        when(memberService.canGovernClub(any(Club.class), org.mockito.ArgumentMatchers.eq(ME))).thenReturn(false);

        AjaxResult result = controller.topicNodeAnswer(request());

        assertEquals("仅主理人或管理员可查看玩法答案", result.get("msg"));
        assertNull(result.get("data"), "被拒时不得夹带任何 data");
        verify(topicService, never()).selectCmsTopicById(any());
        verify(nodeService, never()).selectCmsTopicNodeById(any());
        verify(puzzlePlayService, never()).selectPuzzleTemplate(any());
        verify(puzzlePlayService, never()).revealText(any());
    }

    @Test
    void 管理员拿别团的topicId会被归属校验拦下() {
        when(clubService.selectClubById(CLUB)).thenReturn(club());
        when(memberService.canGovernClub(any(Club.class), org.mockito.ArgumentMatchers.eq(ME))).thenReturn(true);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(clubTopic(CLUB + 1));

        AjaxResult result = controller.topicNodeAnswer(request());

        assertEquals("该团不属于本俱乐部", result.get("msg"));
        verify(nodeService, never()).selectCmsTopicNodeById(any());
        verify(puzzlePlayService, never()).selectPuzzleTemplate(any());
    }

    @Test
    void 站点不属于本团时拒绝且不读模板() {
        when(clubService.selectClubById(CLUB)).thenReturn(club());
        when(memberService.canGovernClub(any(Club.class), org.mockito.ArgumentMatchers.eq(ME))).thenReturn(true);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(clubTopic(CLUB));
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node(TOPIC + 1));

        AjaxResult result = controller.topicNodeAnswer(request());

        assertEquals("该站点不属于本团", result.get("msg"));
        verify(puzzlePlayService, never()).selectPuzzleTemplate(any());
    }

    @Test
    void 未挂靠俱乐部的团一律拒绝() {
        when(clubService.selectClubById(CLUB)).thenReturn(club());
        when(memberService.canGovernClub(any(Club.class), org.mockito.ArgumentMatchers.eq(ME))).thenReturn(true);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(clubTopic(null));

        AjaxResult result = controller.topicNodeAnswer(request());

        assertEquals("该团不属于本俱乐部", result.get("msg"), "clubId 为空要 fail-closed,不能当成通配");
        verify(nodeService, never()).selectCmsTopicNodeById(any());
    }

    @Test
    void 非答题类玩法没有答案可看() {
        when(clubService.selectClubById(CLUB)).thenReturn(club());
        when(memberService.canGovernClub(any(Club.class), org.mockito.ArgumentMatchers.eq(ME))).thenReturn(true);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(clubTopic(CLUB));
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node(TOPIC));
        // selectPuzzleTemplate 只放行 validationMethod 1/3,拍照打卡这类返回 null
        when(puzzlePlayService.selectPuzzleTemplate(any(CmsTopicNode.class))).thenReturn(null);

        AjaxResult result = controller.topicNodeAnswer(request());

        assertEquals("该站点没有可查看的答案", result.get("msg"));
        verify(puzzlePlayService, never()).revealText(any());
    }

    @Test
    void 管理员四闸全过才拿到答案提示与话术() {
        when(clubService.selectClubById(CLUB)).thenReturn(club());
        when(memberService.canGovernClub(any(Club.class), org.mockito.ArgumentMatchers.eq(ME))).thenReturn(true);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(clubTopic(CLUB));
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node(TOPIC));
        CmsMemberTemplate template = template();
        when(puzzlePlayService.selectPuzzleTemplate(any(CmsTopicNode.class))).thenReturn(template);
        when(puzzlePlayService.revealText(template)).thenReturn("梧桐");

        AjaxResult result = controller.topicNodeAnswer(request());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertNotNull(data, "四闸全过必须真的拿到 data");
        assertEquals("梧桐", data.get("answerReveal"));
        assertEquals("这条街最常出现的树是什么?", data.get("question"));
        assertEquals("门牌线索", data.get("gameTitle"));
        assertEquals("答案还没咬合,再看看四周……", data.get("feedbackText"));
        @SuppressWarnings("unchecked")
        java.util.List<String> hints = (java.util.List<String>) data.get("hints");
        assertEquals(2, hints.size());
        assertEquals("抬头看树冠", hints.get(0));
    }

    @Test
    void 缺参数直接拒不落到任何读取() {
        Map<String, Object> body = new HashMap<>();
        body.put("clubId", CLUB);

        AjaxResult result = controller.topicNodeAnswer(body);

        assertEquals("缺少俱乐部、团或站点ID", result.get("msg"));
        verify(clubService, never()).selectClubById(any());
    }
}

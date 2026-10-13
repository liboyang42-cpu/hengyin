package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.MerchantChapterRecruitmentStats;
import com.chengyinhub.business.domain.vo.TopicInfoVO;
import com.chengyinhub.business.mapper.CmsTopicLikeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsCommentMapper;
import com.chengyinhub.business.mapper.UmsMemberFollowMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicLikeService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMerchantChapterApplicationService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.ITopicContentPublisherProjectionService;
import com.chengyinhub.business.service.ITopicRouteService;
import com.chengyinhub.business.service.IUmsCommentService;
import com.chengyinhub.business.service.IUmsMemberFollowService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IViewCollaboratorsService;
import com.chengyinhub.business.service.IViewCommentService;
import com.chengyinhub.business.service.IViewRegistrationService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * `/api/topic/list-to-merchant` 的名额投影 wire 级约束。
 *
 * <p>断言序列化后的 JSON,因为只有这一层能同时抓住
 * 「字段没进白名单 VO」和「字段进了 VO 但没人填」两种失败。
 */
class ApiTopicListToMerchantCapacityTest {

    private static final Long MERCHANT_VIEWER = 900L;

    @BeforeEach
    void bindRequest() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void unbindRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 有开放招商章节 ⇒ 真实剩余名额必须到 wire。 */
    @Test
    void remainingCountReachesTheWire() throws Exception {
        CmsTopic topic = topic(1L);
        Fixture f = fixture(topic, Arrays.asList(openChapter(11L, 5)), statsOf(11L, 2));

        AjaxResult res = f.controller.topicListToMerchant();
        assertEquals(200, res.get("code"), res.toString());

        String json = new ObjectMapper().writeValueAsString(firstRow(res));
        assertTrue(json.contains("\"remainingMerchantCount\":2"),
                "剩余名额没到 wire —— B 的「已满」角标拿不到真实计数。实际: " + json);
    }

    /** 全部占满 ⇒ 0 必须如实下发,这才是「已满」。 */
    @Test
    void fullTopicEmitsZero() throws Exception {
        CmsTopic topic = topic(1L);
        Fixture f = fixture(topic, Arrays.asList(openChapter(11L, 3)), statsOf(11L, 0));

        String json = new ObjectMapper()
                .writeValueAsString(firstRow(f.controller.topicListToMerchant()));
        assertTrue(json.contains("\"remainingMerchantCount\":0"),
                "已满必须如实下发 0。实际: " + json);
    }

    /**
     * ★ 没有开放招商的章节 ⇒ 字段为 null,<b>绝不能伪造成 0</b>。
     * 0 会被前端渲染成「已满」,而事实是这个主题压根不招商。
     */
    @Test
    void topicWithoutOpenChapterDoesNotFabricateFull() {
        CmsTopic topic = topic(1L);
        CmsTopicChapter closed = openChapter(11L, 5);
        closed.setRecruitEnabled(0);
        Fixture f = fixture(topic, Arrays.asList(closed), Collections.emptyMap());

        TopicInfoVO row = firstRow(f.controller.topicListToMerchant());
        assertNull(row.getRemainingMerchantCount(),
                "不招商的主题必须留空,不得伪造成 0(会被渲染成「已满」)");
    }

    /** 不限名额 ⇒ null,不下发数字。 */
    @Test
    void unlimitedCapacityEmitsNoNumber() throws Exception {
        CmsTopic topic = topic(1L);
        Fixture f = fixture(topic, Arrays.asList(openChapter(11L, 0)), statsOf(11L, null));

        TopicInfoVO row = firstRow(f.controller.topicListToMerchant());
        assertNull(row.getRemainingMerchantCount(), "不限名额不能下发成某个数字");
        String json = new ObjectMapper().writeValueAsString(row);
        assertFalse(json.contains("\"remainingMerchantCount\":0"),
                "不限名额被写成了 0,会被渲染成「已满」。实际: " + json);
    }

    /**
     * ★ 禁 N+1:整页统计只允许查一次,不论多少主题。
     * 逐主题调用会让列表页的查询数随主题数线性膨胀。
     */
    @Test
    void statsAreFetchedOnceForTheWholePage() {
        List<CmsTopic> topics = Arrays.asList(topic(1L), topic(2L), topic(3L));
        Map<Long, MerchantChapterRecruitmentStats> stats = new HashMap<>();
        stats.put(11L, statRemaining(1));
        Fixture f = fixture(topics, Arrays.asList(openChapter(11L, 5)), stats);

        f.controller.topicListToMerchant();

        verify(f.recruitService, times(1)).getRecruitmentStatsByChapters(any());
        // 单章版是 N+1 的来源,列表页一次都不许调。
        verify(f.recruitService, times(0)).getRecruitmentStats(anyLong(), any());
    }

    // ====== helpers ======

    @Test
    void compatibilityTypeDoesNotGrantRecruitmentListToPlayerRole() {
        Fixture f = fixture(Collections.<CmsTopic>emptyList(), Collections.<CmsTopicChapter>emptyList(), Collections.<Long, MerchantChapterRecruitmentStats>emptyMap());
        IUmsMemberService members = (IUmsMemberService) ReflectionTestUtils.getField(f.controller, "umsMemberService");
        UmsMember player = new UmsMember(); player.setId(MERCHANT_VIEWER); player.setStatus(1L);
        player.setRole("player"); player.setUserType(2L);
        when(members.selectUmsMemberById(anyLong())).thenReturn(player);
        assertEquals("商家才能报名", f.controller.topicListToMerchant().get("msg"));
    }

    private static class Fixture {
        ApiTopicController controller;
        IMerchantChapterApplicationService recruitService;
    }

    private TopicInfoVO firstRow(AjaxResult res) {
        return (TopicInfoVO) ((TableDataInfo) res.get("data")).getRows().get(0);
    }

    private Map<Long, MerchantChapterRecruitmentStats> statsOf(Long chapterId, Integer remaining) {
        Map<Long, MerchantChapterRecruitmentStats> map = new HashMap<>();
        map.put(chapterId, statRemaining(remaining));
        return map;
    }

    private static MerchantChapterRecruitmentStats statRemaining(Integer remaining) {
        MerchantChapterRecruitmentStats stats = new MerchantChapterRecruitmentStats();
        stats.setRemainingMerchantCount(remaining);
        return stats;
    }

    private CmsTopic topic(Long id) {
        CmsTopic topic = new CmsTopic();
        topic.setId(id);
        topic.setName("赛博夜巡" + id);
        topic.setDelFlag(0);
        topic.setStatus(1);
        topic.setMerchantStatus(1);
        return topic;
    }

    private CmsTopicChapter openChapter(Long id, Integer maxMerchant) {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(id);
        chapter.setName("第一章");
        chapter.setRecruitEnabled(1);
        chapter.setMaxMerchant(maxMerchant);
        return chapter;
    }

    private Fixture fixture(CmsTopic topic, List<CmsTopicChapter> chapters,
                            Map<Long, MerchantChapterRecruitmentStats> stats) {
        return fixture(Collections.singletonList(topic), chapters, stats);
    }

    private Fixture fixture(List<CmsTopic> topics, List<CmsTopicChapter> chapters,
                            Map<Long, MerchantChapterRecruitmentStats> stats) {
        ICmsTopicService topicSvc = mock(ICmsTopicService.class);
        when(topicSvc.selectCmsTopicList(any())).thenReturn(new ArrayList<>(topics));

        ICmsTopicChapterService chapterSvc = mock(ICmsTopicChapterService.class);
        when(chapterSvc.selectCmsTopicChapterList(any())).thenReturn(new ArrayList<>(chapters));

        IUmsMemberService memberSvc = mock(IUmsMemberService.class);
        UmsMember merchant = new UmsMember();
        merchant.setId(MERCHANT_VIEWER);
        merchant.setStatus(1L);
        merchant.setUserType(1L); // 旧兼容字段不能否决真实商家角色
        merchant.setRole("merchant");
        when(memberSvc.selectUmsMemberById(anyLong())).thenReturn(merchant);

        IMerchantChapterApplicationService recruitSvc = mock(IMerchantChapterApplicationService.class);
        when(recruitSvc.getRecruitmentStatsByChapters(any())).thenReturn(stats);

        ITopicContentPublisherProjectionService publisher =
                mock(ITopicContentPublisherProjectionService.class);
        when(publisher.project(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiTopicController c = spy(new ApiTopicController());
        doReturn(MERCHANT_VIEWER).when(c).getAppUserId();
        ReflectionTestUtils.setField(c, "cmsTopicService", topicSvc);
        ReflectionTestUtils.setField(c, "cmsTopicChapterService", chapterSvc);
        ReflectionTestUtils.setField(c, "umsMemberService", memberSvc);
        ReflectionTestUtils.setField(c, "merchantChapterApplicationService", recruitSvc);
        ReflectionTestUtils.setField(c, "topicContentPublisherProjectionService", publisher);
        ReflectionTestUtils.setField(c, "cmsTopicNodeService", mock(ICmsTopicNodeService.class));
        ReflectionTestUtils.setField(c, "memberTemplateService", mock(ICmsMemberTemplateService.class));
        ReflectionTestUtils.setField(c, "cmsRegistrationMerchantService", mock(ICmsRegistrationMerchantService.class));
        ReflectionTestUtils.setField(c, "cmsTopicLikeService", mock(ICmsTopicLikeService.class));
        ReflectionTestUtils.setField(c, "umsMemberFollowService", mock(IUmsMemberFollowService.class));
        ReflectionTestUtils.setField(c, "umsCommentService", mock(IUmsCommentService.class));
        ReflectionTestUtils.setField(c, "omsTicketService", mock(IOmsTicketService.class));
        ReflectionTestUtils.setField(c, "topicRouteService", mock(ITopicRouteService.class));
        ReflectionTestUtils.setField(c, "mmsMerchantService", mock(IMmsMerchantService.class));
        ReflectionTestUtils.setField(c, "viewCollaboratorsService", mock(IViewCollaboratorsService.class));
        ReflectionTestUtils.setField(c, "commentService", mock(IViewCommentService.class));
        ReflectionTestUtils.setField(c, "viewRegistrationService", mock(IViewRegistrationService.class));
        ReflectionTestUtils.setField(c, "cmsActivityService", mock(ICmsActivityService.class));
        CmsTopicLikeMapper tlm = mock(CmsTopicLikeMapper.class);
        when(tlm.selectCmsTopicLikesByMemberAndDataIds(any(), any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(c, "cmsTopicLikeMapper", tlm);
        UmsMemberFollowMapper mfm = mock(UmsMemberFollowMapper.class);
        when(mfm.selectUmsMemberFollowsByMemberAndFollowMemberIds(any(), any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(c, "umsMemberFollowMapper", mfm);
        OmsTicketMapper otm = mock(OmsTicketMapper.class);
        when(otm.selectMinTicketPrices(any(), any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(c, "omsTicketMapper", otm);
        UmsCommentMapper ucm = mock(UmsCommentMapper.class);
        when(ucm.selectRatingSummaryByOwners(any(), any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(c, "umsCommentMapper", ucm);

        Fixture f = new Fixture();
        f.controller = c;
        f.recruitService = recruitSvc;
        return f;
    }
}

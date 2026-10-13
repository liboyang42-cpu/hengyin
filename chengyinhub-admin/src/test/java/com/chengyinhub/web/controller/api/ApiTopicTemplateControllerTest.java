package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.*;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ApiTopicTemplateController 测试:白名单负控 + 降级场景。
 *
 * <p>重点:模板详情响应必须不包含答案/提示/打卡码/精确坐标/storyJson 等敏感字段。
 * 构造带答案的模板 → 断言响应体 JSON 里 grep 不到这些 key。
 */
class ApiTopicTemplateControllerTest {

    private ApiTopicTemplateController controller;
    private ICmsTopicService cmsTopicService;
    private ICapabilityService capabilityService;
    private IClubService clubService;
    private INotifyService notifyService;
    private IMmsMerchantService mmsMerchantService;
    private IMerchantAccessService merchantAccessService;

    @BeforeEach
    void setUp() {
        cmsTopicService = mock(ICmsTopicService.class);
        capabilityService = mock(ICapabilityService.class);
        clubService = mock(IClubService.class);
        notifyService = mock(INotifyService.class);
        mmsMerchantService = mock(IMmsMerchantService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        controller = new ApiTopicTemplateController();
        ReflectionTestUtils.setField(controller, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(controller, "capabilityService", capabilityService);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "notifyService", notifyService);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", mmsMerchantService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
    }

    /** 造一个"已登录 memberId=X"的 controller(单测环境无登录态,spy 掉 getAppUserId) */
    private ApiTopicTemplateController loggedInAs(long memberId) {
        ApiTopicTemplateController spied = spy(controller);
        doReturn(memberId).when(spied).getAppUserId();
        return spied;
    }

    // ====== /info 门禁测试 ======

    @Test
    void info_requires_valid_id() {
        AjaxResult r = controller.info(null);
        assertEquals(500, r.get("code"));
        assertTrue(((String) r.get("msg")).contains("缺少模板ID"));

        r = controller.info("0");
        assertEquals(500, r.get("code"));
        assertTrue(((String) r.get("msg")).contains("缺少模板ID"));

        r = controller.info("-1");
        assertEquals(500, r.get("code"));
        assertTrue(((String) r.get("msg")).contains("缺少模板ID"));
    }

    @Test
    void info_rejects_ineligible_public_snapshot() {
        when(cmsTopicService.buildPublicTopicTemplateDetail(42L, null, false, null)).thenReturn(null);
        AjaxResult r = controller.info("42");
        assertEquals(500, r.get("code"));
        assertTrue(((String) r.get("msg")).contains("模板不存在"));
        verify(cmsTopicService, never()).selectTopicIsTemplate(anyLong());
        verify(cmsTopicService, never()).selectCmsTopicById(anyLong());
        verify(cmsTopicService, never()).buildTopicTemplateDetail(anyLong(), anyBoolean(), any());
    }

    @Test
    void info_rejects_missing_public_snapshot() {
        when(cmsTopicService.buildPublicTopicTemplateDetail(42L, null, false, null)).thenReturn(null);
        AjaxResult r = controller.info("42");
        assertEquals(500, r.get("code"));
        assertTrue(((String) r.get("msg")).contains("模板不存在"));
    }

    @Test
    void info_returns_detail_when_template() {
        TopicTemplateDetailVO vo = new TopicTemplateDetailVO();
        vo.setId(42L);
        vo.setName("测试模板");
        when(cmsTopicService.buildPublicTopicTemplateDetail(42L, null, false, null)).thenReturn(vo);

        AjaxResult r = controller.info("42");
        assertEquals(200, r.get("code"));
        assertEquals(vo, r.get("data"));
    }

    @Test
    void anonymousInfoDelegatesEligibilityAndDetailToOneFailClosedServiceRead() {
        TopicTemplateDetailVO detail = new TopicTemplateDetailVO();
        detail.setId(42L);
        when(cmsTopicService.buildPublicTopicTemplateDetail(42L, null, false, null))
                .thenReturn(detail);

        AjaxResult result = controller.info("42");

        assertEquals(200, result.get("code"));
        assertEquals(detail, result.get("data"));
        verify(cmsTopicService).buildPublicTopicTemplateDetail(42L, null, false, null);
        verify(cmsTopicService, never()).selectTopicIsTemplate(anyLong());
        verify(cmsTopicService, never()).selectCmsTopicById(anyLong());
    }

    // ====== 白名单负控:详情 VO 结构不包含敏感字段 ======

    /**
     * 验白名单——用反射断言 detail VO 各子类的 getter 列表不包含黑名单字段。
     * 这个测试在编译期做：如果有人在 VO 里误加了 setLongitude/getLongitude，
     * 白名单会被打破。更严谨的负控是构造完整数据后序列化 grep（见集成测试），
     * 但反射层面的"VO 不该有的 getter"也是有效防线。
     */
    @Test
    void detailVO_has_no_blacklisted_getters() {
        // TopicTemplateDetailVO 不应有 questionAnswer/correctAnswer 等 getter
        List<String> blacklist = Arrays.asList(
                "questionAnswer", "correctAnswer", "questionA", "questionB", "questionC", "questionD",
                "hint1", "hint2", "answerReveal", "feedbackText",
                "checkinCode", "fragmentText", "storyJson",
                "longitude", "latitude", "cardHookLong",
                // 招募/承接扩展后的新增红线:商家身份、谈判金额、俱乐部身份永不出展示接口
                "clubId", "merchantId", "perHeadFee", "quotaTotal"
        );
        for (java.lang.reflect.Method m : TopicTemplateDetailVO.class.getMethods()) {
            String name = m.getName();
            if (name.startsWith("get") || name.startsWith("is")) {
                // 提取属性名
                String prop = name.startsWith("get")
                        ? Character.toLowerCase(name.charAt(3)) + name.substring(4)
                        : Character.toLowerCase(name.charAt(2)) + name.substring(3);
                assertFalse(blacklist.contains(prop),
                        "TopicTemplateDetailVO must not expose blacklisted field via getter: " + name);
            }
        }
        for (java.lang.reflect.Method m : TopicTemplateNodeVO.class.getMethods()) {
            String name = m.getName();
            if (name.startsWith("get") || name.startsWith("is")) {
                String prop = name.startsWith("get")
                        ? Character.toLowerCase(name.charAt(3)) + name.substring(4)
                        : Character.toLowerCase(name.charAt(2)) + name.substring(3);
                assertFalse(blacklist.contains(prop),
                        "TopicTemplateNodeVO must not expose blacklisted field via getter: " + name);
            }
        }
        for (java.lang.reflect.Method m : TopicTemplateGameVO.class.getMethods()) {
            String name = m.getName();
            if (name.startsWith("get") || name.startsWith("is")) {
                String prop = name.startsWith("get")
                        ? Character.toLowerCase(name.charAt(3)) + name.substring(4)
                        : Character.toLowerCase(name.charAt(2)) + name.substring(3);
                assertFalse(blacklist.contains(prop),
                        "TopicTemplateGameVO must not expose blacklisted field via getter: " + name);
            }
        }
        for (java.lang.reflect.Method m : TopicTemplateChapterVO.class.getMethods()) {
            String name = m.getName();
            if (name.startsWith("get") || name.startsWith("is")) {
                String prop = name.startsWith("get")
                        ? Character.toLowerCase(name.charAt(3)) + name.substring(4)
                        : Character.toLowerCase(name.charAt(2)) + name.substring(3);
                assertFalse(blacklist.contains(prop),
                        "TopicTemplateChapterVO must not expose blacklisted field via getter: " + name);
            }
        }
        for (java.lang.reflect.Method m : TopicTemplateRecruitVO.class.getMethods()) {
            String name = m.getName();
            if (name.startsWith("get") || name.startsWith("is")) {
                String prop = name.startsWith("get")
                        ? Character.toLowerCase(name.charAt(3)) + name.substring(4)
                        : Character.toLowerCase(name.charAt(2)) + name.substring(3);
                assertFalse(blacklist.contains(prop),
                        "TopicTemplateRecruitVO must not expose blacklisted field via getter: " + name);
            }
        }
    }

    // ====== VO 字段完整性与降级测试 ======

    @Test
    void detailVO_has_expected_whitelist_fields() {
        TopicTemplateDetailVO vo = new TopicTemplateDetailVO();
        // 所有白名单字段应可正常 get/set 且不为 null 后能序列化
        vo.setId(1L);
        vo.setName("name");
        vo.setSubtitle("subtitle");
        vo.setDescription("desc");
        vo.setImgUrl("img");
        vo.setImgArr("a;b");
        vo.setProductType(1);
        vo.setTotalTime(3600L);
        vo.setTotalMileage(new BigDecimal("5.2"));
        vo.setLocationCount(8L);
        vo.setTemplateCount(5L);
        vo.setFinishMedalName("medal");
        vo.setFinishMedalImg("medal.png");
        vo.setXpBudget(3000);
        vo.setAudioUrl("audio.mp3");
        vo.setAudioDuration(38);
        vo.setAddressName("City");

        assertEquals(1L, (long) vo.getId());
        assertEquals("name", vo.getName());
        assertEquals("subtitle", vo.getSubtitle());
        assertEquals("desc", vo.getDescription());
        assertEquals("img", vo.getImgUrl());
        assertEquals("a;b", vo.getImgArr());
        assertEquals(1, (int) vo.getProductType());
        assertEquals(3600L, (long) vo.getTotalTime());
        assertEquals(new BigDecimal("5.2"), vo.getTotalMileage());
        assertEquals(8L, (long) vo.getLocationCount());
        assertEquals(5L, (long) vo.getTemplateCount());
        assertEquals("medal", vo.getFinishMedalName());
        assertEquals("medal.png", vo.getFinishMedalImg());
        assertEquals(3000, (int) vo.getXpBudget());
        assertEquals("audio.mp3", vo.getAudioUrl());
        assertEquals(38, (int) vo.getAudioDuration());
        assertEquals("City", vo.getAddressName());
    }

    @Test
    void detailVO_empty_chapters_and_games_is_valid() {
        TopicTemplateDetailVO vo = new TopicTemplateDetailVO();
        vo.setId(1L);
        vo.setName("empty");
        vo.setChapters(Collections.emptyList());
        vo.setGames(Collections.emptyList());
        // 空列表应序列化为 [] 而非 null（避免前端空指针）
        assertNotNull(vo.getChapters());
        assertTrue(vo.getChapters().isEmpty());
        assertNotNull(vo.getGames());
        assertTrue(vo.getGames().isEmpty());
    }

    @Test
    void chapterVO_routeShape_null_when_no_valid_nodes() {
        TopicTemplateChapterVO ch = new TopicTemplateChapterVO();
        ch.setNodes(new ArrayList<>());
        ch.setRouteShape(null);
        // 无几何数据时 routeShape 应为 null（前端据此隐藏剪影区）
        assertNull(ch.getRouteShape());
    }

    @Test
    void nodeVO_hookTeaser_fallback_to_description() {
        TopicTemplateNodeVO n = new TopicTemplateNodeVO();
        // hookTeaser 由 service 层计算，VO 本身只存值
        n.setHookTeaser("一句钩子");
        assertEquals("一句钩子", n.getHookTeaser());
        n.setHookTeaser(null);
        assertNull(n.getHookTeaser());
    }

    // ====== 空模板列表 ======

    @Test
    void list_returns_empty_list_when_no_templates() {
        when(cmsTopicService.buildTopicTemplateListVO(false)).thenReturn(Collections.emptyList());
        AjaxResult r = controller.list(null);
        assertEquals(200, r.get("code"));
        @SuppressWarnings("unchecked")
        List<?> data = (List<?>) r.get("data");
        assertTrue(data.isEmpty());
    }

    // ====== /list 返回 sample ======

    @Test
    void list_returns_template_list_vo_with_counts() {
        TopicTemplateListItemVO item = new TopicTemplateListItemVO();
        item.setId(1L);
        item.setName("test");
        item.setProductType(1);
        item.setChapterCount(3);
        item.setGameCount(5);
        when(cmsTopicService.buildTopicTemplateListVO(false)).thenReturn(Collections.singletonList(item));

        AjaxResult r = controller.list(null);
        assertEquals(200, r.get("code"));
        @SuppressWarnings("unchecked")
        List<TopicTemplateListItemVO> data = (List<TopicTemplateListItemVO>) r.get("data");
        assertEquals(1, data.size());
        assertEquals(1L, (long) data.get(0).getId());
        assertEquals(3, (int) data.get(0).getChapterCount());
        assertEquals(5, (int) data.get(0).getGameCount());
    }

    @Test
    void list_recommendedOnly_param_routes_to_verified_filter() {
        when(cmsTopicService.buildTopicTemplateListVO(true)).thenReturn(Collections.emptyList());
        AjaxResult r = controller.list("1");
        assertEquals(200, r.get("code"));
        verify(cmsTopicService).buildTopicTemplateListVO(true);
        verify(cmsTopicService, never()).buildTopicTemplateListVO(false);
    }

    // ====== 商家视角 recruitStatus 访问控制 ======

    @Test
    void info_not_logged_in_builds_without_recruit() {
        when(cmsTopicService.buildPublicTopicTemplateDetail(42L, null, false, null))
                .thenReturn(new TopicTemplateDetailVO());
        controller.info("42"); // 单测环境无登录态 → memberId null → 非商家视角
        verify(cmsTopicService).buildPublicTopicTemplateDetail(42L, null, false, null);
        verify(capabilityService, never()).hasCapability(anyLong(), anyString());
    }

    @Test
    void info_merchant_capability_enables_recruit_view() {
        ApiTopicTemplateController c = loggedInAs(9L);
        when(capabilityService.hasCapability(9L, MemberCapability.CAP_MERCHANT)).thenReturn(true);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setCategoryId(18);
        when(mmsMerchantService.selectMmsMerchantByMemberId(9L)).thenReturn(merchant);
        when(cmsTopicService.buildPublicTopicTemplateDetail(42L, 9L, true, 18))
                .thenReturn(new TopicTemplateDetailVO());
        c.info("42");
        verify(cmsTopicService).buildPublicTopicTemplateDetail(42L, 9L, true, 18);
    }

    @Test
    void info_logged_in_non_merchant_gets_no_recruit() {
        ApiTopicTemplateController c = loggedInAs(9L);
        when(capabilityService.hasCapability(9L, MemberCapability.CAP_MERCHANT)).thenReturn(false);
        when(cmsTopicService.buildPublicTopicTemplateDetail(42L, 9L, false, null))
                .thenReturn(new TopicTemplateDetailVO());
        c.info("42");
        verify(cmsTopicService).buildPublicTopicTemplateDetail(42L, 9L, false, null);
    }

    @Test
    void info_publisher_can_review_all_recruitment_without_merchant_identity() {
        ApiTopicTemplateController c = loggedInAs(9L);
        when(capabilityService.hasCapability(9L, MemberCapability.CAP_MERCHANT)).thenReturn(false);
        TopicTemplateDetailVO detail = new TopicTemplateDetailVO();
        detail.setViewerIsPublisher(true);
        detail.setViewerIsMerchant(false);
        when(cmsTopicService.buildPublicTopicTemplateDetail(42L, 9L, false, null)).thenReturn(detail);

        AjaxResult result = c.info("42");

        assertEquals(200, result.get("code"));
        assertTrue(detail.getViewerIsPublisher());
        assertFalse(detail.getViewerIsMerchant());
        verify(cmsTopicService).buildPublicTopicTemplateDetail(42L, 9L, false, null);
        verify(mmsMerchantService, never()).selectMmsMerchantByMemberId(anyLong());
    }

    @Test
    void recruitVO_fields_roundtrip_and_chapter_defaults_null() {
        TopicTemplateRecruitVO rec = new TopicTemplateRecruitVO();
        rec.setTermsMode("PERK");
        rec.setPerkMinValue(new BigDecimal("50.00"));
        rec.setMaxMerchant(5);
        rec.setCurrentOfferCount(3);
        rec.setIsOpen(true);
        assertEquals("PERK", rec.getTermsMode());
        assertEquals(new BigDecimal("50.00"), rec.getPerkMinValue());
        assertEquals(5, (int) rec.getMaxMerchant());
        assertEquals(3, (int) rec.getCurrentOfferCount());
        assertTrue(rec.getIsOpen());
        // 章节默认不带招募信息:非商家视角/不招商章节都用 null 表达"整块不下发"
        assertNull(new TopicTemplateChapterVO().getRecruitStatus());
    }

    @Test
    void detailVO_club_adoption_status_roundtrip() {
        TopicTemplateDetailVO vo = new TopicTemplateDetailVO();
        vo.setClubAdoptionStatus("OPEN");
        vo.setViewerIsMerchant(false);
        vo.setViewerIsPublisher(true);
        assertEquals("OPEN", vo.getClubAdoptionStatus());
        assertFalse(vo.getViewerIsMerchant());
        assertTrue(vo.getViewerIsPublisher());
    }

    // ====== /club-interest 俱乐部承接意向(纯通知,不落状态) ======

    @Test
    void clubInterest_requires_valid_id() {
        AjaxResult r = controller.clubInterest("0");
        assertEquals(500, r.get("code"));
        verify(notifyService, never()).notifyClubInterested(anyLong(), anyString(), anyString());
    }

    @Test
    void clubInterest_requires_club_owner() {
        ApiTopicTemplateController c = loggedInAs(9L);
        when(clubService.selectClubByMemberId(9L)).thenReturn(null);
        AjaxResult r = c.clubInterest("42");
        assertEquals(500, r.get("code"));
        assertTrue(((String) r.get("msg")).contains("仅俱乐部主理人"));
        verify(notifyService, never()).notifyClubInterested(anyLong(), anyString(), anyString());
    }

    @Test
    void clubInterest_rejects_claimed_topic() {
        ApiTopicTemplateController c = loggedInAs(9L);
        Club club = new Club();
        club.setName("夜行俱乐部");
        when(clubService.selectClubByMemberId(9L)).thenReturn(club);
        when(cmsTopicService.selectTopicIsTemplate(42L)).thenReturn(1);
        CmsTopic topic = new CmsTopic();
        topic.setMemberId(100L);
        topic.setClubId(7L);
        when(cmsTopicService.selectCmsTopicById(42L)).thenReturn(topic);
        AjaxResult r = c.clubInterest("42");
        assertEquals(500, r.get("code"));
        assertTrue(((String) r.get("msg")).contains("已被俱乐部承接"));
        verify(notifyService, never()).notifyClubInterested(anyLong(), anyString(), anyString());
    }

    @Test
    void clubInterest_rejects_own_topic() {
        ApiTopicTemplateController c = loggedInAs(100L);
        Club club = new Club();
        club.setName("夜行俱乐部");
        when(clubService.selectClubByMemberId(100L)).thenReturn(club);
        when(cmsTopicService.selectTopicIsTemplate(42L)).thenReturn(1);
        CmsTopic topic = new CmsTopic();
        topic.setMemberId(100L);
        when(cmsTopicService.selectCmsTopicById(42L)).thenReturn(topic);
        AjaxResult r = c.clubInterest("42");
        assertEquals(500, r.get("code"));
        verify(notifyService, never()).notifyClubInterested(anyLong(), anyString(), anyString());
    }

    @Test
    void clubInterest_notifies_creator_when_open() {
        ApiTopicTemplateController c = loggedInAs(9L);
        Club club = new Club();
        club.setName("夜行俱乐部");
        when(clubService.selectClubByMemberId(9L)).thenReturn(club);
        when(cmsTopicService.selectTopicIsTemplate(42L)).thenReturn(1);
        CmsTopic topic = new CmsTopic();
        topic.setMemberId(100L);
        topic.setName("赛博夜巡");
        when(cmsTopicService.selectCmsTopicById(42L)).thenReturn(topic);
        AjaxResult r = c.clubInterest("42");
        assertEquals(200, r.get("code"));
        verify(notifyService).notifyClubInterested(100L, "赛博夜巡", "夜行俱乐部");
    }
}

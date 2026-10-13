package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.PublicMemberVO;
import com.chengyinhub.business.domain.vo.SelfMemberVO;
import com.chengyinhub.business.domain.vo.PublicMemberVO.PublicCategoryVO;
import com.chengyinhub.business.service.ICmsActivityLikeService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicLikeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IUmsCreativeSquareLikeService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IUmsMemberFollowService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IPublicMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiUmsMemberControllerPrivacyTest {

    // 2026-08-29 显式新增 identityKind —— 搜索页要在名字后面挂 BIZ / CLUB 标签。
    //
    // ⚠️ 这是**唯一**一次为该功能扩这份精确键集，扩的是一个封顶枚举字段:
    //    identityKind 只可能是 "BIZ" / "CLUB" / null，值域在 SQL 里就收敛
    //    (CASE WHEN user_type = 2 THEN 'BIZ' ELSE NULL END)。
    //    账号级的 user_type **没有**被放出来，它仍在 ApiUserPublicInfoTest 的匿名禁止列表里，
    //    与 password / phone / openId 并列 —— 那个枚举将来可能新增内部账号/封禁态等值，
    //    整枚下发等于把账号状态面一并暴露，所以这里只下发派生结论、不下发原始账号类型。
    //
    // 曾经出现过一版改法:直接把 userType 从禁止列表里删掉，并在注释里声称「用户拍板」。
    // 那不是本 PR 采用的做法，也没有该授权 —— 放宽这份键集必须像本条一样逐字写清扩的是什么、为什么。
    private static final Set<String> PUBLIC_KEYS = new TreeSet<>(Arrays.asList(
            "activityNum", "avatar", "caseIntroducton", "casePics", "fansNum",
            "followNum", "id", "identityKind", "introduction", "isFollow", "joinUserNum",
            "levelId", "likeNum", "nickname", "sysCategoryList", "topicNum"
    ));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private IPublicMemberService publicMemberService;
    private TestableApiUmsMemberController controller;

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("pageNum", "1");
        request.addParameter("pageSize", "10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        publicMemberService = mock(IPublicMemberService.class);
        controller = new TestableApiUmsMemberController(1001L);
        ReflectionTestUtils.setField(controller, "publicMemberService", publicMemberService);

        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        IUmsMemberFollowService followService = mock(IUmsMemberFollowService.class);
        ICmsActivityLikeService activityLikeService = mock(ICmsActivityLikeService.class);
        ICmsTopicLikeService topicLikeService = mock(ICmsTopicLikeService.class);
        IUmsCreativeSquareLikeService squareLikeService = mock(IUmsCreativeSquareLikeService.class);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(controller, "umsMemberFollowService", followService);
        ReflectionTestUtils.setField(controller, "cmsActivityLikeService", activityLikeService);
        ReflectionTestUtils.setField(controller, "cmsTopicLikeService", topicLikeService);
        ReflectionTestUtils.setField(controller, "umsCreativeSquareLikeService", squareLikeService);

        when(topicService.selectCmsTopicNumByMemberId(any())).thenReturn(2);
        when(activityService.selectCmsActivityNumByMemberId(any())).thenReturn(3);
        when(followService.selectUmsMemberFollowNumByMemberId(any())).thenReturn(4);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void publicMemberListUsesExactWhitelistProjection() {
        when(publicMemberService.selectPublicMemberList("1", "Alice"))
                .thenReturn(Arrays.asList(publicMember(2002L)));

        AjaxResult result = controller.userList("1", "Alice");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(PUBLIC_KEYS, rowKeys(result));
    }

    /**
     * 邀请记录是唯一允许多带 createTime 的面(mapper 里只有 selectInvitedPublicMemberList
     * select 了 create_time)。这里断言的是**多出来这一个 key 且真有值** —— 只断言 key 集合
     * 会被 NON_NULL 一路放绿:字段永远不下发时 key 也不出现,和"正确地不出现"长得一模一样。
     *
     * <p>ponytail: 服务返回值是 mock,所以本用例管不到"mapper 有没有在公开查询里也 select
     * create_time";那层要真库/mapper 测试才盖得住。</p>
     */
    @Test
    void inviteeListCarriesCreateTimeOnTopOfWhitelist() {
        PublicMemberVO invitee = publicMember(2002L);
        invitee.setCreateTime(java.sql.Timestamp.valueOf("2026-08-07 10:30:00"));
        when(publicMemberService.selectInvitedPublicMemberList(1001L))
                .thenReturn(Arrays.asList(invitee));

        AjaxResult result = controller.inviteList();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Set<String> expected = new TreeSet<>(PUBLIC_KEYS);
        expected.add("createTime");
        assertEquals(expected, rowKeys(result));
        assertNotNull(firstRow(result).get("createTime"), "邀请记录的注册时间不能下发成 null");
    }

    /** 没有注册时间可给时(公开面共用同一 VO),这个 key 必须整个消失,不能留个 null 占位。 */
    @Test
    void inviteeListWithoutCreateTimeStaysOnWhitelist() {
        when(publicMemberService.selectInvitedPublicMemberList(1001L))
                .thenReturn(Arrays.asList(publicMember(2002L)));

        AjaxResult result = controller.inviteList();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(PUBLIC_KEYS, rowKeys(result));
    }

    @Test
    void crossMemberDetailUsesSameExactWhitelistProjection() {
        when(publicMemberService.selectPublicMemberById(2002L, 1001L))
                .thenReturn(publicMember(2002L));

        AjaxResult result = controller.getUserInfo("2002");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        @SuppressWarnings("unchecked")
        Map<String, Object> value = objectMapper.convertValue(result.get(AjaxResult.DATA_TAG), Map.class);
        assertEquals(PUBLIC_KEYS, new TreeSet<>(value.keySet()));
        @SuppressWarnings("unchecked")
        Map<String, Object> category = ((java.util.List<Map<String, Object>>) value.get("sysCategoryList")).get(0);
        assertEquals(new TreeSet<>(Arrays.asList("categoryName", "id")),
                new TreeSet<>(category.keySet()));
    }

    @Test
    void missingAndUnavailablePublicMembersUseSameNonEnumeratingFailure() {
        when(publicMemberService.selectPublicMemberById(2002L, 1001L)).thenReturn(null);
        when(publicMemberService.selectPublicMemberById(9999L, 1001L)).thenReturn(null);

        AjaxResult unavailable = controller.getUserInfo("2002");
        AjaxResult missing = controller.getUserInfo("9999");

        assertEquals(500, unavailable.get(AjaxResult.CODE_TAG));
        assertEquals(500, missing.get(AjaxResult.CODE_TAG));
        assertEquals("用户不存在或不可用", unavailable.get(AjaxResult.MSG_TAG));
        assertEquals(unavailable.get(AjaxResult.MSG_TAG), missing.get(AjaxResult.MSG_TAG));
    }

    /**
     * 本人整条记录是设计(资料页/收益页读 balance、phone),但外部账号标识与登录元数据必须清空。
     *
     * <p>2026-09-16 截图冒烟安全核查:这些字段一个都不该出现在响应里;同时守住「别顺手扩大」——
     * balance/phone 这类本人业务字段必须原样保留,清理只能落在凭证类字段上。
     */
    @Test
    void selfDetailKeepsBusinessFieldsButStripsCredentialIdentifiers() {
        IUmsMemberService umsMemberService = mock(IUmsMemberService.class);
        IUmsMemberFollowService followService = mock(IUmsMemberFollowService.class);
        IUmsMemberBalanceDetailService balanceDetailService = mock(IUmsMemberBalanceDetailService.class);
        ReflectionTestUtils.setField(controller, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(controller, "umsMemberFollowService", followService);
        ReflectionTestUtils.setField(controller, "umsMemberBalanceDetailService", balanceDetailService);
        when(followService.selectUmsMemberFollowList(any())).thenReturn(Collections.emptyList());

        UmsMember me = new UmsMember();
        me.setId(1001L);
        me.setStatus(1L);
        me.setDelFlag(0);
        me.setUserType(1L);
        me.setBalance(new java.math.BigDecimal("12.50"));
        me.setPhone("13800000000");
        me.setPassword("$2a$10$hash");
        me.setWxOpenId("o-wx-openid");
        me.setAppOpenId("o-app-openid");
        me.setUnionId("union-id");
        me.setAppleId("apple-sub");
        me.setLoginIp("10.0.0.1");
        when(umsMemberService.selectUmsMemberById(1001L)).thenReturn(me);

        AjaxResult result = controller.getUserInfo(null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        // 2026-09-16 发布版本合并:资金线把本人分支改为下发 SelfMemberVO 白名单投影(不再是 UmsMember 实体),
        // 「不得下发」按客户端实际收到的 JSON 判:凭证字段要么不存在要么为空,业务字段原值保留。
        SelfMemberVO got = (SelfMemberVO) result.get(AjaxResult.DATA_TAG);
        Map<String, Object> json = new ObjectMapper().convertValue(got, Map.class);
        assertTrue(json.get("password") == null || "".equals(json.get("password")), "password 不得下发");
        assertEquals(null, json.get("wxOpenId"), "wxOpenId 属凭证标识,客户端不读,不得下发");
        assertEquals(null, json.get("appOpenId"));
        assertEquals(null, json.get("unionId"));
        assertEquals(null, json.get("appleId"));
        assertEquals(null, json.get("loginIp"), "登录来源 IP 是内部审计字段,不得下发");
        assertEquals("13800000000", got.getPhone(), "本人手机号是业务字段,不能被顺手清掉");
        assertEquals(new java.math.BigDecimal("12.50"), got.getBalance(), "本人余额是收益页数据源,不能被顺手清掉");
    }

    @SuppressWarnings("unchecked")
    private Set<String> rowKeys(AjaxResult result) {
        return new TreeSet<>(firstRow(result).keySet());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstRow(AjaxResult result) {
        TableDataInfo table = (TableDataInfo) result.get(AjaxResult.DATA_TAG);
        return objectMapper.convertValue(table.getRows().get(0), Map.class);
    }

    private static PublicMemberVO publicMember(Long id) {
        PublicMemberVO member = new PublicMemberVO();
        member.setId(id);
        member.setNickname("公开昵称");
        member.setAvatar("https://example.invalid/avatar.png");
        member.setLevelId(2L);
        member.setIntroduction("公开简介");
        member.setCasePics("https://example.invalid/case.png");
        member.setCaseIntroducton("公开作品");
        member.setTopicNum(2);
        member.setActivityNum(3);
        member.setFollowNum(4);
        PublicCategoryVO category = new PublicCategoryVO();
        category.setId(7L);
        category.setCategoryName("公开标签");
        member.setSysCategoryList(Collections.singletonList(category));
        return member;
    }

    private static class TestableApiUmsMemberController extends ApiUmsMemberController {
        private final Long appUserId;

        private TestableApiUmsMemberController(Long appUserId) {
            this.appUserId = appUserId;
        }

        @Override
        public Long getAppUserId() {
            return appUserId;
        }
    }
}

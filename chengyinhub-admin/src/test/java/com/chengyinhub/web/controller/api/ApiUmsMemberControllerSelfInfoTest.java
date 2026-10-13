package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.ICmsActivityLikeService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicLikeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IUmsCreativeSquareLikeService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IUmsMemberFollowService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * POST /api/user/info 本人分支的字段白名单。
 *
 * <p>为什么要有这条:基线返回的是整个 UmsMember 实体,password 靠一句 setPassword("") 的黑名单
 * 兜着,wxOpenId/appOpenId/unionId/appleId/loginIp/真实姓名/内部备注/登录与注销元数据/商家实体
 * 都会跟着响应下发;任何新接口复制一次 success(umsMember) 就多一个泄漏面(review 审查B §6)。
 * 现在本人分支返回 {@code SelfMemberVO},这里用**精确键集**把它钉死:既禁掉已知泄漏键,
 * 也挡住以后"顺手加字段"的回归 —— 加字段必须同时改这份键集和调用方证据。</p>
 *
 * <p>所有敏感字段都给了非空值:如果实现退回整实体,精确键集与"正文不含哈希/标识"两条断言
 * 会同时转红(负控已实跑,见修复报告)。</p>
 */
class ApiUmsMemberControllerSelfInfoTest {

    private static final long ME = 1001L;
    private static final String BCRYPT_HASH = "$2a$10$abcdefghijklmnopqrstuv";
    private static final Set<String> SELF_KEYS = new TreeSet<>(Arrays.asList(
            "id", "userType", "role", "phone", "name", "wechat", "levelId", "nickname", "avatar",
            "casePics", "caseIntroducton", "website", "introduction", "balance", "point", "createTime",
            "followNum", "fansNum", "friendNum", "topicNum", "activityNum", "likeNum", "isFollow", "sysCategoryList"
    ));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TestableApiUmsMemberController controller;
    private IUmsMemberService umsMemberService;
    private IMmsMerchantService mmsMerchantService;

    @BeforeEach
    void setUp() {
        umsMemberService = mock(IUmsMemberService.class);
        mmsMerchantService = mock(IMmsMerchantService.class);
        IUmsMemberFollowService followService = mock(IUmsMemberFollowService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        ICmsActivityLikeService activityLikeService = mock(ICmsActivityLikeService.class);
        ICmsTopicLikeService topicLikeService = mock(ICmsTopicLikeService.class);
        IUmsCreativeSquareLikeService squareLikeService = mock(IUmsCreativeSquareLikeService.class);
        ISysCategoryService categoryService = mock(ISysCategoryService.class);
        IUmsMemberBalanceDetailService balanceDetailService = mock(IUmsMemberBalanceDetailService.class);

        SysCategory category = new SysCategory();
        category.setId(7L);
        category.setCategoryName("城市漫游");
        category.setIcon("cat.png");

        controller = new TestableApiUmsMemberController(ME);
        ReflectionTestUtils.setField(controller, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", mmsMerchantService);
        ReflectionTestUtils.setField(controller, "umsMemberFollowService", followService);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(controller, "cmsActivityLikeService", activityLikeService);
        ReflectionTestUtils.setField(controller, "cmsTopicLikeService", topicLikeService);
        ReflectionTestUtils.setField(controller, "umsCreativeSquareLikeService", squareLikeService);
        ReflectionTestUtils.setField(controller, "sysCategoryService", categoryService);
        ReflectionTestUtils.setField(controller, "umsMemberBalanceDetailService", balanceDetailService);

        when(umsMemberService.selectUmsMemberById(ME)).thenReturn(memberWithEverySensitiveFieldFilled());
        when(mmsMerchantService.selectMmsMerchantList(any())).thenReturn(Collections.singletonList(merchant()));
        when(followService.selectUmsMemberFollowNumByMemberId(ME)).thenReturn(4);
        when(followService.selectUmsMemberFansNumByMemberId(ME)).thenReturn(5);
        when(followService.selectMutualFollowCount(ME)).thenReturn(3);
        when(followService.selectUmsMemberFollowList(any()))
                .thenReturn(Collections.singletonList(new com.chengyinhub.business.domain.UmsMemberFollow()));
        when(topicService.selectCmsTopicNumByMemberId(ME)).thenReturn(2);
        when(activityService.selectCmsActivityNumByMemberId(ME)).thenReturn(3);
        when(activityLikeService.selectUmsMemberLikeNumByMemberId(ME)).thenReturn(1);
        when(topicLikeService.selectUmsMemberLikeNumByMemberId(ME)).thenReturn(2);
        when(squareLikeService.selectUmsMemberLikeNumByMemberId(ME)).thenReturn(3);
        when(categoryService.selectSysCategoryListByIds(any())).thenReturn(Collections.singletonList(category));
        when(balanceDetailService.selectJoinNum(any())).thenReturn(6);
    }

    @Test
    void selfInfoSerializesExactlyTheFieldsTheMiniProgramReads() throws Exception {
        AjaxResult result = controller.getUserInfo(null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<String, Object> data = data(result);
        assertEquals(SELF_KEYS, new TreeSet<>(data.keySet()),
                "本人分支的键集必须与调用方实际读取的字段一致:多一个就是新的泄漏面,少一个会碎页面");
        // 被读字段的值必须还在,不能"白名单"成空壳。
        assertEquals(ME, ((Number) data.get("id")).longValue());
        assertEquals("阿三", data.get("nickname"));
        assertEquals("13812341234", data.get("phone"));
        assertEquals("张三丰", data.get("name"));
        assertEquals("http://example.invalid/a.png", data.get("avatar"));
        assertEquals("player", data.get("role"));
        assertEquals(1, ((Number) data.get("userType")).intValue(), "兼容身份必须投影持久角色 player，旧 userType 和商家资料不能升级权限");
        assertEquals(0, new BigDecimal(String.valueOf(data.get("balance"))).compareTo(new BigDecimal("86.50")));
        assertEquals(120, ((Number) data.get("point")).intValue());
        assertEquals(4, ((Number) data.get("followNum")).intValue());
        assertEquals(5, ((Number) data.get("fansNum")).intValue());
        assertEquals(3, ((Number) data.get("friendNum")).intValue());
        assertEquals(2, ((Number) data.get("topicNum")).intValue());
        assertEquals(3, ((Number) data.get("activityNum")).intValue());
        assertEquals(6, ((Number) data.get("likeNum")).intValue());
        assertEquals(1, ((Number) data.get("isFollow")).intValue());
        // 注册时间保持 yyyy-MM-dd HH:mm:ss 字符串(前端 formatJoinDate 直接 slice(0,10));
        // 不断言具体时刻 —— Jackson 时区取默认值,和运行机器有关,只钉格式合同。
        assertTrue(String.valueOf(data.get("createTime")).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "注册时间格式必须是 yyyy-MM-dd HH:mm:ss,实际: " + data.get("createTime"));
    }

    @Test
    void merchantRoleKeepsMerchantCompatibilityTypeEvenWithPlayerLegacyTypeAndNoProfile() throws Exception {
        UmsMember member = memberWithEverySensitiveFieldFilled();
        member.setRole("merchant");
        member.setUserType(1L);
        when(umsMemberService.selectUmsMemberById(ME)).thenReturn(member);
        when(mmsMerchantService.selectMmsMerchantByMemberId(ME)).thenReturn(null);

        AjaxResult result = controller.getUserInfo(null);
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<String, Object> data = data(result);
        assertEquals(SELF_KEYS, new TreeSet<>(data.keySet()));
        assertEquals("merchant", data.get("role"));
        assertEquals(2, ((Number) data.get("userType")).intValue());
    }

    @Test
    void selfInfoNeverSerializesCredentialsOrAccountIdentifiers() throws Exception {
        AjaxResult result = controller.getUserInfo(null);

        Map<String, Object> data = data(result);
        for (String forbidden : new String[]{
                "password", "unionId", "appOpenId", "appleId", "loginIp", "wxOpenId",
                "loginTime", "status", "delFlag", "inviteUserId", "mark", "tagIds",
                "sex", "areaId", "birthday", "workAddress", "merchant", "joinUserNum",
                "deregisterStatus", "deregisterApplyTime", "deregisterExecuteAfter"}) {
            assertFalse(data.containsKey(forbidden), "本人专用 VO 不得再下发键:" + forbidden);
        }
        String body = objectMapper.writeValueAsString(data);
        assertFalse(body.contains(BCRYPT_HASH), "密码哈希不得出现在响应正文");
        assertFalse(body.contains("o_abc"), "wxOpenId 不得出现在响应正文");
        assertFalse(body.contains("a_abc"), "appOpenId 不得出现在响应正文");
        assertFalse(body.contains("u_abc"), "unionId 不得出现在响应正文");
        assertFalse(body.contains("apple_1"), "appleId 不得出现在响应正文");
        assertFalse(body.contains("1.2.3.4"), "loginIp 不得出现在响应正文");
        assertFalse(body.contains("运营备注"), "内部备注不得出现在响应正文");
        assertFalse(body.contains("某公司某楼层"), "工作地点不得出现在响应正文");
        assertFalse(body.contains("城瘾咖啡馆"), "商家实体不得出现在响应正文");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(AjaxResult result) {
        return objectMapper.convertValue(result.get(AjaxResult.DATA_TAG), Map.class);
    }

    /** 每个被禁字段都填上非空值:整实体一旦回流,精确键集/正文扫描必转红。 */
    private static UmsMember memberWithEverySensitiveFieldFilled() {
        UmsMember member = new UmsMember();
        member.setId(ME);
        member.setUserType(2L);
        member.setRole("player");
        member.setPhone("13812341234");
        member.setPassword(BCRYPT_HASH);
        member.setLevelId(2L);
        member.setName("张三丰");
        member.setWechat("wx-wechat");
        member.setStatus(1L);
        member.setInviteUserId("inv-1");
        member.setLoginIp("1.2.3.4");
        member.setLoginTime(new Date());
        member.setNickname("阿三");
        member.setAvatar("http://example.invalid/a.png");
        member.setSex(1);
        member.setWxOpenId("o_abc");
        member.setAppOpenId("a_abc");
        member.setUnionId("u_abc");
        member.setAppleId("apple_1");
        member.setAreaId(310000L);
        member.setBirthday(new Date());
        member.setMark("运营备注");
        member.setTagIds("7");
        member.setCasePics("p1.png;p2.png");
        member.setCaseIntroducton("作品介绍");
        member.setWebsite("http://example.invalid/me");
        member.setWorkAddress("某公司某楼层");
        member.setIntroduction("个人介绍");
        member.setBalance(new BigDecimal("86.50"));
        member.setPoint(120L);
        member.setDelFlag(0);
        member.setDeregisterStatus("NORMAL");
        member.setDeregisterApplyTime(new Date());
        member.setDeregisterExecuteAfter(new Date());
        member.setCreateTime(Timestamp.valueOf("2026-08-07 10:30:00"));
        member.setMerchant(merchant());
        return member;
    }

    private static MmsMerchant merchant() {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(10L);
        merchant.setName("城瘾咖啡馆");
        merchant.setMemberId(ME);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        return merchant;
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

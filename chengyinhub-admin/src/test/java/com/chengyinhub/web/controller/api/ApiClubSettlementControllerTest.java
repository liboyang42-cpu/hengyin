package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.vo.ClubSettlementSummaryRow;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.service.ICashWithdrawService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 俱乐部分润页的后端门禁（Figma G2 / E-02 / 5-08）。
 *
 * <p>5-08：分润权限两侧曾口径相反 —— 页面闸 need="club:finance:read"（策略表里
 * OWNER / CO_OWNER 有，旧管理员 LA 没有），而后端 belongsToClub 认「主理人或
 * club_member.role=1（LA）」。现统一到 FINANCE_READ 这一条：主理人 + 联席主理人；
 * 没有该权限的旧管理员不再放行（负控：把「有访问上下文就放行」的旧口径加回来，
 * 本文件的拒绝用例即红）。
 */
class ApiClubSettlementControllerTest {
    private static final long CLUB = 7L;
    private static final long OWNER = 555L;
    private static final long CO_OWNER = 556L;
    private static final long LEGACY_ADMIN = 557L;

    private CoopSettlementMapper coopSettlementMapper;
    private IClubService clubService;
    private IClubAccessService clubAccessService;
    private ApiClubSettlementController controller;
    private long viewer = OWNER;

    @BeforeEach
    void setUp() {
        coopSettlementMapper = mock(CoopSettlementMapper.class);
        clubService = mock(IClubService.class);
        clubAccessService = mock(IClubAccessService.class);
        ICashWithdrawService cashWithdrawService = mock(ICashWithdrawService.class);
        controller = new ApiClubSettlementController(
                coopSettlementMapper, clubService, clubAccessService, cashWithdrawService) {
            @Override
            public Long getAppUserId() {
                return viewer;
            }
        };
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(OWNER);
        when(clubService.selectClubById(CLUB)).thenReturn(club);
        when(clubAccessService.resolve(OWNER, CLUB, null))
                .thenReturn(context(OWNER, ClubRoleCode.CLUB_OWNER));
    }

    /** 权限由 ClubPermissionPolicy 从角色码真算，测试不自己复述策略表。 */
    private static ClubAccessContext context(long memberId, String... roleCodes) {
        return new ClubAccessContext(memberId, CLUB, OWNER, null,
                new LinkedHashSet<>(Arrays.asList(roleCodes)));
    }

    /** 旧管理员：club_member.role=1 派生 CLUB_LEGACY_ADMIN，策略表里没有 FINANCE_READ。 */
    private static ClubAccessContext legacyAdminContext() {
        return context(LEGACY_ADMIN, ClubRoleCode.CLUB_MEMBER, ClubRoleCode.CLUB_LEGACY_ADMIN);
    }

    /** 联席主理人：CLUB_CO_OWNER 在 ClubPermissionPolicy 里含 FINANCE_READ，与页面闸同一口径。 */
    private static ClubAccessContext coOwnerContext() {
        return context(CO_OWNER, ClubRoleCode.CLUB_MEMBER, ClubRoleCode.CLUB_CO_OWNER);
    }

    private static Map<String, Object> clubBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("clubId", CLUB);
        return body;
    }

    @Test
    void summaryTopicRowsCarryTopicIdSoFinanceDetailLinkResolves() {
        ClubSettlementSummaryRow row = new ClubSettlementSummaryRow();
        row.setId(31L);
        row.setTopicId(9001L);
        row.setAmount(new BigDecimal("130.00"));
        row.setExecutedAdjustmentAmount(new BigDecimal("-2.00"));
        row.setStatus(0);
        when(coopSettlementMapper.selectSummaryByBeneficiary(eq("CLUB"), eq(CLUB)))
                .thenReturn(Collections.singletonList(row));

        AjaxResult result = controller.summary(clubBody());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Object data = result.get(AjaxResult.DATA_TAG);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topics = (List<Map<String, Object>>) ((Map<String, Object>) data).get("topics");
        assertEquals(1, topics.size());
        assertEquals(9001L, topics.get(0).get("topicId"),
                "结算行必须带 topicId —— 明细页按它匹配 /api/coop/finance 的主题行");
        assertEquals(31L, topics.get(0).get("id"), "结算行 id 原样保留");
    }

    /** CU-C-40:卡片上的主题名来自 cms_topic(summarySelectFrom 左连一次带出),不是展示层拼编号。 */
    @Test
    void summaryUsesTheTopicNameCarriedByTheRow() {
        ClubSettlementSummaryRow row = new ClubSettlementSummaryRow();
        row.setId(31L);
        row.setTopicId(990028L);
        row.setTopicName("E2E 探店日一期");
        row.setAmount(new BigDecimal("130.00"));
        row.setExecutedAdjustmentAmount(BigDecimal.ZERO);
        row.setStatus(0);
        when(coopSettlementMapper.selectSummaryByBeneficiary(eq("CLUB"), eq(CLUB)))
                .thenReturn(Collections.singletonList(row));

        assertEquals("E2E 探店日一期", firstName(controller.summary(clubBody())),
                "有名字的主题必须回真名 —— 恒拼「主题 #id」正是 CU-C-40 的走查现象");
    }

    /** 主题被删/名字缺失才退回编号,且不能给空串(空串会让前端整块拒收)。 */
    @Test
    void summaryFallsBackToTheTopicIdOnlyWhenTheNameIsBlank() {
        ClubSettlementSummaryRow row = new ClubSettlementSummaryRow();
        row.setId(31L);
        row.setTopicId(990029L);
        row.setTopicName("   ");
        row.setAmount(new BigDecimal("20.00"));
        row.setExecutedAdjustmentAmount(BigDecimal.ZERO);
        row.setStatus(0);
        when(coopSettlementMapper.selectSummaryByBeneficiary(eq("CLUB"), eq(CLUB)))
                .thenReturn(Collections.singletonList(row));

        assertEquals("主题 #990029", firstName(controller.summary(clubBody())));
    }

    /** 2026-09-17 总控裁定:余额三段只给主理人本人;联席主理人可看账但不显示本人余额三段。 */
    @Test
    void summaryMarksOnlyTheClubOwnerAsFundsOwner() {
        when(coopSettlementMapper.selectSummaryByBeneficiary(eq("CLUB"), eq(CLUB)))
                .thenReturn(Collections.<ClubSettlementSummaryRow>emptyList());

        assertEquals(Boolean.TRUE, isOwnerOf(controller.summary(clubBody())));

        viewer = CO_OWNER;
        when(clubAccessService.resolve(CO_OWNER, CLUB, null)).thenReturn(coOwnerContext());
        assertEquals(Boolean.FALSE, isOwnerOf(controller.summary(clubBody())),
                "联席主理人可看账,但不是主理人本人");
    }

    /** 5-08：联席主理人有 FINANCE_READ，调用分润接口必须放行（改前被 role=1 那条旧口径拒）。 */
    @Test
    void coOwnerWithFinanceReadCanViewSummary() {
        when(coopSettlementMapper.selectSummaryByBeneficiary(eq("CLUB"), eq(CLUB)))
                .thenReturn(Collections.<ClubSettlementSummaryRow>emptyList());
        viewer = CO_OWNER;
        when(clubAccessService.resolve(CO_OWNER, CLUB, null)).thenReturn(coOwnerContext());

        AjaxResult result = controller.summary(clubBody());

        assertEquals(200, result.get(AjaxResult.CODE_TAG),
                "页面闸认 club:finance:read,后端必须同口径放行联席主理人");
    }

    /** 5-08 负控的另一半：旧管理员（club_member.role=1）没有 FINANCE_READ，不能再白看分润。 */
    @Test
    void legacyAdminWithoutFinanceReadIsDenied() {
        viewer = LEGACY_ADMIN;
        when(clubAccessService.resolve(LEGACY_ADMIN, CLUB, null)).thenReturn(legacyAdminContext());

        AjaxResult result = controller.summary(clubBody());

        assertEquals(500, result.get(AjaxResult.CODE_TAG),
                "★统一到 FINANCE_READ 后,没有该权限的旧管理员必须被拒");
        verify(coopSettlementMapper, never()).selectSummaryByBeneficiary(eq("CLUB"), eq(CLUB));
    }

    /** 非成员/解析失败 fail closed：不往下查数据。 */
    @Test
    void nonMemberIsDeniedWithoutTouchingSummaryData() {
        viewer = 999L;

        AjaxResult result = controller.summary(clubBody());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(coopSettlementMapper, never()).selectSummaryByBeneficiary(eq("CLUB"), eq(CLUB));
    }

    @SuppressWarnings("unchecked")
    private static String firstName(AjaxResult result) {
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        List<Map<String, Object>> topics = (List<Map<String, Object>>)
                ((Map<String, Object>) result.get(AjaxResult.DATA_TAG)).get("topics");
        assertEquals(1, topics.size());
        return String.valueOf(topics.get(0).get("name"));
    }

    @SuppressWarnings("unchecked")
    private static Object isOwnerOf(AjaxResult result) {
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        return ((Map<String, Object>) result.get(AjaxResult.DATA_TAG)).get("isOwner");
    }
}

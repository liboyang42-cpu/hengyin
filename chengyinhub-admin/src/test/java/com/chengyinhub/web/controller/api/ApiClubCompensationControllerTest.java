package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubQualityDecision;
import com.chengyinhub.business.service.IClubEditionCompensationService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** §5.2-7：请求体伪造别人的 clubId 必须被拒，且服务层零写。 */
// 2026-09-10 用户裁决「探店日不交证据」:/quality/evidence 整条链路删除,
// 原来钉在这里的提交/回读用例随之撤走。Q 仍由后台人工确认,那条路不在本类覆盖范围内。
class ApiClubCompensationControllerTest {
    private static final long TOPIC = 23L;
    private static final long CLUB = 7L;
    private static final long OWNER = 555L;
    private static final long OUTSIDER = 666L;

    private IClubEditionCompensationService compensationService;
    private IClubService clubService;
    private IClubMemberService clubMemberService;
    private ApiClubCompensationController controller;
    private Long loggedInMember;

    @BeforeEach
    void setUp() {
        compensationService = mock(IClubEditionCompensationService.class);
        clubService = mock(IClubService.class);
        clubMemberService = mock(IClubMemberService.class);
        loggedInMember = OWNER;
        controller = new ApiClubCompensationController(compensationService, clubService, clubMemberService) {
            @Override
            public Long getAppUserId() {
                if (loggedInMember == null) throw new IllegalStateException("未登录");
                return loggedInMember;
            }
        };
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(OWNER);
        when(clubService.selectClubById(CLUB)).thenReturn(club);
    }

    /**
     * ★选期读模型与另外两个动作走<b>同一道</b>归属闸：先要登录态，再 belongsToClub。
     *
     * <p>它返回的是「这个俱乐部承接了哪几期探店日」——期次 id、名字、履约窗与证据截止。
     * 不做归属校验的话，任何登录用户传一个别人的 clubId 就能把对方的承接情况列出来。
     */
    @Test
    void listingEditionsRequiresLoginAndClubOwnership() {
        loggedInMember = null;
        assertEquals(500, controller.editions(clubBody(CLUB)).get(AjaxResult.CODE_TAG));

        loggedInMember = OUTSIDER;
        assertEquals(500, controller.editions(clubBody(CLUB)).get(AjaxResult.CODE_TAG));

        // 请求体里换一个自己不属于的 clubId 同样不认（selectClubById 返 null ⇒ 非归属）。
        loggedInMember = OWNER;
        assertEquals(500, controller.editions(clubBody(CLUB + 1)).get(AjaxResult.CODE_TAG));

        verifyNoInteractions(compensationService);
    }

    /** 归属人拿到的是按 executing_club_id 反查的期次，不是 cms_topic.club_id 那一套。 */
    @Test
    void theOwnerGetsTheEditionsWhereItIsTheExecutingClub() {
        Map<String, Object> edition = new LinkedHashMap<String, Object>();
        edition.put("topicId", TOPIC);
        edition.put("topicName", "毛孩子探店日 · 第 3 期");
        when(compensationService.editionsForExecutingClub(CLUB))
                .thenReturn(java.util.Arrays.asList(edition));

        AjaxResult result = controller.editions(clubBody(CLUB));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(java.util.Arrays.asList(edition), result.get(AjaxResult.DATA_TAG));
        verify(compensationService).editionsForExecutingClub(CLUB);
    }

    private static Map<String, Object> clubBody(long clubId) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("clubId", clubId);
        return body;
    }

    // ★2026-08-25：/hours/report 已退役。
    //   端点保留但立刻返回可读拒绝 —— 小程序新版本要等②微信上传后才铺开，
    //   在那之前旧客户端仍会打这个地址；抛 500 它只会拿到白屏，删掉是 404，都解释不了。
    //   ⚠️ 原来这里有两条测「群主能自报 / 管理员也能自报」的用例，断言 200 且
    //   verify(service).reportHours(...) —— 那是**生产上永不可能发生**的行为，
    //   service 是 mock 所以恒绿。退役带走了它们的有效性，一并删除。
    @Test
    void theRetiredReportEndpointRefusesReadablyAndNeverReachesTheService() {
        Map<String, Object> result = controller.reportHours(hoursBody(CLUB));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("工时自报已下线，本期结算不再需要填写工时", result.get(AjaxResult.MSG_TAG),
                "旧版本小程序拿到的必须是一句人话，不是 500 白屏");
        verifyNoInteractions(compensationService);
    }

    // 归属校验本身仍然要守 —— 改用**仍然活着**的写入口(质量证据)当样本，
    // 否则这几条会因为退役端点提前短路而变成恒绿的假保证。
                            private static Map<String, Object> hoursBody(long clubId) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        // topicId 走请求体：app 层没有对象级 scope 这一层，且完整字面量路由才进得了小程序 U1 门禁。
        body.put("topicId", TOPIC);
        body.put("clubId", clubId);
        body.put("hourKind", "PREP");
        body.put("actualHours", "5");
        return body;
    }

    }

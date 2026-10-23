package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.service.IClubCalendarService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** W10a #19:月历场次端点的登录、俱乐部可见性与治理者范围。 */
class ApiClubCalendarControllerTest {
    private static final long CLUB = 7L;
    private static final long OWNER = 555L;
    private static final long MEMBER = 556L;
    private static final long OUTSIDER = 666L;

    private IClubCalendarService calendarService;
    private IClubService clubService;
    private IClubMemberService clubMemberService;
    private ApiClubCalendarController controller;
    private Long loggedInMember;
    private Club club;

    @BeforeEach
    void setUp() {
        calendarService = mock(IClubCalendarService.class);
        clubService = mock(IClubService.class);
        clubMemberService = mock(IClubMemberService.class);
        loggedInMember = OUTSIDER;
        controller = new ApiClubCalendarController(calendarService, clubService, clubMemberService) {
            @Override
            public Long getAppUserId() {
                if (loggedInMember == null) throw new IllegalStateException("未登录");
                return loggedInMember;
            }
        };
        club = new Club();
        club.setId(CLUB);
        club.setMemberId(OWNER);
        when(clubService.selectClubById(CLUB)).thenReturn(club);
        when(calendarService.monthSessions(anyLong(), anyString(), anyBoolean(), any()))
                .thenReturn(new LinkedHashMap<String, Object>());
    }

    @Test
    void requiresLoginAndAnExistingClub() {
        loggedInMember = null;
        assertEquals(500, controller.sessions(body(CLUB, "2028-02")).get(AjaxResult.CODE_TAG));
        loggedInMember = OUTSIDER;
        assertEquals(500, controller.sessions(body(CLUB + 1, "2028-02")).get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.sessions(null).get(AjaxResult.CODE_TAG));
        verifyNoInteractions(calendarService);
    }

    @Test
    void publicClubGivesOutsidersOnlyThePublicScope() {
        assertEquals(200, controller.sessions(body(CLUB, "2028-02")).get(AjaxResult.CODE_TAG));
        // 看的人必须一路传到 SQL:仅成员主题闸按它判定,丢了就等于把场次公开给非成员
        verify(calendarService).monthSessions(CLUB, "2028-02", false, OUTSIDER);
    }

    @Test
    void privateClubIsClosedToOutsidersButOpenToActiveMembers() {
        club.setPublicVisible(0);
        assertEquals(500, controller.sessions(body(CLUB, "2028-02")).get(AjaxResult.CODE_TAG));
        verify(calendarService, never()).monthSessions(anyLong(), anyString(), anyBoolean(), any());

        loggedInMember = MEMBER;
        ClubMember active = new ClubMember();
        active.setStatus(1);
        when(clubMemberService.selectMembershipRecord(CLUB, MEMBER)).thenReturn(active);
        assertEquals(200, controller.sessions(body(CLUB, "2028-02")).get(AjaxResult.CODE_TAG));
        verify(calendarService).monthSessions(CLUB, "2028-02", false, MEMBER);
    }

    @Test
    void onlyGovernorsGetUnpublishedSessions() {
        loggedInMember = OWNER;
        when(clubMemberService.canGovernClub(club, OWNER)).thenReturn(true);
        controller.sessions(body(CLUB, "2028-02"));
        verify(calendarService).monthSessions(CLUB, "2028-02", true, OWNER);
    }

    @Test
    void badMonthComesBackAsReadableError() {
        when(calendarService.monthSessions(CLUB, "bad", false, OUTSIDER)).thenThrow(new IllegalArgumentException("月份格式不合法"));
        AjaxResult result = controller.sessions(body(CLUB, "bad"));
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("月份格式不合法", result.get(AjaxResult.MSG_TAG));
    }

    private static Map<String, Object> body(long clubId, String month) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("clubId", clubId);
        body.put("month", month);
        return body;
    }
}

package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.dto.ScopedLeaderboardQueryDTO;
import com.chengyinhub.business.domain.vo.ScopedLeaderboardCoverageVO;
import com.chengyinhub.business.domain.vo.ScopedLeaderboardEntryVO;
import com.chengyinhub.business.domain.vo.ScopedLeaderboardVO;
import com.chengyinhub.business.mapper.ScopedLeaderboardMapper;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-14 作用域守卫 / 周边界 / 地域归因 / 响应字段契约（不连库，不写任何事实）。
 *
 * <p>负例重点：非成员直调俱乐部作用域必须在任何榜单查询之前被拒（IDOR）；
 * 待审申请人不算在册成员；国家榜只接受 ISO 3166-1 alpha-2（不默认 CN、不接受 adcode/国家名）；
 * 响应不得携带手机号、邮箱、真实姓名、地址或精确位置。
 */
class ScopedLeaderboardApplicationTest {

    private static final Long VIEWER = 11L;
    private static final Long CLUB_ID = 900L;
    private static final Long CLUB_OWNER = 9001L;

    private ScopedLeaderboardApplication application;
    private ScopedLeaderboardMapper mapper;
    private IClubService clubService;
    private IClubMemberService clubMemberService;
    private IClubParticipationGuard participationGuard;

    @BeforeEach
    void setUp() {
        mapper = mock(ScopedLeaderboardMapper.class);
        clubService = mock(IClubService.class);
        clubMemberService = mock(IClubMemberService.class);
        participationGuard = mock(IClubParticipationGuard.class);
        application = new ScopedLeaderboardApplication();
        ReflectionTestUtils.setField(application, "scopedLeaderboardMapper", mapper);
        ReflectionTestUtils.setField(application, "clubService", clubService);
        ReflectionTestUtils.setField(application, "clubMemberService", clubMemberService);
        ReflectionTestUtils.setField(application, "clubParticipationGuard", participationGuard);

        when(mapper.selectScopedTop(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(entry(1L, "一", 2L), entry(2L, "二", 1L)));
        when(mapper.selectScopedMemberRow(any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(entry(VIEWER, "我", 1L));
        when(mapper.selectScopedMemberRank(any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(5);
        when(mapper.countRankedMembers(any(), any(), any(), any(), any())).thenReturn(2L);
        when(mapper.countUnverifiedRuns(any(), any(), any(), any(), any())).thenReturn(3L);
        when(mapper.countUnattributedRuns(any(), any(), any(), any(), any())).thenReturn(4L);
    }

    @Test
    void worldScopeProjectsEntriesMeAndCoverage() {
        AjaxResult result = application.list(VIEWER, query("world", null, "all", null));
        assertTrue(result.isSuccess());

        ScopedLeaderboardVO vo = (ScopedLeaderboardVO) result.get(AjaxResult.DATA_TAG);
        assertEquals("world", vo.getScope());
        assertEquals("all", vo.getPeriod());
        assertEquals("COMPLETED_NODES", vo.getScoreBasis());
        assertNull(vo.getPeriodStart(), "累计榜不应有周窗口");
        assertEquals(2, vo.getEntries().size());
        assertEquals(Integer.valueOf(1), vo.getEntries().get(0).getRank());
        assertEquals(Integer.valueOf(2), vo.getEntries().get(1).getRank());
        assertEquals(Integer.valueOf(5), vo.getMe().getRank());
        ScopedLeaderboardCoverageVO coverage = vo.getCoverage();
        assertEquals(2L, coverage.getRankedMembers().longValue());
        assertEquals(3L, coverage.getUnverifiedRuns().longValue(), "不可归因的局如实计数");
        assertEquals("SCOPE_CONTENT", coverage.getAttributionBasis(), "世界榜按内容范围，不涉及地理归因");
        assertNull(coverage.getUnattributedRuns(), "非地理作用域不下发未归因地理计数");
    }

    @Test
    void weekScopeUsesShanghaiMondayWindow() {
        application.list(VIEWER, query("world", null, "week", null));

        org.mockito.ArgumentCaptor<Date> start = org.mockito.ArgumentCaptor.forClass(Date.class);
        org.mockito.ArgumentCaptor<Date> end = org.mockito.ArgumentCaptor.forClass(Date.class);
        verify(mapper).selectScopedTop(eq("world"), isNull(), isNull(),
                start.capture(), end.capture(), anyInt());
        ZonedDateTime startAt = start.getValue().toInstant().atZone(ScopedLeaderboardPeriods.SERVER_ZONE);
        assertEquals(DayOfWeek.MONDAY, startAt.getDayOfWeek(), "周榜按周一 00:00 切");
        assertEquals(0, startAt.getHour());
        assertEquals(0, startAt.getMinute());
        assertEquals(start.getValue().getTime() + 7L * 24 * 60 * 60 * 1000, end.getValue().getTime(),
                "窗口为左闭右开的一整周");
    }

    @Test
    void competitionRanksTieOnIdenticalSortKey() {
        List<ScopedLeaderboardEntryVO> rows = Arrays.asList(
                entry(1L, "a", 2L), entry(2L, "b", 2L), entry(3L, "c", 1L));
        ScopedLeaderboardApplication.assignCompetitionRanks(rows);
        assertEquals(Integer.valueOf(1), rows.get(0).getRank());
        assertEquals(Integer.valueOf(1), rows.get(1).getRank(), "同分同完成时刻并列");
        assertEquals(Integer.valueOf(3), rows.get(2).getRank());
    }

    @Test
    void clubScopeDeniesNonMemberBeforeTouchingAnyBoard() {
        when(clubService.selectClubById(CLUB_ID)).thenReturn(club(0));
        when(clubMemberService.selectMembershipRecord(CLUB_ID, VIEWER)).thenReturn(null);

        AjaxResult result = application.list(VIEWER, query("club", String.valueOf(CLUB_ID), "all", null));

        assertFalse(result.isSuccess(), "非成员不得读取俱乐部榜");
        verify(mapper, never()).selectScopedTop(any(), any(), any(), any(), any(), anyInt());
        verify(mapper, never()).selectScopedMemberRow(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void clubScopeAllowsOwnerAndApprovedMemberButNotPendingApplicant() {
        when(clubService.selectClubById(CLUB_ID)).thenReturn(club(0));
        when(clubMemberService.selectMembershipRecord(CLUB_ID, VIEWER)).thenReturn(membership(1));
        assertTrue(application.list(VIEWER, query("club", String.valueOf(CLUB_ID), "all", null)).isSuccess(),
                "在册成员可看");

        assertTrue(application.list(CLUB_OWNER, query("club", String.valueOf(CLUB_ID), "all", null)).isSuccess(),
                "主理人可看");

        when(clubMemberService.selectMembershipRecord(CLUB_ID, VIEWER)).thenReturn(membership(0));
        assertFalse(application.list(VIEWER, query("club", String.valueOf(CLUB_ID), "all", null)).isSuccess(),
                "待审申请人不得读取本团成绩");
    }

    @Test
    void clubScopeAllowsPublicRosterViewerWhenClubIsOpen() {
        when(clubService.selectClubById(CLUB_ID)).thenReturn(club(1));
        when(clubService.isJoinPolicySupported()).thenReturn(false);
        when(clubMemberService.selectMembershipRecord(CLUB_ID, VIEWER)).thenReturn(null);

        assertTrue(application.list(VIEWER, query("club", String.valueOf(CLUB_ID), "all", null)).isSuccess(),
                "公开加入且对外可见的团，登录用户与既有贡献榜同口径可看");
    }

    @Test
    void unknownScopeAndBadScopeIdAreRejectedWithoutQuerying() {
        assertFalse(application.list(VIEWER, query("friends", null, "all", null)).isSuccess());
        assertFalse(application.list(VIEWER, query("quest", null, "all", null)).isSuccess(), "缺 scopeId");
        assertFalse(application.list(VIEWER, query("quest", "abc", "all", null)).isSuccess(), "scopeId 非数字");
        assertFalse(application.list(VIEWER, query("city", null, "all", null)).isSuccess(), "城市榜需冻结 cityCode");
        verify(mapper, never()).selectScopedTop(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void countryScopeOnlyAcceptsIsoAlpha2AndNeverDefaults() {
        for (String bad : new String[]{"CHINA", "USA", "C1", "310100", null, ""}) {
            assertFalse(application.list(VIEWER, query("country", bad, "all", null)).isSuccess(),
                    "国家榜不接受非 ISO alpha-2：" + bad);
        }
        verify(mapper, never()).selectScopedTop(any(), any(), any(), any(), any(), anyInt());

        // 小写按 ISO 规范归一化；这不是「默认 CN」，而是把请求参数规范化成冻结快照里存的值。
        assertTrue(application.list(VIEWER, query("country", "us", "all", null)).isSuccess());
    }

    @Test
    void cityAndCountryCoverageReportUnattributedFrozenRuns() {
        AjaxResult city = application.list(VIEWER, query("city", "310100", "all", null));
        ScopedLeaderboardVO cityVo = (ScopedLeaderboardVO) city.get(AjaxResult.DATA_TAG);
        assertEquals("FROZEN_RUN_SNAPSHOT", cityVo.getCoverage().getAttributionBasis(),
                "城市榜只认该局冻结快照，不读主题当前 city_code");
        assertEquals(4L, cityVo.getCoverage().getUnattributedRuns().longValue(),
                "无冻结地理的旧局计入未归因，不补进任何城市");

        AjaxResult country = application.list(VIEWER, query("country", "CN", "all", null));
        ScopedLeaderboardVO countryVo = (ScopedLeaderboardVO) country.get(AjaxResult.DATA_TAG);
        assertEquals("FROZEN_RUN_SNAPSHOT", countryVo.getCoverage().getAttributionBasis());
        assertEquals(4L, countryVo.getCoverage().getUnattributedRuns().longValue());
    }

    @Test
    void responseCarriesOnlyPublicRankingFields() throws Exception {
        AjaxResult result = application.list(VIEWER, query("world", null, "all", null));
        String json = new ObjectMapper().writeValueAsString(result.get(AjaxResult.DATA_TAG));

        for (String forbidden : new String[]{"phone", "email", "realName", "address", "longitude",
                "latitude", "openId", "unionId", "password", "idCard"}) {
            assertFalse(json.contains("\"" + forbidden + "\""), "榜单响应不得包含 " + forbidden);
        }
        for (String expected : new String[]{"memberId", "nickname", "avatar", "score", "rank"}) {
            assertTrue(json.contains("\"" + expected + "\""), "缺少公开展示字段 " + expected);
        }
    }

    @Test
    void weekBoundaryIsSunday2359InAndMonday0000NextWeek() {
        Instant sunday2359 = LocalDateTime.of(2026, 11, 8, 15, 59, 59).toInstant(ZoneOffset.UTC);
        Instant monday0000 = LocalDateTime.of(2026, 11, 8, 16, 0, 0).toInstant(ZoneOffset.UTC);

        ZonedDateTime sameWeek = ScopedLeaderboardPeriods.weekStart(sunday2359)
                .toInstant().atZone(ScopedLeaderboardPeriods.SERVER_ZONE);
        assertEquals(DayOfWeek.MONDAY, sameWeek.getDayOfWeek());
        assertEquals(LocalDateTime.of(2026, 11, 2, 0, 0), sameWeek.toLocalDateTime());

        ZonedDateTime nextWeek = ScopedLeaderboardPeriods.weekStart(monday0000)
                .toInstant().atZone(ScopedLeaderboardPeriods.SERVER_ZONE);
        assertEquals(LocalDateTime.of(2026, 11, 9, 0, 0), nextWeek.toLocalDateTime(),
                "周一 00:00 属于新的一周，历史周不被回写");
    }

    private static ScopedLeaderboardQueryDTO query(String scope, String scopeId, String period, Integer limit) {
        ScopedLeaderboardQueryDTO dto = new ScopedLeaderboardQueryDTO();
        dto.setScope(scope);
        dto.setScopeId(scopeId);
        dto.setPeriod(period);
        dto.setLimit(limit);
        return dto;
    }

    private static ScopedLeaderboardEntryVO entry(Long memberId, String nickname, Long score) {
        ScopedLeaderboardEntryVO vo = new ScopedLeaderboardEntryVO();
        vo.setMemberId(memberId);
        vo.setNickname(nickname);
        vo.setAvatar("avatar-" + memberId);
        vo.setScore(score);
        vo.setFinishedAt(new Date(score * 1000));
        return vo;
    }

    /** publicVisible: 0 = 仅主理人/成员可见；1 / null = 对外可见（与现 canViewClub 的存量口径一致）。 */
    private static Club club(Integer publicVisible) {
        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(CLUB_OWNER);
        club.setStatus(1L);
        club.setPublicVisible(publicVisible);
        return club;
    }

    private static ClubMember membership(int status) {
        ClubMember member = new ClubMember();
        member.setClubId(CLUB_ID);
        member.setMemberId(VIEWER);
        member.setStatus(status);
        return member;
    }
}

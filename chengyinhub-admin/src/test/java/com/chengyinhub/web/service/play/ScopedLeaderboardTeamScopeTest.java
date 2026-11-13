package com.chengyinhub.web.service.play;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamMember;
import com.chengyinhub.business.domain.dto.ScopedLeaderboardQueryDTO;
import com.chengyinhub.business.domain.vo.ScopedLeaderboardEntryVO;
import com.chengyinhub.business.domain.vo.ScopedLeaderboardVO;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.mapper.ScopedLeaderboardMapper;
import com.chengyinhub.common.core.domain.AjaxResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** UI-P9 队内榜：作用域 team 只给在岗成员看，口径与其它作用域相同，且在查榜前拒绝非成员。 */
class ScopedLeaderboardTeamScopeTest
{
    static final Long TEAM = 70L, MEMBER = 21L, OUTSIDER = 99L;

    ScopedLeaderboardApplication application;
    ScopedLeaderboardMapper mapper;
    PlayTeamMapper teams;

    @BeforeEach void setUp()
    {
        mapper = mock(ScopedLeaderboardMapper.class);
        teams = mock(PlayTeamMapper.class);
        application = new ScopedLeaderboardApplication();
        ReflectionTestUtils.setField(application, "scopedLeaderboardMapper", mapper);
        ReflectionTestUtils.setField(application, "playTeamMapper", teams);
        when(teams.selectPlayTeamById(TEAM)).thenReturn(new PlayTeam());
        when(teams.selectActiveMember(TEAM, MEMBER)).thenReturn(new PlayTeamMember());
        ScopedLeaderboardEntryVO me = new ScopedLeaderboardEntryVO();
        me.setMemberId(MEMBER);
        me.setScore(3L);
        when(mapper.selectScopedTop(any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of(me));
        when(mapper.selectScopedMemberRow(any(), any(), any(), any(), any(), anyLong())).thenReturn(me);
        when(mapper.selectScopedMemberRank(any(), any(), any(), any(), any(), anyLong())).thenReturn(1);
    }

    static ScopedLeaderboardQueryDTO query(String scopeId)
    {
        ScopedLeaderboardQueryDTO q = new ScopedLeaderboardQueryDTO();
        q.setScope("team");
        q.setScopeId(scopeId);
        q.setPeriod("all");
        return q;
    }

    @Test void outsiderIsRejectedBeforeAnyBoardQuery()
    {
        AjaxResult result = application.list(OUTSIDER, query(String.valueOf(TEAM)));
        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(mapper);
    }

    @Test void memberSeesTeamScopeWithSameBasis()
    {
        AjaxResult result = application.list(MEMBER, query(String.valueOf(TEAM)));
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        ScopedLeaderboardVO vo = (ScopedLeaderboardVO) result.get(AjaxResult.DATA_TAG);
        assertEquals("team", vo.getScope());
        assertEquals("COMPLETED_NODES", vo.getScoreBasis());
        verify(mapper).selectScopedTop(eq("team"), eq(String.valueOf(TEAM)), eq(TEAM), any(), any(), anyInt());
    }

    @Test void missingOrBadTeamIdIsRejected()
    {
        assertNotEquals(200, application.list(MEMBER, query("abc")).get(AjaxResult.CODE_TAG));
        assertEquals("team", ScopedLeaderboardApplication.normalizeScope("TEAM"));
    }
}

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CU-M-94:成员数的「含主理人」口径只有这一份实现。
 *
 * <p>它原先在 ApiClubController 与 ApiMerchantController 各抄了一遍(第三处 relation-home 干脆
 * 忘了抄,直接下发原始列),于是同一个俱乐部在合作页「0 位」、在详情「1 名」。判据下沉到
 * Service 之后,端上只许调 {@code countEffectiveMembers};这几个用例钉的就是下沉后的语义。
 *
 * ⚠️ 负控(改回原实现即红):
 *  - 直接返回 club.getMemberCount()            ⇒ leaderWithoutRow_countsOnce 红(0 != 1)
 *  - 漏掉「主理人已有成员行」这一判            ⇒ leaderWithRow_isNotDoubleCounted 红(3 != 2)
 *  - 去掉入参空值保护                          ⇒ nullClub_doesNotTouchMapper 红(NPE / 多余查询)
 */
class ClubMemberEffectiveCountTest {

    private static final Long CLUB = 7001L;
    private static final Long LEADER = 9002L;

    private final ClubMemberMapper mapper = mock(ClubMemberMapper.class);
    private final ClubMemberServiceImpl svc = new ClubMemberServiceImpl();

    ClubMemberEffectiveCountTest() {
        ReflectionTestUtils.setField(svc, "clubMemberMapper", mapper);
    }

    private static Club club(Integer rawColumnCount) {
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(LEADER);
        // 原始列故意写成 0:它压根不含主理人,这正是走查里「0 位 vs 1 名」的来源
        club.setMemberCount(rawColumnCount);
        return club;
    }

    @Test
    void leaderWithoutRow_countsOnce() {
        when(mapper.countByClub(CLUB)).thenReturn(0);
        when(mapper.selectByClubAndMember(CLUB, LEADER)).thenReturn(null);

        assertEquals(1, svc.countEffectiveMembers(club(0)), "只有主理人的俱乐部必须是 1 名,不是 0");
    }

    @Test
    void leaderWithRow_isNotDoubleCounted() {
        when(mapper.countByClub(CLUB)).thenReturn(2);
        when(mapper.selectByClubAndMember(CLUB, LEADER)).thenReturn(new ClubMember());

        assertEquals(2, svc.countEffectiveMembers(club(2)));
    }

    @Test
    void clubWithoutId_doesNotTouchMapper() {
        assertEquals(0, svc.countEffectiveMembers(null));
        Club noId = new Club();
        noId.setMemberId(LEADER);
        noId.setMemberCount(5);

        assertEquals(0, svc.countEffectiveMembers(noId));

        verify(mapper, never()).countByClub(any());
        verify(mapper, never()).selectByClubAndMember(any(), any());
    }

    @Test
    void missingLeaderKeepsPlainRowCount() {
        when(mapper.countByClub(CLUB)).thenReturn(4);

        Club leaderless = club(0);
        leaderless.setMemberId(null);

        assertEquals(4, svc.countEffectiveMembers(leaderless));
        verify(mapper, never()).selectByClubAndMember(any(), any());
    }
}

package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.mapper.ClubCrmMapper;
import com.chengyinhub.business.service.impl.ClubCrmAppServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 第二轮拍板第 1 条:主理人侧五个字段按后端现有俱乐部角色下发。
 * 人数与核销区给「能看核销名单」的岗位(同 topicCustomers);场次管理对齐 ClubEventOps(含联席);导演台对齐 requireClubOwner。
 */
class ClubTopicManageStatsServiceTest {

    private static final Long CLUB = 42L;
    private static final Long TOPIC = 77L;
    private static final Long OWNER = 7L;
    private static final Long CO_OWNER = 8L;
    private static final Long CHECKIN = 9L;
    private static final Long STRANGER = 10L;

    private ClubCrmAppServiceImpl service;
    private ClubCrmMapper mapper;
    private IClubMemberService memberService;
    private IClubAccessService accessService;

    @BeforeEach
    void setUp() {
        service = new ClubCrmAppServiceImpl();
        mapper = mock(ClubCrmMapper.class);
        IClubService clubService = mock(IClubService.class);
        memberService = mock(IClubMemberService.class);
        accessService = mock(IClubAccessService.class);
        ReflectionTestUtils.setField(service, "clubCrmMapper", mapper);
        ReflectionTestUtils.setField(service, "clubService", clubService);
        ReflectionTestUtils.setField(service, "clubMemberService", memberService);
        ReflectionTestUtils.setField(service, "clubAccessService", accessService);

        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(OWNER);
        when(clubService.selectClubById(CLUB)).thenReturn(club);
        when(memberService.canGovernClub(any(Club.class), eq(OWNER))).thenReturn(true);
        when(memberService.canGovernClub(any(Club.class), eq(CO_OWNER))).thenReturn(true);
        when(accessService.resolve(eq(OWNER), eq(CLUB), any())).thenReturn(context(OWNER, ClubRoleCode.CLUB_OWNER));
        when(accessService.resolve(eq(CO_OWNER), eq(CLUB), any())).thenReturn(context(CO_OWNER, ClubRoleCode.CLUB_CO_OWNER));
        when(accessService.resolve(eq(CHECKIN), eq(CLUB), any())).thenReturn(context(CHECKIN, ClubRoleCode.EVENT_CHECKIN));
        when(accessService.resolve(eq(STRANGER), eq(CLUB), any())).thenReturn(null);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ownerMemberId", OWNER);
        row.put("nodeCount", 6L);
        row.put("sessionHeadcount", 12L);
        row.put("pendingVerifyCount", 4L);
        row.put("verifiedByMeCount", 5L);
        row.put("sessionStatus", "RUNNING");
        when(mapper.selectTopicManageStats(eq(CLUB), eq(TOPIC), any(), any())).thenReturn(row);
    }

    private ClubAccessContext context(Long principal, String roleCode) {
        return new ClubAccessContext(principal, CLUB, OWNER, null,
                new LinkedHashSet<>(Arrays.asList(ClubRoleCode.CLUB_MEMBER, roleCode)));
    }

    @Test
    void ownerWithCoopOrderGetsCountsAndAllThreeBlocks() {
        when(memberService.isTopicOwner(TOPIC, CLUB, OWNER)).thenReturn(true);

        Map<String, Object> data = service.topicManageStats(OWNER, CLUB, TOPIC, 901L);

        assertEquals(6, data.get("nodeCount"));
        assertEquals(12, data.get("sessionHeadcount"));
        assertEquals(4, data.get("pendingVerifyCount"));
        assertEquals(5, data.get("verifiedByMeCount"));
        assertEquals(true, data.get("canViewVerify"));
        assertEquals(true, data.get("canManageSessions"));
        assertEquals(true, data.get("canDirect"));
        assertEquals(true, data.get("canReviewChapterApplications"));
        assertFalse(data.containsKey("canManageEvents"), "共用字段已拆掉,不许再下发");
    }

    /** 俱乐部自建、没有合作单的主题:isTopicOwner=false ⇒ 导演台不给(后端 requireClubOwner 会拒);场次管理照给。 */
    @Test
    void ownerOfSelfBuiltTopicWithoutCoopOrderCannotDirectButCanManageSessions() {
        when(memberService.isTopicOwner(TOPIC, CLUB, OWNER)).thenReturn(false);

        Map<String, Object> data = service.topicManageStats(OWNER, CLUB, TOPIC, null);

        assertEquals(false, data.get("canDirect"));
        assertEquals(true, data.get("canReviewChapterApplications"));
        assertEquals(true, data.get("canManageSessions"));
        assertEquals(true, data.get("canViewVerify"));
    }

    @Test
    void checkinStaffSeesVerifyAreaOnly() {
        Map<String, Object> data = service.topicManageStats(CHECKIN, CLUB, TOPIC, null);

        assertEquals(12, data.get("sessionHeadcount"));
        assertEquals(true, data.get("canViewVerify"));
        assertEquals(false, data.get("canManageSessions"));
        assertEquals(false, data.get("canDirect"));
        assertEquals(false, data.get("canReviewChapterApplications"));
        assertEquals("RUNNING", data.get("sessionStatus"), "核销员没有导演台 projection,核销区靠后端下发的本场真状态");
    }

    /** 联席主理人:场次管理与后端 ClubEventOpsServiceImpl 同判据(给);导演台只认主理人(不给)。 */
    @Test
    void coOwnerManagesSessionsButCannotDirect() {
        Map<String, Object> data = service.topicManageStats(CO_OWNER, CLUB, TOPIC, null);

        assertEquals(4, data.get("pendingVerifyCount"));
        assertEquals(true, data.get("canViewVerify"));
        assertEquals(true, data.get("canManageSessions"));
        assertEquals(false, data.get("canDirect"));
        assertEquals(false, data.get("canReviewChapterApplications"));
    }

    @Test
    void outsiderGetsNodeCountOnlyAndNullNotZeroForCounts() {
        Map<String, Object> data = service.topicManageStats(STRANGER, CLUB, TOPIC, null);

        assertEquals(6, data.get("nodeCount"));
        assertNull(data.get("sessionHeadcount"), "看不到 ≠ 0 人");
        assertNull(data.get("pendingVerifyCount"));
        assertNull(data.get("verifiedByMeCount"));
        assertNull(data.get("sessionStatus"), "场次状态与人数同口径,看不到核销的人不给");
        assertEquals(false, data.get("canViewVerify"));
        assertEquals(false, data.get("canManageSessions"));
        assertEquals(false, data.get("canDirect"));
        assertEquals(false, data.get("canReviewChapterApplications"));
    }

    @Test
    void topicOutsideClubIsRejected() {
        when(mapper.selectTopicManageStats(eq(CLUB), eq(999L), any(), any())).thenReturn(null);

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.topicManageStats(OWNER, CLUB, 999L, null));
        assertEquals("主题不存在", e.getMessage());
    }

    @Test
    void inactiveMembershipIsTreatedAsOutsiderNotAsError() {
        when(accessService.resolve(eq(STRANGER), eq(CLUB), any()))
                .thenThrow(new ServiceException("俱乐部身份无效或权限不足"));

        Map<String, Object> data = service.topicManageStats(STRANGER, CLUB, TOPIC, null);

        assertNull(data.get("sessionHeadcount"));
        assertEquals(false, data.get("canViewVerify"));
        assertEquals(false, data.get("canManageSessions"));
    }
}

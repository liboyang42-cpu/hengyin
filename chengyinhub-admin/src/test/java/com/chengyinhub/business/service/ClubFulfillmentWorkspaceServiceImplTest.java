package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.dto.ClubFulfillmentWorkspaceDTO;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.service.impl.ClubFulfillmentWorkspaceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubFulfillmentWorkspaceServiceImplTest {

    private static final Long CLUB_ID = 71L;

    private IClubService clubService;
    private ClubLeadMapper clubLeadMapper;
    private CoopGroupVerifyMapper groupVerifyMapper;
    private ClubFulfillmentWorkspaceServiceImpl service;

    @BeforeEach
    void setUp() {
        clubService = mock(IClubService.class);
        clubLeadMapper = mock(ClubLeadMapper.class);
        groupVerifyMapper = mock(CoopGroupVerifyMapper.class);
        service = new ClubFulfillmentWorkspaceServiceImpl(clubService, clubLeadMapper, groupVerifyMapper);
    }

    @Test
    void missingClubStopsBeforeReadingFulfillmentTables() {
        when(clubService.selectClubById(CLUB_ID)).thenReturn(null);

        assertNull(service.getFulfillment(CLUB_ID));

        verify(clubLeadMapper, never()).selectFulfillmentSessionsByClub(CLUB_ID);
        verify(groupVerifyMapper, never()).selectFulfillmentVerificationsByClub(CLUB_ID);
    }

    @Test
    void aggregatesOnlyReadSafeSessionAttendanceAndGroupVerificationEvidence() throws Exception {
        Club club = new Club();
        club.setId(CLUB_ID);
        when(clubService.selectClubById(CLUB_ID)).thenReturn(club);

        ClubFulfillmentWorkspaceDTO.Session session = new ClubFulfillmentWorkspaceDTO.Session();
        session.setActivityId(101L);
        session.setTopicId(201L);
        session.setTopicName("梧桐夜游");
        session.setActivityName("周六集合");
        session.setLeaderMemberId(301L);
        session.setLeaderDisplayName("带队小林");
        session.setStatus(2);
        when(clubLeadMapper.selectFulfillmentSessionsByClub(CLUB_ID)).thenReturn(Arrays.asList(session));

        ClubFulfillmentWorkspaceDTO.Attendance attendance = new ClubFulfillmentWorkspaceDTO.Attendance();
        attendance.setActivityId(101L);
        attendance.setMemberId(401L);
        attendance.setMemberDisplayName("参与者小周");
        attendance.setArrivedAt(new Date());
        when(clubLeadMapper.selectFulfillmentAttendanceByClub(CLUB_ID)).thenReturn(Arrays.asList(attendance));

        ClubFulfillmentWorkspaceDTO.GroupVerification verification = new ClubFulfillmentWorkspaceDTO.GroupVerification();
        verification.setTopicId(201L);
        verification.setMerchantMemberId(501L);
        verification.setMerchantDisplayName("梧桐咖啡");
        verification.setLeaderMemberId(301L);
        when(groupVerifyMapper.selectFulfillmentVerificationsByClub(CLUB_ID)).thenReturn(Arrays.asList(verification));

        ClubFulfillmentWorkspaceDTO workspace = service.getFulfillment(CLUB_ID);

        assertEquals(CLUB_ID, workspace.getClubId());
        assertEquals(1, workspace.getSessions().size());
        assertEquals("参与者小周", workspace.getSessions().get(0).getAttendees().get(0).getMemberDisplayName());
        assertEquals("梧桐咖啡", workspace.getGroupVerifications().get(0).getMerchantDisplayName());
        assertThrows(NoSuchMethodException.class,
                () -> ClubFulfillmentWorkspaceDTO.Attendance.class.getMethod("getPhone"),
                "俱乐部履约调证不得暴露签到者联系方式");
        verify(clubLeadMapper, times(1)).selectFulfillmentSessionsByClub(CLUB_ID);
        verify(clubLeadMapper, times(1)).selectFulfillmentAttendanceByClub(CLUB_ID);
        verify(groupVerifyMapper, times(1)).selectFulfillmentVerificationsByClub(CLUB_ID);
    }
}

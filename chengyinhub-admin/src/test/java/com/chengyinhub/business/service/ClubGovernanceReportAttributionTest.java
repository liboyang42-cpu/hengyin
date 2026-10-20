package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubGovernanceCase;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubGovernanceMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.impl.ClubGovernanceServiceImpl;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SC-CL-REPORT：举报落库的举报人/被举报人归属，以及既有的重复规则
 * （同一俱乐部 + 同一举报人 + 同一 requestId 只受理一次，重试返回 409 且不再写工单或审计）。
 */
class ClubGovernanceReportAttributionTest {
    private static final long OWNER = 900L;
    private static final long TARGET = 902L;
    private static final long REPORTER = 903L;
    private static final long CLUB = 11L;

    private ClubMemberMapper members;
    private ClubGovernanceMapper governance;
    private BusinessAuditEventMapper audits;
    private ClubGovernanceServiceImpl service;

    @BeforeEach
    void setUp() {
        ClubMapper clubs = mock(ClubMapper.class);
        members = mock(ClubMemberMapper.class);
        governance = mock(ClubGovernanceMapper.class);
        audits = mock(BusinessAuditEventMapper.class);
        service = new ClubGovernanceServiceImpl();
        ReflectionTestUtils.setField(service, "clubMapper", clubs);
        ReflectionTestUtils.setField(service, "clubMemberMapper", members);
        ReflectionTestUtils.setField(service, "clubGovernanceMapper", governance);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", audits);

        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(OWNER);
        club.setStatus(1L);
        club.setDelFlag(0);
        when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(club);
        when(members.selectByClubAndMember(CLUB, REPORTER)).thenReturn(activeMember(REPORTER));
        when(members.selectByClubAndMember(CLUB, TARGET)).thenReturn(activeMember(TARGET));
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        when(governance.insertCase(any(ClubGovernanceCase.class))).thenAnswer(call -> {
            call.getArgument(0, ClubGovernanceCase.class).setId(101L);
            return 1;
        });
    }

    @Test
    void memberReportIsFiledByTheCallerAgainstTheNamedMemberOfThisClub() {
        ClubGovernanceCase report = service.submitReport(REPORTER, CLUB, "member", TARGET,
                Collections.singletonMap("messageId", "771"), "线下骚扰", "report-1");

        ArgumentCaptor<ClubGovernanceCase> stored = ArgumentCaptor.forClass(ClubGovernanceCase.class);
        verify(governance).insertCase(stored.capture());
        assertEquals(CLUB, stored.getValue().getClubId());
        assertEquals(REPORTER, stored.getValue().getFilerMemberId());
        assertEquals(TARGET, stored.getValue().getSubjectMemberId());
        assertEquals(ClubGovernanceCase.TARGET_MEMBER, stored.getValue().getTargetType());
        assertEquals(TARGET, stored.getValue().getTargetId());
        assertEquals("report-1", stored.getValue().getSubmitRequestId());
        assertEquals(ClubGovernanceCase.STATUS_PENDING, report.getStatus());
    }

    @Test
    void clubReportHasNoSubjectMemberSoTheOwnerIsNotSilentlyAttributed() {
        service.submitReport(REPORTER, CLUB, ClubGovernanceCase.TARGET_CLUB, CLUB,
                Collections.emptyMap(), "虚假宣传", "report-club");

        ArgumentCaptor<ClubGovernanceCase> stored = ArgumentCaptor.forClass(ClubGovernanceCase.class);
        verify(governance).insertCase(stored.capture());
        assertEquals(REPORTER, stored.getValue().getFilerMemberId());
        assertNull(stored.getValue().getSubjectMemberId());
        assertEquals(CLUB, stored.getValue().getTargetId());
    }

    @Test
    void retriedReportWithTheSameRequestIdIsRejectedWithoutASecondCaseOrAudit() {
        service.submitReport(REPORTER, CLUB, ClubGovernanceCase.TARGET_MEMBER, TARGET,
                Collections.emptyMap(), "线下骚扰", "report-retry");
        ClubGovernanceCase first = new ClubGovernanceCase();
        first.setId(101L);
        when(governance.selectCaseBySubmitRequestForUpdate(CLUB, REPORTER, "report-retry")).thenReturn(first);

        ServiceException duplicate = assertThrows(ServiceException.class,
                () -> service.submitReport(REPORTER, CLUB, ClubGovernanceCase.TARGET_MEMBER, TARGET,
                        Collections.emptyMap(), "线下骚扰", "report-retry"));

        assertEquals(HttpStatus.CONFLICT, duplicate.getCode());
        verify(governance, times(1)).insertCase(any(ClubGovernanceCase.class));
        verify(audits, times(1)).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
    }

    @Test
    void reporterOutsideTheClubCannotFileAnyCase() {
        when(members.selectByClubAndMember(CLUB, REPORTER)).thenReturn(null);

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.submitReport(REPORTER, CLUB, ClubGovernanceCase.TARGET_MEMBER, TARGET,
                        Collections.emptyMap(), "线下骚扰", "report-outsider"));

        assertEquals(HttpStatus.FORBIDDEN, denied.getCode());
        verify(governance, never()).insertCase(any(ClubGovernanceCase.class));
    }

    private ClubMember activeMember(Long memberId) {
        ClubMember member = new ClubMember();
        member.setId(70L + memberId);
        member.setClubId(CLUB);
        member.setMemberId(memberId);
        member.setStatus(1);
        member.setDelFlag(0);
        return member;
    }
}

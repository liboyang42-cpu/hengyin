package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubAccessServiceImpl;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubRoleAssignment;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubRoleAssignmentMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.ClubRoleAssignmentServiceImpl;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubRoleAssignmentServiceImplTest {
    private static final long OWNER = 900L;
    private static final long TARGET = 901L;
    private static final long OPERATOR = 902L;
    private static final long CLUB = 11L;
    private static final long EVENT = 55L;

    private IClubAccessService access;
    private ClubMapper clubs;
    private ClubMemberMapper members;
    private CmsActivityMapper activities;
    private ClubRoleAssignmentMapper assignments;
    private BusinessAuditEventMapper audits;
    private ClubRoleAssignmentServiceImpl service;

    @BeforeEach
    void setUp() {
        access = mock(IClubAccessService.class);
        clubs = mock(ClubMapper.class);
        members = mock(ClubMemberMapper.class);
        activities = mock(CmsActivityMapper.class);
        assignments = mock(ClubRoleAssignmentMapper.class);
        audits = mock(BusinessAuditEventMapper.class);
        service = new ClubRoleAssignmentServiceImpl();
        ReflectionTestUtils.setField(service, "clubAccessService", access);
        ReflectionTestUtils.setField(service, "clubMapper", clubs);
        ReflectionTestUtils.setField(service, "clubMemberMapper", members);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "clubRoleAssignmentMapper", assignments);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", audits);
        when(access.require(OWNER, CLUB, null, ClubPermission.ROLE_MANAGE)).thenReturn(ownerAccess());
        when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(activeClub(CLUB, OWNER));
        when(members.selectByClubAndMember(CLUB, TARGET)).thenReturn(activeMember(CLUB, TARGET));
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
    }

    @Test
    void ownerAssignsClubOperatorToActiveMemberAndWritesNonPiiAudit() {
        when(assignments.insertClubRoleAssignment(any(ClubRoleAssignment.class))).thenAnswer(call -> {
            call.getArgument(0, ClubRoleAssignment.class).setId(81L);
            return 1;
        });

        ClubRoleAssignment created = service.assign(OWNER, CLUB, TARGET,
                ClubRoleCode.CLUB_OPERATOR, null, null, "req-1");

        assertEquals(81L, created.getId());
        assertEquals(ClubRoleAssignment.SCOPE_CLUB, created.getScopeType());
        assertEquals(CLUB, created.getScopeId());
        verify(assignments).expireActiveByScope(
                org.mockito.ArgumentMatchers.eq(CLUB), org.mockito.ArgumentMatchers.eq(TARGET),
                org.mockito.ArgumentMatchers.eq(ClubRoleAssignment.SCOPE_CLUB),
                org.mockito.ArgumentMatchers.eq(CLUB), any(Date.class),
                org.mockito.ArgumentMatchers.eq(String.valueOf(OWNER)));
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals("CLUB", audit.getValue().getDomainType());
        assertEquals(OWNER, audit.getValue().getOwnerMemberId());
        assertEquals("CLUB_ROLE_ASSIGNED", audit.getValue().getAction());
        String payload = String.valueOf(audit.getValue().getAfterJson());
        assertTrue(payload.contains(ClubRoleCode.CLUB_OPERATOR));
        assertFalse(payload.toLowerCase().contains("phone"));
        assertFalse(payload.toLowerCase().contains("token"));
        org.mockito.InOrder lockOrder = inOrder(clubs, access);
        lockOrder.verify(access).require(OWNER, CLUB, null, ClubPermission.ROLE_MANAGE);
        lockOrder.verify(clubs).selectClubByIdForUpdate(CLUB);
        lockOrder.verify(access).require(OWNER, CLUB, null, ClubPermission.ROLE_MANAGE);
    }

    @Test
    void assignReplaysExactCommittedRequestWithoutASecondMutationOrAudit() {
        ClubRoleAssignment committed = activeAssignment(CLUB, TARGET,
                ClubRoleCode.CLUB_OPERATOR, ClubRoleAssignment.SCOPE_CLUB, CLUB);
        committed.setAssignedByMemberId(OWNER);
        BusinessAuditEvent receipt = roleReceipt(
                "CLUB_ROLE_ASSIGNED", "req-assign-replay", committed, null);
        receipt.setAfterJson("{\"status\": \"ACTIVE\", \"scopeId\": 11, "
                + "\"roleCode\": \"CLUB_OPERATOR\", \"scopeType\": \"CLUB\"}");
        when(audits.selectByDomainActionRequestForUpdate(
                "CLUB", CLUB, "CLUB_ROLE_ASSIGNED", "req-assign-replay")).thenReturn(receipt);
        when(assignments.selectByIdForUpdate(81L)).thenReturn(committed);

        ClubRoleAssignment replayed = service.assign(OWNER, CLUB, TARGET,
                ClubRoleCode.CLUB_OPERATOR, null, null, "req-assign-replay");

        assertEquals(81L, replayed.getId());
        verify(assignments, never()).expireActiveByScope(any(), any(), any(), any(), any(), any());
        verify(assignments, never()).insertClubRoleAssignment(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void assignRejectsRequestIdReuseWithDifferentRoleEnvelope() {
        ClubRoleAssignment committed = activeAssignment(CLUB, TARGET,
                ClubRoleCode.CLUB_OPERATOR, ClubRoleAssignment.SCOPE_CLUB, CLUB);
        committed.setAssignedByMemberId(OWNER);
        BusinessAuditEvent receipt = roleReceipt(
                "CLUB_ROLE_ASSIGNED", "req-assign-mismatch", committed, null);
        when(audits.selectByDomainActionRequestForUpdate(
                "CLUB", CLUB, "CLUB_ROLE_ASSIGNED", "req-assign-mismatch")).thenReturn(receipt);
        when(assignments.selectByIdForUpdate(81L)).thenReturn(committed);

        ServiceException error = assertThrows(ServiceException.class, () -> service.assign(
                OWNER, CLUB, TARGET, ClubRoleCode.CLUB_CO_OWNER,
                null, null, "req-assign-mismatch"));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(assignments, never()).expireActiveByScope(any(), any(), any(), any(), any(), any());
        verify(assignments, never()).insertClubRoleAssignment(any());
    }

    @Test
    void nonOwnerAndDerivedRolesCanNeverBePersisted() {
        when(access.require(TARGET, CLUB, null, ClubPermission.ROLE_MANAGE))
                .thenThrow(new ServiceException("权限不足", HttpStatus.FORBIDDEN));

        assertThrows(ServiceException.class, () -> service.assign(TARGET, CLUB, TARGET,
                ClubRoleCode.CLUB_OPERATOR, null, null, "req-non-owner"));
        assertThrows(ServiceException.class, () -> service.assign(OWNER, CLUB, TARGET,
                ClubRoleCode.CLUB_OWNER, null, null, "req-owner"));
        assertThrows(ServiceException.class, () -> service.assign(OWNER, CLUB, TARGET,
                ClubRoleCode.CLUB_MEMBER, null, null, "req-member"));
        verify(assignments, never()).insertClubRoleAssignment(any());
    }

    @Test
    void eventRoleRequiresActivityOwnedBySameClub() {
        when(activities.selectCmsActivityById(EVENT)).thenReturn(activity(EVENT, 12L));

        assertThrows(ServiceException.class, () -> service.assign(OWNER, CLUB, TARGET,
                ClubRoleCode.EVENT_LEAD, EVENT, null, "req-cross"));
        verify(assignments, never()).insertClubRoleAssignment(any());
    }

    @Test
    void assignmentRequiresActiveTargetMember() {
        ClubMember pending = activeMember(CLUB, TARGET);
        pending.setStatus(0);
        when(members.selectByClubAndMember(CLUB, TARGET)).thenReturn(pending);

        assertThrows(ServiceException.class, () -> service.assign(OWNER, CLUB, TARGET,
                ClubRoleCode.CLUB_CO_OWNER, null, null, "req-pending"));
        verify(assignments, never()).insertClubRoleAssignment(any());
    }

    @Test
    void activeAssignmentInSameScopeReturnsConflictBeforeUniqueKey() {
        ClubRoleAssignment existing = activeAssignment(CLUB, TARGET,
                ClubRoleCode.CLUB_OPERATOR, ClubRoleAssignment.SCOPE_CLUB, CLUB);
        when(assignments.selectActiveByUniqueScope(
                org.mockito.ArgumentMatchers.eq(CLUB), org.mockito.ArgumentMatchers.eq(TARGET),
                org.mockito.ArgumentMatchers.eq(ClubRoleAssignment.SCOPE_CLUB),
                org.mockito.ArgumentMatchers.eq(CLUB), any(Date.class))).thenReturn(existing);

        ServiceException error = assertThrows(ServiceException.class, () -> service.assign(
                OWNER, CLUB, TARGET, ClubRoleCode.CLUB_CO_OWNER, null, null, "req-duplicate"));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(assignments, never()).insertClubRoleAssignment(any());
    }

    @Test
    void revokeRejectsCrossClubAndCrossEventSession() {
        ClubRoleAssignment crossClub = activeAssignment(12L, TARGET,
                ClubRoleCode.CLUB_OPERATOR, ClubRoleAssignment.SCOPE_CLUB, 12L);
        when(assignments.selectByIdForUpdate(81L)).thenReturn(crossClub);
        assertThrows(ServiceException.class,
                () -> service.revoke(OWNER, CLUB, null, 81L, 0, "撤销", "req-cross-club"));

        ClubRoleAssignment crossEvent = activeAssignment(CLUB, TARGET,
                ClubRoleCode.EVENT_LEAD, ClubRoleAssignment.SCOPE_EVENT, 56L);
        when(assignments.selectByIdForUpdate(81L)).thenReturn(crossEvent);
        assertThrows(ServiceException.class,
                () -> service.revoke(OWNER, CLUB, EVENT, 81L, 0, "撤销", "req-cross-event"));
        verify(assignments, never()).revokeCas(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ownerRevokesSameScopeRoleWithCasAndAudit() {
        ClubRoleAssignment row = activeAssignment(CLUB, TARGET,
                ClubRoleCode.EVENT_CHECKIN, ClubRoleAssignment.SCOPE_EVENT, EVENT);
        row.setVersion(2);
        when(assignments.selectByIdForUpdate(81L)).thenReturn(row);
        when(activities.selectCmsActivityById(EVENT)).thenReturn(activity(EVENT, CLUB));
        when(assignments.revokeCas(81L, CLUB, 2, OWNER, "排班变化", String.valueOf(OWNER)))
                .thenReturn(1);

        service.revoke(OWNER, CLUB, EVENT, 81L, 2, "排班变化", "req-revoke");

        verify(assignments).revokeCas(81L, CLUB, 2, OWNER, "排班变化", String.valueOf(OWNER));
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals("CLUB_ROLE_REVOKED", audit.getValue().getAction());
        assertEquals("req-revoke", audit.getValue().getRequestId());
        org.mockito.InOrder lockOrder = inOrder(clubs, access);
        lockOrder.verify(access).require(OWNER, CLUB, null, ClubPermission.ROLE_MANAGE);
        lockOrder.verify(clubs).selectClubByIdForUpdate(CLUB);
        lockOrder.verify(access).require(OWNER, CLUB, null, ClubPermission.ROLE_MANAGE);
    }

    @Test
    void revokingClubOperatorClearsLegacyAdminBeforePermissionReadback() {
        ClubRoleAssignment row = activeAssignment(CLUB, TARGET,
                ClubRoleCode.CLUB_OPERATOR, ClubRoleAssignment.SCOPE_CLUB, CLUB);
        ClubMember legacyMember = activeMember(CLUB, TARGET);
        legacyMember.setRole(1);
        when(assignments.selectByIdForUpdate(81L)).thenReturn(row);
        when(assignments.revokeCas(81L, CLUB, 0, OWNER,
                "取消管理员", String.valueOf(OWNER))).thenReturn(1);
        when(members.selectByClubAndMemberForUpdate(CLUB, TARGET)).thenReturn(legacyMember);
        when(members.updateClubMember(any(ClubMember.class))).thenAnswer(call -> {
            legacyMember.setRole(call.getArgument(0, ClubMember.class).getRole());
            return 1;
        });

        service.revoke(OWNER, CLUB, null, 81L, 0,
                "取消管理员", "req-revoke-legacy");

        ClubAccessServiceImpl resolver = new ClubAccessServiceImpl();
        UmsMemberMapper accounts = mock(UmsMemberMapper.class);
        ReflectionTestUtils.setField(resolver, "clubMapper", clubs);
        ReflectionTestUtils.setField(resolver, "clubMemberMapper", members);
        ReflectionTestUtils.setField(resolver, "clubRoleAssignmentMapper", assignments);
        ReflectionTestUtils.setField(resolver, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(resolver, "umsMemberMapper", accounts);
        when(clubs.selectClubById(CLUB)).thenReturn(activeClub(CLUB, OWNER));
        when(accounts.lockActiveMemberById(TARGET)).thenReturn(TARGET);
        when(members.selectByClubAndMember(CLUB, TARGET)).thenReturn(legacyMember);
        when(assignments.selectActiveForAccess(
                org.mockito.ArgumentMatchers.eq(CLUB), org.mockito.ArgumentMatchers.eq(TARGET),
                org.mockito.ArgumentMatchers.eq(null), any(Date.class)))
                .thenReturn(Collections.emptyList());

        ClubAccessContext readback = resolver.resolve(TARGET, CLUB, null);
        assertFalse(readback.getRoleCodes().contains(ClubRoleCode.CLUB_LEGACY_ADMIN));
        assertFalse(readback.hasPermission(ClubPermission.MEMBER_MANAGE));
        assertFalse(readback.hasPermission(ClubPermission.CONTENT_MANAGE));
    }

    @Test
    void legacyRevokeWithoutAssignmentRechecksOwnerAfterLockAndAuditsRoleClear() {
        ClubMember legacyMember = activeMember(CLUB, TARGET);
        legacyMember.setRole(1);
        when(members.selectByClubAndMemberForUpdate(CLUB, TARGET)).thenReturn(legacyMember);
        when(members.updateClubMember(any(ClubMember.class))).thenReturn(1);

        service.revokeLegacyOperator(OWNER, CLUB, TARGET,
                "通过旧成员角色入口撤销", "legacy-clear");

        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals("CLUB_LEGACY_ADMIN_REVOKED", audit.getValue().getAction());
        assertEquals("CLUB_MEMBER", audit.getValue().getTargetType());
        assertEquals(TARGET, audit.getValue().getTargetId());
        assertEquals("{\"role\":0}", audit.getValue().getAfterJson());
        org.mockito.InOrder lockOrder = inOrder(access, clubs, members);
        lockOrder.verify(access).require(OWNER, CLUB, null, ClubPermission.ROLE_MANAGE);
        lockOrder.verify(clubs).selectClubByIdForUpdate(CLUB);
        lockOrder.verify(access).require(OWNER, CLUB, null, ClubPermission.ROLE_MANAGE);
        lockOrder.verify(members).selectByClubAndMemberForUpdate(CLUB, TARGET);
    }

    @Test
    void legacyRevokeFailsClosedWhenOwnershipChangesBeforeClubLockReadback() {
        ClubAccessContext transferred = new ClubAccessContext(
                OWNER, CLUB, 999L, null, Collections.singleton(ClubRoleCode.CLUB_OWNER));
        when(access.require(OWNER, CLUB, null, ClubPermission.ROLE_MANAGE))
                .thenReturn(ownerAccess(), transferred);

        assertThrows(ServiceException.class, () -> service.revokeLegacyOperator(
                OWNER, CLUB, TARGET, "撤销", "legacy-transfer-race"));

        verify(members, never()).selectByClubAndMemberForUpdate(any(), any());
        verify(members, never()).updateClubMember(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void revokeReplaysExactCommittedRequestAfterResponseLoss() {
        ClubRoleAssignment revoked = activeAssignment(CLUB, TARGET,
                ClubRoleCode.EVENT_CHECKIN, ClubRoleAssignment.SCOPE_EVENT, EVENT);
        revoked.setStatus(ClubRoleAssignment.STATUS_REVOKED);
        revoked.setVersion(3);
        revoked.setRevokedByMemberId(OWNER);
        revoked.setRevokeReason("排班变化");
        BusinessAuditEvent receipt = roleReceipt(
                "CLUB_ROLE_REVOKED", "req-revoke-replay", revoked, "排班变化");
        receipt.setBeforeJson("{\"status\": \"ACTIVE\", \"scopeId\": 55, "
                + "\"roleCode\": \"EVENT_CHECKIN\", \"scopeType\": \"EVENT\"}");
        receipt.setAfterJson("{\"status\": \"REVOKED\"}");
        when(assignments.selectByIdForUpdate(81L)).thenReturn(revoked);
        when(audits.selectByDomainActionRequestForUpdate(
                "CLUB", CLUB, "CLUB_ROLE_REVOKED", "req-revoke-replay")).thenReturn(receipt);
        when(activities.selectCmsActivityById(EVENT)).thenReturn(activity(EVENT, CLUB));

        service.revoke(OWNER, CLUB, EVENT, 81L, 2, "排班变化", "req-revoke-replay");

        verify(assignments, never()).revokeCas(any(), any(), any(), any(), any(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void assignRejectsMalformedOrAlteredAuditJson() {
        ClubRoleAssignment committed = activeAssignment(CLUB, TARGET,
                ClubRoleCode.CLUB_OPERATOR, ClubRoleAssignment.SCOPE_CLUB, CLUB);
        committed.setAssignedByMemberId(OWNER);
        BusinessAuditEvent receipt = roleReceipt(
                "CLUB_ROLE_ASSIGNED", "req-invalid-json", committed, null);
        when(audits.selectByDomainActionRequestForUpdate(
                "CLUB", CLUB, "CLUB_ROLE_ASSIGNED", "req-invalid-json")).thenReturn(receipt);
        when(assignments.selectByIdForUpdate(81L)).thenReturn(committed);
        for (String json : new String[]{"{invalid}", "null",
                receipt.getAfterJson().replace("\"ACTIVE\"", "\"REVOKED\""),
                receipt.getAfterJson().replace("}", ",\"extra\":true}")}) {
            receipt.setAfterJson(json);
            ServiceException error = assertThrows(ServiceException.class, () -> service.assign(
                    OWNER, CLUB, TARGET, ClubRoleCode.CLUB_OPERATOR, null, null, "req-invalid-json"));
            assertEquals(HttpStatus.CONFLICT, error.getCode());
        }
        verify(assignments, never()).insertClubRoleAssignment(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void revokeRejectsRequestIdReuseWithDifferentReason() {
        ClubRoleAssignment revoked = activeAssignment(CLUB, TARGET,
                ClubRoleCode.EVENT_CHECKIN, ClubRoleAssignment.SCOPE_EVENT, EVENT);
        revoked.setStatus(ClubRoleAssignment.STATUS_REVOKED);
        revoked.setVersion(3);
        revoked.setRevokedByMemberId(OWNER);
        revoked.setRevokeReason("排班变化");
        BusinessAuditEvent receipt = roleReceipt(
                "CLUB_ROLE_REVOKED", "req-revoke-mismatch", revoked, "排班变化");
        when(assignments.selectByIdForUpdate(81L)).thenReturn(revoked);
        when(audits.selectByDomainActionRequestForUpdate(
                "CLUB", CLUB, "CLUB_ROLE_REVOKED", "req-revoke-mismatch")).thenReturn(receipt);
        when(activities.selectCmsActivityById(EVENT)).thenReturn(activity(EVENT, CLUB));

        ServiceException error = assertThrows(ServiceException.class, () -> service.revoke(
                OWNER, CLUB, EVENT, 81L, 2, "另一个原因", "req-revoke-mismatch"));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(assignments, never()).revokeCas(any(), any(), any(), any(), any(), any());
    }

    @Test
    void accessEndRevokesEveryClubAndEventRoleWithExplicitReasonAndAudit() {
        when(clubs.selectClubById(CLUB)).thenReturn(activeClub(CLUB, OWNER));
        when(assignments.revokeAllActiveByMember(
                org.mockito.ArgumentMatchers.eq(CLUB), org.mockito.ArgumentMatchers.eq(TARGET),
                org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.eq("MEMBER_BANNED"), any(Date.class),
                org.mockito.ArgumentMatchers.eq("ACCESS_END:" + OWNER))).thenReturn(2);

        assertEquals(2, service.revokeAllForAccessEnd(
                CLUB, TARGET, OWNER, ClubRoleCode.CLUB_OWNER, "MEMBER_BANNED"));

        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals("CLUB_ROLE_ASSIGNMENTS_REVOKED", audit.getValue().getAction());
        assertEquals(TARGET, audit.getValue().getTargetId());
        assertEquals("MEMBER_BANNED", audit.getValue().getReason());
        assertTrue(String.valueOf(audit.getValue().getAfterJson()).contains("\"count\":2"));
    }

    @Test
    void activityManagerAssignsCanonicalEventLeadForOneOwnedOccurrence() {
        ClubAccessContext operatorAccess = new ClubAccessContext(
                OPERATOR, CLUB, OWNER, null, Collections.singleton(ClubRoleCode.CLUB_CO_OWNER));
        when(access.require(OPERATOR, CLUB, null, ClubPermission.ACTIVITY_MANAGE))
                .thenReturn(operatorAccess);
        when(activities.selectCmsActivityById(EVENT)).thenReturn(activity(EVENT, CLUB));
        when(assignments.insertClubRoleAssignment(any(ClubRoleAssignment.class))).thenAnswer(call -> {
            call.getArgument(0, ClubRoleAssignment.class).setId(82L);
            return 1;
        });

        ClubRoleAssignment created = service.assignEventLeadForOccurrence(
                OPERATOR, CLUB, TARGET, EVENT, "series-lead-61-55");

        assertEquals(ClubRoleCode.EVENT_LEAD, created.getRoleCode());
        assertEquals(ClubRoleAssignment.SCOPE_EVENT, created.getScopeType());
        assertEquals(EVENT, created.getScopeId());
        assertEquals(OPERATOR, created.getAssignedByMemberId());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals("CLUB_EVENT_LEAD_ASSIGNED", audit.getValue().getAction());
        assertEquals("series-lead-61-55", audit.getValue().getRequestId());
    }

    /**
     * ★ 单场只有一个领队。改派给另一个成员时,必须把本场其它成员的有效领队一并收掉:
     * expireActiveByScope 只按"同一目标成员"失效,留下多条 ACTIVE 后运行态只解析最早
     * 那条 —— 页面显示已委派,新领队却仍无发车权,且不报错。
     */
    @Test
    void assigningEventLeadToAnotherMemberRevokesThePreviousLeadOfSameOccurrence() {
        ClubAccessContext operatorAccess = new ClubAccessContext(
                OPERATOR, CLUB, OWNER, null, Collections.singleton(ClubRoleCode.CLUB_CO_OWNER));
        when(access.require(OPERATOR, CLUB, null, ClubPermission.ACTIVITY_MANAGE))
                .thenReturn(operatorAccess);
        when(activities.selectCmsActivityById(EVENT)).thenReturn(activity(EVENT, CLUB));
        ClubRoleAssignment previousLead = activeAssignment(
                CLUB, 903L, ClubRoleCode.EVENT_LEAD, ClubRoleAssignment.SCOPE_EVENT, EVENT);
        previousLead.setId(82L);
        previousLead.setVersion(1);
        when(assignments.selectActiveEventLeadsForUpdate(CLUB, EVENT))
                .thenReturn(java.util.Collections.singletonList(previousLead));
        when(assignments.revokeCas(82L, CLUB, 1, OPERATOR,
                "LEAD_REPLACED", String.valueOf(OPERATOR))).thenReturn(1);
        when(assignments.insertClubRoleAssignment(any(ClubRoleAssignment.class))).thenAnswer(call -> {
            call.getArgument(0, ClubRoleAssignment.class).setId(83L);
            return 1;
        });

        ClubRoleAssignment created = service.assignEventLeadForOccurrence(
                OPERATOR, CLUB, TARGET, EVENT, "series-lead-61-56");

        assertEquals(TARGET, created.getTargetMemberId());
        verify(assignments).revokeCas(82L, CLUB, 1, OPERATOR,
                "LEAD_REPLACED", String.valueOf(OPERATOR));
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits, org.mockito.Mockito.times(2)).insertBusinessAuditEvent(audit.capture());
        assertEquals("CLUB_EVENT_LEAD_REVOKED", audit.getAllValues().get(0).getAction());
        assertEquals("LEAD_REPLACED", audit.getAllValues().get(0).getReason());
        assertEquals(Long.valueOf(903L), audit.getAllValues().get(0).getTargetId());
        assertEquals("CLUB_EVENT_LEAD_ASSIGNED", audit.getAllValues().get(1).getAction());
    }

    @Test
    void occurrenceCancellationRevokesEveryCanonicalEventLeadWithAuditAndIsIdempotent() {
        ClubAccessContext operatorAccess = new ClubAccessContext(
                OPERATOR, CLUB, OWNER, null, Collections.singleton(ClubRoleCode.CLUB_CO_OWNER));
        when(access.require(OPERATOR, CLUB, null, ClubPermission.ACTIVITY_MANAGE))
                .thenReturn(operatorAccess);
        when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(activeClub(CLUB, OWNER));
        when(activities.selectCmsActivityById(EVENT)).thenReturn(activity(EVENT, CLUB));
        ClubRoleAssignment lead = activeAssignment(
                CLUB, TARGET, ClubRoleCode.EVENT_LEAD, ClubRoleAssignment.SCOPE_EVENT, EVENT);
        lead.setVersion(2);
        ClubRoleAssignment secondLead = activeAssignment(
                CLUB, 903L, ClubRoleCode.EVENT_LEAD, ClubRoleAssignment.SCOPE_EVENT, EVENT);
        secondLead.setId(82L);
        secondLead.setVersion(1);
        when(assignments.selectActiveEventLeadsForUpdate(CLUB, EVENT))
                .thenReturn(java.util.Arrays.asList(lead, secondLead), Collections.emptyList());
        when(assignments.revokeCas(81L, CLUB, 2, OPERATOR,
                "OCCURRENCE_CANCELLED", String.valueOf(OPERATOR))).thenReturn(1);
        when(assignments.revokeCas(82L, CLUB, 1, OPERATOR,
                "OCCURRENCE_CANCELLED", String.valueOf(OPERATOR))).thenReturn(1);

        assertEquals(2, service.revokeEventLeadForCancelledOccurrence(
                OPERATOR, CLUB, EVENT, "series-cancel-61-55"));
        assertEquals(0, service.revokeEventLeadForCancelledOccurrence(
                OPERATOR, CLUB, EVENT, "series-cancel-61-55-retry"));

        verify(assignments).revokeCas(81L, CLUB, 2, OPERATOR,
                "OCCURRENCE_CANCELLED", String.valueOf(OPERATOR));
        verify(assignments).revokeCas(82L, CLUB, 1, OPERATOR,
                "OCCURRENCE_CANCELLED", String.valueOf(OPERATOR));
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits, org.mockito.Mockito.times(2)).insertBusinessAuditEvent(audit.capture());
        assertEquals("CLUB_EVENT_LEAD_REVOKED", audit.getAllValues().get(0).getAction());
        assertEquals("OCCURRENCE_CANCELLED", audit.getAllValues().get(0).getReason());
        assertEquals("series-cancel-61-55-role-81", audit.getAllValues().get(0).getRequestId());
        assertEquals("series-cancel-61-55-role-82", audit.getAllValues().get(1).getRequestId());
        assertEquals(EVENT, audit.getAllValues().get(0).getRelationId());
    }

    @Test
    void occurrenceCancellationFailsClosedWhenRoleCasOrAuditCannotPersist() throws Exception {
        ClubAccessContext operatorAccess = new ClubAccessContext(
                OPERATOR, CLUB, OWNER, null, Collections.singleton(ClubRoleCode.CLUB_CO_OWNER));
        when(access.require(OPERATOR, CLUB, null, ClubPermission.ACTIVITY_MANAGE))
                .thenReturn(operatorAccess);
        when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(activeClub(CLUB, OWNER));
        when(activities.selectCmsActivityById(EVENT)).thenReturn(activity(EVENT, CLUB));
        ClubRoleAssignment lead = activeAssignment(
                CLUB, TARGET, ClubRoleCode.EVENT_LEAD, ClubRoleAssignment.SCOPE_EVENT, EVENT);
        when(assignments.selectActiveEventLeadsForUpdate(CLUB, EVENT))
                .thenReturn(Collections.singletonList(lead));
        when(assignments.revokeCas(81L, CLUB, 0, OPERATOR,
                "OCCURRENCE_CANCELLED", String.valueOf(OPERATOR))).thenReturn(0);

        ServiceException cas = assertThrows(ServiceException.class,
                () -> service.revokeEventLeadForCancelledOccurrence(
                        OPERATOR, CLUB, EVENT, "series-cancel-cas"));
        assertEquals(HttpStatus.CONFLICT, cas.getCode());
        verify(audits, never()).insertBusinessAuditEvent(any());

        when(assignments.revokeCas(81L, CLUB, 0, OPERATOR,
                "OCCURRENCE_CANCELLED", String.valueOf(OPERATOR))).thenReturn(1);
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(0);
        ServiceException audit = assertThrows(ServiceException.class,
                () -> service.revokeEventLeadForCancelledOccurrence(
                        OPERATOR, CLUB, EVENT, "series-cancel-audit"));
        assertEquals(HttpStatus.CONFLICT, audit.getCode());

        org.springframework.transaction.annotation.Transactional transaction =
                ClubRoleAssignmentServiceImpl.class.getDeclaredMethod(
                        "revokeEventLeadForCancelledOccurrence",
                        Long.class, Long.class, Long.class, String.class)
                        .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertTrue(transaction != null);
        assertTrue(java.util.Arrays.asList(transaction.rollbackFor()).contains(Exception.class));
    }

    @Test
    void platformAdminEnablesCanonicalClubOperatorWithPlatformAudit() {
        when(assignments.insertClubRoleAssignment(any(ClubRoleAssignment.class))).thenAnswer(call -> {
            call.getArgument(0, ClubRoleAssignment.class).setId(83L);
            return 1;
        });

        ClubRoleAssignment created = service.setPlatformOperatorRole(
                77L, CLUB, TARGET, true, "platform-role-enable");

        assertEquals(ClubRoleCode.CLUB_OPERATOR, created.getRoleCode());
        assertEquals(ClubRoleAssignment.SCOPE_CLUB, created.getScopeType());
        assertEquals(CLUB, created.getScopeId());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals("PLATFORM_ADMIN", audit.getValue().getActorRoleCode());
        assertEquals("CLUB_ROLE_ASSIGNED", audit.getValue().getAction());
    }

    @Test
    void platformAdminDisablesOnlyCanonicalClubOperator() {
        ClubRoleAssignment existing = activeAssignment(
                CLUB, TARGET, ClubRoleCode.CLUB_OPERATOR, ClubRoleAssignment.SCOPE_CLUB, CLUB);
        existing.setVersion(3);
        when(assignments.selectActiveByUniqueScope(
                org.mockito.ArgumentMatchers.eq(CLUB), org.mockito.ArgumentMatchers.eq(TARGET),
                org.mockito.ArgumentMatchers.eq(ClubRoleAssignment.SCOPE_CLUB),
                org.mockito.ArgumentMatchers.eq(CLUB), any(Date.class))).thenReturn(existing);
        when(assignments.revokeCas(81L, CLUB, 3, 77L,
                "PLATFORM_LEGACY_ADMIN_DISABLED", "PLATFORM:77")).thenReturn(1);

        ClubRoleAssignment revoked = service.setPlatformOperatorRole(
                77L, CLUB, TARGET, false, "platform-role-disable");

        assertEquals(ClubRoleAssignment.STATUS_REVOKED, revoked.getStatus());
        verify(assignments).revokeCas(81L, CLUB, 3, 77L,
                "PLATFORM_LEGACY_ADMIN_DISABLED", "PLATFORM:77");
    }

    @Test
    void platformDisableWithoutAssignmentStillClearsStoredLegacyAdmin() {
        ClubMember legacyMember = activeMember(CLUB, TARGET);
        legacyMember.setRole(1);
        when(members.selectByClubAndMember(CLUB, TARGET)).thenReturn(legacyMember);
        when(members.selectByClubAndMemberForUpdate(CLUB, TARGET)).thenReturn(legacyMember);
        when(members.updateClubMember(any(ClubMember.class))).thenReturn(1);

        ClubRoleAssignment result = service.setPlatformOperatorRole(
                77L, CLUB, TARGET, false, "platform-role-disable-no-assignment");

        assertEquals(null, result);
        ArgumentCaptor<ClubMember> update = ArgumentCaptor.forClass(ClubMember.class);
        verify(members).updateClubMember(update.capture());
        assertEquals(Integer.valueOf(0), update.getValue().getRole());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals("CLUB_LEGACY_ADMIN_REVOKED", audit.getValue().getAction());
    }

    private ClubAccessContext ownerAccess() {
        return new ClubAccessContext(OWNER, CLUB, OWNER, null,
                Collections.singleton(ClubRoleCode.CLUB_OWNER));
    }

    private Club activeClub(Long id, Long ownerMemberId) {
        Club club = new Club();
        club.setId(id);
        club.setMemberId(ownerMemberId);
        club.setStatus(1L);
        club.setDelFlag(0);
        return club;
    }

    private ClubMember activeMember(Long clubId, Long memberId) {
        ClubMember member = new ClubMember();
        member.setId(71L);
        member.setClubId(clubId);
        member.setMemberId(memberId);
        member.setStatus(1);
        member.setDelFlag(0);
        return member;
    }

    private CmsActivity activity(Long id, Long clubId) {
        CmsActivity activity = new CmsActivity();
        activity.setId(id);
        activity.setClubId(clubId);
        activity.setDelFlag(0);
        return activity;
    }

    private ClubRoleAssignment activeAssignment(Long clubId, Long memberId, String roleCode,
                                                String scopeType, Long scopeId) {
        ClubRoleAssignment row = new ClubRoleAssignment();
        row.setId(81L);
        row.setClubId(clubId);
        row.setTargetMemberId(memberId);
        row.setRoleCode(roleCode);
        row.setScopeType(scopeType);
        row.setScopeId(scopeId);
        row.setStatus(ClubRoleAssignment.STATUS_ACTIVE);
        row.setVersion(0);
        row.setAssignedAt(new Date());
        return row;
    }

    private BusinessAuditEvent roleReceipt(String action, String requestId,
                                           ClubRoleAssignment assignment, String reason) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("CLUB");
        event.setDomainId(CLUB);
        event.setOwnerMemberId(OWNER);
        event.setActorMemberId(OWNER);
        event.setActorRoleCode(ClubRoleCode.CLUB_OWNER);
        event.setAction(action);
        event.setTargetType("CLUB_ROLE_ASSIGNMENT");
        event.setTargetId(assignment.getTargetMemberId());
        event.setRelationId(assignment.getId());
        event.setReason(reason);
        event.setRequestId(requestId);
        String assignmentJson = "{\"roleCode\":\"" + assignment.getRoleCode()
                + "\",\"scopeType\":\"" + assignment.getScopeType()
                + "\",\"scopeId\":" + assignment.getScopeId();
        if ("CLUB_ROLE_ASSIGNED".equals(action)) {
            event.setAfterJson(assignmentJson + ",\"status\":\"ACTIVE\"}");
        } else {
            event.setBeforeJson(assignmentJson + ",\"status\":\"ACTIVE\"}");
            event.setAfterJson("{\"status\":\"REVOKED\"}");
        }
        return event;
    }
}

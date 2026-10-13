package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.MerchantOperator;
import com.chengyinhub.business.domain.MerchantOperatorInvite;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubLeaderMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.MerchantOperatorInviteMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.MerchantOperatorServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantOperatorServiceImplTest {
    private static final long OWNER = 900L;
    private static final long NEW_OWNER = 902L;
    private static final long EMPLOYEE = 901L;
    private static final long MERCHANT = 77L;

    private IMerchantAccessService access;
    private MerchantOperatorMapper operators;
    private MerchantOperatorInviteMapper invites;
    private BusinessAuditEventMapper audits;
    private MmsMerchantMapper merchants;
    private UmsMemberMapper members;
    private ClubLeaderMapper leaders;
    private ClubMapper clubs;
    private MerchantOperatorServiceImpl service;

    @BeforeEach
    void setUp() {
        access = mock(IMerchantAccessService.class);
        operators = mock(MerchantOperatorMapper.class);
        invites = mock(MerchantOperatorInviteMapper.class);
        audits = mock(BusinessAuditEventMapper.class);
        merchants = mock(MmsMerchantMapper.class);
        members = mock(UmsMemberMapper.class);
        leaders = mock(ClubLeaderMapper.class);
        clubs = mock(ClubMapper.class);
        service = new MerchantOperatorServiceImpl();
        ReflectionTestUtils.setField(service, "merchantAccessService", access);
        ReflectionTestUtils.setField(service, "merchantOperatorMapper", operators);
        ReflectionTestUtils.setField(service, "merchantOperatorInviteMapper", invites);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", audits);
        ReflectionTestUtils.setField(service, "mmsMerchantMapper", merchants);
        ReflectionTestUtils.setField(service, "umsMemberMapper", members);
        ReflectionTestUtils.setField(service, "clubLeaderMapper", leaders);
        ReflectionTestUtils.setField(service, "clubMapper", clubs);
        ReflectionTestUtils.setField(service, "tokenSecret", "merchant-operator-test-secret");
        when(access.require(OWNER, MerchantPermission.OPERATOR_MANAGE)).thenReturn(ownerAccess());
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(activeMerchant());
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        when(members.lockActiveMemberById(OWNER)).thenReturn(OWNER);
        when(members.selectUmsMemberById(OWNER)).thenReturn(activeMember(OWNER));
        when(members.lockActiveMemberById(EMPLOYEE)).thenReturn(EMPLOYEE);
        when(members.selectUmsMemberById(EMPLOYEE)).thenReturn(activeMember());
        when(clubs.selectActiveOwnedClubIdsForUpdate(EMPLOYEE)).thenReturn(Collections.emptyList());
    }

    @Test
    void inviteStoresOnlyHashReturnsRawTokenOnceAndWritesActorOwnerAudit() {
        when(invites.insertMerchantOperatorInvite(any(MerchantOperatorInvite.class))).thenAnswer(call -> {
            call.getArgument(0, MerchantOperatorInvite.class).setId(31L);
            return 1;
        });

        com.chengyinhub.business.domain.vo.MerchantInviteCreationVO out = service.invite(
                OWNER, MerchantRoleCode.MERCHANT_CHECKIN, "req-invite-1");

        assertNotNull(out.getToken());
        assertTrue(out.getToken().length() >= 32);
        ArgumentCaptor<MerchantOperatorInvite> saved = ArgumentCaptor.forClass(MerchantOperatorInvite.class);
        verify(invites).insertMerchantOperatorInvite(saved.capture());
        assertEquals(64, saved.getValue().getTokenHash().length());
        assertNotEquals(out.getToken(), saved.getValue().getTokenHash());
        assertEquals(MerchantOperatorInvite.STATUS_PENDING, saved.getValue().getStatus());
        assertTrue(saved.getValue().getExpiresAt().getTime() - saved.getValue().getInvitedAt().getTime()
                >= 23L * 60L * 60L * 1000L);
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals(OWNER, audit.getValue().getActorMemberId());
        assertEquals(OWNER, audit.getValue().getOwnerMemberId());
        assertEquals("MERCHANT_OPERATOR_INVITED", audit.getValue().getAction());
        assertFalse(String.valueOf(audit.getValue().getAfterJson()).contains(out.getToken()));
    }

    @Test
    void ownerRoleCanNeverBePersistedAsOperatorOrInvite() {
        assertThrows(ServiceException.class,
                () -> service.invite(OWNER, MerchantRoleCode.MERCHANT_OWNER, "req-owner"));
        verify(invites, never()).insertMerchantOperatorInvite(any());
    }

    @Test
    void staleOwnerSnapshotCannotCreateInviteAfterMerchantOwnerChanged() {
        MmsMerchant transferred = activeMerchant();
        transferred.setMemberId(NEW_OWNER);
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(transferred);

        ServiceException error = assertThrows(ServiceException.class, () -> service.invite(
                OWNER, MerchantRoleCode.MERCHANT_CHECKIN, "req-stale-owner"));

        assertEquals(403, error.getCode());
        verify(merchants).selectMmsMerchantByIdForUpdate(MERCHANT);
        verify(invites, never()).selectByRequestId(any(), any());
        verify(invites, never()).insertMerchantOperatorInvite(any());
    }

    @Test
    void staleOwnerSnapshotCannotMutateOperatorOrInviteAfterMerchantOwnerChanged() {
        MmsMerchant transferred = activeMerchant();
        transferred.setMemberId(NEW_OWNER);
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(transferred);

        ServiceException updateError = assertThrows(ServiceException.class, () -> service.updateRole(
                OWNER, 41L, MerchantRoleCode.MERCHANT_FINANCE, 0, "req-stale-role"));
        ServiceException removeError = assertThrows(ServiceException.class, () -> service.remove(
                OWNER, 41L, 0, "离职", "req-stale-remove"));
        ServiceException revokeError = assertThrows(ServiceException.class, () -> service.revokeInvite(
                OWNER, 31L, 0, "撤销", "req-stale-revoke"));

        assertEquals(403, updateError.getCode());
        assertEquals(403, removeError.getCode());
        assertEquals(403, revokeError.getCode());
        verify(operators, never()).selectByIdForUpdate(any());
        verify(invites, never()).selectByIdForUpdate(any());
    }

    @Test
    void ownerDisabledAfterAccessSnapshotCannotMutateTeam() {
        UmsMember disabled = activeMember(OWNER);
        disabled.setStatus(0L);
        when(members.selectUmsMemberById(OWNER)).thenReturn(disabled);

        ServiceException error = assertThrows(ServiceException.class, () -> service.updateRole(
                OWNER, 41L, MerchantRoleCode.MERCHANT_FINANCE, 0, "req-disabled-owner"));

        assertEquals(403, error.getCode());
        InOrder locks = inOrder(merchants, members);
        locks.verify(merchants).selectMmsMerchantByIdForUpdate(MERCHANT);
        locks.verify(members).lockActiveMemberById(OWNER);
        verify(operators, never()).selectByIdForUpdate(any());
    }

    @Test
    void inviteRejectsRequestIdReusedForDifferentRole() {
        MerchantOperatorInvite existing = pendingInvite("existing-token-not-returned");
        existing.setRequestId("req-role-replay");
        when(invites.selectByRequestId(MERCHANT, "req-role-replay")).thenReturn(existing);

        ServiceException error = assertThrows(ServiceException.class, () -> service.invite(
                OWNER, MerchantRoleCode.MERCHANT_FINANCE, "req-role-replay"));

        assertEquals(409, error.getCode());
        verify(invites, never()).insertMerchantOperatorInvite(any());
    }

    @Test
    void inviteExactReplayReturnsSameTokenWhenFirstResponseWasLost() {
        when(invites.insertMerchantOperatorInvite(any(MerchantOperatorInvite.class))).thenAnswer(call -> {
            call.getArgument(0, MerchantOperatorInvite.class).setId(31L);
            return 1;
        });

        com.chengyinhub.business.domain.vo.MerchantInviteCreationVO first = service.invite(
                OWNER, MerchantRoleCode.MERCHANT_CHECKIN, "req-exact-replay");
        ArgumentCaptor<MerchantOperatorInvite> saved = ArgumentCaptor.forClass(MerchantOperatorInvite.class);
        verify(invites).insertMerchantOperatorInvite(saved.capture());
        when(invites.selectByRequestId(MERCHANT, "req-exact-replay")).thenReturn(saved.getValue());

        com.chengyinhub.business.domain.vo.MerchantInviteCreationVO replay = service.invite(
                OWNER, MerchantRoleCode.MERCHANT_CHECKIN, "req-exact-replay");

        assertEquals(first.getToken(), replay.getToken());
        assertNotNull(replay.getToken());
    }

    @Test
    void inviteDuplicateKeyLoserUsesCurrentReadAndReturnsWinnerToken() {
        String requestId = "req-concurrent-replay";
        String winnerToken = "9cw74fxezDgcmgs50dBhnHvO7iLUHqLCRpQ6Vi_ULWE"; // gitleaks:allow -- unusable invite fixture
        MerchantOperatorInvite winner = pendingInvite(winnerToken);
        winner.setRequestId(requestId);
        winner.setExpiresAt(new Date(4_102_444_800_000L));
        when(invites.selectByRequestId(MERCHANT, requestId)).thenReturn(null);
        when(invites.insertMerchantOperatorInvite(any(MerchantOperatorInvite.class)))
                .thenThrow(new DuplicateKeyException("request raced"));
        when(invites.selectByRequestIdForUpdate(MERCHANT, requestId)).thenReturn(winner);

        com.chengyinhub.business.domain.vo.MerchantInviteCreationVO replay = service.invite(
                OWNER, MerchantRoleCode.MERCHANT_CHECKIN, requestId);

        assertEquals(winnerToken, replay.getToken());
        verify(invites).selectByRequestIdForUpdate(MERCHANT, requestId);
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void inviteReplayRejectsDifferentActorEvenWhenRowCredentialIsInternallyValid() {
        String requestId = "req-concurrent-replay";
        String otherActorToken = "i8dYlDY-xQ7z28U7XtOiPD2ALHAkd-Ns5scOzm8uVOg"; // gitleaks:allow -- unusable invite fixture
        MerchantOperatorInvite otherActor = pendingInvite(otherActorToken);
        otherActor.setInvitedByMemberId(999L);
        otherActor.setRequestId(requestId);
        otherActor.setExpiresAt(new Date(4_102_444_800_000L));
        when(invites.selectByRequestId(MERCHANT, requestId)).thenReturn(otherActor);

        ServiceException error = assertThrows(ServiceException.class, () -> service.invite(
                OWNER, MerchantRoleCode.MERCHANT_CHECKIN, requestId));

        assertEquals(409, error.getCode());
    }

    @Test
    void inviteReplayRejectsTamperedStoredTokenHash() {
        String requestId = "req-concurrent-replay";
        MerchantOperatorInvite tampered = pendingInvite("not-the-derived-token");
        tampered.setRequestId(requestId);
        tampered.setExpiresAt(new Date(4_102_444_800_000L));
        when(invites.selectByRequestId(MERCHANT, requestId)).thenReturn(tampered);

        ServiceException error = assertThrows(ServiceException.class, () -> service.invite(
                OWNER, MerchantRoleCode.MERCHANT_CHECKIN, requestId));

        assertEquals(409, error.getCode());
    }

    @Test
    void acceptPreviewsTokenThenLocksMerchantBeforeInviteAndCreatesActiveRelation() {
        String token = "safe-token-accept-1234567890";
        MerchantOperatorInvite pending = pendingInvite(token);
        stubPendingToken(token, pending);
        when(members.lockActiveMemberById(EMPLOYEE)).thenReturn(EMPLOYEE);
        when(members.selectUmsMemberById(EMPLOYEE)).thenReturn(activeMember());
        when(operators.selectActiveByMemberId(EMPLOYEE)).thenReturn(null);
        when(operators.insertMerchantOperator(any(MerchantOperator.class))).thenAnswer(call -> {
            call.getArgument(0, MerchantOperator.class).setId(41L);
            return 1;
        });
        when(invites.acceptCas(31L, 0, EMPLOYEE)).thenReturn(1);

        com.chengyinhub.business.domain.vo.MerchantOperatorVO out =
                service.accept(EMPLOYEE, token, "req-accept-1");

        assertEquals(41L, out.getId());
        assertEquals(MerchantRoleCode.MERCHANT_CHECKIN, out.getRoleCode());
        ArgumentCaptor<MerchantOperator> relation = ArgumentCaptor.forClass(MerchantOperator.class);
        verify(operators).insertMerchantOperator(relation.capture());
        assertEquals(MERCHANT, relation.getValue().getMerchantId());
        assertEquals(EMPLOYEE, relation.getValue().getOperatorMemberId());
        assertEquals(MerchantOperator.STATUS_ACTIVE, relation.getValue().getStatus());
        verify(merchants).countMmsMerchantRowsByMemberIdForUpdate(EMPLOYEE);
        verify(merchants, never()).selectMmsMerchantByMemberIdForUpdate(EMPLOYEE);
        verify(invites).acceptCas(31L, 0, EMPLOYEE);
        verify(audits).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
        InOrder locks = inOrder(invites, merchants);
        locks.verify(invites).selectByTokenHash(hash(token));
        locks.verify(merchants).selectMmsMerchantByIdForUpdate(MERCHANT);
        locks.verify(invites).selectByTokenHashForUpdate(hash(token));
    }

    @Test
    void acceptLostResponseReplaysAcceptedRelationWithoutCreatingAnotherOperator() {
        String token = "safe-token-lost-response-123456";
        MerchantOperatorInvite accepted = pendingInvite(token);
        accepted.setStatus(MerchantOperatorInvite.STATUS_ACCEPTED);
        accepted.setAcceptedByMemberId(EMPLOYEE);
        accepted.setAcceptedAt(new Date());
        accepted.setVersion(1);
        when(invites.selectByTokenHash(hash(token))).thenReturn(null);
        when(invites.selectAcceptedByConsumedToken(EMPLOYEE, hash(token))).thenReturn(accepted);
        when(invites.selectAcceptedByConsumedTokenForUpdate(EMPLOYEE, hash(token))).thenReturn(accepted);
        when(operators.selectActiveByMemberId(EMPLOYEE)).thenReturn(activeRelation());

        com.chengyinhub.business.domain.vo.MerchantOperatorVO replay =
                service.accept(EMPLOYEE, token, "req-accept-replay");

        assertEquals(41L, replay.getId());
        assertEquals(MerchantRoleCode.MERCHANT_CHECKIN, replay.getRoleCode());
        verify(operators, never()).insertMerchantOperator(any());
        verify(invites, never()).acceptCas(any(), any(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
        InOrder locks = inOrder(invites, merchants);
        locks.verify(invites).selectByTokenHash(hash(token));
        locks.verify(invites).selectAcceptedByConsumedToken(EMPLOYEE, hash(token));
        locks.verify(merchants).selectMmsMerchantByIdForUpdate(MERCHANT);
        locks.verify(invites).selectAcceptedByConsumedTokenForUpdate(EMPLOYEE, hash(token));
    }

    @Test
    void acceptRejectsExpiredInviteOwnerSelfJoinAndExistingEmployment() {
        String token = "safe-token-negative-1234567890";
        MerchantOperatorInvite expired = pendingInvite(token);
        expired.setExpiresAt(new Date(System.currentTimeMillis() - 1_000L));
        stubPendingToken(token, expired);
        assertThrows(ServiceException.class, () -> service.accept(EMPLOYEE, token, "expired"));

        MerchantOperatorInvite ownerInvite = pendingInvite(token + "-owner");
        stubPendingToken(token + "-owner", ownerInvite);
        assertThrows(ServiceException.class, () -> service.accept(OWNER, token + "-owner", "owner"));

        MerchantOperatorInvite duplicate = pendingInvite(token + "-dup");
        stubPendingToken(token + "-dup", duplicate);
        when(members.lockActiveMemberById(EMPLOYEE)).thenReturn(EMPLOYEE);
        when(members.selectUmsMemberById(EMPLOYEE)).thenReturn(activeMember());
        when(operators.selectActiveByMemberId(EMPLOYEE)).thenReturn(activeRelation());
        assertThrows(ServiceException.class, () -> service.accept(EMPLOYEE, token + "-dup", "dup"));
    }

    @Test
    void acceptRejectsMemberWhoAlreadyOwnsOrAppliedForAnotherMerchant() {
        String token = "safe-token-owner-conflict-123456";
        stubPendingToken(token, pendingInvite(token));
        when(members.lockActiveMemberById(EMPLOYEE)).thenReturn(EMPLOYEE);
        when(members.selectUmsMemberById(EMPLOYEE)).thenReturn(activeMember());
        when(merchants.countMmsMerchantRowsByMemberIdForUpdate(EMPLOYEE)).thenReturn(1);

        assertThrows(ServiceException.class,
                () -> service.accept(EMPLOYEE, token, "merchant-owner-conflict"));

        verify(merchants).countMmsMerchantRowsByMemberIdForUpdate(EMPLOYEE);
        verify(merchants, never()).selectMmsMerchantByMemberIdForUpdate(EMPLOYEE);
        verify(operators, never()).insertMerchantOperator(any());
        verify(invites, never()).acceptCas(any(), any(), any());
    }

    @Test
    void acceptRejectsMultipleMerchantRowsWithoutUsingSingletonRead() {
        String token = "safe-token-multi-owner-conflict-123456";
        stubPendingToken(token, pendingInvite(token));
        when(members.lockActiveMemberById(EMPLOYEE)).thenReturn(EMPLOYEE);
        when(members.selectUmsMemberById(EMPLOYEE)).thenReturn(activeMember());
        when(merchants.countMmsMerchantRowsByMemberIdForUpdate(EMPLOYEE)).thenReturn(2);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.accept(EMPLOYEE, token, "merchant-multi-owner-conflict"));

        assertEquals(409, error.getCode());
        verify(merchants).countMmsMerchantRowsByMemberIdForUpdate(EMPLOYEE);
        verify(merchants, never()).selectMmsMerchantByMemberIdForUpdate(EMPLOYEE);
        verify(operators, never()).selectActiveByMemberId(EMPLOYEE);
        verify(operators, never()).insertMerchantOperator(any());
        verify(invites, never()).acceptCas(any(), any(), any());
    }

    @Test
    void acceptRejectsActiveClubLeaderBeforeCreatingMerchantOperator() {
        String token = "safe-token-club-leader-conflict-123456";
        stubPendingToken(token, pendingInvite(token));
        ClubLeader active = new ClubLeader();
        active.setId(61L);
        active.setMemberId(EMPLOYEE);
        active.setStatus(1);
        active.setDelFlag(0);
        when(leaders.selectClubLeaderByMemberId(EMPLOYEE)).thenReturn(active);

        ServiceException error = assertThrows(
                ServiceException.class, () -> service.accept(EMPLOYEE, token, "club-leader-conflict"));

        assertEquals(409, error.getCode());
        verify(operators, never()).insertMerchantOperator(any());
        verify(invites, never()).acceptCas(any(), any(), any());
    }

    @Test
    void acceptRejectsCurrentClubOwnerEvenWhenLeaderProfileIsMissing() {
        String token = "safe-token-club-owner-conflict-123456";
        stubPendingToken(token, pendingInvite(token));
        when(clubs.selectActiveOwnedClubIdsForUpdate(EMPLOYEE))
                .thenReturn(Collections.singletonList(81L));

        ServiceException error = assertThrows(
                ServiceException.class, () -> service.accept(EMPLOYEE, token, "club-owner-conflict"));

        assertEquals(409, error.getCode());
        verify(operators, never()).insertMerchantOperator(any());
        verify(invites, never()).acceptCas(any(), any(), any());
    }

    @Test
    void acceptRejectsInviteCreatedByFormerOwnerAfterTransfer() {
        String token = "safe-token-former-owner-1234567890";
        MerchantOperatorInvite pending = pendingInvite(token);
        MmsMerchant transferred = activeMerchant();
        transferred.setMemberId(NEW_OWNER);
        when(invites.selectByTokenHash(hash(token))).thenReturn(pending);
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(transferred);
        when(invites.selectByTokenHashForUpdate(hash(token))).thenReturn(pending);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.accept(EMPLOYEE, token, "former-owner-invite"));

        assertEquals(403, error.getCode());
        InOrder locks = inOrder(invites, merchants);
        locks.verify(invites).selectByTokenHash(hash(token));
        locks.verify(merchants).selectMmsMerchantByIdForUpdate(MERCHANT);
        locks.verify(invites).selectByTokenHashForUpdate(hash(token));
        verify(operators, never()).insertMerchantOperator(any());
        verify(invites, never()).acceptCas(any(), any(), any());
    }

    @Test
    void roleUpdateLostResponseExactReplayReturnsAuthoritativeCurrentRelation() {
        MerchantOperator current = activeRelation();
        current.setRoleCode(MerchantRoleCode.MERCHANT_FINANCE);
        current.setVersion(3);
        when(operators.selectByIdForUpdate(41L)).thenReturn(current);
        when(audits.selectByDomainActionRequestForUpdate(
                "MERCHANT", MERCHANT, "MERCHANT_ROLE_CHANGED", "req-role-replay"))
                .thenReturn(mutationReceipt("MERCHANT_ROLE_CHANGED", "MERCHANT_OPERATOR", 41L,
                        "{\"status\": \"ACTIVE\", \"version\": 2, \"roleCode\": \"MERCHANT_CHECKIN\"}",
                        "{\"status\": \"ACTIVE\", \"version\": 3, \"roleCode\": \"MERCHANT_FINANCE\"}",
                        null, "req-role-replay"));

        com.chengyinhub.business.domain.vo.MerchantOperatorVO replay = service.updateRole(
                OWNER, 41L, MerchantRoleCode.MERCHANT_FINANCE, 2, "req-role-replay");

        assertEquals(MerchantOperator.STATUS_ACTIVE, replay.getStatus());
        assertEquals(MerchantRoleCode.MERCHANT_FINANCE, replay.getRoleCode());
        assertEquals(3, replay.getVersion());
        assertEquals(com.chengyinhub.business.domain.vo.MerchantOperatorVO.MUTATION_EXACT_RESULT,
                replay.getMutationState());
        verify(operators, never()).updateRoleCas(any(), any(), any(), any(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void roleUpdateReplayReturnsLaterAuthoritativeStateWithoutClaimingExactResult() {
        MerchantOperator current = activeRelation();
        current.setRoleCode(MerchantRoleCode.MERCHANT_MARKETING);
        current.setVersion(4);
        when(operators.selectByIdForUpdate(41L)).thenReturn(current);
        when(audits.selectByDomainActionRequestForUpdate(
                "MERCHANT", MERCHANT, "MERCHANT_ROLE_CHANGED", "req-role-later"))
                .thenReturn(mutationReceipt("MERCHANT_ROLE_CHANGED", "MERCHANT_OPERATOR", 41L,
                        "{\"status\": \"ACTIVE\", \"version\": 2, \"roleCode\": \"MERCHANT_CHECKIN\"}",
                        "{\"status\": \"ACTIVE\", \"version\": 3, \"roleCode\": \"MERCHANT_FINANCE\"}",
                        null, "req-role-later"));

        com.chengyinhub.business.domain.vo.MerchantOperatorVO replay = service.updateRole(
                OWNER, 41L, MerchantRoleCode.MERCHANT_FINANCE, 2, "req-role-later");

        assertEquals(MerchantRoleCode.MERCHANT_MARKETING, replay.getRoleCode());
        assertEquals(4, replay.getVersion());
        assertEquals(com.chengyinhub.business.domain.vo.MerchantOperatorVO.MUTATION_LATER_AUTHORITATIVE,
                replay.getMutationState());
        verify(operators, never()).updateRoleCas(any(), any(), any(), any(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void roleUpdateRequestIdReusedWithDifferentPayloadReturns409BeforeMutation() {
        MerchantOperator current = activeRelation();
        current.setVersion(2);
        when(operators.selectByIdForUpdate(41L)).thenReturn(current);
        when(audits.selectByDomainActionRequestForUpdate(
                "MERCHANT", MERCHANT, "MERCHANT_ROLE_CHANGED", "req-role-mismatch"))
                .thenReturn(mutationReceipt("MERCHANT_ROLE_CHANGED", "MERCHANT_OPERATOR", 41L,
                        "{\"status\": \"ACTIVE\", \"version\": 2, \"roleCode\": \"MERCHANT_CHECKIN\"}",
                        "{\"status\": \"ACTIVE\", \"version\": 3, \"roleCode\": \"MERCHANT_MARKETING\"}",
                        null, "req-role-mismatch"));
        when(operators.updateRoleCas(41L, MERCHANT, 2,
                MerchantRoleCode.MERCHANT_FINANCE, String.valueOf(OWNER))).thenReturn(1);

        ServiceException error = assertThrows(ServiceException.class, () -> service.updateRole(
                OWNER, 41L, MerchantRoleCode.MERCHANT_FINANCE, 2, "req-role-mismatch"));

        assertEquals(409, error.getCode());
        verify(operators, never()).updateRoleCas(any(), any(), any(), any(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void removeLostResponseExactReplayReturnsAuthoritativeRevokedRelation() {
        MerchantOperator current = activeRelation();
        current.setStatus(MerchantOperator.STATUS_REVOKED);
        current.setVersion(4);
        current.setRevokedByMemberId(OWNER);
        current.setRevokeReason("离职");
        current.setRevokedAt(new Date());
        when(operators.selectByIdForUpdate(41L)).thenReturn(current);
        when(audits.selectByDomainActionRequestForUpdate(
                "MERCHANT", MERCHANT, "MERCHANT_OPERATOR_REMOVED", "req-remove-replay"))
                .thenReturn(mutationReceipt("MERCHANT_OPERATOR_REMOVED", "MERCHANT_OPERATOR", 41L,
                        "{\"status\": \"ACTIVE\", \"version\": 3, \"roleCode\": \"MERCHANT_CHECKIN\"}",
                        "{\"status\": \"REVOKED\", \"version\": 4, \"roleCode\": \"MERCHANT_CHECKIN\"}",
                        "离职", "req-remove-replay"));

        com.chengyinhub.business.domain.vo.MerchantOperatorVO replay = service.remove(
                OWNER, 41L, 3, "离职", "req-remove-replay");

        assertEquals(MerchantOperator.STATUS_REVOKED, replay.getStatus());
        assertEquals(4, replay.getVersion());
        assertEquals(com.chengyinhub.business.domain.vo.MerchantOperatorVO.MUTATION_EXACT_RESULT,
                replay.getMutationState());
        verify(operators, never()).revokeCas(any(), any(), any(), any(), any(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void removeRequestIdReusedWithDifferentReasonReturns409BeforeMutation() {
        MerchantOperator current = activeRelation();
        current.setVersion(3);
        when(operators.selectByIdForUpdate(41L)).thenReturn(current);
        when(audits.selectByDomainActionRequestForUpdate(
                "MERCHANT", MERCHANT, "MERCHANT_OPERATOR_REMOVED", "req-remove-mismatch"))
                .thenReturn(mutationReceipt("MERCHANT_OPERATOR_REMOVED", "MERCHANT_OPERATOR", 41L,
                        "{\"status\": \"ACTIVE\", \"version\": 3, \"roleCode\": \"MERCHANT_CHECKIN\"}",
                        "{\"status\": \"REVOKED\", \"version\": 4, \"roleCode\": \"MERCHANT_CHECKIN\"}",
                        "转岗", "req-remove-mismatch"));
        when(operators.revokeCas(41L, MERCHANT, 3, OWNER, "离职", String.valueOf(OWNER)))
                .thenReturn(1);

        ServiceException error = assertThrows(ServiceException.class, () -> service.remove(
                OWNER, 41L, 3, "离职", "req-remove-mismatch"));

        assertEquals(409, error.getCode());
        verify(operators, never()).revokeCas(any(), any(), any(), any(), any(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void revokeInviteLostResponseExactReplayReturnsAuthoritativeRevokedInvite() {
        MerchantOperatorInvite current = pendingInvite("unused-replay-token-123456");
        current.setStatus(MerchantOperatorInvite.STATUS_REVOKED);
        current.setVersion(1);
        current.setRevokedByMemberId(OWNER);
        current.setRevokeReason("撤销");
        current.setRevokedAt(new Date());
        when(invites.selectByIdForUpdate(31L)).thenReturn(current);
        when(audits.selectByDomainActionRequestForUpdate(
                "MERCHANT", MERCHANT, "MERCHANT_OPERATOR_INVITE_REVOKED", "req-revoke-replay"))
                .thenReturn(mutationReceipt("MERCHANT_OPERATOR_INVITE_REVOKED",
                        "MERCHANT_OPERATOR_INVITE", 31L,
                        "{\"status\": \"PENDING\", \"version\": 0, \"roleCode\": \"MERCHANT_CHECKIN\"}",
                        "{\"status\": \"REVOKED\", \"version\": 1, \"roleCode\": \"MERCHANT_CHECKIN\"}",
                        "撤销", "req-revoke-replay"));

        com.chengyinhub.business.domain.vo.MerchantOperatorInviteVO replay = service.revokeInvite(
                OWNER, 31L, 0, "撤销", "req-revoke-replay");

        assertEquals(MerchantOperatorInvite.STATUS_REVOKED, replay.getStatus());
        assertEquals(1, replay.getVersion());
        assertEquals(com.chengyinhub.business.domain.vo.MerchantOperatorInviteVO.MUTATION_EXACT_RESULT,
                replay.getMutationState());
        verify(invites, never()).revokeCas(any(), any(), any(), any(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void revokeInviteRequestIdReusedForDifferentTargetReturns409BeforeMutation() {
        MerchantOperatorInvite current = pendingInvite("unused-mismatch-token-123456");
        when(invites.selectByIdForUpdate(31L)).thenReturn(current);
        when(audits.selectByDomainActionRequestForUpdate(
                "MERCHANT", MERCHANT, "MERCHANT_OPERATOR_INVITE_REVOKED", "req-revoke-mismatch"))
                .thenReturn(mutationReceipt("MERCHANT_OPERATOR_INVITE_REVOKED",
                        "MERCHANT_OPERATOR_INVITE", 32L,
                        "{\"status\": \"PENDING\", \"version\": 0, \"roleCode\": \"MERCHANT_CHECKIN\"}",
                        "{\"status\": \"REVOKED\", \"version\": 1, \"roleCode\": \"MERCHANT_CHECKIN\"}",
                        "撤销", "req-revoke-mismatch"));
        when(invites.revokeCas(31L, MERCHANT, 0, OWNER, "撤销")).thenReturn(1);

        ServiceException error = assertThrows(ServiceException.class, () -> service.revokeInvite(
                OWNER, 31L, 0, "撤销", "req-revoke-mismatch"));

        assertEquals(409, error.getCode());
        verify(invites, never()).revokeCas(any(), any(), any(), any(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void roleUpdateAndRemovalAreMerchantScopedCasAndAudited() {
        MerchantOperator relation = activeRelation();
        relation.setVersion(2);
        relation.setRoleCode(MerchantRoleCode.MERCHANT_CHECKIN);
        when(operators.selectByIdForUpdate(41L)).thenReturn(relation);
        when(operators.updateRoleCas(41L, MERCHANT, 2,
                MerchantRoleCode.MERCHANT_FINANCE, String.valueOf(OWNER))).thenReturn(1);

        com.chengyinhub.business.domain.vo.MerchantOperatorVO updated = service.updateRole(
                OWNER, 41L, MerchantRoleCode.MERCHANT_FINANCE, 2, "req-role");

        assertEquals(MerchantRoleCode.MERCHANT_FINANCE, updated.getRoleCode());
        assertEquals(3, updated.getVersion());
        assertEquals(com.chengyinhub.business.domain.vo.MerchantOperatorVO.MUTATION_EXACT_RESULT,
                updated.getMutationState());
        InOrder roleLocks = inOrder(merchants, members, operators);
        roleLocks.verify(merchants).selectMmsMerchantByIdForUpdate(MERCHANT);
        roleLocks.verify(members).lockActiveMemberById(OWNER);
        roleLocks.verify(operators).selectByIdForUpdate(41L);
        ArgumentCaptor<BusinessAuditEvent> roleAudit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(roleAudit.capture());
        assertTrue(roleAudit.getValue().getBeforeJson().contains(MerchantRoleCode.MERCHANT_CHECKIN));
        assertTrue(roleAudit.getValue().getAfterJson().contains(MerchantRoleCode.MERCHANT_FINANCE));

        MerchantOperator removable = activeRelation();
        removable.setVersion(3);
        when(operators.selectByIdForUpdate(41L)).thenReturn(removable);
        when(operators.revokeCas(41L, MERCHANT, 3, OWNER, "离职", String.valueOf(OWNER)))
                .thenReturn(1);
        service.remove(OWNER, 41L, 3, "离职", "req-remove");
        verify(operators).revokeCas(41L, MERCHANT, 3, OWNER, "离职", String.valueOf(OWNER));
    }

    private BusinessAuditEvent mutationReceipt(String action, String targetType, Long targetId,
                                                String beforeJson, String afterJson,
                                                String reason, String requestId) {
        BusinessAuditEvent receipt = new BusinessAuditEvent();
        receipt.setDomainType("MERCHANT");
        receipt.setDomainId(MERCHANT);
        receipt.setOwnerMemberId(OWNER);
        receipt.setActorMemberId(OWNER);
        receipt.setActorRoleCode(MerchantRoleCode.MERCHANT_OWNER);
        receipt.setAction(action);
        receipt.setTargetType(targetType);
        receipt.setTargetId(targetId);
        receipt.setRelationId(targetId);
        receipt.setBeforeJson(beforeJson);
        receipt.setAfterJson(afterJson);
        receipt.setReason(reason);
        receipt.setRequestId(requestId);
        receipt.setCreateTime(new Date());
        return receipt;
    }

    private MerchantAccessContext ownerAccess() {
        return new MerchantAccessContext(OWNER, MERCHANT, OWNER, MerchantRoleCode.MERCHANT_OWNER);
    }

    private MerchantOperatorInvite pendingInvite(String token) {
        MerchantOperatorInvite invite = new MerchantOperatorInvite();
        invite.setId(31L);
        invite.setMerchantId(MERCHANT);
        invite.setRoleCode(MerchantRoleCode.MERCHANT_CHECKIN);
        invite.setTokenHash(hash(token));
        invite.setStatus(MerchantOperatorInvite.STATUS_PENDING);
        invite.setInvitedByMemberId(OWNER);
        invite.setInvitedAt(new Date());
        invite.setExpiresAt(new Date(System.currentTimeMillis() + 60_000L));
        invite.setVersion(0);
        return invite;
    }

    private void stubPendingToken(String token, MerchantOperatorInvite invite) {
        String tokenHash = hash(token);
        when(invites.selectByTokenHash(tokenHash)).thenReturn(invite);
        when(invites.selectByTokenHashForUpdate(tokenHash)).thenReturn(invite);
    }

    private MerchantOperator activeRelation() {
        MerchantOperator relation = new MerchantOperator();
        relation.setId(41L);
        relation.setMerchantId(MERCHANT);
        relation.setOperatorMemberId(EMPLOYEE);
        relation.setRoleCode(MerchantRoleCode.MERCHANT_CHECKIN);
        relation.setStatus(MerchantOperator.STATUS_ACTIVE);
        relation.setAcceptedAt(new Date());
        relation.setVersion(0);
        return relation;
    }

    private MmsMerchant activeMerchant() {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(MERCHANT);
        merchant.setMemberId(OWNER);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        return merchant;
    }

    private UmsMember activeMember() {
        return activeMember(EMPLOYEE);
    }

    private UmsMember activeMember(Long memberId) {
        UmsMember member = new UmsMember();
        member.setId(memberId);
        member.setStatus(1L);
        member.setDelFlag(0);
        return member;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}

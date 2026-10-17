package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubGovernanceBan;
import com.chengyinhub.business.domain.ClubGovernanceCase;
import com.chengyinhub.business.domain.ClubGovernanceOwnerTransfer;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.ClubMembershipAccessEndResultVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.ClubGovernanceMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.impl.ClubGovernanceServiceImpl;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubGovernanceServiceImplTest {
    private static final long OWNER = 900L;
    private static final long CO_OWNER = 901L;
    private static final long TARGET = 902L;
    private static final long REPORTER = 903L;
    private static final long CLUB = 11L;
    private static final long OTHER_CLUB = 12L;

    private IClubAccessService access;
    private ClubMapper clubs;
    private ClubMemberMapper members;
    private CmsActivityMapper activities;
    private IClubParticipationGuard participationGuard;
    private IImService imService;
    private IClubMembershipService membershipService;
    private IClubRoleAssignmentService roleAssignmentService;
    private IClubLeaderService clubLeaderService;
    private ICapabilityService capabilityService;
    private IUmsMemberService umsMemberService;
    private IRoleService roleService;
    private IMerchantAccessService merchantAccessService;
    private ClubGovernanceMapper governance;
    private BusinessAuditEventMapper audits;
    private ClubGovernanceServiceImpl service;

    @BeforeEach
    void setUp() {
        access = mock(IClubAccessService.class);
        clubs = mock(ClubMapper.class);
        members = mock(ClubMemberMapper.class);
        activities = mock(CmsActivityMapper.class);
        participationGuard = mock(IClubParticipationGuard.class);
        imService = mock(IImService.class);
        membershipService = mock(IClubMembershipService.class);
        roleAssignmentService = mock(IClubRoleAssignmentService.class);
        clubLeaderService = mock(IClubLeaderService.class);
        capabilityService = mock(ICapabilityService.class);
        umsMemberService = mock(IUmsMemberService.class);
        roleService = mock(IRoleService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        governance = mock(ClubGovernanceMapper.class);
        audits = mock(BusinessAuditEventMapper.class);
        service = new ClubGovernanceServiceImpl();
        ReflectionTestUtils.setField(service, "clubAccessService", access);
        ReflectionTestUtils.setField(service, "clubMapper", clubs);
        ReflectionTestUtils.setField(service, "clubMemberMapper", members);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "clubParticipationGuard", participationGuard);
        ReflectionTestUtils.setField(service, "imService", imService);
        ReflectionTestUtils.setField(service, "clubMembershipService", membershipService);
        ReflectionTestUtils.setField(service, "clubRoleAssignmentService", roleAssignmentService);
        ReflectionTestUtils.setField(service, "clubLeaderService", clubLeaderService);
        ReflectionTestUtils.setField(service, "capabilityService", capabilityService);
        ReflectionTestUtils.setField(service, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(service, "roleService", roleService);
        ReflectionTestUtils.setField(service, "merchantAccessService", merchantAccessService);
        ReflectionTestUtils.setField(service, "clubGovernanceMapper", governance);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", audits);

        when(access.require(OWNER, CLUB, null, ClubPermission.MEMBER_MANAGE)).thenReturn(ownerAccess());
        when(access.require(CO_OWNER, CLUB, null, ClubPermission.MEMBER_MANAGE)).thenReturn(coOwnerAccess());
        when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(activeClub(CLUB, OWNER));
        when(members.selectByClubAndMember(CLUB, TARGET)).thenReturn(activeMember(CLUB, TARGET));
        when(members.selectByClubAndMemberForUpdate(CLUB, TARGET)).thenReturn(activeMember(CLUB, TARGET));
        when(members.selectByClubAndMember(CLUB, REPORTER)).thenReturn(activeMember(CLUB, REPORTER));
        when(members.deleteByClubAndMember(CLUB, TARGET)).thenReturn(1);
        when(clubs.updateMemberCount(CLUB, -1)).thenReturn(1);
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        when(governance.insertClubGovernanceBan(any(ClubGovernanceBan.class))).thenAnswer(call -> {
            call.getArgument(0, ClubGovernanceBan.class).setId(81L);
            return 1;
        });
        when(umsMemberService.lockMemberById(TARGET)).thenReturn(TARGET);
        when(umsMemberService.lockMemberById(OWNER)).thenReturn(OWNER);
        UmsMember targetMember = member(TARGET, IRoleService.ROLE_PLAYER);
        UmsMember formerOwner = member(OWNER, IRoleService.ROLE_CLUB);
        when(umsMemberService.selectUmsMemberById(TARGET)).thenReturn(targetMember);
        when(umsMemberService.selectUmsMemberById(OWNER)).thenReturn(formerOwner);
        when(roleService.resolveRole(targetMember)).thenReturn(IRoleService.ROLE_PLAYER);
        when(roleService.resolveRole(formerOwner)).thenReturn(IRoleService.ROLE_CLUB);
        when(clubs.selectOwnedClubIdsForUpdate(TARGET)).thenReturn(Collections.emptyList());
        when(clubs.selectOwnedClubIdsForUpdate(OWNER)).thenReturn(Collections.emptyList());
        when(clubLeaderService.insertClubLeader(any(ClubLeader.class))).thenReturn(1);
        when(umsMemberService.updateUmsMember(any(UmsMember.class))).thenReturn(1);
    }

    @Test
    void ownerBansActiveMemberAndWritesNonPiiAuditBeforeTheMemberCanRejoin() {
        Date expiresAt = new Date(System.currentTimeMillis() + 86_400_000L);
        ClubMembershipAccessEndResultVO accessEnd = new ClubMembershipAccessEndResultVO();
        accessEnd.setPendingReviewCount(1);
        when(membershipService.handleMemberAccessEnded(
                CLUB, TARGET, "MEMBER_BANNED", "ban-req-1-membership")).thenReturn(accessEnd);

        ClubGovernanceBan created = service.ban(
                OWNER, CLUB, TARGET, "  线下骚扰其他参与者  ", expiresAt, "ban-req-1");

        assertEquals(81L, created.getId());
        assertEquals(ClubGovernanceBan.STATUS_ACTIVE, created.getStatus());
        assertEquals("线下骚扰其他参与者", created.getBanReason());
        assertEquals(expiresAt, created.getExpiresAt());
        assertEquals(accessEnd, created.getMembershipAccessEnd());
        verify(members).deleteByClubAndMember(CLUB, TARGET);
        verify(clubs).updateMemberCount(CLUB, -1);
        verify(imService).leaveClubGroup(CLUB, TARGET);
        verify(roleAssignmentService).revokeAllForAccessEnd(
                CLUB, TARGET, OWNER, ClubRoleCode.CLUB_OWNER, "MEMBER_BANNED");
        verify(membershipService).handleMemberAccessEnded(
                CLUB, TARGET, "MEMBER_BANNED", "ban-req-1-membership");
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals("CLUB_MEMBER_BANNED", audit.getValue().getAction());
        assertEquals("CLUB", audit.getValue().getDomainType());
        assertEquals(CLUB, audit.getValue().getDomainId());
        assertEquals(TARGET, audit.getValue().getTargetId());
        assertEquals("ban-req-1", audit.getValue().getRequestId());
        String payload = String.valueOf(audit.getValue().getAfterJson()).toLowerCase();
        assertTrue(payload.contains("active"));
        assertFalse(payload.contains("phone"));
        assertFalse(payload.contains("openid"));
        assertFalse(payload.contains("token"));
    }

    @Test
    void coOwnerRevokedWhileWaitingForClubLockCannotBanOrWriteAnyReceipt() {
        AtomicBoolean clubLockAcquired = new AtomicBoolean(false);
        when(clubs.selectClubByIdForUpdate(CLUB)).thenAnswer(call -> {
            clubLockAcquired.set(true);
            return activeClub(CLUB, OWNER);
        });
        when(access.require(CO_OWNER, CLUB, null, ClubPermission.MEMBER_MANAGE)).thenAnswer(call -> {
            if (!clubLockAcquired.get()) return coOwnerAccess();
            throw new ServiceException("权限已撤销", HttpStatus.FORBIDDEN);
        });

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.ban(CO_OWNER, CLUB, TARGET, "撤权竞态",
                        futureDays(7), "ban-revoked-after-lock"));

        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
        verify(governance, never()).insertClubGovernanceBan(any());
        verify(roleAssignmentService, never()).revokeAllForAccessEnd(any(), any(), any(), any(), any());
        verify(members, never()).deleteByClubAndMember(any(), any());
        verify(membershipService, never()).handleMemberAccessEnded(any(), any(), any(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void coOwnerRevokedWhileWaitingForClubLockCannotUnbanOrWriteAudit() {
        AtomicBoolean clubLockAcquired = new AtomicBoolean(false);
        when(clubs.selectClubByIdForUpdate(CLUB)).thenAnswer(call -> {
            clubLockAcquired.set(true);
            return activeClub(CLUB, OWNER);
        });
        when(access.require(CO_OWNER, CLUB, null, ClubPermission.MEMBER_MANAGE)).thenAnswer(call -> {
            if (!clubLockAcquired.get()) return coOwnerAccess();
            throw new ServiceException("权限已撤销", HttpStatus.FORBIDDEN);
        });
        ClubGovernanceBan row = ban(CLUB, TARGET, ClubGovernanceBan.STATUS_ACTIVE, futureDays(7));
        row.setId(81L);
        row.setVersion(2);
        when(governance.selectByIdForUpdate(81L)).thenReturn(row);
        when(governance.unbanCas(81L, CLUB, 2, CO_OWNER, "复核通过",
                "unban-revoked-after-lock", String.valueOf(CO_OWNER))).thenReturn(1);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.unban(CO_OWNER, CLUB, 81L, 2,
                        "复核通过", "unban-revoked-after-lock"));

        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
        verify(governance, never()).unbanCas(any(), any(), any(), any(), any(), any(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void coOwnerCannotBanTheClubOwner() {
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.ban(CO_OWNER, CLUB, OWNER, "治理冲突",
                        futureDays(7), "ban-owner"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getCode());
        verify(governance, never()).insertClubGovernanceBan(any());
        verify(members, never()).deleteByClubAndMember(any(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void compatibilityParticipationMethodsDelegateToTheSingleGuard() {
        doThrow(new ServiceException("你当前无法参与该俱乐部", HttpStatus.FORBIDDEN))
                .when(participationGuard).requireAllowed(CLUB, TARGET);

        assertThrows(ServiceException.class, () -> service.requireJoinAllowed(CLUB, TARGET));
        assertThrows(ServiceException.class, () -> service.requireParticipationAllowed(CLUB, TARGET));
        verify(participationGuard, org.mockito.Mockito.times(2)).requireAllowed(CLUB, TARGET);
    }

    @Test
    void expiredBanReleasesTheSlotAndAllowsARealReban() {
        when(governance.expireStaleByClubAndMember(eq(CLUB), eq(TARGET), any(Date.class))).thenReturn(1);

        ClubGovernanceBan created = service.ban(
                OWNER, CLUB, TARGET, "再次违规", futureDays(7), "ban-req-2");

        assertEquals(81L, created.getId());
        verify(governance).expireStaleByClubAndMember(eq(CLUB), eq(TARGET), any(Date.class));
        verify(governance).insertClubGovernanceBan(any(ClubGovernanceBan.class));
    }

    @Test
    void duplicateRequestIdIsRejectedBeforeMembershipOrAuditWrites() {
        when(governance.selectByRequestIdForUpdate(CLUB, "ban-req-1"))
                .thenReturn(ban(CLUB, TARGET, ClubGovernanceBan.STATUS_ACTIVE, futureDays(7)));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.ban(OWNER, CLUB, TARGET, "重复提交", futureDays(7), "ban-req-1"));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(governance, never()).insertClubGovernanceBan(any());
        verify(members, never()).deleteByClubAndMember(any(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void crossClubIdorCannotUnbanAndSameClubUnbanUsesVersionedCasWithAudit() {
        ClubGovernanceBan crossClub = ban(OTHER_CLUB, TARGET, ClubGovernanceBan.STATUS_ACTIVE, futureDays(7));
        crossClub.setId(81L);
        crossClub.setVersion(2);
        when(governance.selectByIdForUpdate(81L)).thenReturn(crossClub);

        ServiceException forbidden = assertThrows(ServiceException.class,
                () -> service.unban(OWNER, CLUB, 81L, 2, "复核通过", "unban-cross"));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getCode());
        verify(governance, never()).unbanCas(any(), any(), any(), any(), any(), any(), any());

        ClubGovernanceBan sameClub = ban(CLUB, TARGET, ClubGovernanceBan.STATUS_ACTIVE, futureDays(7));
        sameClub.setId(81L);
        sameClub.setVersion(2);
        when(governance.selectByIdForUpdate(81L)).thenReturn(sameClub);
        when(governance.unbanCas(81L, CLUB, 2, OWNER, "复核通过", "unban-req-1", String.valueOf(OWNER)))
                .thenReturn(1);

        service.unban(OWNER, CLUB, 81L, 2, "复核通过", "unban-req-1");

        verify(governance).unbanCas(81L, CLUB, 2, OWNER,
                "复核通过", "unban-req-1", String.valueOf(OWNER));
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals("CLUB_MEMBER_UNBANNED", audit.getValue().getAction());
        assertEquals("unban-req-1", audit.getValue().getRequestId());
    }

    @Test
    void listIsScopedByPermissionAndClubId() {
        List<ClubGovernanceBan> rows = Arrays.asList(
                ban(CLUB, TARGET, ClubGovernanceBan.STATUS_ACTIVE, futureDays(7)));
        when(governance.selectByClub(CLUB)).thenReturn(rows);

        assertEquals(rows, service.list(OWNER, CLUB));
        verify(access).require(OWNER, CLUB, null, ClubPermission.MEMBER_MANAGE);

        when(access.require(OWNER, OTHER_CLUB, null, ClubPermission.MEMBER_MANAGE))
                .thenThrow(new ServiceException("权限不足", HttpStatus.FORBIDDEN));
        assertThrows(ServiceException.class, () -> service.list(OWNER, OTHER_CLUB));
        verify(governance, never()).selectByClub(OTHER_CLUB);
    }

    @Test
    void clubBanRequiresARealFutureEndAtAndCapsItAtNinetyDays() {
        ServiceException missing = assertThrows(ServiceException.class,
                () -> service.ban(OWNER, CLUB, TARGET, "违规", null, "ban-no-end"));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getCode());

        ServiceException past = assertThrows(ServiceException.class,
                () -> service.ban(OWNER, CLUB, TARGET, "违规",
                        new Date(System.currentTimeMillis() - 1_000L), "ban-past"));
        assertEquals(HttpStatus.BAD_REQUEST, past.getCode());

        ServiceException tooLong = assertThrows(ServiceException.class,
                () -> service.ban(OWNER, CLUB, TARGET, "违规",
                        new Date(System.currentTimeMillis() + 91L * 86_400_000L), "ban-91d"));
        assertEquals(HttpStatus.BAD_REQUEST, tooLong.getCode());
        verify(governance, never()).insertClubGovernanceBan(any());
    }

    @Test
    void ownerTransferAtomicallySwapsTheSingleOwnerAndWritesAudit() {
        stubSuccessfulOwnerSwap();

        ClubGovernanceOwnerTransfer result = service.transferOwner(
                OWNER, CLUB, TARGET, "交接完成", "transfer-1");

        assertEquals(91L, result.getId());
        assertEquals(OWNER, result.getFromOwnerMemberId());
        assertEquals(TARGET, result.getToOwnerMemberId());
        verify(governance).deleteActiveMemberForOwnerTransfer(CLUB, TARGET);
        verify(governance).upsertFormerOwnerAsMember(eq(CLUB), eq(OWNER), any(Date.class));
        verify(governance).transferClubOwnerCas(CLUB, OWNER, TARGET);
        verify(roleAssignmentService).revokeAllForAccessEnd(
                CLUB, TARGET, OWNER, ClubRoleCode.CLUB_OWNER, "OWNER_TRANSFER_TARGET_PROMOTED");
        verify(roleAssignmentService).revokeAllForAccessEnd(
                CLUB, OWNER, OWNER, ClubRoleCode.CLUB_OWNER, "OWNER_TRANSFER_FORMER_OWNER_DEMOTED");
        ArgumentCaptor<ClubLeader> promotedProfile = ArgumentCaptor.forClass(ClubLeader.class);
        verify(clubLeaderService).insertClubLeader(promotedProfile.capture());
        assertEquals(TARGET, promotedProfile.getValue().getMemberId());
        assertEquals("会员902", promotedProfile.getValue().getLeaderName());
        assertEquals(0, promotedProfile.getValue().getLevel());
        assertEquals(1, promotedProfile.getValue().getStatus());
        assertEquals(0, promotedProfile.getValue().getDelFlag());
        verify(roleService).changeRole(TARGET, IRoleService.ROLE_CLUB, OWNER);
        verify(roleService).changeRole(OWNER, IRoleService.ROLE_PLAYER, 0L);
        verify(capabilityService, never()).grant(any(), any(), any());
        verify(capabilityService, never()).revoke(any(), any());
        verify(umsMemberService, never()).updateUmsMember(any());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals("CLUB_OWNER_TRANSFERRED", audit.getValue().getAction());
        assertEquals(TARGET, audit.getValue().getTargetId());
        org.mockito.InOrder lockOrder = inOrder(clubs, access, umsMemberService);
        lockOrder.verify(umsMemberService).lockMemberById(OWNER);
        lockOrder.verify(umsMemberService).lockMemberById(TARGET);
        lockOrder.verify(clubs).selectClubByIdForUpdate(CLUB);
        lockOrder.verify(access).require(OWNER, CLUB, null, ClubPermission.MEMBER_MANAGE);
    }

    @Test
    void ownerTransferRejectsMerchantCapabilityBeforeAnyIdentityOrOwnerWrite() {
        when(capabilityService.hasCapability(TARGET, MemberCapability.CAP_MERCHANT)).thenReturn(true);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.transferOwner(OWNER, CLUB, TARGET, "商家不可兼任", "transfer-merchant-cap"));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(clubLeaderService, never()).insertClubLeader(any());
        verify(capabilityService, never()).grant(any(), any(), any());
        verify(governance, never()).insertOwnerTransfer(any());
        verify(governance, never()).transferClubOwnerCas(any(), any(), any());
    }

    @Test
    void ownerTransferRejectsActiveMerchantOwnerAndOperatorRelationshipsFailClosed() {
        when(merchantAccessService.resolve(TARGET)).thenReturn(
                new MerchantAccessContext(TARGET, 701L, TARGET, MerchantRoleCode.MERCHANT_OWNER),
                new MerchantAccessContext(TARGET, 701L, 700L, MerchantRoleCode.MERCHANT_MANAGER));

        ServiceException ownerError = assertThrows(ServiceException.class,
                () -> service.transferOwner(OWNER, CLUB, TARGET, "商家 owner 不可兼任", "transfer-merchant-owner"));
        ServiceException operatorError = assertThrows(ServiceException.class,
                () -> service.transferOwner(OWNER, CLUB, TARGET, "商家 operator 不可兼任", "transfer-merchant-operator"));

        assertEquals(HttpStatus.CONFLICT, ownerError.getCode());
        assertEquals(HttpStatus.CONFLICT, operatorError.getCode());
        verify(clubLeaderService, never()).insertClubLeader(any());
        verify(governance, never()).insertOwnerTransfer(any());
        verify(governance, never()).transferClubOwnerCas(any(), any(), any());
    }

    @Test
    void ownerTransferRejectsMerchantDefaultViewAndDirtyMerchantResolutionFailClosed() {
        UmsMember target = umsMemberService.selectUmsMemberById(TARGET);
        when(roleService.resolveRole(target)).thenReturn(IRoleService.ROLE_MERCHANT);

        ServiceException merchantRole = assertThrows(ServiceException.class,
                () -> service.transferOwner(OWNER, CLUB, TARGET, "商家视角不可兼任", "transfer-merchant-role"));
        assertEquals(HttpStatus.CONFLICT, merchantRole.getCode());

        when(roleService.resolveRole(target)).thenReturn(IRoleService.ROLE_PLAYER);
        when(merchantAccessService.resolve(TARGET))
                .thenThrow(new ServiceException("商家身份关系异常", HttpStatus.FORBIDDEN));
        ServiceException dirtyRelationship = assertThrows(ServiceException.class,
                () -> service.transferOwner(OWNER, CLUB, TARGET, "脏关系不可放行", "transfer-merchant-dirty"));

        assertEquals(HttpStatus.FORBIDDEN, dirtyRelationship.getCode());
        verify(clubLeaderService, never()).insertClubLeader(any());
        verify(governance, never()).insertOwnerTransfer(any());
        verify(governance, never()).transferClubOwnerCas(any(), any(), any());
    }

    @Test
    void ownerTransferRejectsTargetAtOwnedClubQuotaBeforeAnyWrite() {
        when(clubs.selectOwnedClubIdsForUpdate(TARGET)).thenReturn(Arrays.asList(21L, 22L));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.transferOwner(OWNER, CLUB, TARGET, "已达配额", "transfer-quota"));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(clubLeaderService, never()).insertClubLeader(any());
        verify(governance, never()).insertOwnerTransfer(any());
        verify(governance, never()).transferClubOwnerCas(any(), any(), any());
    }

    @Test
    void ownerTransferCannotSwapOwnerWhenLeaderIdentityPromotionFails() {
        when(clubLeaderService.insertClubLeader(any(ClubLeader.class))).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.transferOwner(OWNER, CLUB, TARGET, "晋升失败", "transfer-promotion-failed"));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(capabilityService, never()).grant(any(), any(), any());
        verify(governance, never()).insertOwnerTransfer(any());
        verify(governance, never()).transferClubOwnerCas(any(), any(), any());
    }

    @Test
    void ownerTransferRejectsAnExistingLeaderProfileWithoutExplicitActiveStatus() {
        ClubLeader ambiguous = new ClubLeader();
        ambiguous.setId(77L);
        ambiguous.setMemberId(TARGET);
        ambiguous.setStatus(null);
        ambiguous.setDelFlag(0);
        when(clubLeaderService.selectClubLeaderByMemberId(TARGET)).thenReturn(ambiguous);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.transferOwner(OWNER, CLUB, TARGET, "状态不明确不可晋升", "transfer-null-status"));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(capabilityService, never()).grant(any(), any(), any());
        verify(governance, never()).insertOwnerTransfer(any());
        verify(governance, never()).transferClubOwnerCas(any(), any(), any());
    }

    @Test
    void ownerTransferRollsBackTheWholeTransactionWhenARequiredStepFailsAfterOwnerCas() {
        stubSuccessfulOwnerSwap();
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(0);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.setInterfaces(ClubGovernanceService.class);
        proxyFactory.addAdvice(interceptor);
        ClubGovernanceService transactionalService = (ClubGovernanceService) proxyFactory.getProxy();

        assertThrows(ServiceException.class, () -> transactionalService.transferOwner(
                OWNER, CLUB, TARGET, "审计失败须回滚", "transfer-audit-failed"));

        verify(governance).transferClubOwnerCas(CLUB, OWNER, TARGET);
        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void formerOwnerKeepsGlobalLeaderCapabilityAndDefaultViewWhenAnyUndeletedClubRemains() {
        stubSuccessfulOwnerSwap();
        when(clubs.selectOwnedClubIdsForUpdate(OWNER))
                .thenReturn(Collections.singletonList(OTHER_CLUB));

        service.transferOwner(OWNER, CLUB, TARGET, "仍有另一俱乐部", "transfer-keep-leader");

        verify(capabilityService, never()).revoke(OWNER, MemberCapability.CAP_CLUB_LEADER);
        verify(roleService).changeRole(TARGET, IRoleService.ROLE_CLUB, OWNER);
        verify(roleService, never()).changeRole(eq(OWNER), any(), any());
        verify(umsMemberService, never()).updateUmsMember(any());
    }

    @Test
    void coOwnerAndCrossClubTargetCannotTransferOwnership() {
        ServiceException coOwner = assertThrows(ServiceException.class,
                () -> service.transferOwner(CO_OWNER, CLUB, TARGET, "无权交接", "transfer-co"));
        assertEquals(HttpStatus.FORBIDDEN, coOwner.getCode());

        when(members.selectByClubAndMember(CLUB, TARGET)).thenReturn(activeMember(OTHER_CLUB, TARGET));
        when(members.selectByClubAndMemberForUpdate(CLUB, TARGET)).thenReturn(activeMember(OTHER_CLUB, TARGET));
        ServiceException crossClub = assertThrows(ServiceException.class,
                () -> service.transferOwner(OWNER, CLUB, TARGET, "跨团交接", "transfer-cross"));
        assertEquals(HttpStatus.BAD_REQUEST, crossClub.getCode());
        verify(governance, never()).transferClubOwnerCas(any(), any(), any());
    }

    @Test
    void ownerTransferRejectsTargetWithPendingGovernanceRiskBeforeAnyRoleOrOwnerWrite() {
        ClubGovernanceCase pending = new ClubGovernanceCase();
        pending.setId(101L);
        pending.setClubId(CLUB);
        pending.setSubjectMemberId(TARGET);
        pending.setStatus(ClubGovernanceCase.STATUS_PENDING);
        when(governance.selectPendingSubjectCaseForUpdate(CLUB, TARGET)).thenReturn(pending);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.transferOwner(OWNER, CLUB, TARGET, "存在未决风险", "transfer-risk"));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(roleAssignmentService, never()).revokeAllForAccessEnd(any(), any(), any(), any(), any());
        verify(governance, never()).insertOwnerTransfer(any());
        verify(governance, never()).transferClubOwnerCas(any(), any(), any());
    }

    @Test
    void duplicateOwnerTransferRequestCannotSwapTheOwnerTwice() {
        ClubGovernanceOwnerTransfer existing = new ClubGovernanceOwnerTransfer();
        existing.setId(91L);
        existing.setClubId(CLUB);
        existing.setRequestId("transfer-1");
        when(governance.selectOwnerTransferByRequestForUpdate(CLUB, "transfer-1"))
                .thenReturn(existing);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.transferOwner(OWNER, CLUB, TARGET, "重复交接", "transfer-1"));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(governance, never()).insertOwnerTransfer(any());
        verify(governance, never()).transferClubOwnerCas(any(), any(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void memberReportAndBannedMemberAppealEnterSeparatePendingPlatformCases() {
        when(governance.insertCase(any(ClubGovernanceCase.class))).thenAnswer(call -> {
            call.getArgument(0, ClubGovernanceCase.class).setId(101L);
            return 1;
        });
        ClubGovernanceCase report = service.submitReport(
                REPORTER, CLUB, ClubGovernanceCase.TARGET_MEMBER, TARGET,
                Collections.singletonMap("messageId", "771"), "线下安全风险", "report-1");
        assertEquals(ClubGovernanceCase.TYPE_REPORT, report.getCaseType());
        assertEquals(ClubGovernanceCase.TARGET_MEMBER, report.getTargetType());
        assertEquals(TARGET, report.getTargetId());
        assertTrue(report.getEvidenceJson().contains("messageId"));
        assertEquals(ClubGovernanceCase.STATUS_PENDING, report.getStatus());

        ClubGovernanceBan activeBan = ban(CLUB, TARGET, ClubGovernanceBan.STATUS_ACTIVE, futureDays(7));
        when(governance.selectEffectiveByClubAndMemberForUpdate(eq(CLUB), eq(TARGET), any(Date.class)))
                .thenReturn(activeBan);
        ClubGovernanceCase appeal = service.submitAppeal(
                TARGET, CLUB, "封禁事实有误", "appeal-1");
        assertEquals(ClubGovernanceCase.TYPE_APPEAL, appeal.getCaseType());
        assertEquals(activeBan.getId(), appeal.getRelatedBanId());
        verify(governance).selectPendingAppealByBanForUpdate(activeBan.getId());
    }

    @Test
    void reportRejectsCrossClubTargetAndAppealRejectsDuplicatePendingCase() {
        when(members.selectByClubAndMember(CLUB, TARGET)).thenReturn(activeMember(OTHER_CLUB, TARGET));
        ServiceException crossClub = assertThrows(ServiceException.class,
                () -> service.submitReport(REPORTER, CLUB, ClubGovernanceCase.TARGET_MEMBER, TARGET,
                        Collections.emptyMap(), "跨团举报", "report-cross"));
        assertEquals(HttpStatus.BAD_REQUEST, crossClub.getCode());

        ClubGovernanceBan activeBan = ban(CLUB, TARGET, ClubGovernanceBan.STATUS_ACTIVE, futureDays(7));
        when(governance.selectEffectiveByClubAndMemberForUpdate(eq(CLUB), eq(TARGET), any(Date.class)))
                .thenReturn(activeBan);
        ClubGovernanceCase pending = governanceCase(ClubGovernanceCase.TYPE_APPEAL, TARGET, TARGET);
        when(governance.selectPendingAppealByBanForUpdate(activeBan.getId())).thenReturn(pending);
        ServiceException duplicate = assertThrows(ServiceException.class,
                () -> service.submitAppeal(TARGET, CLUB, "重复申诉", "appeal-duplicate"));
        assertEquals(HttpStatus.CONFLICT, duplicate.getCode());
    }

    @Test
    void reportSupportsClubActivityAndMemberTargetsButRejectsEveryCrossScopeTarget() {
        when(governance.insertCase(any(ClubGovernanceCase.class))).thenAnswer(call -> {
            call.getArgument(0, ClubGovernanceCase.class).setId(101L);
            return 1;
        });
        CmsActivity scopedActivity = activity(501L, CLUB);
        when(activities.selectCmsActivityByIdForUpdate(501L)).thenReturn(scopedActivity);

        ClubGovernanceCase clubReport = service.submitReport(REPORTER, CLUB,
                ClubGovernanceCase.TARGET_CLUB, CLUB,
                Collections.singletonMap("occurredAt", "2026-08-23 20:30:00"),
                "俱乐部治理风险", "report-club");
        ClubGovernanceCase activityReport = service.submitReport(REPORTER, CLUB,
                ClubGovernanceCase.TARGET_ACTIVITY, 501L,
                Collections.singletonMap("orderNo", "CY-20260823-01"),
                "活动现场风险", "report-activity");

        assertEquals(ClubGovernanceCase.TARGET_CLUB, clubReport.getTargetType());
        assertEquals(CLUB, clubReport.getTargetId());
        assertEquals(ClubGovernanceCase.TARGET_ACTIVITY, activityReport.getTargetType());
        assertEquals(501L, activityReport.getTargetId());
        assertEquals(null, activityReport.getSubjectMemberId());

        assertThrows(ServiceException.class, () -> service.submitReport(REPORTER, CLUB,
                ClubGovernanceCase.TARGET_CLUB, OTHER_CLUB, Collections.emptyMap(),
                "跨俱乐部", "report-cross-club"));
        when(activities.selectCmsActivityByIdForUpdate(502L)).thenReturn(activity(502L, OTHER_CLUB));
        assertThrows(ServiceException.class, () -> service.submitReport(REPORTER, CLUB,
                ClubGovernanceCase.TARGET_ACTIVITY, 502L, Collections.emptyMap(),
                "跨俱乐部活动", "report-cross-activity"));
    }

    /**
     * CU-C-149:ACTIVITY 这一档只验了「属于本俱乐部且未删」,没验是谁开的场,于是主理人 #9008
     * 举报自己项目 #990030 下的活动 #32 能一路成单 —— 隔离库 club_governance_case.id=2
     * 的 filer_member_id=9008 与活动创建者是同一个人,平台收到一张没有对手的工单。
     * MEMBER 那一档早就有「不能举报自己」,这里补的是同一口径缺的那一格。
     */
    @Test
    void reportRejectsActivityCreatedByTheReporterItself() {
        when(governance.insertCase(any(ClubGovernanceCase.class))).thenAnswer(call -> {
            call.getArgument(0, ClubGovernanceCase.class).setId(101L);
            return 1;
        });
        CmsActivity ownActivity = activity(503L, CLUB);
        ownActivity.setMemberId(REPORTER);
        when(activities.selectCmsActivityByIdForUpdate(503L)).thenReturn(ownActivity);

        ServiceException self = assertThrows(ServiceException.class, () -> service.submitReport(
                REPORTER, CLUB, ClubGovernanceCase.TARGET_ACTIVITY, 503L,
                Collections.emptyMap(), "仅验证权限边界", "report-own-activity"));

        assertEquals(HttpStatus.BAD_REQUEST, self.getCode());
        verify(governance, never()).insertCase(any());
    }

    /**
     * 同一条闸的负控:别人创建的活动仍要报得出去。俱乐部里成员互报现场风险是正当入口,
     * 若把 ACTIVITY 一律按「主理人自己的」挡掉,这条用例必须红。
     */
    @Test
    void reportStillAcceptsActivityCreatedByAnotherMember() {
        when(governance.insertCase(any(ClubGovernanceCase.class))).thenAnswer(call -> {
            call.getArgument(0, ClubGovernanceCase.class).setId(102L);
            return 1;
        });
        CmsActivity othersActivity = activity(504L, CLUB);
        othersActivity.setMemberId(OWNER);
        when(activities.selectCmsActivityByIdForUpdate(504L)).thenReturn(othersActivity);

        ClubGovernanceCase report = service.submitReport(REPORTER, CLUB,
                ClubGovernanceCase.TARGET_ACTIVITY, 504L,
                Collections.singletonMap("orderNo", "CY-20260925-01"),
                "活动现场风险", "report-others-activity");

        assertEquals(504L, report.getTargetId());
        verify(governance).insertCase(any(ClubGovernanceCase.class));
    }

    @Test
    void reportEvidenceOnlyAcceptsBoundedWhitelistedNonUrlValues() {        Map<String, String> unknown = Collections.singletonMap("freeText", "任意正文");
        Map<String, String> url = Collections.singletonMap("locationLabel", "https://evil.example/evidence");
        Map<String, String> tooMany = new LinkedHashMap<>();
        tooMany.put("occurredAt", "2026-08-23 20:30:00");
        tooMany.put("locationLabel", "外滩");
        tooMany.put("orderNo", "ORDER-1");
        tooMany.put("messageId", "1");
        tooMany.put("postId", "2");
        tooMany.put("commentId", "3");
        tooMany.put("extra", "4");

        assertThrows(ServiceException.class, () -> service.submitReport(REPORTER, CLUB,
                ClubGovernanceCase.TARGET_MEMBER, TARGET, unknown, "举报", "evidence-unknown"));
        assertThrows(ServiceException.class, () -> service.submitReport(REPORTER, CLUB,
                ClubGovernanceCase.TARGET_MEMBER, TARGET, url, "举报", "evidence-url"));
        assertThrows(ServiceException.class, () -> service.submitReport(REPORTER, CLUB,
                ClubGovernanceCase.TARGET_MEMBER, TARGET, tooMany, "举报", "evidence-count"));
        verify(governance, never()).insertCase(any());
    }

    @Test
    void platformApprovalOfAppealUsesVersionedCaseAndBanCasWithoutClubManagerAuthority() {
        ClubGovernanceCase appeal = governanceCase(ClubGovernanceCase.TYPE_APPEAL, TARGET, TARGET);
        appeal.setId(101L);
        appeal.setRelatedBanId(81L);
        appeal.setVersion(2);
        when(governance.selectCaseByIdForUpdate(101L)).thenReturn(appeal);
        ClubGovernanceBan activeBan = ban(CLUB, TARGET, ClubGovernanceBan.STATUS_ACTIVE, futureDays(7));
        activeBan.setVersion(4);
        when(governance.selectByIdForUpdate(81L)).thenReturn(activeBan);
        when(governance.platformUnbanCas(eq(81L), eq(CLUB), eq(4), any(String.class), eq("review-1")))
                .thenReturn(1);
        when(governance.reviewCaseCas(101L, 2, ClubGovernanceCase.STATUS_APPROVED,
                7001L, "申诉成立", "review-1")).thenReturn(1);

        service.reviewPlatformCase(7001L, 101L, 2, true, "申诉成立", "review-1");

        verify(governance).platformUnbanCas(eq(81L), eq(CLUB), eq(4), any(String.class), eq("review-1"));
        verify(governance).reviewCaseCas(101L, 2, ClubGovernanceCase.STATUS_APPROVED,
                7001L, "申诉成立", "review-1");
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits, times(2)).insertBusinessAuditEvent(audit.capture());
        assertTrue(audit.getAllValues().stream().anyMatch(event ->
                "PLATFORM_CLUB_MEMBER_UNBANNED_ON_APPEAL".equals(event.getAction())
                        && Long.valueOf(81L).equals(event.getRelationId())));
        assertTrue(audit.getAllValues().stream().anyMatch(event ->
                "CLUB_GOVERNANCE_CASE_APPROVED".equals(event.getAction())
                        && "PLATFORM_AUDITOR".equals(event.getActorRoleCode())));
    }

    @Test
    void platformReviewFailsClosedForCrossClubAppealBan() {
        ClubGovernanceCase appeal = governanceCase(ClubGovernanceCase.TYPE_APPEAL, TARGET, TARGET);
        appeal.setId(101L);
        appeal.setRelatedBanId(81L);
        appeal.setVersion(0);
        when(governance.selectCaseByIdForUpdate(101L)).thenReturn(appeal);
        when(governance.selectByIdForUpdate(81L))
                .thenReturn(ban(OTHER_CLUB, TARGET, ClubGovernanceBan.STATUS_ACTIVE, futureDays(7)));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.reviewPlatformCase(7001L, 101L, 0, true, "申诉成立", "review-cross"));
        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
        verify(governance, never()).reviewCaseCas(any(), any(), any(), any(), any(), any());
    }

    @Test
    void onlyPlatformCanCreateAndResolveAPermanentBan() {
        when(governance.insertClubGovernanceBan(any(ClubGovernanceBan.class))).thenAnswer(call -> {
            call.getArgument(0, ClubGovernanceBan.class).setId(82L);
            return 1;
        });
        ClubGovernanceBan permanent = service.platformPermanentBan(
                7001L, CLUB, TARGET, "平台确认长期安全风险", "platform-ban-1");
        assertEquals(ClubGovernanceBan.SOURCE_PLATFORM, permanent.getSourceType());
        assertEquals(null, permanent.getExpiresAt());

        permanent.setVersion(0);
        when(governance.selectByIdForUpdate(82L)).thenReturn(permanent);
        ServiceException ownerCannotUnban = assertThrows(ServiceException.class,
                () -> service.unban(OWNER, CLUB, 82L, 0, "俱乐部自行解除", "owner-unban-platform"));
        assertEquals(HttpStatus.FORBIDDEN, ownerCannotUnban.getCode());
        verify(governance, never()).unbanCas(eq(82L), any(), any(), any(), any(), any(), any());

        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals("PLATFORM_CLUB_MEMBER_PERMANENTLY_BANNED", audit.getValue().getAction());
        assertEquals("PLATFORM_AUDITOR", audit.getValue().getActorRoleCode());
    }

    private ClubAccessContext ownerAccess() {
        return new ClubAccessContext(OWNER, CLUB, OWNER, null,
                Collections.singleton(ClubRoleCode.CLUB_OWNER));
    }

    private ClubAccessContext coOwnerAccess() {
        return new ClubAccessContext(CO_OWNER, CLUB, OWNER, null,
                new java.util.LinkedHashSet<>(Arrays.asList(
                        ClubRoleCode.CLUB_MEMBER, ClubRoleCode.CLUB_CO_OWNER)));
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

    private UmsMember member(Long id, String role) {
        UmsMember member = new UmsMember();
        member.setId(id);
        member.setNickname("会员" + id);
        member.setRole(role);
        member.setStatus(1L);
        member.setDelFlag(0);
        return member;
    }

    private void stubSuccessfulOwnerSwap() {
        when(governance.insertOwnerTransfer(any(ClubGovernanceOwnerTransfer.class))).thenAnswer(call -> {
            call.getArgument(0, ClubGovernanceOwnerTransfer.class).setId(91L);
            return 1;
        });
        when(governance.deleteActiveMemberForOwnerTransfer(CLUB, TARGET)).thenReturn(1);
        when(governance.upsertFormerOwnerAsMember(eq(CLUB), eq(OWNER), any(Date.class))).thenReturn(1);
        when(governance.transferClubOwnerCas(CLUB, OWNER, TARGET)).thenReturn(1);
    }

    private CmsActivity activity(Long id, Long clubId) {
        CmsActivity row = new CmsActivity();
        row.setId(id);
        row.setClubId(clubId);
        row.setDelFlag(0);
        return row;
    }

    private ClubGovernanceBan ban(Long clubId, Long memberId, String status, Date expiresAt) {
        ClubGovernanceBan row = new ClubGovernanceBan();
        row.setId(81L);
        row.setClubId(clubId);
        row.setTargetMemberId(memberId);
        row.setStatus(status);
        row.setSourceType(ClubGovernanceBan.SOURCE_CLUB);
        row.setBanReason("违规");
        row.setBannedByMemberId(OWNER);
        row.setBannedAt(new Date());
        row.setExpiresAt(expiresAt);
        row.setVersion(0);
        return row;
    }

    private ClubGovernanceCase governanceCase(String type, Long filer, Long subject) {
        ClubGovernanceCase row = new ClubGovernanceCase();
        row.setClubId(CLUB);
        row.setCaseType(type);
        row.setFilerMemberId(filer);
        row.setSubjectMemberId(subject);
        if (ClubGovernanceCase.TYPE_APPEAL.equals(type)) {
            row.setTargetType(ClubGovernanceCase.TARGET_BAN);
            row.setTargetId(81L);
            row.setRelatedBanId(81L);
        } else {
            row.setTargetType(ClubGovernanceCase.TARGET_MEMBER);
            row.setTargetId(subject);
        }
        row.setEvidenceJson("{}");
        row.setReason("治理事项");
        row.setStatus(ClubGovernanceCase.STATUS_PENDING);
        row.setVersion(0);
        return row;
    }

    private Date futureDays(int days) {
        return new Date(System.currentTimeMillis() + days * 86_400_000L);
    }
}

package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubGovernanceOwnerTransfer;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubGovernanceMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.impl.ClubGovernanceServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubOwnerTransferIdentityFlowTest {
    private static final long OWNER = 900L;
    private static final long TARGET = 902L;
    private static final long CLUB_ID = 11L;

    @Test
    void ordinaryMemberBecomesLeaderInRoleInfoAndCanCreateClassicTopicForTransferredClub() {
        AtomicReference<ClubLeader> leaderTruth = new AtomicReference<>();
        UmsMember targetAccount = member(TARGET, IRoleService.ROLE_PLAYER);
        UmsMember ownerAccount = member(OWNER, IRoleService.ROLE_CLUB);
        IClubLeaderService leaderService = mock(IClubLeaderService.class);
        ICapabilityService capabilityService = mock(ICapabilityService.class);
        java.util.Set<Long> leaderCapabilities = new java.util.HashSet<>();
        leaderCapabilities.add(OWNER);
        when(capabilityService.hasCapability(anyLong(), eq(MemberCapability.CAP_CLUB_LEADER)))
                .thenAnswer(call -> leaderCapabilities.contains(call.getArgument(0)));
        org.mockito.Mockito.doAnswer(call -> { leaderCapabilities.add(call.getArgument(0)); return null; })
                .when(capabilityService).grant(anyLong(), eq(MemberCapability.CAP_CLUB_LEADER), anyLong());
        org.mockito.Mockito.doAnswer(call -> { leaderCapabilities.remove(call.getArgument(0)); return null; })
                .when(capabilityService).revoke(anyLong(), eq(MemberCapability.CAP_CLUB_LEADER));
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        IRoleService roleService = mock(IRoleService.class);
        ClubMapper clubMapper = mock(ClubMapper.class);
        Club transferredClub = activeClub(OWNER);

        when(leaderService.selectClubLeaderByMemberId(TARGET)).thenAnswer(call -> leaderTruth.get());
        when(leaderService.insertClubLeader(any(ClubLeader.class))).thenAnswer(call -> {
            ClubLeader profile = call.getArgument(0, ClubLeader.class);
            profile.setId(77L);
            leaderTruth.set(profile);
            return 1;
        });
        when(memberService.lockMemberById(TARGET)).thenReturn(TARGET);
        when(memberService.lockMemberById(OWNER)).thenReturn(OWNER);
        when(memberService.selectUmsMemberById(TARGET)).thenReturn(targetAccount);
        when(memberService.selectUmsMemberById(OWNER)).thenReturn(ownerAccount);
        when(memberService.updateUmsMember(any(UmsMember.class))).thenAnswer(call -> {
            UmsMember patch = call.getArgument(0, UmsMember.class);
            if (Long.valueOf(TARGET).equals(patch.getId())) targetAccount.setRole(patch.getRole());
            if (Long.valueOf(OWNER).equals(patch.getId())) ownerAccount.setRole(patch.getRole());
            return 1;
        });
        when(roleService.resolveRole(any(UmsMember.class))).thenAnswer(call -> {
            String role = call.getArgument(0, UmsMember.class).getRole();
            return role == null ? IRoleService.ROLE_PLAYER : role;
        });
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(transferredClub);
        when(clubMapper.selectOwnedClubIdsForUpdate(TARGET)).thenReturn(Collections.emptyList());
        when(clubMapper.selectOwnedClubIdsForUpdate(OWNER)).thenReturn(Collections.emptyList());

        transferService(leaderService, capabilityService, memberService, roleService, clubMapper)
                .transferOwner(OWNER, CLUB_ID, TARGET, "交接给普通成员", "transfer-flow-1");
        transferredClub.setMemberId(TARGET);

        UserPermission permission = new UserPermission();
        permission.setCanCreateTheme(true);
        when(roleService.getPermission(targetAccount)).thenReturn(permission);
        IClubService clubService = mock(IClubService.class);
        when(clubService.selectClubsByMemberId(TARGET))
                .thenReturn(Collections.singletonList(transferredClub));
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ApiRoleController roleController = spy(new ApiRoleController());
        doReturn(TARGET).when(roleController).getAppUserId();
        ReflectionTestUtils.setField(roleController, "roleService", roleService);
        ReflectionTestUtils.setField(roleController, "capabilityService", capabilityService);
        ReflectionTestUtils.setField(roleController, "umsMemberService", memberService);
        ReflectionTestUtils.setField(roleController, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(roleController, "cmsActivityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(roleController, "smsCouponService", mock(ISmsCouponService.class));
        ReflectionTestUtils.setField(roleController, "clubLeaderService", leaderService);
        ReflectionTestUtils.setField(roleController, "clubService", clubService);
        ReflectionTestUtils.setField(roleController, "clubMemberService", mock(IClubMemberService.class));
        ReflectionTestUtils.setField(roleController, "mmsMerchantService", mock(IMmsMerchantService.class));

        AjaxResult roleResult = roleController.roleInfo();

        assertEquals(200, roleResult.get(AjaxResult.CODE_TAG));
        Map<?, ?> roleData = (Map<?, ?>) roleResult.get("data");
        assertEquals(IRoleService.ROLE_CLUB, roleData.get("role"));
        assertEquals(Boolean.TRUE, roleData.get("isClubLeader"));
        assertEquals(1, ((java.util.List<?>) roleData.get("ownedClubs")).size());

        when(clubService.selectClubById(CLUB_ID)).thenReturn(transferredClub);
        IQuotaService quotaService = mock(IQuotaService.class);
        when(quotaService.checkCreate(TARGET, IQuotaService.TYPE_THEME)).thenReturn(null);
        when(topicService.createTopic(any(TopicCreateDTO.class), eq(TARGET), eq("member902")))
                .thenReturn(990L);
        ApiTopicController topicController = spy(new ApiTopicController());
        doReturn(TARGET).when(topicController).getAppUserId();
        doReturn("member902").when(topicController).getAppUserName();
        ReflectionTestUtils.setField(topicController, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(topicController, "quotaService", quotaService);
        ReflectionTestUtils.setField(topicController, "merchantAccessService", mock(IMerchantAccessService.class));
        ReflectionTestUtils.setField(topicController, "roleService", roleService);
        ReflectionTestUtils.setField(topicController, "clubLeaderService", leaderService);
        ReflectionTestUtils.setField(topicController, "clubService", clubService);
        ReflectionTestUtils.setField(topicController, "coopInviteService", mock(ICoopInviteService.class));
        ReflectionTestUtils.setField(topicController, "umsMemberService", memberService);
        ReflectionTestUtils.setField(topicController, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        // RUN-52 发布者实名:本用例测的是「转让后的新Owner能不能接着发团」,实名闸在这里放行
        // (mock 的 checkRegistered 返回 null = 已登记)。
        ReflectionTestUtils.setField(topicController, "memberIdentityService",
                mock(com.chengyinhub.business.service.impl.MemberIdentityService.class));

        TopicCreateDTO classic = new TopicCreateDTO();
        classic.setName("转让后的经典定向");
        classic.setProductType(ProductType.ORIENTEERING);
        classic.setClubId(CLUB_ID);
        AjaxResult createResult = topicController.createTopic(classic);

        assertEquals(200, createResult.get(AjaxResult.CODE_TAG));
        assertEquals(990L, createResult.get("data"));
        verify(topicService).createTopic(classic, TARGET, "member902");
        assertTrue(leaderTruth.get() != null && Integer.valueOf(1).equals(leaderTruth.get().getStatus()));
    }

    private ClubGovernanceServiceImpl transferService(
            IClubLeaderService leaderService, ICapabilityService capabilityService,
            IUmsMemberService memberService, IRoleService roleService, ClubMapper clubMapper) {
        IClubAccessService accessService = mock(IClubAccessService.class);
        ClubMemberMapper clubMemberMapper = mock(ClubMemberMapper.class);
        ClubGovernanceMapper governanceMapper = mock(ClubGovernanceMapper.class);
        BusinessAuditEventMapper auditMapper = mock(BusinessAuditEventMapper.class);
        IClubRoleAssignmentService roleAssignmentService = mock(IClubRoleAssignmentService.class);
        IMerchantAccessService merchantAccessService = mock(IMerchantAccessService.class);
        when(accessService.require(OWNER, CLUB_ID, null, ClubPermission.MEMBER_MANAGE))
                .thenReturn(new ClubAccessContext(OWNER, CLUB_ID, OWNER, null,
                        Collections.singleton(ClubRoleCode.CLUB_OWNER)));
        when(clubMemberMapper.selectByClubAndMember(CLUB_ID, TARGET))
                .thenReturn(activeMember());
        when(clubMemberMapper.selectByClubAndMemberForUpdate(CLUB_ID, TARGET))
                .thenReturn(activeMember());
        when(governanceMapper.insertOwnerTransfer(any(ClubGovernanceOwnerTransfer.class))).thenAnswer(call -> {
            call.getArgument(0, ClubGovernanceOwnerTransfer.class).setId(91L);
            return 1;
        });
        when(governanceMapper.deleteActiveMemberForOwnerTransfer(CLUB_ID, TARGET)).thenReturn(1);
        when(governanceMapper.upsertFormerOwnerAsMember(eq(CLUB_ID), eq(OWNER), any(Date.class))).thenReturn(1);
        when(governanceMapper.transferClubOwnerCas(CLUB_ID, OWNER, TARGET)).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);

        ClubGovernanceServiceImpl service = new ClubGovernanceServiceImpl();
        ReflectionTestUtils.setField(service, "clubAccessService", accessService);
        ReflectionTestUtils.setField(service, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(service, "clubMemberMapper", clubMemberMapper);
        ReflectionTestUtils.setField(service, "clubRoleAssignmentService", roleAssignmentService);
        ReflectionTestUtils.setField(service, "clubLeaderService", leaderService);
        ReflectionTestUtils.setField(service, "capabilityService", capabilityService);
        ReflectionTestUtils.setField(service, "umsMemberService", memberService);
        ReflectionTestUtils.setField(service, "roleService", roleService);
        ReflectionTestUtils.setField(service, "merchantAccessService", merchantAccessService);
        ReflectionTestUtils.setField(service, "clubGovernanceMapper", governanceMapper);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);
        return service;
    }

    private Club activeClub(long ownerMemberId) {
        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(ownerMemberId);
        club.setName("转让后的俱乐部");
        club.setStatus(1L);
        club.setDelFlag(0);
        return club;
    }

    private ClubMember activeMember() {
        ClubMember member = new ClubMember();
        member.setId(71L);
        member.setClubId(CLUB_ID);
        member.setMemberId(TARGET);
        member.setStatus(1);
        member.setDelFlag(0);
        return member;
    }

    private UmsMember member(long id, String role) {
        UmsMember member = new UmsMember();
        member.setId(id);
        member.setNickname("会员" + id);
        member.setRole(role);
        member.setStatus(1L);
        member.setDelFlag(0);
        return member;
    }
}

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.domain.dto.RoamPoiApplicationRequest;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoamPoiApplicationServiceTest {
    private RoamMapper roamMapper;
    private IMmsMerchantService merchantService;
    private IUmsMemberService memberService;
    private IRoleService roleService;
    private MerchantCommerceService merchantCommerceService;
    private ICmsMemberTemplateService templateService;
    private ICouponRewardAuthorizationService couponAuthorizationService;
    private RoamPoiApplicationServiceImpl service;
    private MmsMerchant merchant;

    @BeforeEach
    void setUp() {
        roamMapper = mock(RoamMapper.class);
        merchantService = mock(IMmsMerchantService.class);
        memberService = mock(IUmsMemberService.class);
        roleService = mock(IRoleService.class);
        merchantCommerceService = mock(MerchantCommerceService.class);
        templateService = mock(ICmsMemberTemplateService.class);
        couponAuthorizationService = mock(ICouponRewardAuthorizationService.class);
        service = new RoamPoiApplicationServiceImpl(roamMapper, merchantService, memberService, roleService,
                merchantCommerceService, templateService, couponAuthorizationService);

        merchant = new MmsMerchant();
        merchant.setId(88L);
        merchant.setMemberId(7L);
        merchant.setName("山岚咖啡");
        when(merchantService.selectMmsMerchantByMemberId(7L)).thenReturn(merchant);
        when(merchantService.selectMmsMerchantById(88L)).thenReturn(merchant);

        UmsMember member = new UmsMember();
        member.setId(7L);
        member.setRole("merchant");
        when(memberService.selectUmsMemberById(7L)).thenReturn(member);
        UserPermission permission = new UserPermission();
        permission.setCanOpenAsNode(true);
        when(roleService.getPermission(member)).thenReturn(permission);
        when(roleService.resolveRole(member)).thenReturn("merchant");
        when(merchantCommerceService.entitlement(any(), any(), any(), any(Integer.class)))
                .thenReturn(new MerchantCommerceService.Entitlement(3, 0));
        when(roamMapper.lockMerchantForNodeReview(7L)).thenReturn(88L);

        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(9L);
        template.setMemberId(7L);
        when(templateService.selectCmsMemberTemplateById(9L)).thenReturn(template);
    }

    @Test
    void placementCreatesPendingRoamPoiWithoutEffectiveOwner() {
        when(roamMapper.countPendingPlacements(88L)).thenReturn(0);
        when(roamMapper.insertPendingMerchantPoi(any())).thenAnswer(invocation -> {
            RoamPoi poi = invocation.getArgument(0);
            poi.setId(101L);
            return 1;
        });

        Long id = service.submitPlacement(7L, placement());

        assertEquals(101L, id);
        ArgumentCaptor<RoamPoi> saved = ArgumentCaptor.forClass(RoamPoi.class);
        verify(roamMapper).insertPendingMerchantPoi(saved.capture());
        RoamPoi pending = saved.getValue();
        assertEquals(Integer.valueOf(1), pending.getApplicationType());
        assertEquals(Integer.valueOf(0), pending.getAuditStatus());
        assertEquals(Integer.valueOf(0), pending.getStatus());
        assertEquals(Long.valueOf(88L), pending.getApplicantMerchantId());
        assertEquals(null, pending.getMerchantId(), "待审申请不得提前获得 effective owner");
        assertEquals("湖滨路 1 号", pending.getAddress());
        assertEquals("HZ", pending.getCityCode());
        assertEquals(Integer.valueOf(90), pending.getRadiusM());
    }

    @Test
    void placementLocksMerchantBeforeCheckingPendingAndInserting() {
        when(roamMapper.countPendingPlacements(88L)).thenReturn(0);
        when(roamMapper.insertPendingMerchantPoi(any())).thenAnswer(invocation -> {
            ((RoamPoi) invocation.getArgument(0)).setId(101L);
            return 1;
        });

        service.submitPlacement(7L, placement());

        InOrder order = inOrder(roamMapper);
        order.verify(roamMapper).lockMerchantForNodeReview(7L);
        order.verify(roamMapper).countPendingPlacements(88L);
        order.verify(roamMapper).insertPendingMerchantPoi(any());
    }

    @Test
    void existingPendingPlacementStopsAtTheSpecificPendingGate() {
        when(roamMapper.countPendingPlacements(88L)).thenReturn(1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.submitPlacement(7L, placement()));

        assertEquals("已有待审核投放申请", error.getMessage());
        verify(roamMapper, never()).insertPendingMerchantPoi(any());
    }

    @Test
    void claimWritesApplicantToExistingPoiWithoutGrantingOwner() {
        RoamPoi claimable = new RoamPoi();
        claimable.setId(600L);
        claimable.setStatus(1);
        when(roamMapper.selectPoiForClaim(600L)).thenReturn(claimable);
        when(roamMapper.submitClaimIfAvailable(600L, 88L)).thenReturn(1);

        assertEquals(600L, service.submitClaim(7L, 600L));

        verify(roamMapper).submitClaimIfAvailable(600L, 88L);
        verify(roamMapper, never()).insertPendingMerchantPoi(any());
    }

    @Test
    void claimLosingThePoiCasReportsTheClaimGate() {
        RoamPoi claimable = new RoamPoi();
        claimable.setId(600L);
        when(roamMapper.selectPoiForClaim(600L)).thenReturn(claimable);
        when(roamMapper.submitClaimIfAvailable(600L, 88L)).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.submitClaim(7L, 600L));

        assertEquals("该节点已有待审核认领申请或已被认领", error.getMessage());
    }

    @Test
    void cancelClaimClearsPendingApplicationAndReopensPoiForOthers() {
        when(roamMapper.cancelPendingClaim(600L, 88L)).thenReturn(1);

        assertTrue(service.cancelClaim(7L, 600L));

        verify(roamMapper).cancelPendingClaim(600L, 88L);
    }

    @Test
    void cancelClaimLosingTheGateReturnsFalseInsteadOfThrowing() {
        when(roamMapper.cancelPendingClaim(600L, 88L)).thenReturn(0);

        assertFalse(service.cancelClaim(7L, 600L));
    }

    @Test
    void cancelClaimRejectsNonPositivePoiBeforeAnyWrite() {
        assertThrows(IllegalArgumentException.class, () -> service.cancelClaim(7L, 0L));

        verify(roamMapper, never()).cancelPendingClaim(any(), any());
    }

    @Test
    void revokedNodePermissionCanStillCancelItsPendingClaim() {
        UserPermission revoked = new UserPermission();
        revoked.setCanOpenAsNode(false);
        when(roleService.getPermission(any(UmsMember.class))).thenReturn(revoked);
        when(roamMapper.cancelPendingClaim(600L, 88L)).thenReturn(1);

        assertTrue(service.cancelClaim(7L, 600L));

        verify(roleService, never()).getPermission(any());
    }

    @Test
    void approvingPlacementPromotesTheSamePoiInsteadOfCreatingAnotherAsset() {
        RoamPoi app = pending(1);
        app.setTemplateId(9L);
        when(roamMapper.selectRoamApplicationById(101L)).thenReturn(app);
        when(roamMapper.markRoamApplicationReviewing(101L)).thenReturn(1);
        when(roamMapper.approveRoamApplication(101L, 1, 88L)).thenReturn(1);

        assertEquals(101L, service.approve(101L));

        verify(roamMapper).approveRoamApplication(101L, 1, 88L);
        verify(roamMapper, never()).insertPendingMerchantPoi(any());
    }

    @Test
    void approvingClaimAtomicallyPromotesApplicantToEffectiveOwner() {
        RoamPoi app = pending(2);
        when(roamMapper.selectRoamApplicationById(600L)).thenReturn(app);
        when(roamMapper.markRoamApplicationReviewing(600L)).thenReturn(1);
        when(roamMapper.approveRoamApplication(600L, 2, 88L)).thenReturn(1);

        assertEquals(600L, service.approve(600L));

        verify(roamMapper).approveRoamApplication(600L, 2, 88L);
        verify(roamMapper, never()).insertPendingMerchantPoi(any());
    }

    @Test
    void losingReviewGateCannotPromoteApplicantToOwner() {
        when(roamMapper.selectRoamApplicationById(101L)).thenReturn(pending(2));
        when(roamMapper.markRoamApplicationReviewing(101L)).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.approve(101L));

        assertEquals("申请已被处置，请刷新后重试", error.getMessage());
        verify(roamMapper, never()).approveRoamApplication(any(), any(), any());
    }

    @Test
    void approvalRechecksQuotaBeforePromotingClaim() {
        when(roamMapper.selectRoamApplicationById(600L)).thenReturn(pending(2));
        when(roamMapper.countActiveNodesByMerchant(88L)).thenReturn(3);
        when(merchantCommerceService.entitlement(7L, "merchant",
                MerchantCommerceService.ENTITLEMENT_CITY_NODE, 3))
                .thenReturn(new MerchantCommerceService.Entitlement(3, 3));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.approve(600L));

        assertEquals("在架据点已达上限(3)", error.getMessage());
        verify(roamMapper, never()).markRoamApplicationReviewing(any());
        verify(roamMapper, never()).approveRoamApplication(any(), any(), any());
    }

    @Test
    void approvalStopsAtRevokedNodePermissionGate() {
        when(roamMapper.selectRoamApplicationById(600L)).thenReturn(pending(2));
        UserPermission revoked = new UserPermission();
        revoked.setCanOpenAsNode(false);
        when(roleService.getPermission(any(UmsMember.class))).thenReturn(revoked);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.approve(600L));

        assertEquals("商家已无城市节点权限", error.getMessage());
        verify(roamMapper, never()).markRoamApplicationReviewing(any());
    }

    @Test
    void approvalStopsWhenSubmittedTemplateChangedOwner() {
        RoamPoi app = pending(1);
        app.setTemplateId(9L);
        when(roamMapper.selectRoamApplicationById(101L)).thenReturn(app);
        CmsMemberTemplate transferred = new CmsMemberTemplate();
        transferred.setId(9L);
        transferred.setMemberId(8L);
        when(templateService.selectCmsMemberTemplateById(9L)).thenReturn(transferred);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.approve(101L));

        assertEquals("互动模板不存在或归属已变化", error.getMessage());
        verify(roamMapper, never()).markRoamApplicationReviewing(any());
    }

    @Test
    void approvalStopsAtCouponOwnershipGate() {
        RoamPoi app = pending(1);
        app.setTemplateId(9L);
        app.setCouponTemplateId(12L);
        when(roamMapper.selectRoamApplicationById(101L)).thenReturn(app);
        doThrow(new IllegalArgumentException("券不属于当前商家"))
                .when(couponAuthorizationService).assertOwnedRewardCoupon(12L, 7L);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.approve(101L));

        assertEquals("券不属于当前商家", error.getMessage());
        verify(roamMapper, never()).markRoamApplicationReviewing(any());
    }

    @Test
    void listMineUsesApplicantIdentityNotEffectiveOwner() {
        RoamPoi pending = pending(1);
        when(roamMapper.selectRoamApplications(88L, null)).thenReturn(Collections.singletonList(pending));

        assertEquals(Collections.singletonList(pending), service.listMine(7L));

        verify(roamMapper).selectRoamApplications(88L, null);
    }

    @Test
    void onlineLocksMerchantBeforeReadingQuotaAndWritingStatus() {
        RoamPoi offline = new RoamPoi();
        offline.setId(501L);
        offline.setMerchantId(88L);
        offline.setStatus(0);
        when(roamMapper.selectMerchantPoiOwned(501L, 88L)).thenReturn(offline);
        when(roamMapper.countActiveNodesByMerchant(88L)).thenReturn(2);
        when(merchantCommerceService.entitlement(7L, "merchant",
                MerchantCommerceService.ENTITLEMENT_CITY_NODE, 2))
                .thenReturn(new MerchantCommerceService.Entitlement(3, 2));
        when(roamMapper.setNodeStatusOwned(501L, 88L, 1)).thenReturn(1);

        assertTrue(service.setOwnedNodeStatus(7L, 501L, 1));

        InOrder gateOrder = inOrder(roamMapper);
        gateOrder.verify(roamMapper).lockMerchantForNodeReview(7L);
        gateOrder.verify(roamMapper).selectMerchantPoiOwned(501L, 88L);
        gateOrder.verify(roamMapper).countActiveNodesByMerchant(88L);
        gateOrder.verify(roamMapper).setNodeStatusOwned(501L, 88L, 1);
    }

    @Test
    void revokedMerchantCanStillTakeItsVisibleNodeOffline() {
        UserPermission revoked = new UserPermission();
        revoked.setCanOpenAsNode(false);
        when(roleService.getPermission(any(UmsMember.class))).thenReturn(revoked);
        RoamPoi online = new RoamPoi();
        online.setId(501L);
        online.setMerchantId(88L);
        online.setStatus(1);
        when(roamMapper.selectMerchantPoiOwned(501L, 88L)).thenReturn(online);
        when(roamMapper.setNodeStatusOwned(501L, 88L, 0)).thenReturn(1);

        assertTrue(service.setOwnedNodeStatus(7L, 501L, 0));

        verify(roleService, never()).getPermission(any());
        verify(roamMapper).setNodeStatusOwned(501L, 88L, 0);
    }

    private RoamPoiApplicationRequest placement() {
        RoamPoiApplicationRequest request = new RoamPoiApplicationRequest();
        request.setName("山岚咖啡据点");
        request.setAddress("湖滨路 1 号");
        request.setLatitude(BigDecimal.valueOf(30.123));
        request.setLongitude(BigDecimal.valueOf(120.456));
        request.setRadiusM(90);
        request.setCityCode("HZ");
        request.setTemplateId(9L);
        request.setDescription("到店说出暗号");
        return request;
    }

    private RoamPoi pending(int type) {
        RoamPoi app = new RoamPoi();
        app.setId(type == 1 ? 101L : 600L);
        app.setApplicationType(type);
        app.setAuditStatus(0);
        app.setApplicantMerchantId(88L);
        app.setStatus(type == 1 ? 0 : 1);
        return app;
    }
}

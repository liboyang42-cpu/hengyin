package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IQrcodeService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IRoamPoiApplicationService;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.impl.MerchantCommerceService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMerchantNodeOperatorAccessTest {
    private static final Long PRINCIPAL = 901L;
    private static final Long OWNER = 71L;
    private static final Long MERCHANT = 17L;

    private IMerchantAccessService accessService;
    private RoamMapper roamMapper;
    private IRoamPoiApplicationService applicationService;
    private IMmsMerchantService merchantService;
    private ICmsMemberTemplateService templateService;
    private ITemplatePublishService templatePublishService;
    private ICouponRewardAuthorizationService couponAuthorization;
    private IRoleService roleService;
    private UmsMember ownerMember;
    private ApiMerchantNodeController controller;

    @BeforeEach
    void setUp() {
        accessService = mock(IMerchantAccessService.class);
        roamMapper = mock(RoamMapper.class);
        applicationService = mock(IRoamPoiApplicationService.class);
        controller = new ApiMerchantNodeController() {
            @Override
            public String getAppUserName() { return "manager"; }
            @Override
            public Long getAppUserId() {
                return PRINCIPAL;
            }
        };
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(controller, "roamPoiApplicationService", applicationService);

        merchantService = mock(IMmsMerchantService.class);
        templateService = mock(ICmsMemberTemplateService.class);
        couponAuthorization = mock(ICouponRewardAuthorizationService.class);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchantService);
        ReflectionTestUtils.setField(controller, "templateService", templateService);
        ReflectionTestUtils.setField(controller, "couponRewardAuthorizationService", couponAuthorization);
        templatePublishService = mock(ITemplatePublishService.class);
        ReflectionTestUtils.setField(controller, "templatePublishService", templatePublishService);
        IWxContentSecurityService contentSecurity = mock(IWxContentSecurityService.class);
        when(contentSecurity.checkText(any(), any())).thenReturn(null);
        ReflectionTestUtils.setField(controller, "wxSec", contentSecurity);

        IUmsMemberService memberService = mock(IUmsMemberService.class);
        ownerMember = new UmsMember();
        ownerMember.setId(OWNER);
        ownerMember.setRole("merchant");
        when(memberService.selectUmsMemberById(OWNER)).thenReturn(ownerMember);
        ReflectionTestUtils.setField(controller, "umsMemberService", memberService);
        roleService = mock(IRoleService.class);
        UserPermission nodePermission = new UserPermission();
        nodePermission.setCanOpenAsNode(true);
        when(roleService.getPermission(ownerMember)).thenReturn(nodePermission);
        ReflectionTestUtils.setField(controller, "roleService", roleService);

        MerchantCommerceService commerce = mock(MerchantCommerceService.class);
        when(commerce.entitlement(OWNER, "merchant", MerchantCommerceService.ENTITLEMENT_CITY_NODE, 0))
                .thenReturn(new MerchantCommerceService.Entitlement(3, 0));
        ReflectionTestUtils.setField(controller, "merchantCommerceService", commerce);
    }

    @Test
    void managerListsOwnerNodesApplicationsAndQuotaThroughMerchantScope() {
        when(accessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(managerAccess());
        when(roamMapper.selectMyNodes(MERCHANT)).thenReturn(Collections.emptyList());
        when(applicationService.listMine(OWNER)).thenReturn(Collections.emptyList());
        when(roamMapper.countActiveNodesByMerchant(MERCHANT)).thenReturn(0);

        AjaxResult result = controller.list();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(roamMapper).selectMyNodes(MERCHANT);
        verify(applicationService).listMine(OWNER);
        verify(roamMapper).countActiveNodesByMerchant(MERCHANT);
    }

    @Test
    void managerListsClaimableNodesThroughProjectPermission() {
        when(accessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(managerAccess());
        when(applicationService.listClaimable("码头")).thenReturn(Collections.<RoamPoi>emptyList());

        AjaxResult result = controller.claimable("码头");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(applicationService).listClaimable("码头");
    }

    @Test
    void managerClaimsNodeAsMerchantOwnerMember() {
        when(accessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(managerAccess());
        when(merchantService.selectMmsMerchantById(MERCHANT)).thenReturn(activeMerchant());
        when(applicationService.submitClaim(OWNER, 301L)).thenReturn(301L);

        AjaxResult result = controller.claim(301L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(applicationService).submitClaim(OWNER, 301L);
    }

    @Test
    void projectManagerWithoutNodeEligibilityCannotClaim() {
        when(accessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(managerAccess());
        when(merchantService.selectMmsMerchantById(MERCHANT)).thenReturn(activeMerchant());
        UserPermission denied = new UserPermission();
        denied.setCanOpenAsNode(false);
        when(roleService.getPermission(ownerMember)).thenReturn(denied);

        AjaxResult result = controller.claim(301L);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(applicationService, never()).submitClaim(any(), any());
    }

    @Test
    void eligibleManagerBringsOwnedNodeOnlineAsMerchantOwnerMember() {
        when(accessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(managerAccess());
        when(merchantService.selectMmsMerchantById(MERCHANT)).thenReturn(activeMerchant());
        when(applicationService.setOwnedNodeStatus(OWNER, 301L, 1)).thenReturn(true);

        AjaxResult result = controller.offline(301L, 1);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(applicationService).setOwnedNodeStatus(OWNER, 301L, 1);
    }

    @Test
    void projectManagerWithoutNodeEligibilityCannotBringNodeOnline() {
        when(accessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(managerAccess());
        when(merchantService.selectMmsMerchantById(MERCHANT)).thenReturn(activeMerchant());
        UserPermission denied = new UserPermission();
        denied.setCanOpenAsNode(false);
        when(roleService.getPermission(ownerMember)).thenReturn(denied);

        AjaxResult result = controller.offline(301L, 1);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(applicationService, never()).setOwnedNodeStatus(any(), any(), any());
    }

    @Test
    void managerCreatesNodeTemplateOwnedByMerchantOwner() {
        when(accessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(managerAccess());
        when(merchantService.selectMmsMerchantById(MERCHANT)).thenReturn(activeMerchant());
        when(templatePublishService.saveMerchantNodeTemplate(any(CmsMemberTemplate.class), eq(OWNER), any()))
                .thenReturn(801L);
        CmsMemberTemplate body = new CmsMemberTemplate();
        body.setTitle("晚风暗号");
        body.setValidationMethod(0);
        body.setImgUrl("https://cdn.chengyinhub.com/profile/upload/template.jpg");
        IUmsMemberService members = (IUmsMemberService) ReflectionTestUtils.getField(controller, "umsMemberService");
        UmsMember principal = new UmsMember(); principal.setId(PRINCIPAL); principal.setWxOpenId("fixture-operator-openid");
        when(members.selectUmsMemberById(PRINCIPAL)).thenReturn(principal);

        AjaxResult result = controller.templateSubmit(body);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(801L, result.get(AjaxResult.DATA_TAG));
        verify(couponAuthorization).assertOwnedRewardCoupon(body.getCouponId(), OWNER);
        verify(templatePublishService).saveMerchantNodeTemplate(eq(body), eq(OWNER), any());
        IWxContentSecurityService safety = (IWxContentSecurityService) ReflectionTestUtils.getField(controller, "wxSec");
        verify(safety).submitMediaCheck(IWxContentSecurityService.BIZ_TYPE_NODE_TEMPLATE, 801L,
                body.getImgUrl(), "fixture-operator-openid");
    }

    @Test
    void managerSubmitsPlacementAsMerchantOwnerAndUsesMerchantRowForQuota() {
        when(accessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(managerAccess());
        when(merchantService.selectMmsMerchantById(MERCHANT)).thenReturn(activeMerchant());
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(801L);
        template.setMemberId(OWNER);
        when(templateService.selectCmsMemberTemplateById(801L)).thenReturn(template);
        when(applicationService.submitPlacement(org.mockito.ArgumentMatchers.eq(OWNER), any()))
                .thenReturn(501L);

        AjaxResult result = controller.save(null, 801L, 30.1, 120.2, 80,
                "晚风咖啡", "湖滨路 1 号", null, null, "330100");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(applicationService).submitPlacement(org.mockito.ArgumentMatchers.eq(OWNER), any());
        verify(roamMapper).countActiveNodesByMerchant(MERCHANT);
    }

    @Test
    void verifierGetsPosterCodeForScopedMerchantWithoutProjectPermission() {
        when(accessService.require(PRINCIPAL, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, MERCHANT, OWNER,
                        MerchantRoleCode.MERCHANT_CHECKIN));
        when(merchantService.selectMmsMerchantById(MERCHANT)).thenReturn(activeMerchant());
        com.chengyinhub.business.domain.vo.CityNodeVO node =
                new com.chengyinhub.business.domain.vo.CityNodeVO();
        node.setPoiId(301L);
        node.setMerchantId(MERCHANT);
        node.setName("晚风咖啡");
        when(roamMapper.selectMerchantNodeDetail(301L)).thenReturn(node);
        IQrcodeService qrcodeService = mock(IQrcodeService.class);
        when(qrcodeService.generateQrcode(any(), org.mockito.ArgumentMatchers.eq(300),
                org.mockito.ArgumentMatchers.eq(300))).thenReturn("https://cdn.example/code.png");
        ReflectionTestUtils.setField(controller, "qrcodeService", qrcodeService);
        ReflectionTestUtils.setField(controller, "dynSecret", "test-secret");

        AjaxResult result = controller.posterCode(301L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(accessService).require(PRINCIPAL, MerchantPermission.VERIFY);
    }

    @Test
    void verifierCannotIssueLongLivedPosterCodeAfterOwnerNodeCapabilityWasRevoked() {
        when(accessService.require(PRINCIPAL, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, MERCHANT, OWNER,
                        MerchantRoleCode.MERCHANT_CHECKIN));
        when(merchantService.selectMmsMerchantById(MERCHANT)).thenReturn(activeMerchant());
        UserPermission denied = new UserPermission();
        denied.setCanOpenAsNode(false);
        when(roleService.getPermission(ownerMember)).thenReturn(denied);

        AjaxResult result = controller.posterCode(301L);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(roamMapper, never()).selectMerchantNodeDetail(any());
    }

    @Test
    void missingProjectPermissionFailsClosedBeforeClaimableRead() {
        when(accessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));

        AjaxResult result = controller.claimable("码头");

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(applicationService, never()).listClaimable(any());
    }

    @Test
    void verifierCannotGenerateAnotherMerchantsPosterCode() {
        when(accessService.require(PRINCIPAL, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, MERCHANT, OWNER,
                        MerchantRoleCode.MERCHANT_CHECKIN));
        com.chengyinhub.business.domain.vo.CityNodeVO node =
                new com.chengyinhub.business.domain.vo.CityNodeVO();
        node.setPoiId(301L);
        node.setMerchantId(99L);
        when(roamMapper.selectMerchantNodeDetail(301L)).thenReturn(node);

        AjaxResult result = controller.posterCode(301L);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
    }

    private MerchantAccessContext managerAccess() {
        return new MerchantAccessContext(PRINCIPAL, MERCHANT, OWNER, MerchantRoleCode.MERCHANT_MANAGER);
    }

    private MmsMerchant activeMerchant() {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(MERCHANT);
        merchant.setMemberId(OWNER);
        merchant.setName("晚风咖啡");
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        merchant.setLocationLat(30.1);
        merchant.setLocationLng(120.2);
        return merchant;
    }
}

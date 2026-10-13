package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantNodeSensorValidationGateTest {
    private static final long MEMBER = 71L;
    private ApiMerchantNodeController controller;
    private ICmsMemberTemplateService templateRows;
    private IWxContentSecurityService security;
    private ITemplatePublishService validator;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiMerchantNodeController());
        doReturn(MEMBER).when(controller).getAppUserId();
        doReturn("merchant").when(controller).getAppUserName();

        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(17L); merchant.setMemberId(MEMBER); merchant.setStatus(1L); merchant.setAccountStatus(1L); merchant.setDelFlag(0);
        when(merchants.selectMmsMerchantById(17L)).thenReturn(merchant);

        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(MEMBER, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(MEMBER, 17L, MEMBER, MerchantRoleCode.MERCHANT_OWNER));

        IUmsMemberService members = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setRole("merchant");
        member.setWxOpenId("openid-" + MEMBER);
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
        IRoleService roles = mock(IRoleService.class);
        UserPermission permission = new UserPermission(); permission.setCanOpenAsNode(true);
        when(roles.getPermission(member)).thenReturn(permission);

        templateRows = mock(ICmsMemberTemplateService.class);
        validator = spy(new TemplatePublishServiceImpl());
        doReturn(92L).when(validator).saveMerchantNodeTemplate(any(), eq(MEMBER), anyString());
        security = mock(IWxContentSecurityService.class);
        when(security.checkText(any(), any())).thenReturn(null);

        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchants);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "umsMemberService", members);
        ReflectionTestUtils.setField(controller, "roleService", roles);
        ReflectionTestUtils.setField(controller, "templateService", templateRows);
        ReflectionTestUtils.setField(controller, "templatePublishService", validator);
        ReflectionTestUtils.setField(controller, "wxSec", security);
        ReflectionTestUtils.setField(controller, "couponRewardAuthorizationService",
                mock(ICouponRewardAuthorizationService.class));
    }

    @Test
    void merchantEndpointRejectsMalformedVm7BeforeDirectInsert() {
        CmsMemberTemplate body = template(7, "still", "{}");

        AjaxResult result = controller.templateSubmit(body);

        assertEquals(500, result.get("code"));
        verify(templateRows, never()).insertCmsMemberTemplate(any());
    }

    @Test
    void merchantEndpointRejectsStepsWithoutWindowBeforeDirectInsert() {
        CmsMemberTemplate body = template(7, "steps", "{\"targetSteps\":120}");

        AjaxResult result = controller.templateSubmit(body);

        assertEquals(500, result.get("code"));
        verify(templateRows, never()).insertCmsMemberTemplate(any());
    }

    @Test
    void merchantEndpointNormalizesNonVm7SensorBlanksBeforeDirectInsert() {
        doAnswer(invocation -> {
            CmsMemberTemplate row = invocation.getArgument(0);
            row.setId(92L);
            return 1;
        }).when(templateRows).insertCmsMemberTemplate(any());
        CmsMemberTemplate body = template(2, "", "");

        AjaxResult result = controller.templateSubmit(body);

        assertEquals(200, result.get("code"));
        assertNull(body.getSensorType());
        assertNull(body.getSensorConfig());
        verify(validator).saveMerchantNodeTemplate(body, MEMBER, "merchant");
    }

    @Test
    void merchantTemplateSubmitPassesSubmitterOpenidToTextCheck() {
        doAnswer(invocation -> {
            CmsMemberTemplate row = invocation.getArgument(0);
            row.setId(93L);
            return 1;
        }).when(templateRows).insertCmsMemberTemplate(any());
        CmsMemberTemplate body = template(2, "", "");

        AjaxResult result = controller.templateSubmit(body);

        assertEquals(200, result.get("code"));
        // RUN-29 负控:这里若传 ""/null(生产 2026-09-18 17:54 即如此),微信 msgSecCheck v2 判 40003,
        // fail-close 会把所有商家据点玩法发布拦死。openid 必须来自当前登录会员。
        verify(security).checkText(anyString(), eq("openid-" + MEMBER));
    }

    private CmsMemberTemplate template(int vm, String sensorType, String sensorConfig) {
        CmsMemberTemplate body = new CmsMemberTemplate();
        body.setTitle("商家据点玩法");
        body.setValidationMethod(vm);
        body.setSensorType(sensorType);
        body.setSensorConfig(sensorConfig);
        return body;
    }
}

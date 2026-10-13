package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.PublicMerchantDiscoveryVO;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMerchantEditProfileTest {

    private static final long PRINCIPAL = 9L;
    private static final long MERCHANT_ID = 77L;

    @Test
    void benefitsTextDoesNotOverwriteRegistrationImages() {
        Fixture f = fixture();
        f.merchant.setDerivatives("https://images.example.com/brand.png");
        MmsMerchant patch = new MmsMerchant();
        patch.setDerivativeBenefits("路线纪念章");
        assertEquals(200, f.controller.updateUserInfo(patch).get(AjaxResult.CODE_TAG));
        ArgumentCaptor<MmsMerchant> saved = ArgumentCaptor.forClass(MmsMerchant.class);
        verify(f.merchantService).updateAuthorizedProfile(saved.capture(), anyLong(), anyString());
        assertEquals("路线纪念章", saved.getValue().getDerivativeBenefits());
        assertNull(saved.getValue().getDerivatives());
        assertEquals("https://images.example.com/brand.png", f.merchant.getDerivatives());
    }

    @Test
    void authenticatedEditReadIncludesPreservedFieldsButExcludesSensitiveAndGovernanceFields() {
        Fixture fixture = fixture();

        Object data = fixture.controller.coopProfile().get(AjaxResult.DATA_TAG);
        JsonNode json = new ObjectMapper().valueToTree(data);

        assertEquals("MerchantEditProfileVO", data.getClass().getSimpleName());
        assertEquals("限定饮品", json.get("derivatives").asText());
        assertEquals("https://merchant.example", json.get("website").asText());
        assertEquals("夜间友好", json.get("preference").asText());
        assertEquals(3, json.get("categoryId").asInt());
        assertEquals(2, json.get("featuredType").asInt());
        assertEquals(66L, json.get("featuredId").asLong());
        assertEquals(1, json.get("locationVerified").asInt());
        assertEquals("咖啡馆", json.get("sysCategoryList").get(0).get("categoryName").asText());
        for (String forbidden : new String[]{"phone", "wechat", "businessLicense", "status",
                "accountStatus", "delFlag", "reson", "createBy", "updateBy"}) {
            assertFalse(json.has(forbidden), forbidden + " must not leave the authenticated edit whitelist");
        }

        JsonNode anonymous = new ObjectMapper().valueToTree(PublicMerchantDiscoveryVO.from(fixture.merchant));
        assertFalse(anonymous.has("derivatives"));
        assertFalse(anonymous.has("website"));
        assertFalse(anonymous.has("preference"));
        assertFalse(anonymous.has("featuredId"));
    }

    @Test
    void descriptionOnlyPatchDoesNotOverwriteExistingProfileOrAcceptSensitiveFields() {
        Fixture fixture = fixture();
        MmsMerchant request = new MmsMerchant();
        request.setDescription("新的品牌故事");
        request.setPhone("attacker-phone");
        request.setBusinessLicense("attacker-license");
        request.setStatus(2L);

        AjaxResult result = fixture.controller.updateUserInfo(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        ArgumentCaptor<MmsMerchant> update = ArgumentCaptor.forClass(MmsMerchant.class);
        verify(fixture.merchantService).updateAuthorizedProfile(
                update.capture(), anyLong(), anyString());
        assertEquals(MERCHANT_ID, update.getValue().getId());
        assertEquals("新的品牌故事", update.getValue().getDescription());
        assertNull(update.getValue().getDerivatives(), "missing derivatives must mean unchanged");
        assertNull(update.getValue().getWebsite(), "missing website must mean unchanged");
        assertNull(update.getValue().getPreference(), "missing preference must mean unchanged");
        assertNull(update.getValue().getPhone());
        assertNull(update.getValue().getBusinessLicense());
        assertNull(update.getValue().getStatus());
        assertEquals("限定饮品", fixture.merchant.getDerivatives(), "controller must not mutate the loaded entity");
    }

    @Test
    void explicitEmptyEditableValueStillClearsThatField() {
        Fixture fixture = fixture();
        MmsMerchant request = new MmsMerchant();
        request.setWebsite("");

        assertEquals(200, fixture.controller.updateUserInfo(request).get(AjaxResult.CODE_TAG));
        ArgumentCaptor<MmsMerchant> update = ArgumentCaptor.forClass(MmsMerchant.class);
        verify(fixture.merchantService).updateAuthorizedProfile(
                update.capture(), anyLong(), anyString());
        assertEquals("", update.getValue().getWebsite());
        assertNull(update.getValue().getDescription());
    }

    @Test
    void emptyOrSensitiveOnlyRequestDoesNotIssueAnInvalidOrGovernanceUpdate() {
        Fixture fixture = fixture();
        MmsMerchant request = new MmsMerchant();
        request.setPhone("attacker-phone");
        request.setStatus(2L);

        assertFalse(Integer.valueOf(200).equals(
                fixture.controller.updateUserInfo(request).get(AjaxResult.CODE_TAG)));
        verify(fixture.merchantService, never()).updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString());
    }

    @Test
    void businessHoursPatchUsesAuthorizedPartialUpdateAndPreservesProtectedFields() {
        Fixture fixture = fixture();
        MmsMerchant request = new MmsMerchant();
        request.setBusinessTime("周五、六 20:00-次日02:00");
        request.setPhone("must-not-change");
        assertEquals(200, fixture.controller.updateUserInfo(request).get(AjaxResult.CODE_TAG));
        ArgumentCaptor<MmsMerchant> update = ArgumentCaptor.forClass(MmsMerchant.class);
        verify(fixture.merchantService).updateAuthorizedProfile(update.capture(), anyLong(), anyString());
        assertEquals("周五、六 20:00-次日02:00", update.getValue().getBusinessTime());
        assertNull(update.getValue().getName());
        assertNull(update.getValue().getPhone());
        assertNull(update.getValue().getBusinessLicense());
    }

    @Test
    void invalidOrAmbiguousHoursCannotReachTheMapper() {
        for (String hours : new String[]{"", "周一 10:00-10:00", "周一 20:00-02:00", "周一 10:00-次日20:00", "周一 25:00-26:00"}) {
            Fixture fixture = fixture();
            MmsMerchant request = new MmsMerchant();
            request.setBusinessTime(hours);
            assertFalse(Integer.valueOf(200).equals(fixture.controller.updateUserInfo(request).get(AjaxResult.CODE_TAG)));
            verify(fixture.merchantService, never()).updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString());
        }
    }

    private static Fixture fixture() {
        Fixture fixture = new Fixture();
        fixture.merchant = new MmsMerchant();
        fixture.merchant.setId(MERCHANT_ID);
        fixture.merchant.setMemberId(PRINCIPAL);
        fixture.merchant.setName("城瘾咖啡");
        fixture.merchant.setDescription("原品牌故事");
        fixture.merchant.setDerivatives("限定饮品");
        fixture.merchant.setWebsite("https://merchant.example");
        fixture.merchant.setPreference("夜间友好");
        fixture.merchant.setCategoryId(3);
        fixture.merchant.setFeaturedType(2);
        fixture.merchant.setFeaturedId(66L);
        fixture.merchant.setLocationVerified(1);
        fixture.merchant.setPhone("13800000000");
        fixture.merchant.setWechat("private-wechat");
        fixture.merchant.setBusinessLicense("private-license");
        fixture.merchant.setStatus(1L);
        fixture.merchant.setAccountStatus(1L);
        fixture.merchant.setDelFlag(0);
        fixture.merchant.setReson("private-audit-note");
        fixture.merchant.setCreateBy("admin");

        fixture.controller = new LoggedInController();
        fixture.merchantService = mock(IMmsMerchantService.class);
        when(fixture.merchantService.selectMmsMerchantById(MERCHANT_ID)).thenReturn(fixture.merchant);
        when(fixture.merchantService.updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString())).thenReturn(1);

        IMerchantAccessService access = mock(IMerchantAccessService.class);
        MerchantAccessContext context = new MerchantAccessContext(PRINCIPAL, MERCHANT_ID, PRINCIPAL,
                MerchantRoleCode.MERCHANT_OWNER);
        when(access.require(PRINCIPAL, MerchantPermission.COOP_MANAGE)).thenReturn(context);
        when(access.require(PRINCIPAL, MerchantPermission.PROFILE_WRITE)).thenReturn(context);

        ISysCategoryService categories = mock(ISysCategoryService.class);
        SysCategory category = new SysCategory();
        category.setId(3L);
        category.setCategoryName("咖啡馆");
        when(categories.selectSysCategoryListByIds(new String[]{"3"}))
                .thenReturn(Collections.singletonList(category));

        ReflectionTestUtils.setField(fixture.controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(fixture.controller, "mmsMerchantService", fixture.merchantService);
        ReflectionTestUtils.setField(fixture.controller, "sysCategoryService", categories);
        ReflectionTestUtils.setField(fixture.controller, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        ReflectionTestUtils.setField(fixture.controller, "umsMemberService", mock(IUmsMemberService.class));
        return fixture;
    }

    private static class LoggedInController extends ApiMerchantController {
        @Override public Long getAppUserId() { return PRINCIPAL; }
    }

    private static class Fixture {
        private LoggedInController controller;
        private IMmsMerchantService merchantService;
        private MmsMerchant merchant;
    }
}

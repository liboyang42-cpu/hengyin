package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.PublicMerchantDiscoveryVO;
import com.chengyinhub.business.service.IMerchantNodeDiscoveryService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
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
        // owner 2026-10-22:店主在「门店信息」自助维护电话/微信号/二维码 ⇒ 本店编辑白名单回显这三项
        assertEquals("13800000000", json.get("phone").asText());
        assertEquals("private-wechat", json.get("wechat").asText());
        assertEquals("https://images.example.com/qr.png", json.get("wechatQrcode").asText());
        for (String forbidden : new String[]{"businessLicense", "status",
                "accountStatus", "delFlag", "reson", "createBy", "updateBy"}) {
            assertFalse(json.has(forbidden), forbidden + " must not leave the authenticated edit whitelist");
        }

        JsonNode anonymous = new ObjectMapper().valueToTree(PublicMerchantDiscoveryVO.from(fixture.merchant));
        for (String privateField : new String[]{"phone", "wechat", "wechatQrcode", "categoryIds"}) {
            assertFalse(anonymous.has(privateField), privateField + " must stay out of anonymous VOs");
        }
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
        request.setBusinessLicense("attacker-license");
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
        request.setBusinessLicense("must-not-change");
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

    // ===== owner 2026-10-22:门店信息 —— 电话 / 微信号 / 二维码可自助改,行业类型可多选 =====

    private static MmsMerchant savedPatch(Fixture f) {
        ArgumentCaptor<MmsMerchant> saved = ArgumentCaptor.forClass(MmsMerchant.class);
        verify(f.merchantService).updateAuthorizedProfile(saved.capture(), anyLong(),
                org.mockito.ArgumentMatchers.eq(MerchantPermission.PROFILE_WRITE));
        return saved.getValue();
    }

    @Test
    void ownerCanUpdatePhoneWechatIdAndQrcodeThroughProfileWrite() {
        Fixture f = fixture();
        MmsMerchant request = new MmsMerchant();
        request.setPhone("13912345678");
        request.setWechat("cy_store-01");
        request.setWechatQrcode("https://images.example.com/new-qr.png");
        assertEquals(200, f.controller.updateUserInfo(request).get(AjaxResult.CODE_TAG));
        MmsMerchant saved = savedPatch(f);
        assertEquals(MERCHANT_ID, saved.getId());
        assertEquals("13912345678", saved.getPhone());
        assertEquals("cy_store-01", saved.getWechat());
        assertEquals("https://images.example.com/new-qr.png", saved.getWechatQrcode());
        verify(f.media).requireAttachment(PRINCIPAL, "https://images.example.com/new-qr.png", true);
        assertNull(saved.getName());
        assertNull(saved.getBusinessLicense());
    }

    @Test
    void wechatIdAndQrcodeCanBeClearedButPhoneCannot() {
        Fixture f = fixture();
        MmsMerchant request = new MmsMerchant();
        request.setWechat("");
        request.setWechatQrcode("");
        assertEquals(200, f.controller.updateUserInfo(request).get(AjaxResult.CODE_TAG));
        MmsMerchant saved = savedPatch(f);
        assertEquals("", saved.getWechat());
        assertEquals("", saved.getWechatQrcode());

        Fixture g = fixture();
        MmsMerchant clearPhone = new MmsMerchant();
        clearPhone.setPhone("");
        assertFalse(Integer.valueOf(200).equals(g.controller.updateUserInfo(clearPhone).get(AjaxResult.CODE_TAG)));
        verify(g.merchantService, never()).updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString());
    }

    @Test
    void malformedPhoneOrWechatIdNeverReachesTheMapper() {
        String[][] bad = {
                {"phone", "1380000000"}, {"phone", "23800000000"}, {"phone", "138000000001"}, {"phone", "attacker-phone"},
                {"wechat", "https://images.example.com/qr.png"}, {"wechat", "含中文"}, {"wechat", "abcdefghijklmnopqrstu"},
        };
        for (String[] item : bad) {
            Fixture f = fixture();
            MmsMerchant request = new MmsMerchant();
            if ("phone".equals(item[0])) request.setPhone(item[1]); else request.setWechat(item[1]);
            assertFalse(Integer.valueOf(200).equals(f.controller.updateUserInfo(request).get(AjaxResult.CODE_TAG)),
                    item[0] + "=" + item[1]);
            verify(f.merchantService, never()).updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString());
        }
    }

    @Test
    void coopProfileExposesLogoAndCoverForTheBrandCenterAvatar() {
        Fixture f = fixture();
        JsonNode json = new ObjectMapper().valueToTree(f.controller.coopProfile().get(AjaxResult.DATA_TAG));
        assertEquals("https://images.example.com/logo.png", json.get("logo").asText());
        assertEquals("https://images.example.com/cover.png", json.get("coverImage").asText());
    }

    @Test
    void coopOnlyOperatorDoesNotSeeStoreContactFields() {
        Fixture f = fixture();
        // 营销岗持 COOP_MANAGE(能进合作经营)但没有 PROFILE_WRITE
        MerchantAccessContext coopOnly = new MerchantAccessContext(PRINCIPAL, MERCHANT_ID, 1L,
                MerchantRoleCode.MERCHANT_MARKETING);
        assertFalse(coopOnly.hasPermission(MerchantPermission.PROFILE_WRITE));
        when(f.access.require(PRINCIPAL, MerchantPermission.COOP_MANAGE)).thenReturn(coopOnly);
        JsonNode json = new ObjectMapper().valueToTree(f.controller.coopProfile().get(AjaxResult.DATA_TAG));
        assertTrue(json.get("phone").isNull());
        assertTrue(json.get("wechat").isNull());
        assertTrue(json.get("wechatQrcode").isNull());
    }

    @Test
    void decorSaveStoresMultiCategoriesWithFirstAsPrimary() {
        Fixture f = fixture();
        MmsMerchant body = new MmsMerchant();
        body.setCategoryIds("5,3,5");
        assertEquals(200, f.controller.saveDecor(body).get(AjaxResult.CODE_TAG));
        MmsMerchant saved = savedPatch(f);
        assertEquals("5,3", saved.getCategoryIds());
        assertEquals(Integer.valueOf(5), saved.getCategoryId(), "旧读者只认 category_id,必须是多选的第一个");
    }

    @Test
    void legacySingleCategoryClientKeepsBothColumnsInSync() {
        Fixture f = fixture();
        MmsMerchant body = new MmsMerchant();
        body.setCategoryId(3);
        assertEquals(200, f.controller.saveDecor(body).get(AjaxResult.CODE_TAG));
        MmsMerchant saved = savedPatch(f);
        assertEquals(Integer.valueOf(3), saved.getCategoryId());
        assertEquals("3", saved.getCategoryIds());
    }

    @Test
    void invalidCategorySelectionsAreRejected() {
        for (String ids : new String[]{"", "a,b", "3,", "0", "1,2,3,4,5,6", "9"}) {
            Fixture f = fixture();
            MmsMerchant body = new MmsMerchant();
            body.setCategoryIds(ids);
            assertFalse(Integer.valueOf(200).equals(f.controller.saveDecor(body).get(AjaxResult.CODE_TAG)), ids);
            verify(f.merchantService, never()).updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString());
        }
    }

    @Test
    void readersPreferMultiCategoryColumnAndFallBackToLegacyId() {
        MmsMerchant m = new MmsMerchant();
        m.setCategoryId(3);
        assertEquals("3", ApiMerchantController.categoryIdsOf(m));
        m.setCategoryIds("5,3");
        assertEquals("5,3", ApiMerchantController.categoryIdsOf(m));
        Fixture f = fixture();
        f.merchant.setCategoryIds("5,3");
        JsonNode json = new ObjectMapper().valueToTree(f.controller.coopProfile().get(AjaxResult.DATA_TAG));
        assertEquals(2, json.get("sysCategoryList").size());
        assertEquals("5,3", json.get("categoryIds").asText());
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
        fixture.merchant.setWechatQrcode("https://images.example.com/qr.png");
        fixture.merchant.setLogo("https://images.example.com/logo.png");
        fixture.merchant.setCoverImage("https://images.example.com/cover.png");
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
        fixture.access = access;

        ISysCategoryService categories = mock(ISysCategoryService.class);
        SysCategory category = new SysCategory();
        category.setId(3L);
        category.setCategoryName("咖啡馆");
        when(categories.selectSysCategoryListByIds(new String[]{"3"}))
                .thenReturn(Collections.singletonList(category));
        SysCategory food = new SysCategory();
        food.setId(5L);
        food.setCategoryName("美食");
        when(categories.selectSysCategoryListByIds(new String[]{"5", "3"}))
                .thenReturn(java.util.Arrays.asList(food, category));
        fixture.categories = categories;
        fixture.media = mock(AppMediaReferenceGuard.class);
        when(fixture.media.requireAttachment(anyLong(), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(1));

        ReflectionTestUtils.setField(fixture.controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(fixture.controller, "mmsMerchantService", fixture.merchantService);
        ReflectionTestUtils.setField(fixture.controller, "sysCategoryService", categories);
        ReflectionTestUtils.setField(fixture.controller, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        ReflectionTestUtils.setField(fixture.controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(fixture.controller, "mediaReferences", fixture.media);
        ReflectionTestUtils.setField(fixture.controller, "merchantNodeDiscoveryService",
                mock(IMerchantNodeDiscoveryService.class));
        return fixture;
    }

    private static class LoggedInController extends ApiMerchantController {
        @Override public Long getAppUserId() { return PRINCIPAL; }
    }

    private static class Fixture {
        private LoggedInController controller;
        private IMmsMerchantService merchantService;
        private MmsMerchant merchant;
        private IMerchantAccessService access;
        private ISysCategoryService categories;
        private AppMediaReferenceGuard media;
    }
}

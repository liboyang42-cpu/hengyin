package com.chengyinhub.web.controller.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.business.service.IWxContentSecurityService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家自己保存门店 AI 形象(/api/merchant/npc/save)。
 *
 * 守的是三条会静默出错的性质:
 *  1) 越权:这张表后台超管也在写,只按 id 更新就等于谁都能改别人的形象;
 *  2) avatar 字段的形态 —— 它最终进 img src,不校验就是任意字符串的垃圾场;
 *  3) 新建时 scope_type/scope_id 必须落对,落错了形象存进去了但门店永远读不出来(不报错,只是空)。
 */
class ApiMerchantNpcSaveTest {

    private static final Long MEMBER_ID = 201L;
    private static final Long MERCHANT_ID = 11L;

    private NpcProfileMapper npcProfileMapper;
    private IMmsMerchantService mmsMerchantService;
    private IWxContentSecurityService security;
    private ApiMerchantController controller;

    @BeforeEach
    void setUp() {
        npcProfileMapper = mock(NpcProfileMapper.class);
        mmsMerchantService = mock(IMmsMerchantService.class);
        security = mock(IWxContentSecurityService.class);
        controller = new TestableController(MEMBER_ID);
        when(npcProfileMapper.insertMerchantNpc(any())).thenReturn(1);
        when(npcProfileMapper.updateMerchantSelfNpc(any())).thenReturn(1);

        MmsMerchant m = new MmsMerchant();
        m.setId(MERCHANT_ID);
        when(mmsMerchantService.selectMmsMerchantById(MERCHANT_ID)).thenReturn(m);
        when(security.checkText(anyString(), any())).thenReturn(null);

        IMerchantAccessService accessService = mock(IMerchantAccessService.class);
        when(accessService.require(MEMBER_ID, MerchantPermission.PROFILE_WRITE))
                .thenReturn(new MerchantAccessContext(
                        MEMBER_ID, MERCHANT_ID, MEMBER_ID, MerchantRoleCode.MERCHANT_OWNER));

        ReflectionTestUtils.setField(controller, "npcProfileMapper", npcProfileMapper);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", mmsMerchantService);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
        // merchantOpenid() 会查会员表拿 openid 送内容安全 —— 不 mock 会 NPE 在校验之后,
        // 表现为「保存接口 500」,很容易被当成业务逻辑的问题
        IUmsMemberService umsMemberService = mock(IUmsMemberService.class);
        UmsMember owner = new UmsMember();
        owner.setWxOpenId("o_test_openid");
        when(umsMemberService.selectUmsMemberById(MEMBER_ID)).thenReturn(owner);
        ReflectionTestUtils.setField(controller, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        ReflectionTestUtils.setField(controller, "profileReviewService",
                mock(com.chengyinhub.business.service.impl.ProfileReviewService.class));
    }

    @Test
    void zeroAffectedRowsMustNotClaimSaved() {
        when(npcProfileMapper.insertMerchantNpc(any())).thenReturn(0);
        assertEquals(500, controller.npcSave(body("小店长", "px1:p01", "欢迎")).get(AjaxResult.CODE_TAG));
    }

    // ===== avatar 形态 =====

    @Test
    void rejectsAvatarThatIsNeitherPresetNorUrl() {
        // 这个值最终会进 img src。放行任意字符串等于把它变成垃圾场。
        for (String bad : new String[]{"px1:invalid", "http-invalid", "javascript:alert(1)", "lego1:sk0", "p01", "data:image/png;base64,xx"}) {
            AjaxResult r = controller.npcSave(body("小店长", bad, "欢迎"));
            assertEquals(500, r.get(AjaxResult.CODE_TAG), "应拒绝:" + bad);
        }
        verify(npcProfileMapper, never()).insertMerchantNpc(any());
    }

    @Test
    void acceptsPresetCodeAndUploadedUrl() {
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null)).thenReturn(new ArrayList<NpcProfile>());
        for (String ok : new String[]{"px1:p07", "/profile/2026/a.png", "https://cdn.example.com/a.png"}) {
            AjaxResult r = controller.npcSave(body("小店长", ok, "欢迎"));
            assertEquals(200, r.get(AjaxResult.CODE_TAG), "应接受:" + ok);
        }
    }

    @Test
    void rejectsMissingOrOverlongFields() {
        assertEquals(500, controller.npcSave(body(null, "px1:p01", null)).get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.npcSave(body("  ", "px1:p01", null)).get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.npcSave(body("小店长", null, null)).get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.npcSave(body(repeat("长", 33), "px1:p01", null)).get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.npcSave(body("小店长", "px1:p01", repeat("话", 256))).get(AjaxResult.CODE_TAG));
        // 边界内要放行,不能把 32 字也一起拒了
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null)).thenReturn(new ArrayList<NpcProfile>());
        assertEquals(200, controller.npcSave(body(repeat("长", 32), "px1:p01", null)).get(AjaxResult.CODE_TAG));
    }

    // ===== 新建 =====

    @Test
    void insertLandsOnMerchantScope() {
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null)).thenReturn(new ArrayList<NpcProfile>());

        controller.npcSave(body("小店长", "px1:p07", "欢迎光临"));

        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(npcProfileMapper).insertMerchantNpc(cap.capture());
        NpcProfile saved = cap.getValue();
        // scope 落错不会报错,只是这条形象门店永远读不出来 —— 读路径按 scope_type=2 + scopeId 查
        assertEquals(Integer.valueOf(2), saved.getScopeType());
        assertEquals(MERCHANT_ID, saved.getScopeId());
        assertEquals("px1:p07", saved.getAvatar());
        assertEquals("小店长", saved.getName());
        assertEquals(Integer.valueOf(1), saved.getEnabled());
        // M-28 先发后审:文本已同步过内容安全,保存即对玩家生效(玩家读闸只放 audit_status=1)。
        assertEquals(Integer.valueOf(1), saved.getAuditStatus(), "过了同步机审的保存必须立刻生效");
        // persona 是 NOT NULL 列;商家没填就落空串,不编假人设
        assertEquals("", saved.getPersona());
    }

    // ===== 人设(persona)=====

    @Test
    void personaIsTrimmedTextCheckedAndPersistedOnCreate() {
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null)).thenReturn(new ArrayList<NpcProfile>());
        NpcProfile body = body("小店长", "px1:p01", "欢迎");
        body.setPersona("  语气亲切，熟悉咖啡豆产地  ");
        body.setKnowledge("  招牌是手冲咖啡  ");

        assertEquals(200, controller.npcSave(body).get(AjaxResult.CODE_TAG));

        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(npcProfileMapper).insertMerchantNpc(cap.capture());
        assertEquals("语气亲切，熟悉咖啡豆产地", cap.getValue().getPersona());
        assertEquals("招牌是手冲咖啡", cap.getValue().getKnowledge());
        ArgumentCaptor<String> checked = ArgumentCaptor.forClass(String.class);
        verify(security).checkText(checked.capture(), any());
        assertTrue(checked.getValue().contains("语气亲切"), "persona 必须一起送内容安全");
        assertTrue(checked.getValue().contains("招牌是手冲咖啡"), "knowledge 必须一起送内容安全");
    }

    @Test
    void personaIsWrittenOnUpdateAndExplicitBlankClearsIt() {
        NpcProfile mine = new NpcProfile();
        mine.setId(42L);
        mine.setScopeId(MERCHANT_ID);
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null))
                .thenReturn(new ArrayList<>(Arrays.asList(mine)));

        NpcProfile body = body("小店长", "px1:p01", null);
        body.setPersona("说话像老朋友");
        controller.npcSave(body);
        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(npcProfileMapper).updateMerchantSelfNpc(cap.capture());
        assertEquals("说话像老朋友", cap.getValue().getPersona());

        // 显式空串 = 清空人设(清空后玩家侧回到「分身还没上线」);不传 = 本次不改
        NpcProfile clearBody = body("小店长", "px1:p01", null);
        clearBody.setPersona("");
        controller.npcSave(clearBody);
        verify(npcProfileMapper, times(2)).updateMerchantSelfNpc(cap.capture());
        assertEquals("", cap.getValue().getPersona());
    }

    @Test
    void personalityPresetLabelIsStoredAsTheCodeTheChatReaderUses() {
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null)).thenReturn(new ArrayList<NpcProfile>());
        NpcProfile body = body("小店长", "px1:p01", "欢迎");
        body.setPersona("毒舌");

        assertEquals(200, controller.npcSave(body).get(AjaxResult.CODE_TAG));

        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(npcProfileMapper).insertMerchantNpc(cap.capture());
        assertEquals("savage", cap.getValue().getPersona());
    }

    @Test
    void updateGoesLiveButRejectedUnchangedPhotoCannotBypassMachineReview() {
        NpcProfile mine = new NpcProfile();
        mine.setId(42L);
        mine.setScopeId(MERCHANT_ID);
        mine.setAuditStatus(2);
        mine.setAvatar("/profile/2026/bad.png");
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null))
                .thenReturn(new ArrayList<>(Arrays.asList(mine)));
        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);

        controller.npcSave(body("小店长", "/profile/2026/bad.png", null));
        verify(npcProfileMapper).updateMerchantSelfNpc(cap.capture());
        assertEquals(Integer.valueOf(0), cap.getValue().getAuditStatus(), "被机审打回的同一张图重新保存仍回到待审");

        controller.npcSave(body("小店长", "/profile/2026/new.png", null));
        controller.npcSave(body("小店长", "px1:p01", null));
        verify(npcProfileMapper, times(3)).updateMerchantSelfNpc(cap.capture());
        assertEquals(Integer.valueOf(1), cap.getAllValues().get(cap.getAllValues().size() - 2).getAuditStatus());
        assertEquals(Integer.valueOf(1), cap.getValue().getAuditStatus());
    }

    @Test
    void overlongPersonaIsRejectedBeforeAnyWrite() {
        NpcProfile tooLong = body("小店长", "px1:p01", null);
        tooLong.setPersona(repeat("人", 501));
        assertEquals(500, controller.npcSave(tooLong).get(AjaxResult.CODE_TAG));
        verify(npcProfileMapper, never()).insertMerchantNpc(any());
        verify(npcProfileMapper, never()).updateMerchantSelfNpc(any());

        // 边界内放行,不能把 500 字也一起拒了
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null)).thenReturn(new ArrayList<NpcProfile>());
        NpcProfile boundary = body("小店长", "px1:p01", null);
        boundary.setPersona(repeat("人", 500));
        assertEquals(200, controller.npcSave(boundary).get(AjaxResult.CODE_TAG));
    }

    // ===== 越权 =====

    @Test
    void updateRefusesRowOwnedByAnotherMerchant() {
        NpcProfile other = new NpcProfile();
        other.setId(999L);
        other.setScopeId(MERCHANT_ID + 1);       // 别人的形象
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null))
                .thenReturn(new ArrayList<>(Arrays.asList(other)));

        AjaxResult r = controller.npcSave(body("小店长", "px1:p01", null));

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        verify(npcProfileMapper, never()).updateMerchantSelfNpc(any());
        verify(npcProfileMapper, never()).insertMerchantNpc(any());
    }

    @Test
    void updateIgnoresClientSuppliedIdAndUsesOwnRow() {
        NpcProfile mine = new NpcProfile();
        mine.setId(42L);
        mine.setScopeId(MERCHANT_ID);
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null))
                .thenReturn(new ArrayList<>(Arrays.asList(mine)));

        NpcProfile body = body("小店长", "px1:p01", null);
        body.setId(777L);                        // 客户端自称要改 777
        body.setScopeId(MERCHANT_ID + 5);        // 还自称属于别的商家
        controller.npcSave(body);

        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(npcProfileMapper).updateMerchantSelfNpc(cap.capture());
        // id 只能来自库里查出来的那条,不能是客户端传的
        assertEquals(Long.valueOf(42L), cap.getValue().getId());
        assertNotEquals(Long.valueOf(777L), cap.getValue().getId());
        assertEquals(MERCHANT_ID, cap.getValue().getScopeId(), "mapper 归属条件必须使用登录态商家 ID");
    }

    // ===== 内容安全 =====

    @Test
    void rejectsWhenTextFailsContentSecurity() {
        when(security.checkText(anyString(), any())).thenReturn("内容不合规");
        AjaxResult r = controller.npcSave(body("小店长", "px1:p01", "违规词"));
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        verify(npcProfileMapper, never()).insertMerchantNpc(any());
    }

    @Test
    void submitsMediaCheckOnlyForUploadedPhoto() {
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null)).thenReturn(new ArrayList<NpcProfile>());

        controller.npcSave(body("小店长", "px1:p01", null));
        // 预设是内置资源,送检没有意义,而且会白白消耗微信的调用额度
        verify(security, never()).submitMediaCheckOne(anyInt(), anyLong(), anyString(), any());

        when(npcProfileMapper.insertMerchantNpc(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, NpcProfile.class).setId(55L);
            return 1;
        });
        controller.npcSave(body("小店长", "/profile/2026/a.png", null));
        // #334 F5:送检对象是这一行 NPC(bizId=npc_profile.id),不是借商家资料(bizId=merchantId)
        verify(security).submitMediaCheckOne(IWxContentSecurityService.BIZ_TYPE_MERCHANT_NPC, 55L,
                "/profile/2026/a.png", "o_test_openid");
        verify(security, never()).submitMediaCheckOne(
                org.mockito.ArgumentMatchers.eq(IWxContentSecurityService.BIZ_TYPE_MERCHANT_PROFILE),
                anyLong(), anyString(), any());
    }

    @Test
    void updatedPhotoIsCheckedAgainstOwnNpcRow() {
        NpcProfile mine = new NpcProfile();
        mine.setId(42L);
        mine.setScopeId(MERCHANT_ID);
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null))
                .thenReturn(new ArrayList<>(Arrays.asList(mine)));
        when(npcProfileMapper.updateMerchantSelfNpc(any())).thenReturn(1);

        controller.npcSave(body("小店长", "/profile/2026/b.png", null));

        verify(security).submitMediaCheckOne(IWxContentSecurityService.BIZ_TYPE_MERCHANT_NPC, 42L,
                "/profile/2026/b.png", "o_test_openid");
    }

    // ===== 读 =====

    @Test
    void profileReturnsOwnRowIncludingUnaudited() {
        NpcProfile mine = new NpcProfile();
        mine.setId(42L);
        mine.setScopeId(MERCHANT_ID);
        mine.setAuditStatus(0);                  // 刚提交、还没过审
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null))
                .thenReturn(new ArrayList<>(Arrays.asList(mine)));

        AjaxResult r = controller.npcProfile();
        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        // 编辑页必须看得见未过审的那条,否则商家会以为没保存上又存一遍
        assertEquals(mine, r.get(AjaxResult.DATA_TAG));
    }

    @Test
    void profileReturnsNullWhenNoneConfigured() {
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null)).thenReturn(new ArrayList<NpcProfile>());
        AjaxResult r = controller.npcProfile();
        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        assertNull(r.get(AjaxResult.DATA_TAG));
    }

    private static NpcProfile body(String name, String avatar, String greeting) {
        NpcProfile p = new NpcProfile();
        p.setName(name);
        p.setAvatar(avatar);
        p.setGreeting(greeting);
        return p;
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static class TestableController extends ApiMerchantController {
        private final Long memberId;
        private TestableController(Long memberId) { this.memberId = memberId; }
        @Override public Long getAppUserId() { return memberId; }
    }
}

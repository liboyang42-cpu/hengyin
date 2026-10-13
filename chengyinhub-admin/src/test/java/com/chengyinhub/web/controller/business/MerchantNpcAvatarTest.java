package com.chengyinhub.web.controller.business;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.vo.ai.NpcProfileVO;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiMerchantController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 门店 AI 形象(npc_profile scope_type=2)读路径 + 后台写路径。
 *
 * 这里守的是三条会静默出错的性质:
 *  1) 读路径真的按 scope_type=2 查(改回 1 就等于「门店分身永远取不出来」,页面只是空白不报错);
 *  2) 形象查询炸了不能把整个商家主页判失败(降级必须是 npc=null,不是 500);
 *  3) 后台新增不写 persona 假人设、审核态落 1、非法输入被拒。
 */
class MerchantNpcAvatarTest {

    private static final Long MERCHANT_ID = 88L;

    private NpcProfileMapper npcProfileMapper;
    private NpcProfileService npcProfileService;
    private IMmsMerchantService mmsMerchantService;

    @BeforeEach
    void setUp() {
        npcProfileMapper = mock(NpcProfileMapper.class);
        npcProfileService = new NpcProfileService();
        ReflectionTestUtils.setField(npcProfileService, "npcProfileMapper", npcProfileMapper);
        mmsMerchantService = mock(IMmsMerchantService.class);
    }

    // ===================== 读路径 =====================

    @Test
    void resolveForMerchant_queriesScopeTwo() {
        when(npcProfileMapper.selectEnabledByScope(2, MERCHANT_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(profile(1L, "店长阿喵", "/px/cat.png", "欢迎光临"))));

        NpcProfileVO vo = npcProfileService.resolveForMerchant(MERCHANT_ID);

        // 写死 2 —— 传 1 会取到剧本角色,传 3 会取到全局 NPC,两种都不报错只是「拿错人」
        verify(npcProfileMapper).selectEnabledByScope(2, MERCHANT_ID);
        assertNotNull(vo);
        assertEquals("店长阿喵", vo.getName());
        assertEquals("/px/cat.png", vo.getAvatar());
        assertEquals("欢迎光临", vo.getGreeting());
    }

    @Test
    void resolveForMerchant_takesFirstBySort_whenMultiple() {
        // mapper 已按 sort ASC 排序,取第一条 = 取 sort 最小的那条
        when(npcProfileMapper.selectEnabledByScope(2, MERCHANT_ID)).thenReturn(new ArrayList<>(Arrays.asList(
                profile(1L, "主形象", "/px/a.png", "你好"),
                profile(2L, "备用形象", "/px/b.png", "嗨"))));

        assertEquals("主形象", npcProfileService.resolveForMerchant(MERCHANT_ID).getName());
    }

    @Test
    void resolveForMerchant_returnsNull_whenNoneOrNoId() {
        when(npcProfileMapper.selectEnabledByScope(2, MERCHANT_ID)).thenReturn(new ArrayList<NpcProfile>());
        assertNull(npcProfileService.resolveForMerchant(MERCHANT_ID));

        assertNull(npcProfileService.resolveForMerchant(null));
        verify(npcProfileMapper, never()).selectEnabledByScope(2, null);
    }

    @Test
    void publicHome_carriesNpc() throws Exception {
        when(npcProfileMapper.selectEnabledByScope(2, MERCHANT_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(profile(1L, "店长阿喵", "/px/cat.png", "欢迎光临"))));

        mockMvcForPublicHome(visibleMerchant())
                .perform(post("/api/merchant/public-home")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + MERCHANT_ID + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.npc.name").value("店长阿喵"))
                .andExpect(jsonPath("$.data.npc.avatar").value("/px/cat.png"))
                .andExpect(jsonPath("$.data.npc.greeting").value("欢迎光临"));
    }

    @Test
    void publicHome_stillOk_whenNpcLookupThrows() throws Exception {
        // 负控:形象查询炸了,主页不能连带 500 —— 商家资料本身跟形象没有依赖关系
        when(npcProfileMapper.selectEnabledByScope(2, MERCHANT_ID))
                .thenThrow(new RuntimeException("db down"));

        mockMvcForPublicHome(visibleMerchant())
                .perform(post("/api/merchant/public-home")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + MERCHANT_ID + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("测试小店"))
                .andExpect(jsonPath("$.data.npc").doesNotExist());
    }

    // ===================== 后台写路径 =====================

    @Test
    void add_writesEmptyPersonaAndApprovedStatus() {
        CmsMerchantNpcController controller = merchantNpcController();
        when(mmsMerchantService.selectMmsMerchantById(MERCHANT_ID)).thenReturn(visibleMerchant());
        when(npcProfileMapper.insertMerchantNpc(any(NpcProfile.class))).thenReturn(1);

        NpcProfile body = new NpcProfile();
        body.setScopeId(MERCHANT_ID);
        body.setName("  店长阿喵  ");
        body.setAvatar(" /px/cat.png ");
        body.setGreeting("  欢迎光临  ");

        AjaxResult result = controller.add(body);
        assertEquals(200, result.get(AjaxResult.CODE_TAG));

        ArgumentCaptor<NpcProfile> captor = ArgumentCaptor.forClass(NpcProfile.class);
        verify(npcProfileMapper).insertMerchantNpc(captor.capture());
        NpcProfile saved = captor.getValue();
        assertEquals("店长阿喵", saved.getName());
        assertEquals("/px/cat.png", saved.getAvatar());
        assertEquals("欢迎光临", saved.getGreeting());
        // 静态出场不读人设:不编一段假人设塞进去,但 persona 是 NOT NULL 故必须是空串而非 null
        assertEquals("", saved.getPersona());
        assertEquals(Integer.valueOf(1), saved.getAuditStatus());
        assertEquals(Integer.valueOf(1), saved.getEnabled());
        assertEquals(Integer.valueOf(0), saved.getSort());
    }

    @Test
    void add_rejectsMissingMerchantAndFields() {
        CmsMerchantNpcController controller = merchantNpcController();
        when(mmsMerchantService.selectMmsMerchantById(anyLong())).thenReturn(null);

        NpcProfile orphan = new NpcProfile();
        orphan.setScopeId(MERCHANT_ID);
        orphan.setName("店长");
        orphan.setAvatar("/px/cat.png");
        assertEquals(500, controller.add(orphan).get(AjaxResult.CODE_TAG));

        when(mmsMerchantService.selectMmsMerchantById(MERCHANT_ID)).thenReturn(visibleMerchant());

        NpcProfile noAvatar = new NpcProfile();
        noAvatar.setScopeId(MERCHANT_ID);
        noAvatar.setName("店长");
        assertEquals(500, controller.add(noAvatar).get(AjaxResult.CODE_TAG));

        NpcProfile longName = new NpcProfile();
        longName.setScopeId(MERCHANT_ID);
        longName.setName(repeat("喵", 33));
        longName.setAvatar("/px/cat.png");
        assertEquals(500, controller.add(longName).get(AjaxResult.CODE_TAG));

        verify(npcProfileMapper, never()).insertMerchantNpc(any(NpcProfile.class));
    }

    @Test
    void editAndRemove_refuseRowsOutsideMerchantScope() {
        CmsMerchantNpcController controller = merchantNpcController();
        // selectMerchantNpcById 自带 scope_type = 2 过滤:指向剧本角色/全局 NPC 的 id 在这里查不到
        when(npcProfileMapper.selectMerchantNpcById(9L)).thenReturn(null);

        NpcProfile body = new NpcProfile();
        body.setId(9L);
        body.setName("改名");
        assertEquals(500, controller.edit(body).get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.remove(9L).get(AjaxResult.CODE_TAG));

        verify(npcProfileMapper, never()).updateMerchantNpc(any(NpcProfile.class));
        verify(npcProfileMapper, never()).deleteMerchantNpcById(anyLong());
    }

    @Test
    void edit_refusesEmptyPatch() {
        CmsMerchantNpcController controller = merchantNpcController();
        when(npcProfileMapper.selectMerchantNpcById(9L)).thenReturn(profile(9L, "店长", "/px/cat.png", "hi"));

        NpcProfile body = new NpcProfile();
        body.setId(9L);
        // 只带 id:<set> 会只剩 update_time,数据库回 1 行 ⇒ 「什么都没改」被报成「已保存」
        assertEquals(500, controller.edit(body).get(AjaxResult.CODE_TAG));
        verify(npcProfileMapper, never()).updateMerchantNpc(any(NpcProfile.class));
    }

    @Test
    void edit_refusesBlankingAvatar() {
        CmsMerchantNpcController controller = merchantNpcController();
        when(npcProfileMapper.selectMerchantNpcById(9L)).thenReturn(profile(9L, "店长", "/px/cat.png", "hi"));

        NpcProfile body = new NpcProfile();
        body.setId(9L);
        body.setAvatar("   ");
        assertEquals(500, controller.edit(body).get(AjaxResult.CODE_TAG));
        verify(npcProfileMapper, never()).updateMerchantNpc(any(NpcProfile.class));
    }

    // ===================== 三处查询口径不许漂 =====================

    /**
     * 门店形象的筛选口径出现在三个地方,谁漂了都不会报错,只会让玩家看到不一致的脸:
     *   NpcProfileMapper.selectEnabledByScope   店铺主页(canonical)
     *   RoamMapper.selectPoisNear               漫游地图 · roam_poi 来源
     *   ApiMapMapper.selectNearbyNodes          漫游地图 · 附近报名商家来源
     * 少一个 enabled/audit_status/del_flag 条件 = 后台停用了地图上还在;
     * 少了 id tiebreak = 同 sort 的两条形象在不同页面各选各的,同一家店两张脸。
     */
    @Test
    void allThreeAvatarQueriesShareTheSameFilterAndOrder() throws Exception {
        String canonical = statement(readMapper("mapper/business/NpcProfileMapper.xml"), "selectEnabledByScope");
        String roam = statement(readMapper("mapper/business/RoamMapper.xml"), "selectPoisNear");
        String nearby = statement(readMapper("mapper/business/ApiMapMapper.xml"), "selectNearbyNodes");

        // ⚠️ 两处联查必须断言**带别名**的写法(n.del_flag)。写成裸 del_flag = '0' 是假绿:
        //    selectNearbyNodes 外层本来就有 where del_flag = '0',把子查询里那条删掉照样命中。
        assertFilter("canonical", canonical, "", "order by sort asc, id asc");
        assertFilter("roam", roam, "n.", "order by n.sort asc, n.id asc");
        assertFilter("nearby", nearby, "n.", "order by n.sort asc, n.id asc");
        // 两处联查必须钉死 scope_type = 2,否则会把剧本角色(1)/全局 NPC(3)画到商家点位上
        assertTrue(roam.toLowerCase().contains("scope_type = 2"), "roam 联查未钉死 scope_type = 2");
        assertTrue(nearby.toLowerCase().contains("scope_type = 2"), "nearby 联查未钉死 scope_type = 2");
    }

    private void assertFilter(String source, String rawSql, String alias, String expectedOrder) {
        String sql = normalize(rawSql.toLowerCase());
        assertTrue(sql.contains(alias + "enabled = 1"), source + " 缺 " + alias + "enabled = 1");
        assertTrue(sql.contains(alias + "audit_status = 1"), source + " 缺 " + alias + "audit_status = 1");
        assertTrue(sql.contains(alias + "del_flag = '0'"), source + " 缺 " + alias + "del_flag = '0'");
        assertTrue(sql.contains(expectedOrder), source + " 的排序缺 id tiebreak,同 sort 时各选各的");
    }

    private String readMapper(String resource) throws Exception {
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "读不到 " + resource);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** 取出某个 statement 的 SQL 正文,并剥掉 XML 注释 —— 注释里也写着这些词,会假绿。 */
    private String statement(String xml, String id) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<(select|update|insert|delete)\\s+id=\"" + id + "\"[\\s\\S]*?</\\1>")
                .matcher(xml);
        assertTrue(m.find(), "找不到 statement: " + id);
        return m.group().replaceAll("(?s)<!--.*?-->", "");
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ");
    }

    // ===================== helpers =====================

    private CmsMerchantNpcController merchantNpcController() {
        CmsMerchantNpcController controller = new CmsMerchantNpcController();
        ReflectionTestUtils.setField(controller, "npcProfileMapper", npcProfileMapper);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", mmsMerchantService);
        return controller;
    }

    private MockMvc mockMvcForPublicHome(MmsMerchant merchant) {
        when(mmsMerchantService.selectMmsMerchantById(MERCHANT_ID)).thenReturn(merchant);
        ApiMerchantController controller = new ApiMerchantController();
        ReflectionTestUtils.setField(controller, "mmsMerchantService", mmsMerchantService);
        ReflectionTestUtils.setField(controller, "npcProfileService", npcProfileService);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private MmsMerchant visibleMerchant() {
        MmsMerchant m = new MmsMerchant();
        m.setId(MERCHANT_ID);
        m.setMemberId(1001L);
        m.setName("测试小店");
        m.setStatus(1L);
        m.setAccountStatus(1L);
        m.setDelFlag(0);
        return m;
    }

    private NpcProfile profile(Long id, String name, String avatar, String greeting) {
        NpcProfile p = new NpcProfile();
        p.setId(id);
        p.setScopeType(2);
        p.setScopeId(MERCHANT_ID);
        p.setName(name);
        p.setAvatar(avatar);
        p.setGreeting(greeting);
        p.setSort(0);
        return p;
    }

    private String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}

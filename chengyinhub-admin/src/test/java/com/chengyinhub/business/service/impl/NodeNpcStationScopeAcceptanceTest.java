package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.vo.ai.NpcProfileVO;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.ConsentGuard;
import com.chengyinhub.business.service.IChapterMerchantNodeService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.VoiceCloneClient;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SC-NPC-NODE 验收:商家给某一站配 NPC,配置只落在这一站;相邻站读不到;
 * 无管理权(组织者/玩家/别家商家)的写入在闸口被拒,一条 npc_profile 写都不发生。
 *
 * <p>npc_profile 用一个按 XML 语义(scope_type=4 + scope_id + audit/enabled 读闸)实现的内存表,
 * 写入口与玩家读入口走同一张表 —— 不是各自 mock 一个返回值。
 */
class NodeNpcStationScopeAcceptanceTest {

    private static final Long OWNER = 88L;
    private static final Long STATION_A = 31L;
    private static final Long STATION_B = 32L;   // 同一主题的相邻站
    private static final Long ORGANIZER_NODE = 33L;

    private final List<NpcProfile> table = new ArrayList<>();
    private IChapterMerchantNodeService gate;
    private NpcProfileMapper mapper;
    private IWxContentSecurityService wxSec;
    private VoiceCloneClient voice;
    private NodeNpcService service;
    private NpcProfileService reader;

    @BeforeEach
    void setUp() {
        gate = mock(IChapterMerchantNodeService.class);
        mapper = mock(NpcProfileMapper.class);
        wxSec = mock(IWxContentSecurityService.class);
        voice = mock(VoiceCloneClient.class);
        service = new NodeNpcService(gate, mapper, wxSec, voice);
        ReflectionTestUtils.setField(service, "consentGuard", mock(ConsentGuard.class));
        AppMediaReferenceGuard media = mock(AppMediaReferenceGuard.class);
        when(media.requireAttachment(anyLong(), anyString(), eq(true))).thenAnswer(c -> c.getArgument(1));
        when(media.processingUrl(anyString(), anyLong(), eq("AUDIO"))).thenAnswer(c -> c.getArgument(0));
        ReflectionTestUtils.setField(service, "mediaReferences", media);
        reader = new NpcProfileService();
        ReflectionTestUtils.setField(reader, "npcProfileMapper", mapper);

        when(gate.requireOwnLiveNode(OWNER, STATION_A)).thenReturn(node(STATION_A, OWNER));
        when(gate.requireOwnLiveNode(OWNER, STATION_B)).thenReturn(node(STATION_B, OWNER));
        when(gate.requireOwnNode(OWNER, STATION_A)).thenReturn(node(STATION_A, OWNER));

        // 内存表:语义照 NpcProfileMapper.xml(scope_type=4 写死、del_flag 省略、读闸 audit=1 AND enabled=1)。
        when(mapper.selectNodeNpcByNodeId(anyLong())).thenAnswer(c -> {
            Long nodeId = c.getArgument(0);
            for (NpcProfile p : table) {
                if (Integer.valueOf(4).equals(p.getScopeType()) && nodeId.equals(p.getScopeId())) return copy(p);
            }
            return null;
        });
        when(mapper.selectEnabledByScope(anyInt(), anyLong())).thenAnswer(c -> {
            int type = c.getArgument(0);
            Long scopeId = c.getArgument(1);
            List<NpcProfile> out = new ArrayList<>();
            for (NpcProfile p : table) {
                if (Integer.valueOf(type).equals(p.getScopeType()) && scopeId.equals(p.getScopeId())
                        && Integer.valueOf(1).equals(p.getAuditStatus()) && Integer.valueOf(1).equals(p.getEnabled())) {
                    out.add(copy(p));
                }
            }
            return out;
        });
        when(mapper.insertNodeNpc(any(NpcProfile.class))).thenAnswer(c -> {
            NpcProfile p = c.getArgument(0);
            for (NpcProfile row : table) {
                if (row.getScopeType().equals(p.getScopeType()) && row.getScopeId().equals(p.getScopeId())) {
                    throw new org.springframework.dao.DuplicateKeyException("uk_active_scope");
                }
            }
            p.setId(500L + table.size());
            table.add(copy(p));
            return 1;
        });
        when(mapper.updateNodeNpc(any(NpcProfile.class))).thenAnswer(c -> {
            NpcProfile upd = c.getArgument(0);
            for (NpcProfile row : table) {
                if (row.getId().equals(upd.getId()) && Integer.valueOf(4).equals(row.getScopeType())) {
                    row.setName(upd.getName());
                    row.setAvatar(upd.getAvatar());
                    row.setGreeting(upd.getGreeting());
                    return 1;
                }
            }
            return 0;
        });
    }

    private static CmsTopicNode node(Long id, Long merchant) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setTopicId(100L);
        n.setChapterId(9L);
        n.setMerchantMemberId(merchant);
        return n;
    }

    private static NpcProfile copy(NpcProfile p) {
        NpcProfile c = new NpcProfile();
        c.setId(p.getId());
        c.setScopeType(p.getScopeType());
        c.setScopeId(p.getScopeId());
        c.setName(p.getName());
        c.setAvatar(p.getAvatar());
        c.setGreeting(p.getGreeting());
        c.setPersona(p.getPersona());
        c.setAuditStatus(p.getAuditStatus());
        c.setEnabled(p.getEnabled());
        c.setVoiceStatus(p.getVoiceStatus());
        c.setVoiceVersion(p.getVoiceVersion());
        return c;
    }

    private static NpcProfile draft(String name, String greeting) {
        NpcProfile p = new NpcProfile();
        p.setName(name);
        p.setAvatar("px1:p01");
        p.setGreeting(greeting);
        return p;
    }

    @Test
    void merchantSavesNpcForStationAAndOnlyStationAShowsIt() {
        NpcProfile saved = service.save(OWNER, STATION_A, draft("阿福", "欢迎来到第一站"), "o_openid");

        assertEquals(1, table.size(), "一站一条");
        assertEquals(Integer.valueOf(NodeNpcService.SCOPE_NODE), table.get(0).getScopeType());
        assertEquals(STATION_A, table.get(0).getScopeId(), "只写到本站 scope_id");
        assertEquals(saved.getId(), table.get(0).getId());

        // 回填:本站读得回刚配的内容
        NpcProfile back = service.detailForMerchant(OWNER, STATION_A);
        assertEquals("阿福", back.getName());
        assertEquals("欢迎来到第一站", back.getGreeting());

        // 玩家读:本站出场 = 节点 NPC;相邻站没有节点级 NPC
        NpcProfileVO atA = reader.resolveForNode(STATION_A);
        assertNotNull(atA);
        assertEquals("阿福", atA.getName());
        assertNull(reader.resolveForNode(STATION_B), "相邻站不得继承本站配置");
        assertNull(mapper.selectNodeNpcByNodeId(STATION_B), "相邻站商家回填仍是空表单");
        // 门店分身兜底(resolveForNodeOrMerchant)是另一条 scope_type=2 的读,不是本站配置泄漏
        assertNull(reader.resolveForNodeOrMerchant(STATION_B, OWNER), "本店未配门店分身时相邻站完全没有 NPC");

        // 再次保存本站是 update 同一行,不会扩散到别站
        service.save(OWNER, STATION_A, draft("阿福", "改一句"), "o_openid");
        assertEquals(1, table.size());
        assertEquals("改一句", table.get(0).getGreeting());
        assertNull(reader.resolveForNode(STATION_B));
    }

    @Test
    void savingStationBDoesNotTouchStationA() {
        service.save(OWNER, STATION_A, draft("阿福", "A 站"), "o_openid");
        service.save(OWNER, STATION_B, draft("阿禄", "B 站"), "o_openid");
        assertEquals(2, table.size());
        assertEquals("阿福", reader.resolveForNode(STATION_A).getName());
        assertEquals("阿禄", reader.resolveForNode(STATION_B).getName());
    }

    /** 组织者自建点位 / 玩家 / 别家商家:requireOwnLiveNode 抛 ⇒ 三个写入口一条写都不发。 */
    @Test
    void nonOwnerIsRefusedOnEveryWriteEntryWithoutAnyRowWritten() {
        Long organizer = 70L;
        Long player = 71L;
        for (Long actor : new Long[]{organizer, player}) {
            when(gate.requireOwnLiveNode(actor, ORGANIZER_NODE))
                    .thenThrow(new IllegalStateException("点位不存在或无权操作"));
            when(gate.requireOwnLiveNode(actor, STATION_A))
                    .thenThrow(new IllegalStateException("点位不存在或无权操作"));
        }
        for (Long actor : new Long[]{organizer, player}) {
            for (Long nodeId : new Long[]{ORGANIZER_NODE, STATION_A}) {
                assertEquals("点位不存在或无权操作", assertThrows(IllegalStateException.class,
                        () -> service.save(actor, nodeId, draft("冒名", "我来改"), "o_x")).getMessage());
                assertEquals("点位不存在或无权操作", assertThrows(IllegalStateException.class,
                        () -> service.voiceEnroll(actor, actor, nodeId, "/profile/a.mp3")).getMessage());
                assertEquals("点位不存在或无权操作", assertThrows(IllegalStateException.class,
                        () -> service.voiceReset(actor, nodeId)).getMessage());
            }
        }
        assertEquals(0, table.size());
        verify(mapper, never()).insertNodeNpc(any());
        verify(mapper, never()).updateNodeNpc(any());
        verify(mapper, never()).updateNodeNpcVoice(any());
        verifyNoInteractions(wxSec, voice);
    }

    /** 本站已有 NPC 时,非归属者的 voiceReset 也不能清掉商家已配的声音。 */
    @Test
    void nonOwnerCannotResetExistingStationVoice() {
        service.save(OWNER, STATION_A, draft("阿福", "A 站"), "o_openid");
        table.get(0).setVoiceStatus(NodeNpcService.VOICE_READY);
        table.get(0).setVoiceVersion(3L);
        when(gate.requireOwnLiveNode(71L, STATION_A)).thenThrow(new IllegalStateException("点位不存在或无权操作"));

        assertThrows(IllegalStateException.class, () -> service.voiceReset(71L, STATION_A));
        assertThrows(IllegalStateException.class, () -> service.voiceEnroll(71L, 71L, STATION_A, "/profile/b.mp3"));
        assertThrows(IllegalStateException.class, () -> service.save(71L, STATION_A, draft("冒名", "x"), "o_x"));

        verify(mapper, never()).updateNodeNpcVoice(any());
        verify(mapper, never()).updateNodeNpc(any());
        assertEquals(Integer.valueOf(NodeNpcService.VOICE_READY), table.get(0).getVoiceStatus());
        assertEquals("阿福", table.get(0).getName());
    }
}

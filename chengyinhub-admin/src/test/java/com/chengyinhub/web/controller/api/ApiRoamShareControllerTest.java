package com.chengyinhub.web.controller.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.mapper.RoamShareSnapshotMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.support.RoamSharePrivacyService;
import com.chengyinhub.business.service.support.RoamShareSnapshotService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 第二轮拍板 17:足迹分享快照。接收方匿名可读,所以这里测的主要是信任边界:
 * 只留白名单字段、坐标降精度、形状不对整份拒绝、令牌不可猜、失效后读不到、分享者身份不下发。
 */
class ApiRoamShareControllerTest {

    private static class AsMember extends ApiRoamShareController {
        private final long id;
        AsMember(long id) { this.id = id; }
        @Override Long uid() { return id; }
    }

    private static ApiRoamShareController controller(long memberId, RoamShareSnapshotMapper mapper) {
        ApiRoamShareController c = new AsMember(memberId);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        when(members.lockMemberById(memberId)).thenReturn(memberId);
        ReflectionTestUtils.setField(c, "snapshotMapper", mapper);
        ReflectionTestUtils.setField(c, "privacyService", new RoamSharePrivacyService());
        RoamShareSnapshotService service = new RoamShareSnapshotService(mapper, members);
        ReflectionTestUtils.setField(service,"locationConsent",mock(com.chengyinhub.business.service.impl.RoamLocationConsentService.class));
        com.chengyinhub.business.mapper.RoamMapper roam = mock(com.chengyinhub.business.mapper.RoamMapper.class);
        com.chengyinhub.business.domain.RoamSession session = new com.chengyinhub.business.domain.RoamSession();
        session.setId(12L); session.setMemberId(memberId); session.setStatus(1); session.setFinishedAt(new java.util.Date());
        when(roam.selectOwnedSessionForUpdate(12L,memberId)).thenReturn(session);
        ReflectionTestUtils.setField(service, "roamMapper", roam);
        ReflectionTestUtils.setField(c, "snapshotService", service);
        ReflectionTestUtils.setField(c, "wxContentSecurityService",
                mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
        ReflectionTestUtils.setField(c, "umsMemberService", mock(com.chengyinhub.business.service.IUmsMemberService.class));
        return c;
    }

    private static final String SNAPSHOT = "{\"routeName\":\"外滩等3处足迹回看\",\"time\":\"42:05\",\"distance\":3.2,"
            + "\"explorePct\":12,\"shops\":2,\"medal\":\"城市点亮 50 格\",\"secretHome\":\"不该留下\","
            + "\"track\":[{\"lat\":31.2304567,\"lng\":121.4737123,\"acc\":5},{\"lat\":-90,\"lng\":-180}],"
            + "\"pois\":[{\"name\":\"外滩\",\"cat\":\"landmark\",\"catLabel\":\"地标\",\"lat\":90,\"lng\":180,\"id\":77}]}";

    @Test
    void publish_rejects_flagged_share_text_before_anything_is_stored() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        ApiRoamShareController c = controller(9, mapper);
        com.chengyinhub.business.service.IWxContentSecurityService sec =
                mock(com.chengyinhub.business.service.IWxContentSecurityService.class);
        when(sec.checkText(anyString(), any())).thenReturn("内容含违规信息");
        ReflectionTestUtils.setField(c, "wxContentSecurityService", sec);

        AjaxResult r = c.publish(1700000000000L, 12L, SNAPSHOT);
        assertEquals(500, r.get("code"));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sec).checkText(text.capture(), any());
        assertTrue(text.getValue().contains("外滩等3处足迹回看") && text.getValue().contains("城市点亮 50 格")
                && text.getValue().contains("地标"), text.getValue());
        verify(mapper, never()).upsertOwnedSnapshot(anyString(), anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void publish_normalizes_whitelisted_fields_and_rounds_coordinates() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.selectTokenByMemberTs(9L, 1700000000000L)).thenReturn(null, "0123456789abcdef0123456789abcdef");
        AjaxResult r = controller(9, mapper).publish(1700000000000L, 12L, SNAPSHOT);
        assertEquals(200, r.get("code"));
        assertEquals("0123456789abcdef0123456789abcdef", ((Map<?, ?>) r.get("data")).get("token"));

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(mapper).upsertOwnedSnapshot(token.capture(), eq(9L), eq(1700000000000L), eq(12L), json.capture());
        assertTrue(token.getValue().matches("[a-f0-9]{32}"), "令牌必须是随机 32 位十六进制");
        JSONObject stored = JSON.parseObject(json.getValue());
        assertFalse(stored.containsKey("secretHome"), "白名单外字段不能进匿名可读的快照");
        assertEquals("31.230", stored.getJSONArray("track").getJSONObject(0).getBigDecimal("lat").toPlainString(),
                "服务端必须在落库前将真实坐标量化到最多 3 位小数");
        assertEquals("-90.000", stored.getJSONArray("track").getJSONObject(1).getBigDecimal("lat").toPlainString());
        assertEquals("180.000", stored.getJSONArray("pois").getJSONObject(0).getBigDecimal("lng").toPlainString(),
                "世界经纬度边界值必须可接受");
        assertFalse(stored.getJSONArray("track").getJSONObject(0).containsKey("acc"));
        assertFalse(stored.getJSONArray("pois").getJSONObject(0).containsKey("id"));
        assertEquals(1699999200000L, stored.getLongValue("ts"), "时间只留到小时");
        assertFalse(json.getValue().contains("\"9\"") || stored.containsKey("memberId"), "快照里不带分享者身份");
    }

    @Test
    void repeatPublishKeepsTokenAndQuantizedSnapshotStable() {
        String stableToken = "existing-share-token";
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.selectTokenByMemberTs(9L, 1700000000000L))
                .thenReturn(null, stableToken, stableToken, stableToken);
        ApiRoamShareController c = controller(9, mapper);

        AjaxResult first = c.publish(1700000000000L, 12L, SNAPSHOT);
        AjaxResult second = c.publish(1700000000000L, 12L, SNAPSHOT);

        assertEquals(stableToken, ((Map<?, ?>) first.get("data")).get("token"));
        assertEquals(stableToken, ((Map<?, ?>) second.get("data")).get("token"));
        ArgumentCaptor<String> snapshots = ArgumentCaptor.forClass(String.class);
        verify(mapper, times(2)).upsertOwnedSnapshot(anyString(), eq(9L), eq(1700000000000L), eq(12L), snapshots.capture());
        assertEquals(snapshots.getAllValues().get(0), snapshots.getAllValues().get(1));
        verify(mapper, times(1)).countByMember(9L);
    }

    @Test
    void publish_rejects_bad_shapes_without_writing() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        ApiRoamShareController c = controller(9, mapper);
        assertEquals(500, c.publish(1L, 12L, "{\"track\":[{\"lat\":91,\"lng\":1}]}").get("code"), "越界坐标");
        assertEquals(500, c.publish(1L, 12L, "{\"track\":[{\"lat\":{\"x\":1},\"lng\":1}]}").get("code"), "嵌套对象冒充坐标");
        assertEquals(500, c.publish(1L, 12L, "{\"routeName\":{\"a\":1}}").get("code"), "非字符串文本");
        assertEquals(500, c.publish(1L, 12L, "{\"distance\":-1}").get("code"), "负距离");
        assertEquals(500, c.publish(1L, 12L, "not json").get("code"));
        assertEquals(500, c.publish(0L, 12L, SNAPSHOT).get("code"), "没有足迹 ts");
        StringBuilder big = new StringBuilder("{\"track\":[");
        for (int i = 0; i <= ApiRoamShareController.MAX_TRACK_POINTS; i++) big.append(i == 0 ? "" : ",").append("{\"lat\":1,\"lng\":1}");
        assertEquals(500, c.publish(1L, 12L, big.append("]}").toString()).get("code"), "轨迹点数上限");
        assertEquals(500, controller(0, mapper).publish(1L, 12L, SNAPSHOT).get("code"), "未登录不能发布");
        verify(mapper, never()).upsertOwnedSnapshot(anyString(), anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void publish_evicts_oldest_only_when_a_new_footprint_would_exceed_the_cap() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.selectTokenByMemberTs(9L, 5L)).thenReturn(null, "0123456789abcdef0123456789abcdef");
        when(mapper.countByMember(9L)).thenReturn(RoamShareSnapshotService.MAX_SNAPSHOTS_PER_MEMBER);
        controller(9, mapper).publish(5L, 12L, SNAPSHOT);
        verify(mapper).deleteOldestByMember(9L, 1);

        RoamShareSnapshotMapper existing = mock(RoamShareSnapshotMapper.class);
        when(existing.selectTokenByMemberTs(9L, 5L)).thenReturn("0123456789abcdef0123456789abcdef");
        when(existing.countByMember(9L)).thenReturn(RoamShareSnapshotService.MAX_SNAPSHOTS_PER_MEMBER);
        controller(9, existing).publish(5L, 12L, SNAPSHOT);
        verify(existing, never()).deleteOldestByMember(anyLong(), anyInt());
    }

    @Test
    void read_is_anonymous_by_token_and_revoked_or_malformed_tokens_read_as_gone() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.selectSnapshotJsonByToken("0123456789abcdef0123456789abcdef"))
                .thenReturn("{\"ts\":1699999200000,\"routeName\":\"外滩\",\"track\":[{\"lat\":31.2304567,\"lng\":121.4737123}]}");
        when(mapper.selectVersionByToken("0123456789abcdef0123456789abcdef")).thenReturn(3);
        ApiRoamShareController anonymous = controller(0, mapper);
        AjaxResult ok = anonymous.read("0123456789abcdef0123456789abcdef");
        assertEquals(200, ok.get("code"));
        JSONObject shared = (JSONObject) ok.get("data");
        assertEquals("外滩", shared.getString("routeName"));
        assertEquals("31.230", shared.getJSONArray("track").getJSONObject(0).getBigDecimal("lat").toPlainString(),
                "存量精确坐标在匿名读取时也必须量化");
        assertEquals(3, shared.getIntValue("version"), "读侧带快照版本");
        assertEquals("self_reported", shared.getString("memoryKind"), "分享内容标为自报回忆,不是履约凭证");

        AjaxResult gone = anonymous.read("fedcba9876543210fedcba9876543210");
        assertEquals(500, gone.get("code"));
        assertEquals(ApiRoamShareController.GONE, gone.get("msg"));
        assertEquals(500, anonymous.read("1 or 1=1").get("code"));
        verify(mapper, never()).selectSnapshotJsonByToken("1 or 1=1");
    }

    @Test
    void revoke_deletes_only_the_callers_snapshots() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.deleteByMember(9L)).thenReturn(3);
        AjaxResult r = controller(9, mapper).revoke();
        assertEquals(200, r.get("code"));
        assertEquals(3, ((Map<?, ?>) r.get("data")).get("revoked"));
        assertEquals(500, controller(0, mapper).revoke().get("code"));
        verify(mapper, times(1)).deleteByMember(anyLong());
    }

    private static final String TOKEN_A = "0123456789abcdef0123456789abcdef";

    @Test
    void revokeOne_onlyDeletesCallersOwnTokenAndOthersTokensAreRejected() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.deleteByMemberToken(9L, TOKEN_A)).thenReturn(1);
        AjaxResult mine = controller(9, mapper).revoke(TOKEN_A);
        assertEquals(200, mine.get("code"));
        assertEquals("one", ((Map<?, ?>) mine.get("data")).get("scope"));

        // 他人令牌:WHERE member_id 不匹配 → 删 0 行 → 明确拒绝
        AjaxResult others = controller(10, mapper).revoke(TOKEN_A);
        assertEquals(500, others.get("code"));
        assertEquals(ApiRoamShareController.NOT_OWNED, others.get("msg"));
        assertEquals(500, controller(9, mapper).revoke("1 or 1=1").get("code"));
        assertEquals(500, controller(0, mapper).revoke(TOKEN_A).get("code"), "未登录不能撤回");
        verify(mapper, never()).deleteByMember(anyLong());
        verify(mapper, never()).deleteByMemberToken(anyLong(), eq("1 or 1=1"));
    }

    @Test
    void revokeAll_withoutTokenKeepsLegacyScope() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.deleteByMember(9L)).thenReturn(2);
        AjaxResult all = controller(9, mapper).revoke("");
        assertEquals("all", ((Map<?, ?>) all.get("data")).get("scope"));
        verify(mapper, never()).deleteByMemberToken(anyLong(), anyString());
    }

    @Test
    void read_afterRevokeBetweenQueriesIsGone() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.selectSnapshotJsonByToken(TOKEN_A)).thenReturn("{\"ts\":1699999200000}");
        when(mapper.selectVersionByToken(TOKEN_A)).thenReturn(null);
        AjaxResult r = controller(0, mapper).read(TOKEN_A);
        assertEquals(500, r.get("code"));
        assertEquals(ApiRoamShareController.GONE, r.get("msg"));
    }

    @Test
    void publish_withStaleExpectedVersionConflictsWithoutOverwriting() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.updateSnapshotIfVersion(eq(9L), eq(5L), anyString(), eq(2))).thenReturn(0);
        when(mapper.selectVersionByMemberTs(9L, 5L)).thenReturn(3);
        AjaxResult r = controller(9, mapper).publish(5L, SNAPSHOT, 2);
        assertEquals(500, r.get("code"));
        assertEquals(ApiRoamShareController.CONFLICT, r.get("msg"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) r.get("data")).get("conflict"));
        assertEquals(3, ((Map<?, ?>) r.get("data")).get("version"));
        verify(mapper, never()).upsertSnapshot(anyString(), anyLong(), anyLong(), anyString());

        when(mapper.updateSnapshotIfVersion(eq(9L), eq(5L), anyString(), eq(3))).thenReturn(1);
        when(mapper.selectTokenByMemberTs(9L, 5L)).thenReturn(TOKEN_A);
        when(mapper.selectVersionByMemberTs(9L, 5L)).thenReturn(4);
        AjaxResult ok = controller(9, mapper).publish(5L, SNAPSHOT, 3);
        assertEquals(200, ok.get("code"));
        assertEquals(TOKEN_A, ((Map<?, ?>) ok.get("data")).get("token"));
        assertEquals(4, ((Map<?, ?>) ok.get("data")).get("version"));
        assertEquals(500, controller(9, mapper).publish(5L, SNAPSHOT, 0).get("code"), "非法版本");
    }

    @Test
    void publish_keepsRedactionDeclarationButNeverZoneCoordinatesAndRejectsUnknownVersion() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.selectTokenByMemberTs(9L, 7L)).thenReturn(null, TOKEN_A);
        ApiRoamShareController c = controller(9, mapper);
        String ok = "{\"redaction\":{\"v\":1,\"clip\":true,\"pct\":10,\"zones\":2,\"home\":{\"lat\":31.2,\"lng\":121.4}}}";
        assertEquals(200, c.publish(7L, ok).get("code"));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(mapper).upsertSnapshot(anyString(), eq(9L), eq(7L), json.capture());
        JSONObject redaction = JSON.parseObject(json.getValue()).getJSONObject("redaction");
        assertEquals(2, redaction.getIntValue("zones"));
        assertFalse(redaction.containsKey("home"), "遮挡区域坐标不得落库");

        assertEquals(500, c.publish(7L, "{\"redaction\":{\"v\":2,\"clip\":true,\"pct\":10,\"zones\":0}}").get("code"),
                "服务端不认识的去敏版本整份拒绝");
        assertEquals(500, c.publish(7L, "{\"redaction\":{\"v\":1,\"clip\":true,\"pct\":10,\"zones\":[{\"lat\":1,\"lng\":1}]}}").get("code"),
                "区域只能声明数量,不能夹带坐标");
        assertEquals(500, c.publish(7L, "{\"redaction\":{\"v\":1,\"clip\":true,\"pct\":41,\"zones\":0}}").get("code"));
    }
}

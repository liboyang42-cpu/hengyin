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
        ReflectionTestUtils.setField(c, "snapshotService", new RoamShareSnapshotService(mapper, members));
        return c;
    }

    private static final String SNAPSHOT = "{\"routeName\":\"外滩等3处足迹回看\",\"time\":\"42:05\",\"distance\":3.2,"
            + "\"explorePct\":12,\"shops\":2,\"medal\":\"城市点亮 50 格\",\"secretHome\":\"不该留下\","
            + "\"track\":[{\"lat\":31.2304567,\"lng\":121.4737123,\"acc\":5},{\"lat\":-90,\"lng\":-180}],"
            + "\"pois\":[{\"name\":\"外滩\",\"cat\":\"landmark\",\"catLabel\":\"地标\",\"lat\":90,\"lng\":180,\"id\":77}]}";

    @Test
    void publish_normalizes_whitelisted_fields_and_rounds_coordinates() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.selectTokenByMemberTs(9L, 1700000000000L)).thenReturn(null, "0123456789abcdef0123456789abcdef");
        AjaxResult r = controller(9, mapper).publish(1700000000000L, SNAPSHOT);
        assertEquals(200, r.get("code"));
        assertEquals("0123456789abcdef0123456789abcdef", ((Map<?, ?>) r.get("data")).get("token"));

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(mapper).upsertSnapshot(token.capture(), eq(9L), eq(1700000000000L), json.capture());
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

        AjaxResult first = c.publish(1700000000000L, SNAPSHOT);
        AjaxResult second = c.publish(1700000000000L, SNAPSHOT);

        assertEquals(stableToken, ((Map<?, ?>) first.get("data")).get("token"));
        assertEquals(stableToken, ((Map<?, ?>) second.get("data")).get("token"));
        ArgumentCaptor<String> snapshots = ArgumentCaptor.forClass(String.class);
        verify(mapper, times(2)).upsertSnapshot(anyString(), eq(9L), eq(1700000000000L), snapshots.capture());
        assertEquals(snapshots.getAllValues().get(0), snapshots.getAllValues().get(1));
        verify(mapper, times(1)).countByMember(9L);
    }

    @Test
    void publish_rejects_bad_shapes_without_writing() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        ApiRoamShareController c = controller(9, mapper);
        assertEquals(500, c.publish(1L, "{\"track\":[{\"lat\":91,\"lng\":1}]}").get("code"), "越界坐标");
        assertEquals(500, c.publish(1L, "{\"track\":[{\"lat\":{\"x\":1},\"lng\":1}]}").get("code"), "嵌套对象冒充坐标");
        assertEquals(500, c.publish(1L, "{\"routeName\":{\"a\":1}}").get("code"), "非字符串文本");
        assertEquals(500, c.publish(1L, "{\"distance\":-1}").get("code"), "负距离");
        assertEquals(500, c.publish(1L, "not json").get("code"));
        assertEquals(500, c.publish(0L, SNAPSHOT).get("code"), "没有足迹 ts");
        StringBuilder big = new StringBuilder("{\"track\":[");
        for (int i = 0; i <= ApiRoamShareController.MAX_TRACK_POINTS; i++) big.append(i == 0 ? "" : ",").append("{\"lat\":1,\"lng\":1}");
        assertEquals(500, c.publish(1L, big.append("]}").toString()).get("code"), "轨迹点数上限");
        assertEquals(500, controller(0, mapper).publish(1L, SNAPSHOT).get("code"), "未登录不能发布");
        verify(mapper, never()).upsertSnapshot(anyString(), anyLong(), anyLong(), anyString());
    }

    @Test
    void publish_evicts_oldest_only_when_a_new_footprint_would_exceed_the_cap() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.selectTokenByMemberTs(9L, 5L)).thenReturn(null, "0123456789abcdef0123456789abcdef");
        when(mapper.countByMember(9L)).thenReturn(RoamShareSnapshotService.MAX_SNAPSHOTS_PER_MEMBER);
        controller(9, mapper).publish(5L, SNAPSHOT);
        verify(mapper).deleteOldestByMember(9L, 1);

        RoamShareSnapshotMapper existing = mock(RoamShareSnapshotMapper.class);
        when(existing.selectTokenByMemberTs(9L, 5L)).thenReturn("0123456789abcdef0123456789abcdef");
        when(existing.countByMember(9L)).thenReturn(RoamShareSnapshotService.MAX_SNAPSHOTS_PER_MEMBER);
        controller(9, existing).publish(5L, SNAPSHOT);
        verify(existing, never()).deleteOldestByMember(anyLong(), anyInt());
    }

    @Test
    void read_is_anonymous_by_token_and_revoked_or_malformed_tokens_read_as_gone() {
        RoamShareSnapshotMapper mapper = mock(RoamShareSnapshotMapper.class);
        when(mapper.selectSnapshotJsonByToken("0123456789abcdef0123456789abcdef"))
                .thenReturn("{\"ts\":1699999200000,\"routeName\":\"外滩\",\"track\":[{\"lat\":31.2304567,\"lng\":121.4737123}]}");
        ApiRoamShareController anonymous = controller(0, mapper);
        AjaxResult ok = anonymous.read("0123456789abcdef0123456789abcdef");
        assertEquals(200, ok.get("code"));
        JSONObject shared = (JSONObject) ok.get("data");
        assertEquals("外滩", shared.getString("routeName"));
        assertEquals("31.230", shared.getJSONArray("track").getJSONObject(0).getBigDecimal("lat").toPlainString(),
                "存量精确坐标在匿名读取时也必须量化");

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
}

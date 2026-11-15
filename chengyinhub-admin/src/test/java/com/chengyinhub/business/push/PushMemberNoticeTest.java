package com.chengyinhub.business.push;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.mapper.push.PushMapper;

/** R11 growth-loops: single-member notices over the links-push fan-out. */
class PushMemberNoticeTest
{
    private PushMapper mapper;
    private final List<String> bodies = new ArrayList<>();
    private PushFanoutService service;

    @BeforeEach
    void setUp() throws Exception
    {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        mapper = mock(PushMapper.class);
        ApnsTransport transport = (host, path, headers, body) -> {
            bodies.add(new String(body, StandardCharsets.UTF_8));
            return new ApnsTransport.ApnsResponse(200, null);
        };
        service = new PushFanoutService(mapper, new ApnsSender("K", "T", pem, "com.chengyinhub.chengyinApp", "sandbox", mapper, transport, () -> 1L));
        when(mapper.selectEnabledDevices(anyCollection(), anyString())).thenReturn(List.of(
                new PushDevice(1L, 5L, "aa".repeat(32), "SANDBOX", "en-US"),
                new PushDevice(2L, 5L, "bb".repeat(32), "SANDBOX", "zh-Hans")));
    }

    private static MemberNotice notice()
    {
        return new MemberNotice(PushMessage.KIND_GROWTH, "winback", "/pages/roam/index", "中文标题", "中文内容", "Title", "Body", 3600);
    }

    @Test
    void sendsEachDeviceInItsLanguageWithGrowthKind()
    {
        assertEquals(2, service.notifyMemberNow(5L, notice()));
        JSONObject en = JSON.parseObject(bodies.get(0));
        JSONObject zh = JSON.parseObject(bodies.get(1));
        assertEquals("growth", en.getString("kind"));
        assertEquals("/pages/roam/index", en.getString("page"));
        assertEquals("Title", en.getJSONObject("aps").getJSONObject("alert").getString("title"));
        assertEquals("中文标题", zh.getJSONObject("aps").getJSONObject("alert").getString("title"));
        assertFalse(en.containsKey("conversation_id"));
        assertTrue(service.hasPushableDevice(5L));
    }

    @Test
    void accountNotificationsOffBlocks()
    {
        when(mapper.selectPref(5L, 4, 5L)).thenReturn(Map.of("level", 2));
        assertEquals(0, service.notifyMemberNow(5L, notice()));
        assertTrue(bodies.isEmpty());
    }

    @Test
    void afterCommitCollapsesRepeatsOnTheSameThread()
    {
        MemberNotice city = new MemberNotice(PushMessage.KIND_CITY, "city-x", "/pages/roam/index", "a", "b", "c", "d", 60);
        service.notifyMemberAfterCommit(5L, city);
        service.notifyMemberAfterCommit(5L, city);
        assertEquals(2, bodies.size()); // two devices, one notice
        assertEquals("city", JSON.parseObject(bodies.get(0)).getString("kind"));
    }
}

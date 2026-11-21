package com.chengyinhub.business.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.mapper.push.PushMapper;

class ApnsSenderTest
{
    private static final String TOKEN = "a".repeat(64);
    private KeyPair keys;
    private String pemWithEscapes;
    private PushMapper mapper;
    private FakeTransport transport;
    private AtomicLong now;

    record Call(String host, String path, Map<String, String> headers, String body) { }

    static class FakeTransport implements ApnsTransport
    {
        final List<Call> calls = new ArrayList<>();
        ApnsResponse next = new ApnsResponse(200, null);

        @Override
        public ApnsResponse post(String host, String path, Map<String, String> headers, byte[] body)
        {
            calls.add(new Call(host, path, Map.copyOf(headers), new String(body, StandardCharsets.UTF_8)));
            return next;
        }
    }

    @BeforeEach
    void setUp() throws Exception
    {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keys = generator.generateKeyPair();
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPrivate().getEncoded());
        // 环境变量里常见的写法：真实换行被写成字面量 \n
        pemWithEscapes = ("-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----").replace("\n", "\\n");
        mapper = mock(PushMapper.class);
        transport = new FakeTransport();
        now = new AtomicLong(1_800_000_000L);
    }

    private ApnsSender sender(String env)
    {
        return new ApnsSender("KEY123ABCD", "9VHY8N755R", pemWithEscapes, "com.chengyinhub.chengyinApp", env, mapper, transport, now::get);
    }

    private static List<PushDevice> device(String env)
    {
        return List.of(new PushDevice(1L, 42L, TOKEN, env, "zh-Hans"));
    }

    @Test
    void jwtHasEs256HeaderClaimsAndVerifiableSignature() throws Exception
    {
        ApnsSender sender = sender("sandbox");
        assertTrue(sender.configured());
        String jwt = sender.jwt();
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        JSONObject header = JSON.parseObject(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        JSONObject claims = JSON.parseObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        assertEquals("ES256", header.getString("alg"));
        assertEquals("KEY123ABCD", header.getString("kid"));
        assertEquals("9VHY8N755R", claims.getString("iss"));
        assertEquals(1_800_000_000L, claims.getLongValue("iat"));
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        assertEquals(64, signature.length, "JWS ES256 签名是 r||s 64 字节");
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(signature));
    }

    @Test
    void jwtIsCachedAndRefreshedAfterFiftyMinutes()
    {
        ApnsSender sender = sender("sandbox");
        String first = sender.jwt();
        now.addAndGet(49 * 60);
        assertEquals(first, sender.jwt());
        now.addAndGet(60);
        assertNotEquals(first, sender.jwt());
    }

    @Test
    void sendsAlertHeadersToSandboxHost()
    {
        ApnsSender sender = sender("sandbox");
        assertEquals(1, sender.send(device("SANDBOX"), "{\"aps\":{}}", "chat-9", 1_800_086_400L));
        Call call = transport.calls.get(0);
        assertEquals("api.sandbox.push.apple.com", call.host());
        assertEquals("/3/device/" + TOKEN, call.path());
        assertTrue(call.headers().get("authorization").startsWith("bearer "));
        assertEquals("com.chengyinhub.chengyinApp", call.headers().get("apns-topic"));
        assertEquals("alert", call.headers().get("apns-push-type"));
        assertEquals("10", call.headers().get("apns-priority"));
        assertEquals("chat-9", call.headers().get("apns-collapse-id"));
        assertEquals("1800086400", call.headers().get("apns-expiration"));
        assertEquals("{\"aps\":{}}", call.body());
    }

    @Test
    void productionUsesProductionHostAndSkipsSandboxDevices()
    {
        ApnsSender sender = sender("production");
        assertEquals(0, sender.send(device("SANDBOX"), "{}", null, 0));
        assertTrue(transport.calls.isEmpty());
        sender.send(device("PRODUCTION"), "{}", null, 0);
        assertEquals("api.push.apple.com", transport.calls.get(0).host());
        assertFalse(transport.calls.get(0).headers().containsKey("apns-collapse-id"));
    }

    @Test
    void goneOrBadDeviceTokenDisablesTheToken()
    {
        ApnsSender sender = sender("sandbox");
        transport.next = new ApnsTransport.ApnsResponse(410, "Unregistered");
        assertEquals(0, sender.send(device("SANDBOX"), "{}", null, 0));
        verify(mapper).disableToken(TOKEN, "SANDBOX");

        PushMapper second = mock(PushMapper.class);
        ApnsSender other = new ApnsSender("KEY123ABCD", "9VHY8N755R", pemWithEscapes, "t.t", "sandbox", second, transport, now::get);
        for (String reason : List.of("BadDeviceToken", "DeviceTokenNotForTopic"))
        {
            transport.next = new ApnsTransport.ApnsResponse(400, reason);
            other.send(device("SANDBOX"), "{}", null, 0);
        }
        verify(second, org.mockito.Mockito.times(2)).disableToken(TOKEN, "SANDBOX");
    }

    @Test
    void otherFailuresKeepTheToken()
    {
        ApnsSender sender = sender("sandbox");
        transport.next = new ApnsTransport.ApnsResponse(400, "PayloadTooLarge");
        sender.send(device("SANDBOX"), "{}", null, 0);
        transport.next = new ApnsTransport.ApnsResponse(429, "TooManyRequests");
        sender.send(device("SANDBOX"), "{}", null, 0);
        transport.next = new ApnsTransport.ApnsResponse(403, "ExpiredProviderToken");
        sender.send(device("SANDBOX"), "{}", null, 0);
        verify(mapper, never()).disableToken(any(), any());
    }

    @Test
    void missingConfigurationIsANoOp()
    {
        ApnsSender empty = new ApnsSender("", "", "", "", "", mapper, transport, now::get);
        assertFalse(empty.configured());
        assertEquals(0, empty.send(device("SANDBOX"), "{}", null, 0));
        ApnsSender badEnv = new ApnsSender("K", "T", pemWithEscapes, "t.t", "staging", mapper, transport, now::get);
        assertFalse(badEnv.configured());
        ApnsSender badKey = new ApnsSender("K", "T", "-----BEGIN PRIVATE KEY-----\\nnotakey\\n-----END PRIVATE KEY-----", "t.t", "sandbox",
                mapper, transport, now::get);
        assertFalse(badKey.configured());
        assertTrue(transport.calls.isEmpty());
        verify(mapper, never()).disableToken(any(), any());
    }

    @Test
    void maskNeverPrintsTheWholeToken()
    {
        String masked = ApnsSender.mask(TOKEN);
        assertFalse(masked.contains(TOKEN));
        assertTrue(masked.startsWith("aaaaaa"));
    }

    @Test
    void reasonIsParsedFromApnsErrorBody()
    {
        assertEquals("BadDeviceToken", JdkApnsTransport.reason("{\"reason\":\"BadDeviceToken\"}"));
        assertEquals(null, JdkApnsTransport.reason(""));
    }
}

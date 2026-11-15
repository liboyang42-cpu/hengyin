package com.chengyinhub.business.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chengyinhub.business.mapper.push.PushMapper;
import com.chengyinhub.common.exception.ServiceException;

class PushDeviceServiceTest
{
    private static final String TOKEN = "ABCDEF0123456789abcdef0123456789ABCDEF0123456789abcdef0123456789";
    private PushMapper mapper;
    private PushDeviceService service;

    @BeforeEach
    void setUp()
    {
        mapper = mock(PushMapper.class);
        service = new PushDeviceService(mapper);
    }

    private Map<String, Object> body()
    {
        Map<String, Object> b = new HashMap<>();
        b.put("platform", "IOS");
        b.put("token", TOKEN);
        b.put("environment", "SANDBOX");
        b.put("bundleId", "com.chengyinhub.chengyinApp");
        b.put("locale", "zh-Hans");
        return b;
    }

    @Test
    void registerUpsertsLowercaseTokenAndReturnsDeviceId()
    {
        when(mapper.selectDeviceId(TOKEN.toLowerCase(), "SANDBOX")).thenReturn(7L);
        Map<String, Object> out = service.register(42L, body());
        assertEquals(7L, out.get("deviceId"));
        assertEquals(true, out.get("enabled"));
        verify(mapper).upsertDevice(42L, "IOS", TOKEN.toLowerCase(), "SANDBOX", "com.chengyinhub.chengyinApp", "zh-Hans");
    }

    @Test
    void localeIsOptional()
    {
        Map<String, Object> b = body();
        b.remove("locale");
        when(mapper.selectDeviceId(anyString(), anyString())).thenReturn(1L);
        service.register(42L, b);
        verify(mapper).upsertDevice(42L, "IOS", TOKEN.toLowerCase(), "SANDBOX", "com.chengyinhub.chengyinApp", null);
    }

    @Test
    void registerRejectsInvalidFields()
    {
        assertBad(with("platform", "ANDROID"));
        assertBad(with("platform", null));
        assertBad(with("token", "abc"));
        assertBad(with("token", "zz" + TOKEN.substring(2)));
        assertBad(with("token", TOKEN + "0"));            // odd length
        assertBad(with("token", TOKEN.repeat(4)));        // > 200
        assertBad(with("token", 12345));
        assertBad(with("environment", "sandbox"));
        assertBad(with("environment", "DEV"));
        assertBad(with("bundleId", "nodots"));
        assertBad(with("bundleId", "com.example/../x"));
        assertBad(with("locale", "zh Hans"));
        assertBad(with("locale", "x".repeat(40)));
        Map<String, Object> extra = body();
        extra.put("memberId", 1);
        assertBad(extra);
        assertThrows(ServiceException.class, () -> service.register(42L, null));
        verify(mapper, never()).upsertDevice(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void registerRequiresSignedInMember()
    {
        ServiceException e = assertThrows(ServiceException.class, () -> service.register(null, body()));
        assertEquals(Integer.valueOf(401), e.getCode());
    }

    @Test
    void unregisterOnlyDisablesTheCallersTokenAndIsIdempotent()
    {
        when(mapper.disableOwnedToken(42L, TOKEN.toLowerCase())).thenReturn(0);
        Map<String, Object> out = service.unregister(42L, Map.of("token", TOKEN));
        assertEquals(false, out.get("enabled"));
        verify(mapper).disableOwnedToken(42L, TOKEN.toLowerCase());
        assertThrows(ServiceException.class, () -> service.unregister(42L, Map.of("token", "nothex")));
        assertThrows(ServiceException.class, () -> service.unregister(42L, Map.of("token", TOKEN, "environment", "SANDBOX")));
    }

    private Map<String, Object> with(String key, Object value)
    {
        Map<String, Object> b = body();
        b.put(key, value);
        return b;
    }

    private void assertBad(Map<String, Object> b)
    {
        ServiceException e = assertThrows(ServiceException.class, () -> service.register(42L, b), String.valueOf(b));
        assertEquals(Integer.valueOf(400), e.getCode());
    }
}

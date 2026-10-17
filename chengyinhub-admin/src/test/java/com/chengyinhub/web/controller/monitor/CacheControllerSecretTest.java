package com.chengyinhub.web.controller.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.domain.SysCache;

/** 缓存监控不能成为绕过参数掩码、读取任意 Redis 值（短信验证码、会话）的旁路。 */
class CacheControllerSecretTest
{
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> redis = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final CacheController controller = new CacheController();

    CacheControllerSecretTest()
    {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(i -> "raw:" + i.getArgument(0));
        ReflectionTestUtils.setField(controller, "redisTemplate", redis);
    }

    private Object value(String name, String key)
    {
        AjaxResult result = controller.getCacheValue(name, key);
        Object data = result.get(AjaxResult.DATA_TAG);
        return data == null ? null : ((SysCache) data).getCacheValue();
    }

    @Test
    void sensitiveConfigSessionAndCaptchaValuesAreMasked()
    {
        assertEquals("******", value("sys_config:", "sys_config:app.wechat.appsecret"));
        assertEquals("******", value("sys_config:", "sys_config:sys.user.initPassword"));
        assertEquals("******", value("sys_config:", "sys_config:sms.aliyun.accessKeyId"));
        assertEquals("******", value("login_tokens:", "login_tokens:abc"));
        assertEquals("******", value("captcha_codes:", "captcha_codes:abc"));
        assertEquals("raw:sys_config:sys.index.skinName", value("sys_config:", "sys_config:sys.index.skinName"));
    }

    @Test
    void keysOutsideListedCacheGroupsCannotBeReadEnumeratedOrDeleted()
    {
        assertEquals(500, controller.getCacheValue("sys_config:", "sms_13800000000_code").get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.getCacheValue("sms_", "sms_13800000000_code").get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.getCacheKeys("").get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.getCacheKeys("sms_").get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.clearCacheName("").get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.clearCacheKey("app_password_fail_13800000000").get(AjaxResult.CODE_TAG));
        verify(values, never()).get(anyString());
        verify(redis, never()).keys(anyString());
        verify(redis, never()).delete(anyString());
        verify(redis, never()).delete(org.mockito.ArgumentMatchers.<Collection<String>>any());

        assertEquals(200, controller.clearCacheKey("rate_limit:x").get(AjaxResult.CODE_TAG));
        verify(redis).delete("rate_limit:x");
    }
}

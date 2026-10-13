package com.chengyinhub.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserSafeMessageTest
{
    @Test
    void stackFrameWithoutProjectPackageMustStillBeMasked()
    {
        assertEquals(UserSafeMessage.GENERIC, UserSafeMessage.of("at com.acme.Foo.bar"));
    }

    @Test
    void normalBusinessMessageRemainsVisible()
    {
        assertEquals("活动已经结束", UserSafeMessage.of("活动已经结束"));
    }

    @Test
    void identityAndSessionIdentifiersMustBeMasked()
    {
        assertEquals(UserSafeMessage.GENERIC, UserSafeMessage.of("openid=oABC123456789"));
        assertEquals(UserSafeMessage.GENERIC, UserSafeMessage.of("unionid: oUNION123456789"));
        assertEquals(UserSafeMessage.GENERIC, UserSafeMessage.of("cookie=sessionid=abc123456789"));
        assertEquals(UserSafeMessage.GENERIC, UserSafeMessage.of("session=abc123456789"));
    }
}

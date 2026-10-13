package com.chengyinhub.common.core.domain;

import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.utils.UserSafeMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AjaxResultSafeMessageTest
{
    @Test
    void errorFactoryMasksTechnicalExceptionDetails()
    {
        AjaxResult result = AjaxResult.error("暂不可联系: java.lang.IllegalStateException at com.acme.Client.call");

        assertEquals(UserSafeMessage.GENERIC, result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void directErrorConstructorMasksRequestPaths()
    {
        AjaxResult result = new AjaxResult(HttpStatus.ERROR, "upstream /api/secret failed");

        assertEquals(UserSafeMessage.GENERIC, result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void errorBoundaryMasksCredentialsAndBearerTokens()
    {
        assertEquals(UserSafeMessage.GENERIC,
                AjaxResult.error("token=wx-secret-value").get(AjaxResult.MSG_TAG));
        assertEquals(UserSafeMessage.GENERIC,
                AjaxResult.error("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature")
                        .get(AjaxResult.MSG_TAG));
        assertEquals(UserSafeMessage.GENERIC,
                AjaxResult.error("password: correct-horse-battery-staple").get(AjaxResult.MSG_TAG));
    }

    @Test
    void businessErrorsRemainActionable()
    {
        AjaxResult result = AjaxResult.error("活动已经结束");

        assertEquals("活动已经结束", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void successPayloadIsNotRewritten()
    {
        AjaxResult result = AjaxResult.success("/api/example");

        assertEquals("/api/example", result.get(AjaxResult.MSG_TAG));
    }
}

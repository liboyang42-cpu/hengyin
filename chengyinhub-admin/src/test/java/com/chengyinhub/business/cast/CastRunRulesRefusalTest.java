package com.chengyinhub.business.cast;

import com.chengyinhub.business.service.support.CastRunRules;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 角色 Cast v1:业务拒绝 → AjaxResult(/api/play/run/* 与 /api/topic/cast/voice 共用);带 CAST_ 错误码时放 data.errorCode,客户端据此识别「配音未开通」。 */
class CastRunRulesRefusalTest
{
    @Test
    void 配音未开通_回503与errorCode()
    {
        AjaxResult result = CastRunRules.refusal(new ServiceException("配音服务未开通", 503)
                .setDetailMessage(CastRunRules.ERR_TTS_NOT_CONFIGURED));
        assertEquals(503, result.get(AjaxResult.CODE_TAG));
        assertEquals("CAST_TTS_NOT_CONFIGURED", ((Map<?, ?>) result.get(AjaxResult.DATA_TAG)).get("errorCode"));
    }

    @Test
    void 普通拒绝_保留4xx_不带errorCode()
    {
        AjaxResult result = CastRunRules.refusal(new ServiceException("本局不存在", 404));
        assertEquals(404, result.get(AjaxResult.CODE_TAG));
        assertNull(result.get(AjaxResult.DATA_TAG));
        assertEquals(400, CastRunRules.refusal(new ServiceException("x")).get(AjaxResult.CODE_TAG));
    }
}

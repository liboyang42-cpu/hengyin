package com.chengyinhub.web.service.play;

import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 站点未解锁是正常业务拒绝:4xx 信封 + errorCode,客户端不应显示服务器错误。 */
class PlayRouteLockedEnvelopeTest {
    @Test
    void lockedStationIsABusinessRefusalNotAServerError() {
        AjaxResult locked = PlayCommandErrorContract.routeLocked("该地点尚未解锁");
        assertEquals(423, locked.get("code"));
        assertEquals("该地点尚未解锁", locked.get("msg"));
        assertEquals("ROUTE_LOCKED", locked.get("errorCode"));
        assertEquals(true, locked.get("intentNotExecuted"));
    }
}

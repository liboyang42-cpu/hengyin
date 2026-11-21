package com.chengyinhub.web.service.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ErrorCode;
import com.chengyinhub.common.exception.ErrorContract;
import org.junit.jupiter.api.Test;

/**
 * GAME-10 (x-journey-app): play refusals carry a typed refusal, so the v2 contract answers 4xx whatever the
 * wording. Before, "这次定位暂不计入,稍后再试" contained 稍后 and became a 5xx system failure in the App.
 * The old (mini-program) body stays byte-for-byte the same.
 */
class PlayRefusalTypedContractTest
{
    private static ErrorContract.Decision decide(AjaxResult r)
    {
        return ErrorContract.decide((Integer) r.get(AjaxResult.CODE_TAG), (String) r.get(AjaxResult.MSG_TAG),
                r.errorCode(), null);
    }

    @Test
    void retryableRefusalWithLaterWordingIsARefusalNotAFailure()
    {
        AjaxResult r = PlayCommandErrorContract.retryable("这次定位暂不计入,稍后再试",
                PlayCommandErrorContract.ARRIVE_LOCATION_UNTRUSTED);
        assertTrue(ErrorContract.looksLikeFailure("这次定位暂不计入,稍后再试"), "the wording alone would have read as a failure");
        ErrorContract.Decision d = decide(r);
        assertEquals(ErrorCode.REFUSED, d.errorCode());
        assertTrue(d.httpStatus() >= 400 && d.httpStatus() < 500);
        // Old body unchanged: code 500, msg, machine fields.
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("ARRIVE_LOCATION_UNTRUSTED", r.get("errorCode"));
        assertEquals(Boolean.TRUE, r.get("retryable"));
        assertNull(r.get(AjaxResult.DATA_TAG));
    }

    @Test
    void everyRefusalBuilderIsTyped()
    {
        assertEquals(ErrorCode.REFUSED, PlayCommandErrorContract.rejected("请稍后再试", "ROUTE_LOCKED").errorCode());
        assertEquals(ErrorCode.REFUSED, PlayCommandErrorContract.unresolvedEffect("m", "SENSOR_REJECTED").errorCode());
        assertEquals(ErrorCode.REFUSED, PlayCommandErrorContract.retryableUncertain("m", "ARRIVE_GUARD_THROTTLE").errorCode());
        assertEquals(ErrorCode.REFUSED, PlayCommandErrorContract.arriveFirst("先到这一站", "ARRIVE_NOT_RECORDED").errorCode());
        AjaxResult conflict = PlayCommandErrorContract.refresh("状态已变化", "INTENT_STALE_STATE");
        assertEquals(ErrorCode.CONFLICT, conflict.errorCode());
        assertEquals(409, conflict.get(AjaxResult.CODE_TAG));
        assertEquals(409, decide(conflict).httpStatus());
    }

    @Test
    void pendingIsNotARefusal()
    {
        assertNull(PlayCommandErrorContract.pending("m").errorCode());
        assertNull(PlayCommandErrorContract.unresolved("m").errorCode());
    }
}

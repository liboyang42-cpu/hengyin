package com.chengyinhub.common.core.domain;

import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * SMOKE-500-R4 G1：Controller 就地 catch 后 error(e.getMessage()) 会把参数错误/状态冲突/带码业务异常一律压成 500。
 * refusal(e) 必须还原成与全局异常处理一致的业务码，且原文案不变。
 */
class AjaxResultRefusalTest {

    @Test
    void illegalArgumentMapsTo400() {
        AjaxResult r = AjaxResult.refusal(new IllegalArgumentException("标签不存在"));
        assertEquals(HttpStatus.BAD_REQUEST, r.get(AjaxResult.CODE_TAG));
        assertEquals("标签不存在", r.get(AjaxResult.MSG_TAG));
    }

    @Test
    void illegalStateMapsTo409() {
        AjaxResult r = AjaxResult.refusal(new IllegalStateException("仅取消合作或履约完成后可重试退款"));
        assertEquals(HttpStatus.CONFLICT, r.get(AjaxResult.CODE_TAG));
        assertEquals("仅取消合作或履约完成后可重试退款", r.get(AjaxResult.MSG_TAG));
    }

    @Test
    void codedServiceExceptionKeepsItsCodeAndData() {
        ServiceException e = new ServiceException("原取消生命周期已变化，请人工核对原结果", HttpStatus.CONFLICT)
                .setData(Map.of("state", "CANCELLED"));
        AjaxResult r = AjaxResult.refusal(e);
        assertEquals(HttpStatus.CONFLICT, r.get(AjaxResult.CODE_TAG));
        assertEquals(Map.of("state", "CANCELLED"), r.get(AjaxResult.DATA_TAG));
    }

    @Test
    void uncodedServiceExceptionAndOtherRuntimeStay500() {
        AjaxResult plain = AjaxResult.refusal(new ServiceException("业务失败"));
        assertEquals(HttpStatus.ERROR, plain.get(AjaxResult.CODE_TAG));
        assertFalse(plain.containsKey(AjaxResult.DATA_TAG));
        assertEquals(HttpStatus.ERROR, AjaxResult.refusal(new RuntimeException("x")).get(AjaxResult.CODE_TAG));
    }
}

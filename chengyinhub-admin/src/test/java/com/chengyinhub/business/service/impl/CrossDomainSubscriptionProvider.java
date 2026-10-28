package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.common.utils.http.HttpUtils;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Same-package use of the existing HTTP seam; DTO/openid/template/errcode/catch remain real. */
public final class CrossDomainSubscriptionProvider extends WxSubscribeMsgServiceImpl {
    private final String loopback;
    private final AtomicInteger attempts;
    public CrossDomainSubscriptionProvider(String loopback, AtomicInteger attempts) {
        assertTrue(loopback.matches("http://127\\.0\\.0\\.1:[0-9]+/subscribe"));
        this.loopback=loopback; this.attempts=attempts;
    }
    @Override String post(String url,String body) {
        assertEquals("https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=fixture-access",url);
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        var dto=JSON.parseObject(body); assertEquals("fixture-openid",dto.getString("touser"));
        assertEquals("fixture-official-template",dto.getString("template_id"));
        assertEquals("pages/index/index",dto.getString("page"));
        assertTrue(dto.getJSONObject("data").containsKey("thing1"));
        assertTrue(dto.getJSONObject("data").containsKey("time2"));
        assertTrue(dto.getJSONObject("data").containsKey("thing3"));
        attempts.incrementAndGet();
        return HttpUtils.sendPost(loopback,body,"application/json");
    }
}

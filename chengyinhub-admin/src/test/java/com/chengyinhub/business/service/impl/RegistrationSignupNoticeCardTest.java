package com.chengyinhub.business.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CU-M-124：报名成功通知的卡面文案不许承诺卡片做不到的事。
 *
 * 没有主题可去的报名下发的是通用卡（无 cardType），小程序 onCardTap 只认 action / result / bcId，
 * 点它哪也去不了。原文案「你的报名已提交，可在此查看进度」正是这条走查读到的假承诺：
 * 把这半句加回去，下面第一条断言立即判红（本条负控即此 —— 只有删掉承诺才会绿）。
 */
class RegistrationSignupNoticeCardTest
{
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void unthemedSignupNoticeOnlyStatesTheFactItCanBackUp() throws Exception
    {
        JsonNode card = JSON.readTree(RegistrationCheckoutServiceImpl.signupNoticeExtra(null));

        assertEquals("报名成功", card.path("title").asText());
        assertEquals("你的报名已提交", card.path("sub").asText(),
                "无主题的报名卡没有可点开的进度页，副标题只能写已发生的事实");
        assertFalse(card.has("action"), "通用卡不带 action，前端就不该给出可点入口");
        assertFalse(card.has("cardType"), "无主题的报名不该冒充 signup 卡");
        assertNoPromiseOfProgress(card.toString());
    }

    @Test
    void themedSignupNoticeStillRoutesToTheTopic() throws Exception
    {
        JsonNode card = JSON.readTree(RegistrationCheckoutServiceImpl.signupNoticeExtra(88L));

        assertEquals("signup", card.path("cardType").asText());
        assertEquals(88L, card.path("topicId").asLong(),
                "挂到主题的报名照旧发可点开的 signup 卡，收口不许把它一并降成通用卡");
    }

    private static void assertNoPromiseOfProgress(String extra)
    {
        assertFalse(extra.contains("查看进度"), "卡面又出现了打不开的「查看进度」承诺");
        assertFalse(extra.contains("可在此"), "卡面又出现了无去向的「可在此」指引");
        assertTrue(extra.contains("报名已提交"), "删除假承诺时不许把真实结果一起删掉");
    }
}

package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BE-14 作者填写的国家归属校验：只接受 ISO 3166-1 alpha-2（两位大写），
 * 缺失等于 UNKNOWN（不得推断），非法值在保存入口就被拒、不静默丢弃。
 *
 * <p>这不是榜单算法测试：它守的是「发布者显式声明」这一前提——没有显式声明，
 * 榜单与发布冻结都不允许把城市 adcode / 用户位置猜成国家。
 */
class TopicLocationCountryContractTest {

    private static final String BASE_CONTRACT =
            "{\"schemaVersion\":1,\"locationSlots\":[],\"locationBindings\":[]}";

    @Test
    void countryCodeIsNormalizedToUpperCaseIsoAlpha2() {
        assertEquals("CN", TopicLocationContract.isoCountry("cn", "placeCountryCode"));
        assertEquals("US", TopicLocationContract.isoCountry(" us ", "placeCountryCode"));
        assertNull(TopicLocationContract.isoCountry("", "placeCountryCode"));
        assertNull(TopicLocationContract.isoCountry(null, "placeCountryCode"));
    }

    @Test
    void nonIsoValuesAreRejectedInsteadOfSilentlyDropped() {
        for (String bad : new String[]{"CHN", "C", "310100", "中", "C1"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TopicLocationContract.isoCountry(bad, "placeCountryCode"),
                    "非法国家码必须报错：" + bad);
        }
    }

    @Test
    void parseCarriesValidatedCountryThroughTheContract() {
        String raw = "{\"schemaVersion\":1,\"locationSlots\":[],\"locationBindings\":[],"
                + "\"placeCountryCode\":\"de\"}";
        String canonical = TopicLocationContract.canonical(raw);
        assertTrue(canonical.contains("\"placeCountryCode\":\"DE\""), "保存时应归一化为大写：" + canonical);
        assertEquals("DE", TopicLocationContract.placeCountryCode(canonical));
    }

    @Test
    void parseRejectsInvalidCountryRatherThanStoringIt() {
        String raw = "{\"schemaVersion\":1,\"locationSlots\":[],\"locationBindings\":[],"
                + "\"placeCountryCode\":\"Germany\"}";
        assertThrows(IllegalArgumentException.class, () -> TopicLocationContract.parse(raw));
    }

    @Test
    void missingCountryIsUnknownNotAMadeUpDefault() {
        assertNull(TopicLocationContract.placeCountryCode(BASE_CONTRACT), "未填写 = UNKNOWN，不得默认 CN");
        assertTrue(!TopicLocationContract.parse(BASE_CONTRACT).has("placeCountryCode"),
                "未填写就不写这个键，不能用默认值占位");
    }

    @Test
    void unreadableContractYieldsUnknownInsteadOfThrowing() {
        assertNull(TopicLocationContract.placeCountryCode("{not json"));
        assertNull(TopicLocationContract.placeCountryCode(null));
    }
}

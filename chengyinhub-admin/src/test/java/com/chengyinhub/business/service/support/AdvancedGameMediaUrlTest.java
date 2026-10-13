package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-09-24 C-01:问答 / 扫码的选填媒体地址,空串 = 没填,**摘掉再存**(空串不进库,与取景轮廓同口径)。
 * 已发出去的小程序会把没配的配图 / 音频送成 "",按「格式不对」拒掉的话,不配图的问答整份发不出去。
 * 配对:真填了一个不合法的地址(http://)仍然要拒 —— 放宽的只是「空」,不是格式。
 */
class AdvancedGameMediaUrlTest {

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();

    private static String qa(String imageUrl) {
        return "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"这座桥建于哪一年?\","
                + "\"answerText\":\"1937\",\"imageUrl\":\"" + imageUrl + "\",\"audioUrl\":\"\",\"xp\":0}}";
    }

    @Test
    void blankOptionalMediaIsNotFilledNotInvalid() {
        String stored = validator.validateAndNormalize(qa(""));
        assertNotNull(stored);
        assertTrue(!stored.contains("imageUrl") && !stored.contains("audioUrl"), "空串要摘掉再存,不进库:" + stored);
        assertNotNull(validator.validateAndNormalize("{\"schemaVersion\":1,\"scan\":{\"enabled\":true,\"kind\":\"TEXT\","
                + "\"reply\":\"欢迎\",\"audioUrl\":\"\",\"imageUrl\":\"\",\"xp\":0}}"));
    }

    @Test
    void realButInvalidUrlIsStillRejected() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(qa("http://x/y.png"))).getMessage().contains("https"));
    }
}

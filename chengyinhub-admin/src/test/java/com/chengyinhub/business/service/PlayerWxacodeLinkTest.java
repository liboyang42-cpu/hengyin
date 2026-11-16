package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** ONB-5:标准链接码编码的 scene 与小程序码相同,App 的 /door universal link 原样取回。 */
class PlayerWxacodeLinkTest {

    @Test
    void linkCarriesTheSameSceneAsTheMiniProgramCode() {
        assertEquals("https://www.chengyinhub.com/door?scene=sh.12", IPlayerWxacodeService.linkFor("sh.12"));
        assertEquals("https://www.chengyinhub.com/door?scene=tp.7", IPlayerWxacodeService.linkFor(" tp.7 "));
        assertEquals("https://www.chengyinhub.com/door?scene=abcdef0123456789abcdef0123456789",
                IPlayerWxacodeService.linkFor("abcdef0123456789abcdef0123456789"));
    }

    @Test
    void reservedCharactersAreEncodedOnce() {
        assertEquals("https://www.chengyinhub.com/door?scene=tm.a%26b%3Dc", IPlayerWxacodeService.linkFor("tm.a&b=c"));
    }

    @Test
    void emptySceneHasNoLink() {
        assertNull(IPlayerWxacodeService.linkFor(null));
        assertNull(IPlayerWxacodeService.linkFor("  "));
    }
}

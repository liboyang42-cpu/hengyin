package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialPlaceholderGuardTest {

    @Test
    void unchangedOfficialPassphraseIsRefused() {
        assertEquals(OfficialPlaceholderGuard.MESSAGE, OfficialPlaceholderGuard.check(passphraseSource(), copy(" 城市醒了 ")));
    }

    @Test
    void ownPassphrasePublishes() {
        assertNull(OfficialPlaceholderGuard.check(passphraseSource(), copy("老街灯亮了")));
    }

    @Test
    void nonOfficialOrOtherSeedSourcesAreNotGuarded() {
        CmsTemplateLibrary someoneElse = passphraseSource();
        someoneElse.setMemberId(42L);
        assertNull(OfficialPlaceholderGuard.check(someoneElse, copy("城市醒了")));
        CmsTemplateLibrary merchantGame = passphraseSource();
        merchantGame.setCreateBy("seed_merchant_game_a8_20260823");
        assertNull(OfficialPlaceholderGuard.check(merchantGame, copy("城市醒了")));
    }

    @Test
    void optionQuestionNeedsAnyOwnEdit() {
        CmsTemplateLibrary source = passphraseSource();
        source.setValidationMethod(3); source.setQuestionAnswer(null);
        source.setQuestionA("一杯招牌饮品"); source.setQuestionB("一面老墙"); source.setCorrectAnswer("A");
        CmsMemberTemplate same = copy(null);
        same.setValidationMethod(3); same.setQuestionA("一杯招牌饮品"); same.setQuestionB("一面老墙"); same.setCorrectAnswer("A");
        assertEquals(OfficialPlaceholderGuard.MESSAGE, OfficialPlaceholderGuard.check(source, same));
        same.setQuestionB("门口的老槐树");
        assertNull(OfficialPlaceholderGuard.check(source, same));
    }

    @Test
    void switchIsOnUnlessExplicitlyOff() {
        assertTrue(OfficialPlaceholderGuard.enabled(null));
        assertTrue(OfficialPlaceholderGuard.enabled("true"));
        assertFalse(OfficialPlaceholderGuard.enabled("false"));
        assertFalse(OfficialPlaceholderGuard.enabled(" off "));
    }

    private static CmsTemplateLibrary passphraseSource() {
        CmsTemplateLibrary source = new CmsTemplateLibrary();
        source.setMemberId(100000L);
        source.setCreateBy("seed_library_12packs_20260820");
        source.setValidationMethod(1);
        source.setQuestionName("向店员说出今日暗号,并在这里输入暗号");
        source.setQuestionAnswer("城市醒了");
        return source;
    }

    private static CmsMemberTemplate copy(String answer) {
        CmsMemberTemplate t = new CmsMemberTemplate();
        t.setValidationMethod(1);
        t.setQuestionName("向店员说出今日暗号,并在这里输入暗号");
        t.setQuestionAnswer(answer);
        return t;
    }
}

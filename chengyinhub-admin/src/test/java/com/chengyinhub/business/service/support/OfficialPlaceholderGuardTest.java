package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialPlaceholderGuardTest {

    @Test
    void unchangedOfficialPassphraseIsRefused() {
        assertEquals(OfficialPlaceholderGuard.MESSAGE, OfficialPlaceholderGuard.check(9001L, text(" 城市醒了 ")));
    }

    @Test
    void ownPassphraseOrNoSourcePublishes() {
        assertNull(OfficialPlaceholderGuard.check(9001L, text("老街灯亮了")));
        assertNull(OfficialPlaceholderGuard.check(null, text("城市醒了")), "自己从零写的题不归守卫管");
        assertNull(OfficialPlaceholderGuard.check(0L, text("城市醒了")));
    }

    @Test
    void optionQuestionNeedsAnyOwnEdit() {
        CmsMemberTemplate same = new CmsMemberTemplate();
        same.setValidationMethod(3);
        same.setQuestionName("这家店最早靠什么被街坊记住？");
        same.setQuestionA("一杯招牌饮品"); same.setQuestionB("一面老墙"); same.setQuestionC("一只店猫"); same.setQuestionD("一张旧唱片");
        same.setCorrectAnswer("a");
        assertEquals(OfficialPlaceholderGuard.MESSAGE, OfficialPlaceholderGuard.check(9001L, same));
        same.setQuestionB("门口的老槐树");
        assertNull(OfficialPlaceholderGuard.check(9001L, same));
    }

    @Test
    void switchIsOnUnlessExplicitlyOff() {
        assertTrue(OfficialPlaceholderGuard.enabled(null));
        assertTrue(OfficialPlaceholderGuard.enabled("true"));
        assertFalse(OfficialPlaceholderGuard.enabled("false"));
        assertFalse(OfficialPlaceholderGuard.enabled(" off "));
    }

    /** 写死的示例原文必须与种子迁移逐字一致,种子改了这里就红。 */
    @Test
    void samplesMatchTheSeedMigration() throws Exception {
        String rel = "sql/migration_seed_template_library_12packs_20260820.sql";
        Path path = Files.exists(Paths.get(rel)) ? Paths.get(rel) : Paths.get("chengyinhub-admin").resolve(rel);
        String seed = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        for (Map.Entry<String, String> e : OfficialPlaceholderGuard.TEXT_SAMPLES.entrySet()) {
            assertTrue(seed.contains("'" + e.getKey() + "', '" + e.getValue() + "'"), e.getKey());
        }
        for (Map.Entry<String, List<String>> e : OfficialPlaceholderGuard.CHOICE_SAMPLES.entrySet()) {
            List<String> o = e.getValue();
            assertTrue(seed.contains("'" + e.getKey() + "', '" + o.get(0) + "', '" + o.get(1) + "', '" + o.get(2) + "', '"
                    + o.get(3) + "',\n       '" + o.get(4) + "'"), e.getKey());
        }
    }

    private static CmsMemberTemplate text(String answer) {
        CmsMemberTemplate t = new CmsMemberTemplate();
        t.setValidationMethod(1);
        t.setQuestionName("向店员说出今日暗号,并在这里输入暗号");
        t.setQuestionAnswer(answer);
        return t;
    }
}

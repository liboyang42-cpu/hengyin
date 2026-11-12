package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CT-07 指认结案模板:正确答案只有一个真源(原 qa.options)、人物与选项一一对应、
 * 结局按服务端结论选段,并且作者不能再填第二份答案或自造提前结案的终态。
 */
class AccusationTemplateSupportTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WIN = "你指认了李师傅,证据链成立。";
    private static final String LOSE = "这次指认没能说服任何人。";

    private static ObjectNode config() {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode qa = root.putObject("qa");
        qa.put("enabled", true).put("mode", "PICK").put("title", "指认凶手").put("judge", true);
        ArrayNode options = qa.putArray("options");
        options.addObject().put("id", "o1").put("label", "张医生").put("correct", false);
        options.addObject().put("id", "o2").put("label", "李师傅").put("correct", true);
        options.addObject().put("id", "o3").put("label", "周老板").put("correct", false);
        ObjectNode accusation = root.putObject("accusation");
        accusation.put("enabled", true);
        ArrayNode suspects = accusation.putArray("suspects");
        for (String[] row : new String[][]{{"o1", "张医生"}, {"o2", "李师傅"}, {"o3", "周老板"}}) {
            suspects.addObject().put("id", row[0]).put("name", row[1])
                    .put("avatar", "/profile/upload/2026/11/08/" + row[0] + ".png");
        }
        accusation.put("winScreen", WIN).put("loseScreen", LOSE);
        return root;
    }

    @Test
    void blockAbsentMeansOrdinaryEnding() {
        ObjectNode plain = JSON.createObjectNode();
        plain.putObject("qa").put("enabled", true).put("mode", "PICK");
        AccusationTemplateSupport.validate(plain);
        assertFalse(AccusationTemplateSupport.isEnabled(plain));

        ObjectNode view = AccusationTemplateSupport.publicView(plain, null, null);
        assertFalse(view.path("enabled").asBoolean(true));
        assertEquals(0, view.path("suspects").size());
        assertNull(AccusationTemplateSupport.resultScreen(plain, true));
    }

    @Test
    void validBlockProjectsCardsButNoAnswerOrEndingScreens() {
        ObjectNode config = config();
        ((ObjectNode) config.path("accusation").path("suspects").get(0))
                .putArray("testimony").addObject().put("key", "clue.a").put("text", "他说八点就在家");

        AccusationTemplateSupport.validate(config, Set.of("clue.a"));

        ObjectNode view = AccusationTemplateSupport.publicView(config, null, key -> "clue.a".equals(key));
        assertTrue(view.path("enabled").asBoolean(false));
        assertEquals(3, view.path("suspects").size());
        // 姓名只有一个真源:原问答的选项文案
        assertEquals("李师傅", view.path("suspects").get(1).path("name").asText());
        // 已获证词下发,未获证词不下发
        assertEquals(1, view.path("suspects").get(0).path("testimony").size());
        assertFalse(view.path("suspects").get(1).has("testimony"));

        String wire = view.toString();
        assertFalse(wire.contains("correct"), wire);
        assertFalse(wire.contains("winScreen"), wire);
        assertFalse(wire.contains("loseScreen"), wire);
        assertFalse(wire.contains(WIN), wire);
        assertFalse(wire.contains(LOSE), wire);
        assertFalse(wire.contains("recordAs"), wire);

        // 正确答案由原 QA 派生,不另存一份
        assertEquals("o2", AccusationTemplateSupport.correctSuspectId(config.get("qa")));
    }

    @Test
    void derivedAnswerIsNullUnlessExactlyOneCorrectOption() {
        assertEquals("o2", AccusationTemplateSupport.correctSuspectId(config().get("qa")));

        ObjectNode twoCorrect = config();
        ((ObjectNode) twoCorrect.path("qa").path("options").get(0)).put("correct", true);
        assertNull(AccusationTemplateSupport.correctSuspectId(twoCorrect.path("qa")));

        ObjectNode none = config();
        ((ObjectNode) none.path("qa").path("options").get(1)).put("correct", false);
        assertNull(AccusationTemplateSupport.correctSuspectId(none.path("qa")));
        assertNull(AccusationTemplateSupport.correctSuspectId(null));
    }

    @Test
    void authorCannotDeclareASecondAnswerSource() {
        ObjectNode duplicate = config();
        ((ObjectNode) duplicate.path("accusation")).put("correctSuspectId", "o2");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AccusationTemplateSupport.validate(duplicate));
        assertTrue(error.getMessage().contains("不支持的配置字段"), error.getMessage());
    }

    @Test
    void endingScreenFollowsServerVerdictOnly() {
        ObjectNode config = config();
        assertEquals(WIN, AccusationTemplateSupport.resultScreen(config, true).asText());
        assertEquals(LOSE, AccusationTemplateSupport.resultScreen(config, false).asText());
    }

    @Test
    void suspectsMustMirrorQaOptionsOneToOne() {
        ObjectNode missing = config();
        ((ArrayNode) missing.path("accusation").path("suspects")).remove(2);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AccusationTemplateSupport.validate(missing)).getMessage().contains("一一对应"));

        ObjectNode unknown = config();
        ObjectNode first = (ObjectNode) unknown.path("accusation").path("suspects").get(0);
        first.put("id", "o9").put("name", "陌生人");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AccusationTemplateSupport.validate(unknown)).getMessage().contains("不是问答的选项"));

        ObjectNode duplicated = config();
        ((ObjectNode) duplicated.path("accusation").path("suspects").get(2))
                .put("id", "o1").put("name", "张医生");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AccusationTemplateSupport.validate(duplicated)).getMessage().contains("重复"));
    }

    @Test
    void suspectNameMustComeFromTheOptionLabel() {
        ObjectNode config = config();
        ((ObjectNode) config.path("accusation").path("suspects").get(0)).put("name", "李师傅");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AccusationTemplateSupport.validate(config)).getMessage().contains("必须与选项文案一致"));

        ObjectNode missingName = config();
        ((ObjectNode) missingName.path("accusation").path("suspects").get(0)).remove("name");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AccusationTemplateSupport.validate(missingName)).getMessage().contains("缺少姓名"));
    }

    @Test
    void finalQuestionStaysAnOriginalJudgedSinglePick() {
        ObjectNode unjudged = config();
        ((ObjectNode) unjudged.path("qa")).put("judge", false);
        assertThrows(IllegalArgumentException.class, () -> AccusationTemplateSupport.validate(unjudged));

        ObjectNode multi = config();
        ((ObjectNode) multi.path("qa")).put("multi", true);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AccusationTemplateSupport.validate(multi)).getMessage().contains("单选"));

        ObjectNode typed = config();
        ((ObjectNode) typed.path("qa")).put("mode", "TYPE");
        assertThrows(IllegalArgumentException.class, () -> AccusationTemplateSupport.validate(typed));

        ObjectNode twoCorrect = config();
        ((ObjectNode) twoCorrect.path("qa").path("options").get(0)).put("correct", true);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AccusationTemplateSupport.validate(twoCorrect)).getMessage().contains("只有一个正确选项"));
    }

    @Test
    void portraitNeedsASafeReference() {
        ObjectNode missing = config();
        ((ObjectNode) missing.path("accusation").path("suspects").get(0)).remove("avatar");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AccusationTemplateSupport.validate(missing)).getMessage().contains("头像"));

        ObjectNode badReference = config();
        ((ObjectNode) badReference.path("accusation").path("suspects").get(0)).put("avatar", "http://x/a.png");
        assertThrows(IllegalArgumentException.class, () -> AccusationTemplateSupport.validate(badReference));
    }

    @Test
    void testimonyKeysUseExistingClueRuleAndRegistry() {
        ObjectNode config = config();
        ((ObjectNode) config.path("accusation").path("suspects").get(0))
                .putArray("testimony").addObject().put("key", "clue.zzz").put("text", "他说八点就在家");
        AccusationTemplateSupport.validate(config);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AccusationTemplateSupport.validate(config, Set.of("clue.a")))
                .getMessage().contains("未登记的线索"));

        ObjectNode badKey = config();
        ((ObjectNode) badKey.path("accusation").path("suspects").get(0))
                .putArray("testimony").addObject().put("key", "suspect.o1").put("text", "证词");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AccusationTemplateSupport.validate(badKey))
                .getMessage().contains("证词键不受支持"));
    }

    /**
     * 原文只有 {@code earlyAccuse:{allowed,recordAs}}。这里确认三件事:
     * 不认额外的证据门槛键、不自造留痕码枚举、不替作者补一个平台默认终态。
     */
    @Test
    void earlyAccuseStaysAllowedPlusRecordAs() {
        ObjectNode withRequires = config();
        ((ObjectNode) withRequires.path("accusation")).putObject("earlyAccuse")
                .put("allowed", true).put("recordAs", "EARLY_ACCUSATION")
                .putArray("requires").addObject().put("op", "EQ").put("var", "clue.a").put("value", 1);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AccusationTemplateSupport.validate(withRequires))
                .getMessage().contains("不支持的配置字段"));

        ObjectNode missingRecord = config();
        ((ObjectNode) missingRecord.path("accusation")).putObject("earlyAccuse").put("allowed", true);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AccusationTemplateSupport.validate(missingRecord)).getMessage().contains("recordAs"));

        ObjectNode missingAllowed = config();
        ((ObjectNode) missingAllowed.path("accusation")).putObject("earlyAccuse")
                .put("recordAs", "EARLY_ACCUSATION");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AccusationTemplateSupport.validate(missingAllowed)).getMessage().contains("allowed"));

        // 作者自己写的记录名照收:平台不自造取值集合,也不强加默认
        ObjectNode authorChoice = config();
        ((ObjectNode) authorChoice.path("accusation")).putObject("earlyAccuse")
                .put("allowed", true).put("recordAs", "提前结案-作者口径");
        AccusationTemplateSupport.validate(authorChoice);

        ObjectNode projected = AccusationTemplateSupport.publicView(authorChoice, null, key -> true);
        assertTrue(projected.path("earlyAccuse").path("allowed").asBoolean(false));
        // 结束态记录是服务端写的,提交前不下发
        assertFalse(projected.path("earlyAccuse").has("recordAs"));
    }

    @Test
    void noDedicatedLengthThresholdOnScreensOrTestimony() {
        ObjectNode config = config();
        ((ObjectNode) config.path("accusation")).put("winScreen", "赢".repeat(5000)).put("loseScreen", "输".repeat(5000));
        ((ObjectNode) config.path("accusation").path("suspects").get(0))
                .put("identity", "身份".repeat(300))
                .putArray("testimony").addObject().put("key", "clue.a").put("text", "证词".repeat(500));
        AccusationTemplateSupport.validate(config);
    }

    @Test
    void endingScreensAreRequiredAndUnknownFieldsRejected() {
        ObjectNode noLose = config();
        ((ObjectNode) noLose.path("accusation")).remove("loseScreen");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AccusationTemplateSupport.validate(noLose)).getMessage().contains("错误指认的结局"));

        ObjectNode blankWin = config();
        ((ObjectNode) blankWin.path("accusation")).put("winScreen", "   ");
        assertThrows(IllegalArgumentException.class, () -> AccusationTemplateSupport.validate(blankWin));

        ObjectNode unknownField = config();
        ((ObjectNode) unknownField.path("accusation").path("suspects").get(0)).put("correct", true);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AccusationTemplateSupport.validate(unknownField))
                .getMessage().contains("不支持的配置字段"));
    }

    @Test
    void introScreensOnlyNeedToBeAnArray() {
        ObjectNode config = config();
        // 单屏内部形状原文未给:对象/文字都原样透传
        ArrayNode screens = ((ObjectNode) config.path("accusation")).putArray("introScreens");
        screens.addObject().put("title", "案发当晚");
        screens.add("纯文字引导");
        AccusationTemplateSupport.validate(config);

        ObjectNode notArray = config();
        ((ObjectNode) notArray.path("accusation")).put("introScreens", "案发当晚");
        assertThrows(IllegalArgumentException.class, () -> AccusationTemplateSupport.validate(notArray));
    }
}

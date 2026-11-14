package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 逐字猜词判定回归。用例只对应原资料写明的行为：
 * 原 17.6 实跑（{@code E____ S} 只补 {@code NDLES}、{@code NICOLASPERNELLE} 重建为 {@code NICOLAS AND PERNELLE}）、
 * M.7 的 {@code format} 预填与 L3 逐位揭示、MP-05 的别名/大小写、0.4 的重登恢复与不泄答案。
 *
 * <p>刻意不断言传统猜字母「猜中揭示全部相同位置」，也不断言 LETTER/WORD/BOTH 或本类自带次数上限：
 * 资料没有这些规则，答题次数归共用题级配置。
 */
class HangmanGameplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode config(String answer) {
        return JSON.createObjectNode().put("enabled", true).put("answer", answer);
    }

    /** 按实测样式构造 format 槽位数组：给定下标给字符，其余槽留空，分隔符留空。 */
    private static ArrayNode format(String answer, int... given) {
        ArrayNode slots = JSON.createArrayNode();
        int[] cps = answer.codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            slots.add(Character.isLetterOrDigit(cps[i]) && contains(given, i)
                    ? new String(Character.toChars(cps[i])) : "");
        }
        return slots;
    }

    private static boolean contains(int[] values, int value) {
        for (int candidate : values) if (candidate == value) return true;
        return false;
    }

    private static ObjectNode input(String value) {
        return JSON.createObjectNode().put("input", value);
    }

    private static ObjectNode slots(Object... pairs) {
        ObjectNode payload = JSON.createObjectNode();
        ObjectNode map = payload.putObject("slots");
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], (String) pairs[i + 1]);
        return payload;
    }

    // ---------- 作者保存 / 发布校验 ----------

    @Test void answerIsRequiredAndMustCarryComparableCharacters() {
        ObjectNode missing = JSON.createObjectNode().put("enabled", true);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.validate(missing))
                .getMessage().contains("答案"));
        assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.validate(config("")));
        assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.validate(config("--- ---")));
    }

    @Test void formatMustBeASlotArrayMatchingTheAnswerLength() {
        ObjectNode section = config("ENDLESS");
        section.put("format", "E____ S");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.validate(section))
                .getMessage().contains("槽位数组"), "format 必须是槽位数组，不是字符串掩码");

        section.set("format", format("ENDLESS", 0));
        ((ArrayNode) section.get("format")).remove(1);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.validate(section))
                .getMessage().contains("槽数"));
    }

    @Test void prefilledSlotMustMatchTheFrozenAnswerAtThatPosition() {
        ObjectNode section = config("ENDLESS");
        section.set("format", format("ENDLESS", 0));
        ((ArrayNode) section.get("format")).set(1, JSON.getNodeFactory().textNode("X"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.validate(section))
                .getMessage().contains("不符"));
    }

    @Test void formatMustLeaveAtLeastOneBlankSlot() {
        ObjectNode section = config("ORB");
        section.set("format", format("ORB", 0, 1, 2));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.validate(section))
                .getMessage().contains("空白槽"));
        section.set("format", format("ORB", 0, 1));
        assertDoesNotThrow(() -> HangmanGameplay.validate(section));
    }

    @Test void duplicateOrEmptyAliasIsRejected() {
        ObjectNode section = config("BOUCHERIE");
        section.putArray("aliases").add("BOUCHERIE");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.validate(section))
                .getMessage().contains("重复"));
        section.putArray("aliases").add("BOUCHERIE PARISIENNE").add("boucherie parisienne");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.validate(section))
                .getMessage().contains("重复"), "大小写不区分时别名同样重复");
        section.putArray("aliases").add("  ");
        assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.validate(section));
    }

    /** 2026-11-13 走查:单字符/数字答案「先错后对」必须能解开(错字留在槽里不能挡住后一次提交),且能让本站过关。 */
    @Test void singleDigitAnswerWrongThenRightSolves() {
        ObjectNode section = config("8");
        ObjectNode kit = HangmanGameplay.initialState(section);
        ObjectNode wrong = HangmanGameplay.judge(section, kit, JSON.createObjectNode().set("slots", JSON.createObjectNode().put("0", "3")));
        assertFalse(wrong.path("solved").asBoolean());
        assertFalse(kit.path("solved").asBoolean());
        ObjectNode right = HangmanGameplay.judge(section, kit, JSON.createObjectNode().set("slots", JSON.createObjectNode().put("0", "8")));
        assertTrue(right.path("solved").asBoolean());
        assertTrue(kit.path("solved").asBoolean());
        ObjectNode again = HangmanGameplay.judge(section, kit, JSON.createObjectNode().put("input", "8"));
        assertTrue(again.path("alreadySolved").asBoolean(), "解开之后重复提交原样回放,不报错");
    }

    @Test void singleCharacterAnswerAcceptsInputShapeAfterWrongGuess() {
        ObjectNode section = config("U");
        ObjectNode kit = HangmanGameplay.initialState(section);
        assertFalse(HangmanGameplay.judge(section, kit, JSON.createObjectNode().put("input", "x")).path("solved").asBoolean());
        assertTrue(HangmanGameplay.judge(section, kit, JSON.createObjectNode().put("input", "u")).path("solved").asBoolean());
    }

    @Test void disabledSectionIsInert() {
        ObjectNode disabled = JSON.createObjectNode().put("enabled", false);
        assertDoesNotThrow(() -> HangmanGameplay.validate(disabled));
        assertEquals("{\"enabled\":false}", HangmanGameplay.safeConfig(disabled).toString());
    }

    // ---------- 不泄答案 ----------

    @Test void safeConfigAndProjectionNeverCarryAnswerAliasesOrPrefilledCharacters() {
        String answer = "NICOLAS AND PERNELLE";
        ObjectNode section = config(answer);
        section.set("format", format(answer, 0, 8));
        section.putArray("aliases").add("NICOLAS ET PERNELLE");
        HangmanGameplay.validate(section);

        JsonNode safe = HangmanGameplay.safeConfig(section);
        assertEquals(20, safe.path("length").asInt(), "答案 20 个码点");
        assertEquals(16, safe.path("blankCount").asInt(), "20 码点减 2 分隔符减 2 预填");
        assertFalse(safe.has("answer"));
        assertFalse(safe.has("aliases"));
        assertFalse(safe.has("format"));

        JsonNode view = HangmanGameplay.publicProjection(section, HangmanGameplay.initialState(section));
        assertEquals("N______ A__ ________", view.path("display").asText());
        assertEquals("PREFILLED", view.path("slots").get(0).path("kind").asText());
        assertEquals("SEPARATOR", view.path("slots").get(7).path("kind").asText());
        assertEquals("PREFILLED", view.path("slots").get(8).path("kind").asText());
        assertEquals("BLANK", view.path("slots").get(1).path("kind").asText());
        assertFalse(view.path("slots").get(1).has("ch"), "空白槽不带字符");
        assertFalse(view.toString().contains("PERNELLE"), "投影不得出现未揭示的答案片段");
    }

    // ---------- 只补空白槽（原 17.6 实测） ----------

    @Test void playerFillsOnlyBlankSlotsAndServerRebuildsTheWord() {
        ObjectNode section = config("ENDLESS");
        section.set("format", format("ENDLESS", 0, 6));
        HangmanGameplay.validate(section);
        ObjectNode state = HangmanGameplay.initialState(section);
        assertEquals("E_____S", HangmanGameplay.publicProjection(section, state).path("display").asText());

        JsonNode view = HangmanGameplay.judge(section, state, input("NDLES"));
        assertEquals(HangmanGameplay.VERDICT_CORRECT, view.path("verdict").asText());
        assertEquals("ENDLESS", view.path("answerText").asText());
        assertEquals("ENDLESS", view.path("display").asText());
        assertTrue(view.path("solved").asBoolean());
    }

    @Test void explicitSlotSubmissionUsesAnswerIndexesAndRejectsPrefilledSlots() {
        ObjectNode section = config("ENDLESS");
        section.set("format", format("ENDLESS", 0, 6));
        HangmanGameplay.validate(section);
        ObjectNode state = HangmanGameplay.initialState(section);

        assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.judge(section, state,
                slots("0", "E", "1", "N", "2", "D", "3", "L", "4", "E", "5", "S")), "预填槽不接受提交");
        assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.judge(section, state,
                slots("9", "N")), "越界下标拒收");
        JsonNode view = HangmanGameplay.judge(section, state,
                slots("1", "N", "2", "D", "3", "L", "4", "E", "5", "S"));
        assertEquals(HangmanGameplay.VERDICT_CORRECT, view.path("verdict").asText());
    }

    @Test void partialExplicitSubmissionIsRejectedInsteadOfFillingFromTheAnswer() {
        ObjectNode section = config("ENDLESS");
        section.set("format", format("ENDLESS", 0));
        HangmanGameplay.validate(section);
        ObjectNode state = HangmanGameplay.initialState(section);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> HangmanGameplay.judge(section, state, slots("1", "N"))).getMessage().contains("空白槽"));
    }

    @Test void shortInputCannotPassAsAPartialReconstruction() {
        ObjectNode section = config("ENDLESS");
        section.set("format", format("ENDLESS", 0));
        HangmanGameplay.validate(section);
        ObjectNode state = HangmanGameplay.initialState(section);
        JsonNode view = HangmanGameplay.judge(section, state, input("NDLE"));
        assertEquals(HangmanGameplay.VERDICT_WRONG, view.path("verdict").asText(), "缺字符不可能判对");
        assertFalse(view.has("answerText"));
        assertFalse(view.path("solved").asBoolean());
    }

    @Test void wholeWordSubmissionWithSpacesOmittedIsAcceptedAndRebuilt() {
        String answer = "NICOLAS AND PERNELLE";
        ObjectNode section = config(answer);
        HangmanGameplay.validate(section);
        ObjectNode state = HangmanGameplay.initialState(section);

        JsonNode view = HangmanGameplay.judge(section, state, input("NICOLASANDPERNELLE"));
        assertEquals(HangmanGameplay.VERDICT_CORRECT, view.path("verdict").asText());
        assertEquals(answer, view.path("answerText").asText(), "服务端重建为带分隔符的原答案");
        assertEquals(20, view.path("length").asInt());
    }

    @Test void wholeWordSubmissionContradictingAPrefilledSlotIsRejected() {
        ObjectNode section = config("ENDLESS");
        section.set("format", format("ENDLESS", 0));
        HangmanGameplay.validate(section);
        ObjectNode state = HangmanGameplay.initialState(section);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> HangmanGameplay.judge(section, state, input("XNDLESS")))
                .getMessage().contains("题面已给出"));
    }

    @Test void wrongReconstructionIsReportedWrongWithoutLeakingTheAnswer() {
        ObjectNode section = config("LANES");
        section.set("format", format("LANES", 0));
        HangmanGameplay.validate(section);
        ObjectNode state = HangmanGameplay.initialState(section);
        JsonNode view = HangmanGameplay.judge(section, state, input("ANXS"));
        assertEquals(HangmanGameplay.VERDICT_WRONG, view.path("verdict").asText());
        assertFalse(view.has("answerText"));
        assertFalse(view.path("solved").asBoolean());
        assertEquals("LANXS", view.path("display").asText(), "只显示玩家自己补进去的字符");
    }

    @Test void caseInsensitiveByDefaultAndSensitiveWhenAuthorOptsIn() {
        ObjectNode loose = config("LEGEND");
        HangmanGameplay.validate(loose);
        assertEquals(HangmanGameplay.VERDICT_CORRECT,
                HangmanGameplay.judge(loose, HangmanGameplay.initialState(loose), input("legend"))
                        .path("verdict").asText());

        ObjectNode strict = config("LEGEND").put("caseSensitive", true);
        HangmanGameplay.validate(strict);
        assertEquals(HangmanGameplay.VERDICT_WRONG,
                HangmanGameplay.judge(strict, HangmanGameplay.initialState(strict), input("legend"))
                        .path("verdict").asText());
        assertEquals(HangmanGameplay.VERDICT_CORRECT,
                HangmanGameplay.judge(strict, HangmanGameplay.initialState(strict), input("LEGEND"))
                        .path("verdict").asText());
    }

    @Test void aliasOfDifferentLengthIsAcceptedAsAWholeWordCandidate() {
        ObjectNode section = config("BOUCHERIE");
        section.putArray("aliases").add("BOUCHERIE PARISIENNE");
        HangmanGameplay.validate(section);

        JsonNode alias = HangmanGameplay.judge(section, HangmanGameplay.initialState(section),
                input("boucherie parisienne"));
        assertEquals(HangmanGameplay.VERDICT_CORRECT, alias.path("verdict").asText());
        assertEquals("BOUCHERIE", alias.path("answerText").asText(), "重建结果仍是冻结答案本身");
        assertEquals(9, alias.path("length").asInt(), "别名不改变答案槽位数");

        assertEquals(HangmanGameplay.VERDICT_WRONG,
                HangmanGameplay.judge(section, HangmanGameplay.initialState(section), input("BOUCHERI"))
                        .path("verdict").asText());
    }

    // ---------- Unicode ----------

    @Test void chineseAndSupplementaryPlaneCharactersCountAsOneSlotEach() {
        ObjectNode han = config("城门失火");
        HangmanGameplay.validate(han);
        ObjectNode hanState = HangmanGameplay.initialState(han);
        assertEquals(4, HangmanGameplay.publicProjection(han, hanState).path("length").asInt());
        assertEquals("____", HangmanGameplay.publicProjection(han, hanState).path("display").asText());
        JsonNode solved = HangmanGameplay.judge(han, hanState, input("城门失火"));
        assertEquals(HangmanGameplay.VERDICT_CORRECT, solved.path("verdict").asText());
        assertEquals("城门失火", solved.path("answerText").asText());

        ObjectNode astral = config("\uD835\uDD18NIT");
        astral.set("format", format("\uD835\uDD18NIT", 0));
        HangmanGameplay.validate(astral);
        ObjectNode astralState = HangmanGameplay.initialState(astral);
        assertEquals(4, HangmanGameplay.publicProjection(astral, astralState).path("length").asInt(),
                "代理对算一个槽位");
        assertEquals("𝔘___", HangmanGameplay.publicProjection(astral, astralState).path("display").asText());
        JsonNode hit = HangmanGameplay.judge(astral, astralState, input("NIT"));
        assertEquals(HangmanGameplay.VERDICT_CORRECT, hit.path("verdict").asText());
        assertEquals("𝔘NIT", hit.path("answerText").asText());
    }

    // ---------- 重登恢复 ----------

    @Test void reloginRestoresPlayerFilledSlotsFromState() throws Exception {
        ObjectNode section = config("LEGEND");
        section.set("format", format("LEGEND", 0));
        HangmanGameplay.validate(section);
        ObjectNode state = HangmanGameplay.initialState(section);
        HangmanGameplay.judge(section, state, input("EGEND"));

        ObjectNode restored = (ObjectNode) JSON.readTree(JSON.writeValueAsString(state));
        JsonNode view = HangmanGameplay.publicProjection(section, restored);
        assertEquals("LEGEND", view.path("display").asText());
        assertTrue(view.path("solved").asBoolean());

        JsonNode again = HangmanGameplay.judge(section, restored, input("LEGEND"));
        assertEquals(HangmanGameplay.VERDICT_CORRECT, again.path("verdict").asText());
        assertTrue(again.path("alreadySolved").asBoolean(), "已解开后重提不再改状态");
    }

    @Test void partiallyFilledProgressSurvivesReloginWithoutBecomingSolved() throws Exception {
        ObjectNode section = config("ENDLESS");
        section.set("format", format("ENDLESS", 0, 6));
        HangmanGameplay.validate(section);
        ObjectNode state = HangmanGameplay.initialState(section);
        JsonNode wrong = HangmanGameplay.judge(section, state, input("NDLEX"));
        assertEquals(HangmanGameplay.VERDICT_WRONG, wrong.path("verdict").asText());
        assertEquals("ENDLEXS", wrong.path("display").asText());

        ObjectNode restored = (ObjectNode) JSON.readTree(JSON.writeValueAsString(state));
        JsonNode view = HangmanGameplay.publicProjection(section, restored);
        assertEquals("ENDLEXS", view.path("display").asText());
        assertFalse(view.path("solved").asBoolean());

        JsonNode fixed = HangmanGameplay.judge(section, restored, slots("5", "S"));
        assertEquals(HangmanGameplay.VERDICT_CORRECT, fixed.path("verdict").asText());
        assertEquals("ENDLESS", fixed.path("answerText").asText());
    }

    @Test void stateNodeKeepsOneAnswerFreeShape() throws Exception {
        ObjectNode section = config("ORB");
        HangmanGameplay.validate(section);
        ObjectNode state = HangmanGameplay.initialState(section);
        List<?> keys = new ArrayList<>(JSON.convertValue(state, Map.class).keySet());
        assertEquals(Arrays.asList("version", "filled", "solved", "lastVerdict"), keys);
        String encoded = JSON.writeValueAsString(state);
        assertFalse(encoded.contains("O"), "状态里不得带答案字符");
        assertEquals(encoded, JSON.writeValueAsString(JSON.readTree(encoded)));
    }

    // ---------- L3 提示揭示（与预填共用状态） ----------

    @Test void hintRevealSharesStateAndDoesNotSolveOrRevealOtherSlots() {
        ObjectNode section = config("ORB");
        section.set("format", format("ORB", 0));
        HangmanGameplay.validate(section);
        ObjectNode state = HangmanGameplay.initialState(section);

        JsonNode first = HangmanGameplay.revealAt(section, state, 1);
        assertFalse(first.path("alreadyRevealed").asBoolean());
        assertEquals("OR_", first.path("display").asText());
        assertFalse(first.path("solved").asBoolean(), "揭示一个槽不等于解开");

        JsonNode again = HangmanGameplay.revealAt(section, state, 1);
        assertTrue(again.path("alreadyRevealed").asBoolean());
        assertEquals("OR_", again.path("display").asText());

        assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.revealAt(section, state, 0),
                "预填槽不需要揭示");
        assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.revealAt(section, state, 3));
        assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.revealAt(section, null, 1));

        JsonNode solved = HangmanGameplay.judge(section, state, slots("2", "B"));
        assertEquals(HangmanGameplay.VERDICT_CORRECT, solved.path("verdict").asText());
        assertEquals("ORB", solved.path("answerText").asText());
    }

    @Test void missingStateIsRejectedSoCallersCannotJudgeWithoutPerRunState() {
        ObjectNode section = config("ORB");
        HangmanGameplay.validate(section);
        assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.judge(section, null, input("ORB")));
    }

    @Test void blankSubmissionIsRejectedAndChangesNothing() {
        ObjectNode section = config("ORB");
        HangmanGameplay.validate(section);
        ObjectNode state = HangmanGameplay.initialState(section);
        assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.judge(section, state, input("   ")));
        assertThrows(IllegalArgumentException.class, () -> HangmanGameplay.judge(section, state, JSON.createObjectNode()));
        assertEquals(0, state.path("filled").size());
        assertFalse(state.path("solved").asBoolean());
    }
}

package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancedGamePublicProjectionTest {

    @Test
    void backgroundMusicReachesPlayersOnlyWithItsPublicFields() throws Exception {
        String raw = "{\"schemaVersion\":1,\"backgroundMusic\":{\"title\":\"老城茶馆\","
                + "\"audioUrl\":\"https://cdn.example.com/tea.mp3\",\"loop\":false}}";
        com.fasterxml.jackson.databind.JsonNode music = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(AdvancedGamePublicProjection.safeConfigJson(raw)).path("backgroundMusic");
        assertEquals("老城茶馆", music.path("title").asText());
        assertEquals("https://cdn.example.com/tea.mp3", music.path("audioUrl").asText());
        assertFalse(music.path("loop").asBoolean());
        String none = AdvancedGamePublicProjection.safeConfigJson("{\"schemaVersion\":1}");
        assertFalse(none.contains("backgroundMusic"));
    }

    @Test
    void profileProjectionExposesMandatoryQuestionsWithoutRevealingEffects() throws Exception {
        String raw = "{\"schemaVersion\":1,\"profile\":{\"enabled\":true,"
                + "\"avatar\":{\"enabled\":true,\"required\":false},\"questions\":["
                + "{\"key\":\"name\",\"label\":\"姓名\",\"kind\":\"text\",\"required\":false},"
                + "{\"key\":\"job\",\"label\":\"职业\",\"kind\":\"pick\",\"required\":false,"
                + "\"options\":[{\"key\":\"a\",\"label\":\"甲\",\"effects\":[{\"var\":\"sys.luck\",\"op\":\"INC\",\"value\":2}]}]}]}}";
        String safe = AdvancedGamePublicProjection.safeConfigJson(raw);
        com.fasterxml.jackson.databind.JsonNode profile = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(safe).path("profile");
        for (com.fasterxml.jackson.databind.JsonNode question : profile.path("questions")) {
            assertTrue(question.path("required").asBoolean());
        }
        assertFalse(profile.path("avatar").path("required").asBoolean());
        assertFalse(safe.contains("effects"));
        assertFalse(safe.contains("sys.luck"));
    }

    @Test
    void publicNodeSummaryKeepsCapabilitiesButStripsFutureOutcomesAndWeights() {
        String source = "{\"schemaVersion\":1,"
                + "\"random\":{\"enabled\":true,\"deckName\":\"今日线索卡\",\"drawCount\":1,"
                + "\"items\":[{\"id\":\"secret\",\"weight\":99}]},"
                + "\"branch\":{\"enabled\":true,\"startStepId\":\"a\",\"steps\":[{\"id\":\"a\","
                + "\"options\":[{\"id\":\"x\",\"nextStepId\":\"ending\",\"score\":100}]}]}}";

        String safe = AdvancedGamePublicProjection.safeConfigJson(source);

        assertTrue(safe.contains("\"drawCount\":1"));
        // 卡池名是给玩家看的,要投影出去;牌堆内容仍然一件不给
        assertTrue(safe.contains("今日线索卡"));
        assertTrue(safe.contains("\"branch\":{\"enabled\":true}"));
        assertFalse(safe.contains("items"));
        assertFalse(safe.contains("weight"));
        assertFalse(safe.contains("nextStepId"));
        assertFalse(safe.contains("score"));
    }

    @Test
    void templateSecretsAlsoSanitizesAdvancedConfig() {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"random\":{\"enabled\":true,\"drawCount\":1,"
                + "\"items\":[{\"id\":\"answer\",\"weight\":1}]}}" );

        TemplateSecrets.strip(template);

        assertFalse(template.getAdvancedConfigJson().contains("answer"));
        assertFalse(template.getAdvancedConfigJson().contains("weight"));
    }

    @Test
    void playerNodesEndpointCannotPutRawAdvancedConfigIntoResponse() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("src", "main", "java", "com", "chengyinhub",
                "web", "service", "play", "PlayNodeQueryApplication.java")), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertTrue(source.contains("AdvancedGamePublicProjection .safeConfigJson(themeNodeConfig==null?tpl.getAdvancedConfigJson():themeNodeConfig.toString(), read.bargainOffered)"));
        assertFalse(source.contains("m.put(\"advancedConfigJson\", tpl.getAdvancedConfigJson())"));
        assertFalse(source.contains("m.put(\"advancedConfigJson\", themeNodeConfig.toString())"));
    }

    /**
     * ★ 这七个玩法一度只有 validator 和 action、没有投影 —— 商家配的标题、秒数、
     * 六个骰面一个都到不了客户端,而且**静默**:不报错、不抛异常,只是那段 JSON 里没有。
     *
     * <p>这条用例就是那个缺口的负控:把投影删掉它立刻红。
     */
    @Test
    void decisionAndChallengeGamesReachTheClientAtAll() {
        String safe = AdvancedGamePublicProjection.safeConfigJson(sevenGames());

        assertTrue(safe.contains("\"kicker\":\"抛一次,认结果\""), "抛硬币标题");
        assertTrue(safe.contains("\"action\":\"这杯店家请\""), "正面要做什么");
        assertTrue(safe.contains("\"action\":\"这杯你请\""), "反面要做什么");
        assertTrue(safe.contains("\"跟店员说一句今天的天气\""), "骰子第一面");
        assertTrue(safe.contains("\"diceCount\":2"));
        assertTrue(safe.contains("\"rounds\":3"), "变色就点轮数");
        assertTrue(safe.contains("\"goalMs\":320"));
        assertTrue(safe.contains("\"goal\":30"), "弹球目标次数");
        assertTrue(safe.contains("\"doneText\":\"时间到。\""), "倒计时到点文案");
        assertTrue(safe.contains("\"targetSeconds\":10.0"));
        assertTrue(safe.contains("\"toleranceMs\":300"));
    }

    /**
     * 白名单的价值全在这个默认值上:没写进 copyXxx 的字段一律不出去。
     *
     * <p>把任何一个 copy 改成"整段搬过去"(如 {@code target.set("diceRoll", source)})
     * 这条就红。没有它,投影退化成透传也不会有人发现。
     */
    @Test
    void unknownFieldsInThoseSevenAreNotPassedThrough() {
        String safe = AdvancedGamePublicProjection.safeConfigJson(sevenGames());

        assertFalse(safe.contains("内部备注"), "配置里的私货不该出现在下发里");
        assertFalse(safe.contains("merchantMemo"));
        assertFalse(safe.contains("costCents"));
    }

    /**
     * ★ 变色就点每轮等多久由服务端现随机。下发了就等于告诉客户端什么时候变色,
     * 这一屏测的不再是反应,是定时器。
     */
    @Test
    void reactionNeverShipsWhenTheScreenWillChangeColour() {
        String safe = AdvancedGamePublicProjection.safeConfigJson(sevenGames());

        assertFalse(safe.contains("waitMs"));
        assertFalse(safe.contains("schedule"));
        assertFalse(safe.contains("2100"), "那串预排的等待毫秒不该出现");
    }

    /** 关掉的玩法只下发一个 false,不带载荷 —— 不然客户端会照着画一屏没人能玩的东西。 */
    @Test
    void disabledGamesShipNothingButTheFlag() {
        String safe = AdvancedGamePublicProjection.safeConfigJson("{\"schemaVersion\":1,"
                + "\"coinFlip\":{\"enabled\":false,\"kicker\":\"别下发我\",\"heads\":{\"action\":\"也别下发我\"}},"
                + "\"diceRoll\":{\"enabled\":false,\"faces\":[\"一\",\"二\",\"三\",\"四\",\"五\",\"六\"]}}");

        assertTrue(safe.contains("\"coinFlip\":{\"enabled\":false}"));
        assertTrue(safe.contains("\"diceRoll\":{\"enabled\":false}"));
        assertFalse(safe.contains("别下发我"));
    }

    /** 弹球不限时那一档不给 seconds:给了客户端会照着画一条倒计时条,而这一档本来不催。 */
    @Test
    void untimedBallShakeCarriesNoDeadline() {
        String safe = AdvancedGamePublicProjection.safeConfigJson("{\"schemaVersion\":1,"
                + "\"ballShake\":{\"enabled\":true,\"goal\":30,\"timed\":false,\"seconds\":12}}");

        assertTrue(safe.contains("\"timed\":false"));
        assertFalse(safe.contains("\"seconds\":12"));
    }

    /**
     * V5 契约(2026-09-16 裁决收窄):requires / effects 两段整段不进公开投影;
     * check 只留 enabled / checkId / skill / tier 与 mods[].label + mods[].value。
     *
     * <p>客户端不做条件判定 —— locked / missingKeys / held / advantage 由 Encounter
     * 接口按本局状态给,条件键名是剧透,所以 mods[].when、advantageIf / disadvantageIf、
     * failCostTag 都不下发。这条用例整条链路走一遍:先过 validator,再过 projection。
     *
     * <p>负控:注释掉 safeConfigJson 里的 {@code copyCheck} 那一行,本用例立刻红。
     */
    @Test
    void v5JourneyModulesSurviveValidatorThenNarrowToNonConditionalCheck() {
        String normalized = new AdvancedGameConfigValidator().validateAndNormalize(journeyConfig());
        assertNotNull(normalized, "带三段的合法配置必须能过校验器");

        String safe = AdvancedGamePublicProjection.safeConfigJson(normalized);

        assertTrue(safe.contains("\"check\":{\"enabled\":true"), safe);
        assertTrue(safe.contains("\"checkId\":\"ask_lin\""), safe);
        assertTrue(safe.contains("\"skill\":\"察言\""), safe);
        assertTrue(safe.contains("\"tier\":\"medium\""), safe);
        assertTrue(safe.contains("\"mods\":[{\"value\":2,\"label\":\"手握签收联 071\"}]"), safe);

        assertFalse(safe.contains("\"requires\""), "requires 整段不该进公开投影:" + safe);
        assertFalse(safe.contains("\"effects\""), "effects 整段不该进公开投影:" + safe);
        assertFalse(safe.contains("\"when\""), "mods 的条件不该进公开投影:" + safe);
        assertFalse(safe.contains("advantageIf"), safe);
        assertFalse(safe.contains("disadvantageIf"), safe);
        assertFalse(safe.contains("failCostTag"), "失败代价键不该进公开投影:" + safe);
    }

    /**
     * ★ 判定结果与结算效果**不下发**:successText / failText / successEffects / failEffects
     * 是判定之后才该知道的东西,提前给等于把结果告诉玩家。它们由服务端结算回执给出。
     */
    @Test
    void v5JourneyCheckOutcomeFieldsNeverShip() {
        String normalized = new AdvancedGameConfigValidator().validateAndNormalize(journeyConfig());
        assertNotNull(normalized);

        String safe = AdvancedGamePublicProjection.safeConfigJson(normalized);

        assertFalse(safe.contains("successText"), safe);
        assertFalse(safe.contains("failText"), safe);
        assertFalse(safe.contains("successEffects"), safe);
        assertFalse(safe.contains("failEffects"), safe);
        assertFalse(safe.contains("林老板把账本推过来"), safe);
        assertFalse(safe.contains("他没听懂"), safe);
        assertFalse(safe.contains("relation.lin_trust"), safe);
    }

    /** 白名单的默认值:契约没列的字段一律不下发,哪怕是三层里任意一层多写的。 */
    @Test
    void v5JourneyModulesDropFieldsOutsideTheContract() {
        String normalized = new AdvancedGameConfigValidator().validateAndNormalize(journeyConfigWithExtras());
        assertNotNull(normalized, "多写了字段不该让校验器拒收,它只是不该下发");

        String safe = AdvancedGamePublicProjection.safeConfigJson(normalized);

        assertFalse(safe.contains("secret"), safe);
        assertFalse(safe.contains("memo"), safe);
        assertFalse(safe.contains("内部备注"), "配置里的私货不该出现在下发里:" + safe);
    }

    /** 关掉的 check 只下发 false,不带载荷。 */
    @Test
    void disabledCheckShipsNothingButTheFlag() {
        String safe = AdvancedGamePublicProjection.safeConfigJson("{\"schemaVersion\":1,"
                + "\"check\":{\"enabled\":false,\"checkId\":\"ask_lin\",\"tier\":\"medium\","
                + "\"successText\":\"别下发我\"}}");

        assertTrue(safe.contains("\"check\":{\"enabled\":false}"), safe);
        assertFalse(safe.contains("别下发我"));
    }

    // ==================== R3 三个新玩法:题面下发,答案留下 ====================

    private static String sortConfig(String answerOrder) {
        return "{\"schemaVersion\":1,\"sort\":{\"enabled\":true,\"prompt\":\"按苦味从轻到重排\","
                + "\"maxAttempts\":3,\"items\":[{\"id\":\"mocha\",\"label\":\"摩卡\"},"
                + "{\"id\":\"latte\",\"label\":\"拿铁\"},{\"id\":\"espresso\",\"label\":\"浓缩\"}],"
                + "\"answerOrder\":[" + answerOrder + "]}}";
    }

    @Test
    void sortShipsItemsButNeverTheAnswerOrder() {
        String safe = AdvancedGamePublicProjection.safeConfigJson(
                sortConfig("\"latte\",\"mocha\",\"espresso\""));

        assertTrue(safe.contains("摩卡") && safe.contains("拿铁") && safe.contains("浓缩"), safe);
        assertTrue(safe.contains("\"maxAttempts\":3"), safe);
        assertFalse(safe.contains("answerOrder"), "排序答案下发了,这一屏就只剩照着摆:" + safe);
    }

    /**
     * 换一个 answerOrder,投影必须逐字符相同 —— 这证明下发顺序只由 id 集合决定,
     * 与答案毫无关系。负控:把 displayOrder 换成"照配置顺序下发",本用例立刻红。
     */
    @Test
    void sortDisplayOrderIsIndependentOfTheAnswerOrder() {
        assertEquals(
                AdvancedGamePublicProjection.safeConfigJson(sortConfig("\"latte\",\"mocha\",\"espresso\"")),
                AdvancedGamePublicProjection.safeConfigJson(sortConfig("\"espresso\",\"latte\",\"mocha\"")));
    }

    private static String matchConfig(String pairs) {
        return "{\"schemaVersion\":1,\"match\":{\"enabled\":true,\"prompt\":\"把店和招牌连起来\","
                + "\"left\":[{\"id\":\"l1\",\"label\":\"甲\"},{\"id\":\"l2\",\"label\":\"乙\"}],"
                + "\"right\":[{\"id\":\"r1\",\"label\":\"一\"},{\"id\":\"r2\",\"label\":\"二\"}],"
                + "\"pairs\":" + pairs + "}}";
    }

    @Test
    void matchShipsBothSidesButNeverThePairs() {
        String safe = AdvancedGamePublicProjection.safeConfigJson(
                matchConfig("[[\"l1\",\"r1\"],[\"l2\",\"r2\"]]"));

        assertTrue(safe.contains("甲") && safe.contains("乙") && safe.contains("一") && safe.contains("二"), safe);
        assertFalse(safe.contains("pairs"), "连线答案下发了,这一屏就只剩照着连:" + safe);
    }

    /** 换一组 pairs,投影必须逐字符相同 —— 右侧顺序与答案无关。 */
    @Test
    void matchDisplayOrderIsIndependentOfThePairs() {
        assertEquals(
                AdvancedGamePublicProjection.safeConfigJson(matchConfig("[[\"l1\",\"r1\"],[\"l2\",\"r2\"]]")),
                AdvancedGamePublicProjection.safeConfigJson(matchConfig("[[\"l1\",\"r2\"],[\"l2\",\"r1\"]]")));
    }

    private static String classifyConfig(String answer) {
        return "{\"schemaVersion\":1,\"classify\":{\"enabled\":true,\"prompt\":\"分冷热\","
                + "\"bins\":[{\"id\":\"hot\",\"label\":\"热的\"},{\"id\":\"iced\",\"label\":\"冰的\"}],"
                + "\"items\":[{\"id\":\"americano\",\"label\":\"美式\"},{\"id\":\"frappe\",\"label\":\"星冰乐\"}],"
                + "\"answer\":" + answer + "}}";
    }

    @Test
    void classifyShipsBinsAndItemsButNeverTheAnswer() {
        String safe = AdvancedGamePublicProjection.safeConfigJson(
                classifyConfig("{\"americano\":\"hot\",\"frappe\":\"iced\"}"));

        assertTrue(safe.contains("热的") && safe.contains("冰的"), safe);
        assertTrue(safe.contains("美式") && safe.contains("星冰乐"), safe);
        assertFalse(safe.contains("\"answer\""), "分类映射下发了,答案直接给了客户端:" + safe);
    }

    /** 换一份 answer,投影必须逐字符相同 —— 投影里没有任何能反推映射的字段。 */
    @Test
    void classifyProjectionDoesNotChangeWithTheAnswer() {
        assertEquals(
                AdvancedGamePublicProjection.safeConfigJson(
                        classifyConfig("{\"americano\":\"hot\",\"frappe\":\"iced\"}")),
                AdvancedGamePublicProjection.safeConfigJson(
                        classifyConfig("{\"americano\":\"iced\",\"frappe\":\"hot\"}")));
    }

    // ==================== R16 时间轴对照题:两版题面下发,答案留下 ====================

    private static String compareConfig(String answer) {
        return "{\"schemaVersion\":1,\"compare\":{\"enabled\":true,\"prompt\":\"找出两版对不上的地方\","
                + "\"maxAttempts\":3,"
                + "\"left\":{\"label\":\"版本 A\",\"items\":["
                + "{\"id\":\"a1\",\"time\":\"14:00\",\"text\":\"取号\"},"
                + "{\"id\":\"a2\",\"time\":\"14:10\",\"text\":\"称重\"}]},"
                + "\"right\":{\"label\":\"版本 B\",\"items\":["
                + "{\"id\":\"b1\",\"time\":\"14:00\",\"text\":\"取号\"},"
                + "{\"id\":\"b2\",\"time\":\"14:15\",\"text\":\"送检\"}]},"
                + "\"answer\":" + answer + "}}";
    }

    @Test
    void compareShipsBothTimelinesButNeverTheAnswer() {
        String safe = AdvancedGamePublicProjection.safeConfigJson(compareConfig("[\"b2\"]"));

        assertTrue(safe.contains("版本 A") && safe.contains("版本 B"), safe);
        assertTrue(safe.contains("14:00") && safe.contains("称重") && safe.contains("送检"), safe);
        assertTrue(safe.contains("\"maxAttempts\":3"), safe);
        assertFalse(safe.contains("\"answer\""), "对照答案下发了,该标哪几条直接给了客户端:" + safe);
    }

    /**
     * items 顺序就是时间轴:投影必须原样保留,绝不能走 displayOrder 洗牌。
     * 负控:把 copyCompare 换成打乱(或改成 displayOrder),本用例立刻红。
     */
    @Test
    void compareKeepsTimelineOrderUnshuffled() {
        String safe = AdvancedGamePublicProjection.safeConfigJson(compareConfig("[\"b2\"]"));

        assertTrue(safe.indexOf("\"a1\"") < safe.indexOf("\"a2\""), "左侧时间轴顺序被改:" + safe);
        assertTrue(safe.indexOf("\"a2\"") < safe.indexOf("\"b1\""), "左侧条目跑到了右侧之后:" + safe);
        assertTrue(safe.indexOf("\"b1\"") < safe.indexOf("\"b2\""), "右侧时间轴顺序被改:" + safe);
    }

    /** 换一份 answer,投影必须逐字符相同 —— 投影里没有任何能反推答案的字段。 */
    @Test
    void compareProjectionDoesNotChangeWithTheAnswer() {
        assertEquals(
                AdvancedGamePublicProjection.safeConfigJson(compareConfig("[\"b2\"]")),
                AdvancedGamePublicProjection.safeConfigJson(compareConfig("[\"a2\",\"b2\"]")));
    }

    // ==================== R22-SEC C2:模板老叙事段不再投影 ====================

    /**
     * R22-SEC C2:模板上的 enter / deliver / unlock / revisit 老叙事段不再进公开投影 ——
     * 叙事真源是章节故事流,继续投影死配置会误导「模板详情 = 叙事真源」。
     *
     * <p>负控:把 copyEnter / copyDeliver / copyUnlock / copyRevisit 任一加回投影,本用例立刻红。
     */
    @Test
    void legacyNarrativeSectionsAreNotProjected() {
        String normalized = new AdvancedGameConfigValidator().validateAndNormalize(narrativeConfig());
        assertNotNull(normalized, "叙事段配置必须能过校验器");
        assertTrue(normalized.contains("推门时风铃响了一声"), "示例配置本身必须带老叙事段");
        assertTrue(normalized.contains("一杯热茶"));

        String safe = AdvancedGamePublicProjection.safeConfigJson(normalized);

        for (String section : new String[] {"\"enter\"", "\"deliver\"", "\"unlock\"", "\"revisit\"", "\"outcome\""}) {
            assertFalse(safe.contains(section), "模板老叙事段不该进公开投影:" + section + " / " + safe);
        }
        assertFalse(safe.contains("推门时风铃响了一声"), safe);
        assertFalse(safe.contains("他今天没戴眼镜"), safe);
        assertFalse(safe.contains("一杯热茶"), safe);
        assertFalse(safe.contains("偏门还亮着"), safe);
        assertFalse(safe.contains("门槛被踩平了"), safe);
        assertFalse(safe.contains("老账房的夜"), safe);
        assertFalse(safe.contains("等一个不回来的人"), safe);
    }

    /** 老配置(五段全缺)的投影形状一字不变。 */
    @Test
    void legacyConfigProjectionCarriesNoNarrativeSections() {
        String safe = AdvancedGamePublicProjection.safeConfigJson(
                "{\"schemaVersion\":1,\"timer\":{\"enabled\":true,\"durationSeconds\":60}}");

        assertFalse(safe.contains("\"enter\""));
        assertFalse(safe.contains("\"outcome\""));
        assertFalse(safe.contains("\"deliver\""));
        assertFalse(safe.contains("\"unlock\""));
        assertFalse(safe.contains("\"revisit\""));
    }

    // ==================== R22-SEC C2:brief / route 老叙事段不再投影 ====================

    /**
     * R22-SEC C2:brief / route 与其余老叙事段一并不再进公开投影(真源在章节故事流)。
     *
     * <p>负控:把 copyBrief / copyRoute 任一加回投影,本用例立刻红。
     */
    @Test
    void legacyBriefAndRouteAreNotProjected() {
        String normalized = new AdvancedGameConfigValidator().validateAndNormalize(briefConfig());
        assertNotNull(normalized, "brief / route 配置必须能过校验器");
        assertTrue(normalized.contains("账本上写着什么"), "示例配置本身必须带 brief");

        String safe = AdvancedGamePublicProjection.safeConfigJson(normalized);

        assertFalse(safe.contains("\"brief\""), safe);
        assertFalse(safe.contains("\"route\""), safe);
        assertFalse(safe.contains("账本上写着什么"), safe);
        assertFalse(safe.contains("随身带着签收联"), safe);
        assertFalse(safe.contains("约 6 分钟"), safe);
        assertFalse(safe.contains("tag.hurried"), "条件里的键名不该进公开投影:" + safe);
    }

    /**
     * 序列化扫描:带条件的叙事配置过投影后,整段 JSON 里搜不到任何叙事文本与状态键前缀。
     *
     * <p>负控:把任何一个老叙事段的 copyXxx 加回投影,对应文本立刻出现,本用例红。
     */
    @Test
    void narrativeConditionsNeverLeakStateKeyNamesIntoPublicProjection() {
        String normalized = new AdvancedGameConfigValidator().validateAndNormalize(conditionalNarrativeConfig());
        assertNotNull(normalized, "带条件的叙事配置必须能过校验器");
        for (String prefix : new String[] {"clue.", "relation.", "tag.", "counter."}) {
            assertTrue(normalized.contains(prefix), "示例配置本身必须带 " + prefix + " 条件:" + normalized);
        }

        String safe = AdvancedGamePublicProjection.safeConfigJson(normalized);

        for (String text : new String[] {"随身带着签收联", "路上慢一点", "别跑", "门槛被踩平了",
                "他认得你了", "他朝你点头"}) {
            assertFalse(safe.contains(text), "老叙事文本不该进公开投影:" + text + " / " + safe);
        }
        assertFalse(safe.contains("\"when\""), safe);
        for (String prefix : new String[] {"clue.", "relation.", "tag.", "counter."}) {
            assertFalse(safe.contains(prefix), "公开投影出现了状态键前缀 " + prefix + ":" + safe);
        }
    }

    /**
     * 全量扫描(R12 裁决):requires / effects / check / brief / revisit / outcome 六段全配满
     * 且带条件时,公开投影序列化后搜不到任何状态键前缀 —— clue. / relation. / tag. / counter. / sys.
     *
     * <p>负控一:把 requires 或 effects 任一段照拷回投影,clue. / counter. / sys. 立刻出现;
     * 负控二:把 mods[].when 拷回投影,clue. 立刻出现。两种都应当让本用例红。
     */
    @Test
    void fullJourneyConfigNeverLeaksStateKeyNamesIntoPublicProjection() {
        String normalized = new AdvancedGameConfigValidator().validateAndNormalize(fullJourneyConfig());
        assertNotNull(normalized, "六段全配满的配置必须能过校验器");
        for (String prefix : new String[] {"clue.", "relation.", "tag.", "counter.", "sys."}) {
            assertTrue(normalized.contains(prefix), "示例配置本身必须带 " + prefix + " 引用:" + normalized);
        }

        String safe = AdvancedGamePublicProjection.safeConfigJson(normalized);

        for (String prefix : new String[] {"clue.", "relation.", "tag.", "counter.", "sys."}) {
            assertFalse(safe.contains(prefix), "公开投影出现了状态键前缀 " + prefix + ":" + safe);
        }
        assertFalse(safe.contains("\"when\""), safe);
    }

    /** 老配置(两段全缺)的投影里不冒出 brief / route。 */
    @Test
    void legacyConfigProjectionCarriesNoBriefOrRoute() {
        String safe = AdvancedGamePublicProjection.safeConfigJson(
                "{\"schemaVersion\":1,\"timer\":{\"enabled\":true,\"durationSeconds\":60}}");

        assertFalse(safe.contains("\"brief\""));
        assertFalse(safe.contains("\"route\""));
    }

    private static String briefConfig() {
        return "{\"schemaVersion\":1,"
                + "\"brief\":{\"question\":\"账本上写着什么\",\"body\":\"替我去柜台取那本账\","
                + "\"carry\":[{\"text\":\"随身带着签收联\"},"
                + "{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"},\"text\":\"路上慢一点\"}]},"
                + "\"route\":{\"walk\":\"约 6 分钟\",\"todo\":\"找到柜台旁带城瘾游戏标识的展示台\"}}";
    }

    /** 两个叙事数组各配两条带键名条件的条目;四种键前缀都出现一遍。 */
    private static String conditionalNarrativeConfig() {
        return "{\"schemaVersion\":1,"
                + "\"brief\":{\"question\":\"账本上写着什么\",\"body\":\"替我去柜台取那本账\","
                + "\"carry\":[{\"text\":\"随身带着签收联\"},"
                + "{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},\"text\":\"路上慢一点\"},"
                + "{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"},\"text\":\"别跑\"}]},"
                + "\"revisit\":{\"changes\":[{\"text\":\"门槛被踩平了\"},"
                + "{\"when\":{\"var\":\"counter.visits\",\"op\":\"GTE\",\"value\":2},\"text\":\"他认得你了\"},"
                + "{\"when\":{\"var\":\"relation.arong\",\"op\":\"GTE\",\"value\":1},\"text\":\"他朝你点头\"}]}}";
    }

    /** 六段全配满、四种状态键前缀与 sys.luck 都出现一遍的全量示例。 */
    private static String fullJourneyConfig() {
        return "{\"schemaVersion\":1,"
                + "\"enter\":{\"npcId\":123,\"opener\":\"推门时风铃响了一声\","
                + "\"questions\":[{\"id\":\"night\",\"q\":\"还在等谁?\",\"a\":\"等一个不回来的人\"}]},"
                + "\"outcome\":{\"title\":\"老账房的夜\",\"npcId\":123,\"line\":\"他认得你了\","
                + "\"lineIfFail\":\"他没认出来\",\"open\":\"风停了\","
                + "\"facts\":[{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},"
                + "\"text\":\"你翻到过那一页\"}]},"
                + "\"deliver\":{\"reward\":\"一杯热茶\",\"line\":\"茶还温着\",\"claimLater\":true},"
                + "\"unlock\":{\"hook\":\"偏门还亮着\"},"
                + "\"revisit\":{\"changes\":[{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"},"
                + "\"text\":\"你上回跑着来\"}]},"
                + "\"brief\":{\"question\":\"账本上写着什么\",\"body\":\"替我去柜台取那本账\","
                + "\"carry\":[{\"when\":{\"var\":\"counter.visits\",\"op\":\"GTE\",\"value\":1},"
                + "\"text\":\"随身带着签收联\"}]},"
                + "\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":3}],"
                + "\"effects\":[{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":1},"
                + "{\"op\":\"ADD_TAG\",\"value\":\"tag.entered_once\"}],"
                + "\"check\":{\"enabled\":true,\"checkId\":\"ask_lin\",\"skill\":\"察言\",\"tier\":\"medium\","
                + "\"mods\":[{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":2},"
                + "\"value\":2,\"label\":\"手握签收联 071\"}],"
                + "\"advantageIf\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.polite\"}],"
                + "\"disadvantageIf\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"}],"
                + "\"successEffects\":[{\"op\":\"INC\",\"var\":\"relation.lin_trust\",\"value\":1}],"
                + "\"failEffects\":[{\"op\":\"ADD_TAG\",\"value\":\"tag.hurried\"}],"
                + "\"failCostTag\":\"tag.hurried\"}}";
    }

    private static String narrativeConfig() {
        return "{\"schemaVersion\":1,"
                + "\"enter\":{\"npcId\":123,\"opener\":\"推门时风铃响了一声\",\"aside\":\"他今天没戴眼镜\","
                + "\"questions\":[{\"id\":\"night\",\"q\":\"还在等谁?\",\"a\":\"等一个不回来的人\"},"
                + "{\"id\":\"ledger\",\"q\":\"账本呢?\",\"a\":\"锁在柜里\"}]},"
                + "\"outcome\":{\"title\":\"老账房的夜\",\"npcId\":123,\"line\":\"他认得你了\","
                + "\"lineIfFail\":\"他没认出来\",\"open\":\"风停了\","
                + "\"facts\":[{\"text\":\"算盘换过位置\"},"
                + "{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},\"text\":\"你翻到过那一页\"}]},"
                + "\"deliver\":{\"reward\":\"一杯热茶\",\"line\":\"茶还温着\",\"claimLater\":true},"
                + "\"unlock\":{\"hook\":\"偏门还亮着\"},"
                + "\"revisit\":{\"changes\":[{\"text\":\"门槛被踩平了\"},"
                + "{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"},\"text\":\"你上回跑着来\"}]}}";
    }

    private static String journeyConfig() {
        return "{\"schemaVersion\":1,"
                + "\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":3},"
                + "{\"op\":\"HAS_TAG\",\"value\":\"tag.entered_once\"}],"
                + "\"effects\":[{\"op\":\"INC\",\"var\":\"counter.visits\",\"value\":-1},"
                + "{\"op\":\"SET\",\"var\":\"clue.ledger_page\",\"value\":1}],"
                + "\"check\":{\"enabled\":true,\"checkId\":\"ask_lin\",\"skill\":\"察言\",\"tier\":\"medium\","
                + "\"mods\":[{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":2},"
                + "\"value\":2,\"label\":\"手握签收联 071\"}],"
                + "\"advantageIf\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.polite\"}],"
                + "\"disadvantageIf\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"}],"
                + "\"successEffects\":[{\"op\":\"INC\",\"var\":\"relation.lin_trust\",\"value\":1}],"
                + "\"failEffects\":[{\"op\":\"ADD_TAG\",\"value\":\"tag.hurried\"}],"
                + "\"successText\":\"林老板把账本推过来\",\"failText\":\"他没听懂\","
                + "\"failCostTag\":\"tag.hurried\"}}";
    }

    private static String journeyConfigWithExtras() {
        return "{\"schemaVersion\":1,"
                + "\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"EQ\",\"value\":1,\"memo\":\"内部备注\"}],"
                + "\"effects\":[{\"op\":\"SET\",\"var\":\"clue.ledger_page\",\"value\":1,\"memo\":\"内部备注\"}],"
                + "\"check\":{\"enabled\":true,\"checkId\":\"ask_lin\",\"tier\":\"easy\","
                + "\"secret\":\"内部备注\"}}";
    }

    /**
     * R22-SEC S4:投影失败不能再静默 —— 记一条 WARN,方向仍是 fail-closed(不下发,返回 null)。
     *
     * <p>负控:去掉 safeConfigJson 的 LOG.warn,本用例立刻红。
     */
    @Test
    void projectionFailureIsLoggedAsWarn() {
        Logger logger = (Logger) LoggerFactory.getLogger(AdvancedGamePublicProjection.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertNull(AdvancedGamePublicProjection.safeConfigJson("{broken"),
                    "方向不变:坏配置不下发");

            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN),
                    "投影失败必须留 WARN:" + appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }

    /** 七个玩法各配一份齐全配置,外加三处只有商家该看到的私货。 */
    private static String sevenGames() {
        return "{\"schemaVersion\":1,"
                + "\"coinFlip\":{\"enabled\":true,\"kicker\":\"抛一次,认结果\",\"merchantMemo\":\"内部备注\","
                + "\"heads\":{\"label\":\"正面\",\"action\":\"这杯店家请\"},"
                + "\"tails\":{\"label\":\"反面\",\"action\":\"这杯你请\"}},"
                + "\"diceRoll\":{\"enabled\":true,\"diceCount\":2,\"costCents\":1200,"
                + "\"faces\":[\"跟店员说一句今天的天气\",\"二\",\"三\",\"四\",\"五\",\"六\"]},"
                + "\"reaction\":{\"enabled\":true,\"rounds\":3,\"goalMs\":320,\"schedule\":[2100,3400,1900]},"
                + "\"ballShake\":{\"enabled\":true,\"goal\":30,\"timed\":true,\"seconds\":12},"
                + "\"quietHold\":{\"enabled\":true,\"seconds\":15,\"kicker\":\"别出声\"},"
                + "\"countdown\":{\"enabled\":true,\"seconds\":90,\"doneText\":\"时间到。\"},"
                + "\"stopwatch\":{\"enabled\":true,\"targetSeconds\":10,\"toleranceMs\":300,\"tries\":3}}";
    }
}

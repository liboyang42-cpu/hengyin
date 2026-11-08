package com.chengyinhub.business.service.support;

import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.chengyinhub.business.util.ClueTransformSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G0 接线回归:指认结案(CT-07)、图片选项、有限谜面变换、提示阶梯(BE-16)必须真的走到原
 * 保存校验与玩家安全投影上,而不是只存在于各自 helper 里。
 *
 * <p>这一层刻意测的是"接线"而不是各 helper 的内部规则 —— 它们各自的单测已覆盖细节。
 * 负向断言是本类的价值:任何秘密字段(答案、正确嫌疑人、ending 文案、提示正文)漏进投影,这里立刻红。
 */
class AdvancedGameHelpWiringTest {

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 一份形状合法的最小配置:两名嫌疑人 + 图片单选 + 倒序题面 + 一级文字提示 + 整局免费 1 次。 */
    private static String config(String... extraLevels) {
        StringBuilder ladder = new StringBuilder("[{\"level\":1,\"type\":\"TEXT\","
                + "\"payload\":\"先看时间线\"}");
        for (String level : extraLevels) ladder.append(',').append(level);
        ladder.append(']');
        return "{\"schemaVersion\":1,"
                + "\"hintFreeUsesPerRun\":1,"
                + "\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"title\":\"谁是真凶\",\"options\":["
                + "{\"id\":\"A\",\"label\":\"管家\",\"correct\":true,"
                + "\"img\":\"https://cdn.example.com/a.png\",\"alt\":\"管家\"},"
                + "{\"id\":\"B\",\"label\":\"园丁\","
                + "\"img\":\"https://cdn.example.com/b.png\",\"alt\":\"园丁\"}]},"
                + "\"accusation\":{\"enabled\":true,\"suspects\":["
                + "{\"id\":\"A\",\"name\":\"管家\",\"identity\":\"仆人\",\"avatar\":\"https://cdn.example.com/a.png\"},"
                + "{\"id\":\"B\",\"name\":\"园丁\",\"identity\":\"花匠\",\"avatar\":\"https://cdn.example.com/b.png\"}],"
                + "\"introScreens\":[\"案情回顾\"],\"winScreen\":\"你指认对了\",\"loseScreen\":\"你指认错了\","
                + "\"earlyAccuse\":{\"allowed\":false}},"
                + "\"clueTransform\":{\"mode\":\"REVERSE\"},"
                + "\"hintLadder\":" + ladder
                + "}";
    }

    @Test
    void accusationConfigIsAcceptedByTheSharedValidator() throws Exception {
        String normalized = validator.validateAndNormalize(config());
        assertNotNull(normalized);
        JsonNode saved = mapper.readTree(normalized);
        assertTrue(saved.has("accusation"));
        assertTrue(saved.has("clueTransform"));
        assertTrue(saved.has("hintLadder"));
    }

    /** 秘密三件套:正确嫌疑人、ending 文案、提示正文 —— 一个都不能进公开投影。 */
    @Test
    void publicProjectionNeverCarriesAccusationOutcomeOrHintBody() throws Exception {
        String normalized = validator.validateAndNormalize(config());
        JsonNode safe = mapper.readTree(AdvancedGamePublicProjection.safeConfigJson(normalized));
        assertFalse(safe.has("hintLadder"), "提示阶梯及其正文属服务端口径,整段不下发");
        assertFalse(safe.has("hintFreeUsesPerRun"),
                "整局免费次数是服务端口径(玩家从 /api/play/help 读剩余额度),配置面不下发");
        String raw = safe.toString();
        assertFalse(raw.contains("先看时间线"), "提示正文不得提前下发");
        assertFalse(raw.contains("你指认对了"), "结局文案提交后才给");
        assertFalse(raw.contains("你指认错了"), "结局文案提交后才给");
        assertFalse(raw.contains("correct"), "对错标记只留在服务端");
        JsonNode accusation = safe.path("accusation");
        assertTrue(accusation.path("enabled").asBoolean());
        assertEquals(2, accusation.path("suspects").size());
        assertEquals("管家", accusation.path("suspects").get(0).path("name").asText());
        assertFalse(accusation.path("suspects").get(0).has("testimony"), "未获得的证词不下发");
    }

    /** 图片选项走原 optionId 判定,投影只补 img/alt。 */
    @Test
    void qaOptionMediaIsProjectedWithAccessibleAltText() throws Exception {
        String normalized = validator.validateAndNormalize(config());
        JsonNode safe = mapper.readTree(AdvancedGamePublicProjection.safeConfigJson(normalized));
        JsonNode first = safe.path("qa").path("options").get(0);
        assertEquals("A", first.path("id").asText());
        assertEquals("https://cdn.example.com/a.png", first.path("img").asText());
        assertEquals("管家", first.path("alt").asText());
        assertFalse(first.has("correct"), "选项对错不下发");
    }

    /** 倒序只作用题面;MIRROR 保留文字并给呈现指令。 */
    @Test
    void clueTransformOnlyChangesThePublicPrompt() throws Exception {
        String normalized = validator.validateAndNormalize(config());
        JsonNode safe = mapper.readTree(AdvancedGamePublicProjection.safeConfigJson(normalized));
        assertEquals("REVERSE", safe.path("clueTransform").path("mode").asText());
        assertEquals(ClueTransformSupport.reverseGraphemes("谁是真凶"), safe.path("qa").path("title").asText());

        String mirror = validator.validateAndNormalize(config().replace("\"REVERSE\"", "\"MIRROR\""));
        JsonNode mirrorSafe = mapper.readTree(AdvancedGamePublicProjection.safeConfigJson(mirror));
        assertEquals("MIRROR", mirrorSafe.path("clueTransform").path("mode").asText());
        assertEquals("谁是真凶", mirrorSafe.path("qa").path("title").asText(), "镜像由呈现层翻转,服务端不改文字");
    }

    /** 没有经审核算法与参数的密码类直接拒绝,不由保存端猜一个。 */
    @Test
    void unsupportedClueTransformModesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(config().replace("\"REVERSE\"", "\"CYPHER\"")));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(config().replace("\"REVERSE\"", "\"FLIP\"")));
    }

    /** BE-16:宝石与金币在本树没有真实账本,配了就拒,运行期才不会假扣款。 */
    @Test
    void hintCostWithoutRealLedgerIsRejected() {
        String gems = config("{\"level\":2,\"type\":\"TEXT\",\"payload\":\"再看物证\","
                + "\"cost\":{\"gems\":2}}");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(gems));
        String coins = config("{\"level\":2,\"type\":\"TEXT\",\"payload\":\"再看物证\","
                + "\"cost\":{\"coins\":1}}");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(coins));
    }

    /** 位置类工具会绕过既有到达与权限判定,本期不可配置。 */
    @Test
    void locationHintLevelIsRejected() {
        String location = config("{\"level\":2,\"type\":\"LOCATION\",\"payload\":\"x\"}");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(location));
    }

    /** 梯级必须 level 唯一且 1..6;旧的层内 freeQuota 负值仍然拒。 */
    @Test
    void hintLadderRequiresUniqueLevelsAndRejectsNegativeLayerQuota() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                config("{\"level\":1,\"type\":\"TEXT\",\"payload\":\"重复同级\"}")));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                config("{\"level\":2,\"type\":\"TEXT\",\"payload\":\"看物证\",\"freeQuota\":-1}")));
        assertDoesNotThrow(() -> validator.validateAndNormalize(
                config("{\"level\":2,\"type\":\"IMAGE\",\"payload\":\"https://cdn.example.com/h.png\","
                        + "\"cost\":{\"points\":0},\"freeQuota\":0}")));
    }

    /** 配了图没写替代文字 = 视障玩家看到空白卡,保存期就得拦。 */
    @Test
    void qaOptionImageWithoutAltTextIsRejected() {
        String broken = config().replace("\"alt\":\"管家\"", "\"alt\":\"\"");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(broken));
    }

    /** 真实样本口径:format 是字符串数组,"_" 是空槽,固定字符原样保留。 */
    @Test
    void hangmanAcceptsTheRealUnderscoreSlotFormat() throws Exception {
        String saved = validator.validateAndNormalize(hangman(
                "[\"E\",\"_\",\"_\",\"_\",\"_\",\"_\",\"S\"]", "\"answer\":\"ENDLESS\","));
        assertNotNull(saved);
        JsonNode safe = mapper.readTree(AdvancedGamePublicProjection.safeConfigJson(saved));
        JsonNode view = safe.path("hangman");
        assertTrue(view.path("enabled").asBoolean());
        assertEquals(7, view.path("length").asInt());
        assertEquals(5, view.path("blankCount").asInt(), "ENDLESS 的 5 个字母待填");
    }

    /** "_" 不能再被当成「与答案不符的固定字符」——那正是旧稿把真实样本判死的原因。 */
    @Test
    void hangmanBlankMarkerIsNotTreatedAsAFixedMismatch() {
        assertDoesNotThrow(() -> validator.validateAndNormalize(
                hangman("[\"_\",\"_\",\"_\"]", "\"answer\":\"ORB\",")));
    }

    /** 槽数与答案字符数必须一致;留空全部槽 = 没有可猜的东西,同样拒。 */
    @Test
    void hangmanRejectsWrongSlotCountAndAllFixedFormat() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                hangman("[\"_\",\"_\"]", "\"answer\":\"ORB\",")));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                hangman("[\"O\",\"R\",\"B\"]", "\"answer\":\"ORB\",")));
    }

    /** 答案、别名、format 预填字符都是冻结配置里的秘密,一个都不进玩家投影。 */
    @Test
    void hangmanProjectionNeverLeaksAnswerAliasesOrPrefill() throws Exception {
        String saved = validator.validateAndNormalize(hangman(
                "[\"E\",\"_\",\"_\",\"_\",\"_\",\"_\",\"S\"]",
                "\"answer\":\"ENDLESS\",\"aliases\":[\"无尽\"],"));
        String safe = AdvancedGamePublicProjection.safeConfigJson(saved);
        assertFalse(safe.contains("ENDLESS"), "答案不得下发");
        assertFalse(safe.contains("无尽"), "别名不得下发");
        assertFalse(safe.contains("format"), "预填槽位配置不得下发");
    }

    // ==================== 保存 → 读回 → 发布闸 → 旧版兼容 ====================

    /** 保存→读回:规范化是幂等的,新段在第二遍校验后逐字保留(不是校验完就丢)。 */
    @Test
    void savedConfigReadsBackWithNewSectionsAndSameBytes() throws Exception {
        String saved = validator.validateAndNormalize(config());
        String again = validator.validateAndNormalize(saved);
        assertEquals(saved, again, "保存结果再进一次校验必须逐字一致(读回语义)");
        JsonNode readBack = mapper.readTree(saved);
        assertEquals("REVERSE", readBack.path("clueTransform").path("mode").asText());
        assertEquals(1, readBack.path("hintLadder").size());
        assertTrue(readBack.path("accusation").path("enabled").asBoolean());
        assertEquals("你指认对了", readBack.path("accusation").path("winScreen").asText(),
                "读回原样保留作者写的结局文案(只在作者实体里,不进玩家投影)");
    }

    /** 固定旧版兼容:没有新段的旧配置保存与投影都零变化,不额外造键。 */
    @Test
    void legacyConfigWithoutNewSectionsIsUntouched() throws Exception {
        String legacy = "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"title\":\"旧题\","
                + "\"options\":[{\"id\":\"A\",\"label\":\"甲\",\"correct\":true},{\"id\":\"B\",\"label\":\"乙\"}]}}";
        String saved = validator.validateAndNormalize(legacy);
        assertNotNull(saved);
        JsonNode safe = mapper.readTree(AdvancedGamePublicProjection.safeConfigJson(saved));
        assertFalse(safe.has("accusation"), "没配指认就不造键");
        assertFalse(safe.has("clueTransform"), "STANDARD/缺省不下发模式");
        assertFalse(safe.has("hangman"));
        assertFalse(safe.has("hintLadder"), "提示阶梯整段不下发");
        assertFalse(safe.path("qa").path("options").get(0).has("img"), "没配图就不造媒体键");
        assertEquals("旧题", safe.path("qa").path("title").asText());
    }

    /** DIGIT 是 G4 内容层 / G5 台账层已支持的层级;保存闸不能把已支持的能力全局拒掉。 */
    @Test
    void digitHintLevelIsAcceptedAndUsableAtRuntime() throws Exception {
        String saved = validator.validateAndNormalize(ladderConfig("[{\"level\":1,\"type\":\"DIGIT\"}]"));
        JsonNode ladder = mapper.readTree(saved).path("hintLadder");
        assertTrue(PlayHelpUsagePolicy.levels(ladder).get(0).usable(),
                "运行期必须把 DIGIT 判为可用,保存闸与运行期同一份清单");
        // 运行期 trim + 大写,保存闸同口径 —— 作者写小写不该在保存期被拒。
        assertDoesNotThrow(() -> validator.validateAndNormalize(
                ladderConfig("[{\"level\":1,\"type\":\"digit\"}]")));
    }

    /**
     * 整局共享免费次数住根层 `hintFreeUsesPerRun`(与 hintLadder 同级):作者写多少存多少,
     * 缺省不补默认。层内 `freeQuota` 只保留 0 作旧配置占位,>0 直接拒并指向根层字段 ——
     * 两套额度语义并存必然让作者看到两个数。
     */
    @Test
    void runSharedFreeUsesLiveAtTheRootAndLayerQuotaIsRetired() throws Exception {
        for (int uses : new int[]{0, 1, 3}) {
            String saved = validator.validateAndNormalize(
                    "{\"schemaVersion\":1,\"hintFreeUsesPerRun\":" + uses + ","
                            + "\"hintLadder\":[{\"level\":1,\"type\":\"TEXT\",\"payload\":\"看时间\"}]}");
            assertEquals(uses, mapper.readTree(saved).path("hintFreeUsesPerRun").asInt(),
                    "整局共享次数必须原样保存");
        }
        // 缺省 = 旧行为:不加键、不补默认数字。
        String legacy = validator.validateAndNormalize(ladderConfig(
                "[{\"level\":1,\"type\":\"TEXT\",\"payload\":\"看时间\"}]"));
        assertFalse(mapper.readTree(legacy).has("hintFreeUsesPerRun"), "缺省不得补默认值");
        // 层内旧占位 0 仍可保存(老草稿不因空键发不出去)。
        assertDoesNotThrow(() -> validator.validateAndNormalize(ladderConfig(
                "[{\"level\":1,\"type\":\"TEXT\",\"payload\":\"看时间\",\"freeQuota\":0}]")));
        // 层内 >0 被拒,错误文案指向根层字段。
        IllegalArgumentException perLevel = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(ladderConfig(
                        "[{\"level\":1,\"type\":\"TEXT\",\"payload\":\"看时间\",\"freeQuota\":1}]")));
        assertTrue(perLevel.getMessage().contains("hintFreeUsesPerRun"), perLevel.getMessage());
        // 根层值非法:负数/小数/超上限都拒。
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"hintFreeUsesPerRun\":-1}"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"hintFreeUsesPerRun\":1.5}"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"hintFreeUsesPerRun\":" + (PlayHelpRunQuotaPolicy.MAX_RUN_FREE_USES + 1) + "}"));
    }

    /** 未支持的枚举准确拒绝;不带新段的旧内容不被牵连。 */
    @Test
    void unsupportedHintTypesAreRejectedWithoutBreakingLegacyContent() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                ladderConfig("[{\"level\":1,\"type\":\"ROT13\",\"payload\":\"x\"}]")));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                ladderConfig("[{\"level\":1,\"type\":\"LOCATION\",\"payload\":\"x\"}]")));
        assertDoesNotThrow(() -> validator.validateAndNormalize(
                ladderConfig("[{\"level\":1,\"type\":\"TEXT\",\"payload\":\"旧提示\"}]")));
    }

    /** 每级 payload 形状必须与运行期 {@code PlayHelpContent.approved} 的读取口径一致。 */
    @Test
    void hintPayloadShapesMatchTheRuntimeContentReader() {
        assertDoesNotThrow(() -> validator.validateAndNormalize(ladderConfig(
                "[{\"level\":1,\"type\":\"TEXT\",\"payload\":\"看时间\"},"
                        + "{\"level\":2,\"type\":\"IMAGE\",\"payload\":\"media-asset:7\"},"
                        + "{\"level\":3,\"type\":\"LETTER\",\"payload\":3},"
                        + "{\"level\":4,\"type\":\"ANSWER\"},"
                        + "{\"level\":5,\"type\":\"DIGIT\"},"
                        + "{\"level\":6,\"type\":\"SKIP\"}]")));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                ladderConfig("[{\"level\":1,\"type\":\"IMAGE\",\"payload\":\"data:image/png;base64,AA\"}]")));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                ladderConfig("[{\"level\":1,\"type\":\"LETTER\",\"payload\":0}]")));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                ladderConfig("[{\"level\":1,\"type\":\"ANSWER\",\"payload\":{\"text\":\"x\"}}]")));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                ladderConfig("[{\"level\":1,\"type\":\"TEXT\",\"payload\":\"\"}]")));
        // DIGIT / SKIP 没有可下发内容:空值放行,作者写了实际内容就拒(不静默丢掉)。
        assertDoesNotThrow(() -> validator.validateAndNormalize(ladderConfig(
                "[{\"level\":1,\"type\":\"DIGIT\",\"payload\":\"\"}]")));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                ladderConfig("[{\"level\":1,\"type\":\"DIGIT\",\"payload\":\"3\"}]")));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                ladderConfig("[{\"level\":1,\"type\":\"SKIP\",\"payload\":\"直接给答案\"}]")));
    }

    /**
     * 提前指认是用户已授权的玩法:{@code allowed=true} 必须能保存(且必须有作者写的 recordAs),
     * 保存闸不替运行期否掉它。运行期缺「本局尚未走完」的事实 → early 恒 false,是**已登记的缺口**,
     * 由运行时接线补,而不是在这里把配置拒掉掩盖它。
     */
    @Test
    void earlyAccuseIsAuthorableAndOnlyItsShapeIsGated() throws Exception {
        String early = config().replace("\"allowed\":false", "\"allowed\":true,\"recordAs\":\"作者写的结束态\"");
        JsonNode saved = mapper.readTree(validator.validateAndNormalize(early));
        assertTrue(saved.path("accusation").path("earlyAccuse").path("allowed").asBoolean(),
                "作者开提前指认必须原样存下来");
        assertEquals("作者写的结束态",
                saved.path("accusation").path("earlyAccuse").path("recordAs").asText(),
                "recordAs 是作者自由文本,不设枚举也不补默认值");
        // allowed=true 却没写 recordAs:形状闸仍然要拦(CT-07 要求作者写明结束态记录)。
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                config().replace("\"allowed\":false", "\"allowed\":true")));
        // 提交前的公开投影仍不下发留痕码与结局文案。
        JsonNode safe = mapper.readTree(AdvancedGamePublicProjection.safeConfigJson(
                validator.validateAndNormalize(early)));
        assertFalse(safe.toString().contains("作者写的结束态"), "recordAs 不下发");
        assertFalse(safe.path("accusation").path("earlyAccuse").has("recordAs"));
    }

    /**
     * 呈现/帮助段不是玩法:发布闸与运行期每一次「启用了哪些机制」扫描都必须跳过它们,
     * 否则只多配一条提示/指认的普通题节点会被判成没有完成证明或到达证明被短路。
     */
    @Test
    void presentationSectionsAreNotCountedAsGameplayMechanics() throws Exception {
        for (String section : new String[]{"accusation", "clueTransform", "hintLadder"}) {
            assertTrue(GameplaySupportMatrix.presentation(section), section);
            assertTrue(GameplaySupportMatrix.addon(section),
                    section + " 必须对所有按 addon() 扫描的既有调用者是次要件");
        }
        assertFalse(GameplaySupportMatrix.addon("qa"));

        // 能力表目录不能被呈现段扩行:客户端目录与 32/10 断言都钉死了这份表。
        Set<String> keys = new HashSet<>();
        for (JsonNode row : GameplaySupportMatrix.view().path("types")) keys.add(row.path("key").asText());
        for (JsonNode row : GameplaySupportMatrix.view().path("addons")) keys.add(row.path("key").asText());
        assertFalse(keys.contains("accusation"));
        assertFalse(keys.contains("clueTransform"));
        assertFalse(keys.contains("hintLadder"));
        assertTrue(keys.contains("hangman"), "逐字猜词有服务端完成证明,必须登记才过发布闸");

        // 只配了提示/指认的节点仍走原基本题型结算(validationMethod=1 → ANSWER):
        // 发布路径先看 basicCompletionKind,命中就走基础到达证明校验,不会被判成「没有完成证明」。
        JsonNode onlyHelpSections = mapper.readTree(validator.validateAndNormalize(ladderConfig(
                "[{\"level\":1,\"type\":\"TEXT\",\"payload\":\"看时间\"}]")));
        assertEquals("ANSWER", GameplaySupportMatrix.basicCompletionKind(onlyHelpSections, 1));
        JsonNode qaWithHelp = mapper.readTree(validator.validateAndNormalize(config()));
        assertNull(GameplaySupportMatrix.basicCompletionKind(qaWithHelp, 1),
                "qa 段仍是真玩法;多配呈现/帮助段不能把节点降级成基本题型");
        // 反证:真玩法段存在时不会被降级,basicCompletionKind 必须为 null 才走高级玩法发布闸。
        assertNull(GameplaySupportMatrix.basicCompletionKind(
                mapper.readTree(validator.validateAndNormalize(hangman("[\"_\",\"_\",\"_\"]", "\"answer\":\"ORB\","))), 1));
    }

    /**
     * 逐字猜词有真实服务端完成证明,所以必须过发布闸 —— 不能因为是新玩法就永久 fail-closed。
     * 证明来源是 SUBMIT_HANGMAN 的 solved 事实(槽位重建 == 冻结答案),不是呈现段。
     */
    @Test
    void hangmanPassesThePublishGateAfterBeingRegisteredAsProofCapable() throws Exception {
        JsonNode onlyHangman = mapper.readTree(validator.validateAndNormalize(hangman(
                "[\"_\",\"_\",\"_\"]", "\"answer\":\"ORB\",")));
        JsonNode theme = mapper.readTree("{\"runtimeScope\":\"TEAM\",\"worldMode\":\"WORLD_SHARED\","
                + "\"gameplayRuntime\":{\"quests\":[{\"nodeId\":31}]}}");
        assertDoesNotThrow(() -> GameplaySupportMatrix.validateNode(theme, 31L, "站点", onlyHangman, 0),
                "只配逐字猜词的节点必须能发布,否则新玩法永远不可用");
        assertNull(GameplaySupportMatrix.basicCompletionKind(onlyHangman, 0),
                "hangman 是真玩法,不是基本题型兜底");
        assertFalse(GameplaySupportMatrix.addon("hangman"), "hangman 不是附加项,不能只当陪衬");
    }

    /** 只带提示阶梯的最小配置(不掺指认/变换),用于测阶梯自身的保存闸。 */
    private static String ladderConfig(String ladderJson) {
        return "{\"schemaVersion\":1,\"hintLadder\":" + ladderJson + "}";
    }

    private static String hangman(String format, String answerPair) {
        return "{\"schemaVersion\":1,\"hangman\":{\"enabled\":true," + answerPair
                + "\"format\":" + format + "}}";
    }
}

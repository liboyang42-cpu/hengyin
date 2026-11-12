package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * BE-16 提示阶梯策略回归。断言只覆盖原资料写明的行为：
 * 六级 {@code TEXT/IMAGE/LETTER/ANSWER/SKIP/LOCATION} 是可配项、按作者顺序、不强制六层；
 * 「同一级重新查看不算新使用」；「无阶梯配置即保持旧行为」；宝石/金币无账本必须拒绝，而不是免费。
 *
 * <p>刻意不断言任何具体费率：M.7 的 -30 分 / 2 宝石 / 5 宝石 / 1 金币 / 300 每站是竞品实测与
 * {@code hint_probe.sh} 的可调模型参数，不是城瘾默认值。
 */
class PlayHelpUsagePolicyTest {

    private static final long MEMBER = 42L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode entry(int level, String type) {
        return JSON.createObjectNode().put("level", level).put("type", type);
    }

    private static ObjectNode paid(int level, String type, int points, int freeQuota) {
        ObjectNode entry = entry(level, type);
        entry.putObject("cost").put("points", points);
        entry.put("freeQuota", freeQuota);
        return entry;
    }

    private static ArrayNode array(ObjectNode... entries) {
        ArrayNode array = JSON.createArrayNode();
        for (ObjectNode entry : entries) array.add(entry);
        return array;
    }

    @Test
    void missingLadderKeepsLegacyBehaviour() {
        assertTrue(PlayHelpUsagePolicy.levels(null).isEmpty());
        assertTrue(PlayHelpUsagePolicy.levels(JSON.createObjectNode().path("hintLadder")).isEmpty());
        assertTrue(PlayHelpUsagePolicy.levels(
                com.fasterxml.jackson.databind.node.NullNode.getInstance()).isEmpty());
    }

    @Test
    void keepsAuthorOrderAndDoesNotForceSixLevels() {
        ArrayNode configured = array(paid(3, "ANSWER", 40, 0), paid(1, "TEXT", 0, 1),
                paid(2, "IMAGE", 20, 0));
        List<PlayHelpUsagePolicy.LevelSpec> specs = PlayHelpUsagePolicy.levels(configured);
        assertEquals(3, specs.size());
        assertEquals(3, specs.get(0).level());
        assertEquals(1, specs.get(1).level());
        assertEquals(2, specs.get(2).level());
        assertEquals(PlayHelpUsagePolicy.TYPE_ANSWER, specs.get(0).type());
        assertEquals(40, specs.get(0).costPoints());
        assertTrue(specs.get(1).freeOnFirstUse());
        assertFalse(specs.get(2).freeOnFirstUse());
    }

    @Test
    void gemsAndCoinsAreRejectedBecauseNoLedgerExists() {
        ObjectNode gems = entry(1, "TEXT").put("freeQuota", 0);
        gems.putObject("cost").put("gems", 2);
        ObjectNode coins = entry(2, "TEXT").put("freeQuota", 0);
        coins.putObject("cost").put("coins", 1);
        List<PlayHelpUsagePolicy.LevelSpec> specs = PlayHelpUsagePolicy.levels(array(gems, coins));
        assertFalse(specs.get(0).usable());
        assertEquals(PlayHelpUsagePolicy.REASON_GEMS_LEDGER_ABSENT, specs.get(0).reason());
        assertFalse(specs.get(1).usable());
        assertEquals(PlayHelpUsagePolicy.REASON_COINS_LEDGER_ABSENT, specs.get(1).reason());
    }

    @Test
    void zeroGemOrCoinCostIsNotACost() {
        ObjectNode zero = entry(1, "TEXT").put("freeQuota", 0);
        zero.putObject("cost").put("points", 0).put("gems", 0).put("coins", 0);
        PlayHelpUsagePolicy.LevelSpec spec = PlayHelpUsagePolicy.levels(array(zero)).get(0);
        assertTrue(spec.usable());
        assertEquals(0, spec.costPoints());
    }

    @Test
    void unknownCurrencyOrNonIntegralCostIsRejected() {
        ObjectNode unknown = entry(1, "TEXT");
        unknown.putObject("cost").put("xp", 15);
        ObjectNode fractional = entry(2, "TEXT");
        fractional.putObject("cost").put("points", 1.5);
        ObjectNode negative = entry(3, "TEXT");
        negative.putObject("cost").put("points", -1);
        List<PlayHelpUsagePolicy.LevelSpec> specs =
                PlayHelpUsagePolicy.levels(array(unknown, fractional, negative));
        for (PlayHelpUsagePolicy.LevelSpec spec : specs) {
            assertFalse(spec.usable());
            assertEquals(PlayHelpUsagePolicy.REASON_CONFIG_INVALID, spec.reason());
        }
    }

    @Test
    void locationToolsStayUnavailable() {
        PlayHelpUsagePolicy.LevelSpec spec =
                PlayHelpUsagePolicy.levels(array(entry(6, "LOCATION"))).get(0);
        assertFalse(spec.usable());
        assertEquals(PlayHelpUsagePolicy.REASON_LOCATION_NOT_WIRED, spec.reason());
    }

    @Test
    void letterAndDigitAreBothUsableAsTheDocWrites() {
        List<PlayHelpUsagePolicy.LevelSpec> specs = PlayHelpUsagePolicy.levels(
                array(entry(3, "LETTER"), entry(4, "DIGIT")));
        assertTrue(specs.get(0).usable());
        assertEquals(PlayHelpUsagePolicy.TYPE_LETTER, specs.get(0).type());
        assertTrue(specs.get(1).usable());
        assertEquals(PlayHelpUsagePolicy.TYPE_DIGIT, specs.get(1).type());
    }

    @Test
    void unknownTypeIsUnavailableButDoesNotBreakOtherLevels() {
        List<PlayHelpUsagePolicy.LevelSpec> specs =
                PlayHelpUsagePolicy.levels(array(entry(1, "MIRROR"), paid(2, "TEXT", 10, 0)));
        assertFalse(specs.get(0).usable());
        assertEquals(PlayHelpUsagePolicy.REASON_TYPE_UNSUPPORTED, specs.get(0).reason());
        assertTrue(specs.get(1).usable());
    }

    @Test
    void brokenStructureFailsClosed() {
        ObjectNode notArray = JSON.createObjectNode().put("hintLadder", "TEXT");
        assertThrows(IllegalArgumentException.class, () -> PlayHelpUsagePolicy.levels(notArray));
        ArrayNode duplicate = array(paid(1, "TEXT", 0, 1), paid(1, "IMAGE", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> PlayHelpUsagePolicy.levels(duplicate));
        ArrayNode zero = array(entry(0, "TEXT"));
        assertThrows(IllegalArgumentException.class, () -> PlayHelpUsagePolicy.levels(zero));
    }

    @Test
    void unlockedLevelRereadsForFree() {
        ArrayNode configured = array(paid(1, "TEXT", 30, 0), paid(2, "IMAGE", 30, 0));
        List<PlayHelpUsagePolicy.LevelView> locked = PlayHelpUsagePolicy.views(configured, Set.of());
        assertEquals(30, locked.get(0).costPoints());
        assertFalse(locked.get(0).unlocked());

        List<PlayHelpUsagePolicy.LevelView> reread =
                PlayHelpUsagePolicy.views(configured, new LinkedHashSet<>(List.of(1)));
        assertTrue(reread.get(0).unlocked());
        assertEquals(0, reread.get(0).costPoints());
        assertTrue(reread.get(0).free());
        assertEquals(30, reread.get(1).costPoints());
    }

    @Test
    void scopeKeyCarriesEveryDimensionAndStaysWithinLedgerLimit() {
        PlayHelpUsagePolicy.Scope scope =
                new PlayHelpUsagePolicy.Scope(9001L, 55L, 77L, "run-uuid-0001");
        String key = PlayHelpUsagePolicy.scopeKey(scope);
        assertEquals(scope, PlayHelpUsagePolicy.parseScope(key));
        assertTrue(key.length() <= PlayHelpUsagePolicy.MAX_LEDGER_KEY_LENGTH);

        String factKey = PlayHelpUsagePolicy.levelFactKey(MEMBER, key, 3);
        assertTrue(factKey.length() <= PlayHelpUsagePolicy.MAX_LEDGER_KEY_LENGTH);
        assertTrue(factKey.endsWith(":L3"));
        assertTrue(PlayHelpUsagePolicy.pointsBusinessKey(MEMBER, key, 3).length()
                <= PlayHelpUsagePolicy.MAX_LEDGER_KEY_LENGTH);

        // 键必须把所有维度都编进去，否则跨局/跨级会共用同一个唯一键。
        assertNotEquals(factKey, PlayHelpUsagePolicy.levelFactKey(MEMBER, key, 4));
        String otherRun = PlayHelpUsagePolicy.scopeKey(
                new PlayHelpUsagePolicy.Scope(9001L, 55L, 77L, "run-uuid-0002"));
        assertNotEquals(factKey, PlayHelpUsagePolicy.levelFactKey(MEMBER, otherRun, 3));
        // 同一局同一层的另一个玩家必须是不同的键：否则 B 会撞上 A 的解锁/收费事实。
        assertNotEquals(factKey, PlayHelpUsagePolicy.levelFactKey(MEMBER + 1, key, 3));
        String otherNode = PlayHelpUsagePolicy.scopeKey(
                new PlayHelpUsagePolicy.Scope(9001L, 55L, 78L, "run-uuid-0001"));
        assertNotEquals(factKey, PlayHelpUsagePolicy.levelFactKey(MEMBER, otherNode, 3));
        String selfPlay = PlayHelpUsagePolicy.scopeKey(
                new PlayHelpUsagePolicy.Scope(9001L, null, 77L, "run-uuid-0001"));
        assertNotEquals(key, selfPlay);
        assertTrue(selfPlay.contains(":0:"));
        assertEquals(selfPlay, PlayHelpUsagePolicy.scopeKey(PlayHelpUsagePolicy.parseScope(selfPlay)));
    }

    @Test
    void runIdentityIsRequired() {
        assertEquals(PlayHelpUsagePolicy.REASON_RUN_REQUIRED,
                assertThrows(IllegalArgumentException.class,
                        () -> new PlayHelpUsagePolicy.Scope(1L, null, 2L, "default")).getMessage());
        assertEquals(PlayHelpUsagePolicy.REASON_RUN_REQUIRED,
                assertThrows(IllegalArgumentException.class,
                        () -> new PlayHelpUsagePolicy.Scope(1L, null, 2L, " ")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> PlayHelpUsagePolicy.parseScope("help1:1:0:2:"));
        assertThrows(IllegalArgumentException.class,
                () -> PlayHelpUsagePolicy.parseScope("help1:1:0:2:abc:extra"));
    }

    /**
     * 高级局实例名 {@code game:<id>} 含冒号，必须能真正往返 —— 否则修好归属守卫又被 parse 卡死。
     * 同时两类原局的作用域键**不能相同**：共享路线局的实例名也可能是 {@code game:<id>}。
     */
    @Test
    void advancedScopeRoundTripsThroughTheEncodedInstanceAndNeverCollidesWithRouteScope() {
        PlayHelpUsagePolicy.Scope advanced =
                PlayHelpUsagePolicy.Scope.advanced(9001L, 55L, 77L, 4242L);
        assertEquals("game:4242", advanced.playInstance());
        assertEquals(PlayHelpUsagePolicy.RunKind.ADVANCED_GAME, advanced.kind());
        assertEquals(Long.valueOf(4242L), advanced.advancedSessionId());

        String key = PlayHelpUsagePolicy.scopeKey(advanced);
        assertTrue(key.length() <= PlayHelpUsagePolicy.MAX_LEDGER_KEY_LENGTH);
        PlayHelpUsagePolicy.Scope parsed = PlayHelpUsagePolicy.parseScope(key);
        assertEquals(advanced, parsed, "含冒号的实例名必须原样往返");
        assertEquals(PlayHelpUsagePolicy.RunKind.ADVANCED_GAME, parsed.kind());
        assertEquals(key, PlayHelpUsagePolicy.scopeKey(parsed));

        // 同样的 topic/activity/node/实例字符串，但属于路线局：键必须不同。
        PlayHelpUsagePolicy.Scope routeLookalike =
                new PlayHelpUsagePolicy.Scope(9001L, 55L, 77L, "game:4242");
        assertEquals(PlayHelpUsagePolicy.RunKind.TOPIC_ROUTE, routeLookalike.kind());
        assertNotEquals(key, PlayHelpUsagePolicy.scopeKey(routeLookalike));
        assertNotEquals(PlayHelpUsagePolicy.levelFactKey(MEMBER, key, 1),
                PlayHelpUsagePolicy.levelFactKey(MEMBER, PlayHelpUsagePolicy.scopeKey(routeLookalike), 1));
        assertNotEquals(PlayHelpUsagePolicy.pointsBusinessKey(MEMBER, key, 1),
                PlayHelpUsagePolicy.pointsBusinessKey(MEMBER,
                        PlayHelpUsagePolicy.scopeKey(routeLookalike), 1));
        assertNotEquals(PlayHelpUsagePolicy.requestFactKey(MEMBER, key, "req-1"),
                PlayHelpUsagePolicy.requestFactKey(MEMBER,
                        PlayHelpUsagePolicy.scopeKey(routeLookalike), "req-1"));
    }

    /** 旧 4 参构造与旧 {@code help1:...} 键必须逐字节不变：存量局的事实/回执不能被改名。 */
    @Test
    void legacyFourArgScopeKeepsTheOldKeyFormatByteForByte() {
        PlayHelpUsagePolicy.Scope legacy =
                new PlayHelpUsagePolicy.Scope(9001L, 55L, 77L, "run-uuid-0001");
        assertEquals(PlayHelpUsagePolicy.RunKind.TOPIC_ROUTE, legacy.kind());
        String expected = "help1:9001:55:77:"
                + java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("run-uuid-0001".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(expected, PlayHelpUsagePolicy.scopeKey(legacy));
        assertEquals(legacy, PlayHelpUsagePolicy.parseScope(expected));
        assertNull(legacy.advancedSessionId());
    }

    /** 高级局作用域必须带真 sessionId：0/负数不接受。 */
    @Test
    void advancedScopeRequiresAPositiveSessionId() {
        assertThrows(IllegalArgumentException.class,
                () -> PlayHelpUsagePolicy.Scope.advanced(9001L, 55L, 77L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> PlayHelpUsagePolicy.Scope.advanced(9001L, 55L, 77L, -3L));
    }

    @Test
    void fingerprintChangesWhenPriceOrFreeFlagChanges() {
        PlayHelpUsagePolicy.Scope scope = new PlayHelpUsagePolicy.Scope(1L, null, 2L, "run-a");
        String key = PlayHelpUsagePolicy.scopeKey(scope);
        String paid = PlayHelpUsagePolicy.factFingerprint(MEMBER, key, 1, false, 30);
        assertEquals(64, paid.length());
        assertEquals(paid, PlayHelpUsagePolicy.factFingerprint(MEMBER, key, 1, false, 30));
        assertNotEquals(paid, PlayHelpUsagePolicy.factFingerprint(MEMBER, key, 1, false, 40));
        assertNotEquals(paid, PlayHelpUsagePolicy.factFingerprint(MEMBER + 1, key, 1, false, 30));
        assertNotEquals(paid, PlayHelpUsagePolicy.factFingerprint(MEMBER, key, 1, true, 30));
    }

    @Test
    void requestKeyIsStablePerScopeAndRequestButNotAcrossThem() {
        String scope = PlayHelpUsagePolicy.scopeKey(new PlayHelpUsagePolicy.Scope(1L, null, 2L, "run-a"));
        String otherRun = PlayHelpUsagePolicy.scopeKey(new PlayHelpUsagePolicy.Scope(1L, null, 2L, "run-b"));

        String key = PlayHelpUsagePolicy.requestFactKey(MEMBER, scope, "req-1");
        assertTrue(key.startsWith("help1req:"));
        assertTrue(key.length() <= PlayHelpUsagePolicy.MAX_LEDGER_KEY_LENGTH);
        assertEquals(key, PlayHelpUsagePolicy.requestFactKey(MEMBER, scope, "req-1"));
        assertNotEquals(key, PlayHelpUsagePolicy.requestFactKey(MEMBER, scope, "req-2"));
        assertNotEquals(key, PlayHelpUsagePolicy.requestFactKey(MEMBER, otherRun, "req-1"));
        assertNotEquals(key, PlayHelpUsagePolicy.requestFactKey(MEMBER + 1, scope, "req-1"),
                "同一 requestId 由另一个会员提交不能撞同一行回执");
        // 请求回执键不能与每局每级事实键撞：否则 G4 会把回执当成「该级已解锁」。
        assertNotEquals(key, PlayHelpUsagePolicy.levelFactKey(MEMBER, scope, 1));

        assertEquals(PlayHelpUsagePolicy.REASON_REQUEST_ID_INVALID,
                assertThrows(IllegalArgumentException.class,
                        () -> PlayHelpUsagePolicy.requestFactKey(MEMBER, scope, "")).getMessage());
        assertEquals(PlayHelpUsagePolicy.REASON_REQUEST_ID_INVALID,
                assertThrows(IllegalArgumentException.class,
                        () -> PlayHelpUsagePolicy.requestFactKey(MEMBER, scope, null)).getMessage());
        String tooLong = "r".repeat(PlayHelpUsagePolicy.MAX_REQUEST_ID_LENGTH + 1);
        assertThrows(IllegalArgumentException.class,
                () -> PlayHelpUsagePolicy.requestFactKey(MEMBER, scope, tooLong));
        // 同级不同代价同样是不同的动作身份。
        assertNotEquals(PlayHelpUsagePolicy.requestFingerprint(MEMBER, scope, 1, false, 30),
                PlayHelpUsagePolicy.requestFingerprint(MEMBER, scope, 1, false, 40));
    }

    /**
     * 层内 freeQuota 只作旧配置兼容：保存闸现在只让作者填 0，整局免费额度由 hintFreeUsesPerRun 表达。
     * 运行期不再因为 >1 把整层标成不可用 —— 那只会让历史脏数据把玩家的按钮变灰。
     */
    @Test
    void legacyPerLayerFreeQuotaStaysReadableInsteadOfBlockingTheLevel() {
        assertTrue(PlayHelpUsagePolicy.levels(array(paid(1, "TEXT", 0, 3))).get(0).usable());
        assertTrue(PlayHelpUsagePolicy.levels(array(paid(1, "TEXT", 0, 1))).get(0).freeOnFirstUse());
    }
}

package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 4 自适应难度 variants 的发布期形状闸。
 *
 * <p>四条硬约束里有两条只能在这里挡住(另两条「开局冻结一处求值」「求值在下发白名单之前」
 * 是运行期的事):
 *   ① 单向只放宽 —— 收紧一律拒。线下真人体验里加压会直接变成投诉。
 *   ② 只动看不见的容错面 —— 题面(题目数量 / 内容 / 答案)一个字都不许碰,
 *      因为线下玩家会互相对比,题面不同当场穿帮。
 *
 * <p>⚠️ 次数类字段 {@code 0 = 不限},所以「放宽」不等于「数字变大」:
 * 3→0 是放宽(变不限)但数字变小,0→5 是收紧但数字变大。天真的大小比较两种都判反。
 */
class AdvancedGameVariantsValidatorTest {

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();

    private String cfg(String variants) {
        return "{\"schemaVersion\":1,"
                + "\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"title\":\"题\",\"options\":["
                + "{\"id\":\"a\",\"label\":\"甲\",\"correct\":false},"
                + "{\"id\":\"b\",\"label\":\"乙\",\"correct\":true}],\"xp\":5},"
                + "\"timer\":{\"enabled\":true,\"durationSeconds\":60,\"timeoutResult\":\"FAILED\"},"
                // sort 也开着:放宽次数类字段的用例要有个真的启用了的段可指 ——
                // 「指向未启用的玩法段」本身是另一条闸(relaxingADisabledKitIsRejected)。
                + "\"sort\":{\"enabled\":true,\"prompt\":\"按年代排\",\"items\":["
                + "{\"id\":\"a\",\"label\":\"甲\"},{\"id\":\"b\",\"label\":\"乙\"}],"
                + "\"answerOrder\":[\"a\",\"b\"],\"maxAttempts\":2,\"xp\":5},"
                + "\"variants\":" + variants + "}";
    }

    private String rejected(String variants) {
        return assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(cfg(variants)))
                .getMessage();
    }

    // ==================== 1. 放宽方向 ====================

    @Test
    void relaxingTimerUpwardsIsAccepted() {
        assertNotNull(validator.validateAndNormalize(cfg(
                "[{\"when\":{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3},"
                        + "\"relax\":{\"timer.durationSeconds\":\"+30\"}}]")));
    }

    @Test
    void tighteningTimerIsRejected() {
        assertTrue(rejected("[{\"when\":{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3},"
                + "\"relax\":{\"timer.durationSeconds\":\"-10\"}}]").contains("只能放宽"));
    }

    /** 0 = 不限:把有限次数改成 0 是放宽,不是收紧 —— 天真的大小比较会判反。 */
    @Test
    void settingAttemptsToUnlimitedIsRelaxingNotTightening() {
        assertNotNull(validator.validateAndNormalize(cfg(
                "[{\"when\":{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3},"
                        + "\"relax\":{\"sort.maxAttempts\":\"=0\"}}]")));
    }

    /** 反向:把「不限」改成有限次数是收紧,数字却变大了。 */
    @Test
    void turningUnlimitedIntoAFiniteCountIsRejected() {
        assertTrue(rejected("[{\"when\":{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3},"
                + "\"relax\":{\"sort.maxAttempts\":\"=5\"}}]").contains("只能放宽"));
    }

    // ==================== 2. 只动容错面 ====================

    @Test
    void touchingQuestionSurfaceIsRejected() {
        for (String path : new String[]{"qa.options", "qa.title", "hiddenObject.hotspots", "qa.xp"}) {
            assertTrue(rejected("[{\"when\":{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3},"
                            + "\"relax\":{\"" + path + "\":\"+1\"}}]").contains("容错面"),
                    path + " 属题面,必须拒 —— 线下玩家会互相对比,题面不同当场穿帮");
        }
    }

    /**
     * 音乐角的曲目时长只画进度条、音乐角不判定通关 —— 放宽它对玩家难度毫无作用,只会让进度条走不满。
     * 断言落在「不在白名单」上:若它还在白名单,这里会改由「指向未启用的玩法段」拒掉,测试照样能绿 ——
     * 所以只断言抛异常不够,必须断言是哪一道闸(2026-09-23 走查 W-01)。
     */
    @Test
    void musicCornerDurationIsNotAToleranceSurface() {
        assertTrue(rejected("[{\"when\":{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3},"
                        + "\"relax\":{\"musicCorner.durationSeconds\":\"+30\"}}]").contains("不在白名单"),
                "musicCorner.durationSeconds 不是容错面,必须在白名单这一道就拒");
    }

    // ==================== 2b. 9-23 扩面:更多次数 + 判定误差 ====================

    private static final String WHEN = "{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3}";
    private static final String COMPASS = "\"compass\":{\"enabled\":true,\"title\":\"朝北\",\"bearing\":0,"
            + "\"tolerance\":20,\"holdSeconds\":2}";
    private static final String STOPWATCH = "\"stopwatch\":{\"enabled\":true,\"targetSeconds\":10,"
            + "\"toleranceMs\":300,\"tries\":3}";
    private static final String ESTIMATE = "\"estimate\":{\"enabled\":true,\"title\":\"猜价\",\"min\":0,"
            + "\"max\":100,\"answer\":40,\"tolerance\":2.5,\"xp\":5}";

    private String with(String sections, String relax) {
        return "{\"schemaVersion\":1," + sections + ",\"variants\":[{\"when\":" + WHEN + ",\"relax\":" + relax + "}]}";
    }

    private String rejectedWith(String sections, String relax) {
        return assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(with(sections, relax))).getMessage();
    }

    @Test
    void toleranceWideningIsAccepted() {
        assertNotNull(validator.validateAndNormalize(with(COMPASS, "{\"compass.tolerance\":\"+15\"}")));
        assertNotNull(validator.validateAndNormalize(with(STOPWATCH, "{\"stopwatch.toleranceMs\":\"+300\"}")));
        assertNotNull(validator.validateAndNormalize(with(ESTIMATE, "{\"estimate.tolerance\":\"+2\"}")));
    }

    /** 误差没有「不限」这一说:=N 证明不了比原值宽,一律拒。 */
    @Test
    void toleranceSetIsRejected() {
        assertTrue(rejectedWith(COMPASS, "{\"compass.tolerance\":\"=60\"}").contains("证明不了"));
    }

    /** 放宽后越过 kit 自己的上界(罗盘 90°)由整份复核拒,不在运行期悄悄夹值。 */
    @Test
    void toleranceBeyondKitBoundIsRejected() {
        assertTrue(rejectedWith(COMPASS, "{\"compass.tolerance\":\"+100\"}").contains("放宽后不合法"));
    }

    @Test
    void moreAttemptFieldsAreRelaxable() {
        assertNotNull(validator.validateAndNormalize(with(STOPWATCH, "{\"stopwatch.tries\":\"+2\"}")));
        assertNotNull(validator.validateAndNormalize(with(STOPWATCH, "{\"stopwatch.tries\":\"=0\"}")));
    }

    /** 拍照审核的次数是「放不放过」的触发点,不是容错面:白名单这一道就拒。 */
    @Test
    void photoCheckTriesAreNotATolerance() {
        String photo = "\"photoCheck\":{\"enabled\":true,\"requirement\":\"门牌\",\"maxTries\":2,\"fallback\":\"pass\",\"xp\":5}";
        assertTrue(rejectedWith(photo, "{\"photoCheck.maxTries\":\"+2\"}").contains("不在白名单"));
    }

    // ==================== 2c. 9-24 挑战类:越小越容易(LOWER)与达标线 ====================

    private static final String QUIET = "\"quietHold\":{\"enabled\":true,\"seconds\":10}";
    private static final String REACT = "\"reaction\":{\"enabled\":true,\"rounds\":3,\"goalMs\":400}";
    private static final String SHAKE = "\"ballShake\":{\"enabled\":true,\"goal\":20,\"timed\":true,\"seconds\":10}";

    @Test
    void lowerKindAcceptsOnlyMinus() {
        assertNotNull(validator.validateAndNormalize(with(QUIET, "{\"quietHold.seconds\":\"-3\"}")));
        assertTrue(rejectedWith(QUIET, "{\"quietHold.seconds\":\"+3\"}").contains("只能写 -N"),
                "坚持几秒:加秒是收紧");
        assertTrue(rejectedWith(QUIET, "{\"quietHold.seconds\":\"=5\"}").contains("只能写 -N"));
    }

    /** 减到 kit 下界(安静挑战 5 秒)以下由整份复核拒,不在运行期悄悄夹。 */
    @Test
    void lowerBelowKitMinimumIsRejected() {
        assertTrue(rejectedWith(QUIET, "{\"quietHold.seconds\":\"-8\"}").contains("放宽后不合法"));
    }

    @Test
    void reactionGoalAndShakeTimeAndGoalAreRelaxable() {
        assertNotNull(validator.validateAndNormalize(with(REACT, "{\"reaction.goalMs\":\"+150\"}")));
        assertNotNull(validator.validateAndNormalize(with(SHAKE, "{\"ballShake.seconds\":\"+5\"}")));
        assertNotNull(validator.validateAndNormalize(with(SHAKE, "{\"ballShake.goal\":\"-5\"}")));
    }

    /** 负控:弹球没开限时,放宽时长配了等于没配 —— 必须拒。 */
    @Test
    void shakeSecondsWithoutTimedIsRejected() {
        String untimed = SHAKE.replace("\"timed\":true", "\"timed\":false");
        assertTrue(rejectedWith(untimed, "{\"ballShake.seconds\":\"+5\"}").contains("没开限时"));
    }

    /** 其他类仍然不收 -N(LOWER 的口子没有顺手开给别人)。 */
    @Test
    void minusStillRejectedForOtherKinds() {
        assertTrue(rejectedWith(COMPASS, "{\"compass.tolerance\":\"-5\"}").contains("收紧"));
    }

    // ==================== 3. 形状 ====================

    @Test
    void whenReusesTheSameConditionDsl() {
        assertTrue(rejected("[{\"when\":{\"op\":\"NOPE\",\"var\":\"sys.hp\",\"value\":3},"
                + "\"relax\":{\"timer.durationSeconds\":\"+30\"}}]").contains("variants 的 when"),
                "错误要指向 variants 的 when,而不是随便抛了个异常就算数");
    }

    @Test
    void unknownSysVarInWhenIsRejected() {
        assertTrue(rejected("[{\"when\":{\"op\":\"LTE\",\"var\":\"sys.nope\",\"value\":3},"
                + "\"relax\":{\"timer.durationSeconds\":\"+30\"}}]").contains("sys.nope"),
                "错误要点名那个变量");
    }

    /** 无条件的放宽 = 直接改配置,不是自适应难度。 */
    @Test
    void relaxWithoutWhenIsRejected() {
        assertTrue(rejected("[{\"relax\":{\"timer.durationSeconds\":\"+30\"}}]").contains("必须带 when"));
    }

    @Test
    void notConfiguredIsLegal() {
        assertNotNull(validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"timer\":{\"enabled\":true,\"durationSeconds\":60,\"timeoutResult\":\"FAILED\"}}"));
    }

    @Test
    void tooManyVariantsAreRejected() {
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 17; i++) {
            if (i > 0) many.append(',');
            many.append("{\"when\":{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3},")
                .append("\"relax\":{\"timer.durationSeconds\":\"+1\"}}");
        }
        assertTrue(rejected(many.append(']').toString()).contains("最多配置"),
                "要因为超条数被拒,不是因为别的");
    }

    // ==================== 4. 放宽后必须仍是一份合法配置(发布期判红,不在运行期静默夹值) ====================

    /**
     * 越界的放宽要在发布期判红。此前是运行期 Math.min 夹到上限 —— 作者写 +90000、
     * 发布放行、跑起来悄悄变成 86400,两头都不知道那个数字没生效。
     * 这正是本仓在 validateMistakeTier 上写明反对的做法:「坏值直接拒、不在运行期悄悄落回」。
     */
    @Test
    void relaxingBeyondTheKitsOwnMaximumIsRejectedAtPublish() {
        String error = rejected("[{\"when\":{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3},"
                + "\"relax\":{\"timer.durationSeconds\":\"+90000\"}}]");
        assertTrue(error.contains("放宽后不合法"), "要说清是放宽之后越界,而不是形状不对:" + error);
        assertTrue(error.contains("第 1 条"), "还要说清是哪一条,配了多条时不然只能一条条试:" + error);
    }

    /** 边界:刚好顶到上限是合法的 —— 闸卡的是越界,不是「不许放宽到满」。 */
    @Test
    void relaxingExactlyToTheMaximumIsAccepted() {
        assertNotNull(validator.validateAndNormalize(cfg(
                "[{\"when\":{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3},"
                        + "\"relax\":{\"timer.durationSeconds\":\"+86340\"}}]")), "60 + 86340 = 86400,正好是上限");
    }

    /**
     * 逐条模拟,不累加 —— 运行期是「首条命中为准」,两条各自合法就都该放行。
     * 若按累加判,这条会被误杀。
     */
    @Test
    void variantsAreCheckedOneByOneBecauseOnlyTheFirstMatchApplies() {
        assertNotNull(validator.validateAndNormalize(cfg(
                "[{\"when\":{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3},"
                        + "\"relax\":{\"timer.durationSeconds\":\"+50000\"}},"
                        + "{\"when\":{\"op\":\"LTE\",\"var\":\"sys.luck\",\"value\":0},"
                        + "\"relax\":{\"timer.durationSeconds\":\"+50000\"}}]")),
                "两条各自 60+50000 合法;只有按「全部同时命中」累加才会越界,而运行期不会那样跑");
    }

    /** 多条时要指名出错的那一条,而不是笼统说「variants 不合法」。 */
    @Test
    void theOffendingVariantIsNamedByItsOrdinal() {
        String error = rejected("[{\"when\":{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3},"
                + "\"relax\":{\"timer.durationSeconds\":\"+30\"}},"
                + "{\"when\":{\"op\":\"LTE\",\"var\":\"sys.luck\",\"value\":0},"
                + "\"relax\":{\"timer.durationSeconds\":\"+90000\"}}]");
        assertTrue(error.contains("第 2 条"), "出错的是第二条,不能报成第一条或不报:" + error);
    }

    /** relax 指向一个没启用的玩法段:运行期原本静默跳过,等于这条配置从没生效过。 */
    @Test
    void relaxingADisabledKitIsRejected() {
        String error = rejected("[{\"when\":{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3},"
                + "\"relax\":{\"typeIn.seconds\":\"+10\"}}]");
        assertTrue(error.contains("没有启用"), "本配置里 typeIn 没开,放宽它没有意义:" + error);
    }

    /** 底值已经是「不限」(0)还要 +N:放宽不了更松,同样是一条永远不生效的配置。 */
    @Test
    void relaxingAnAlreadyUnlimitedCountIsRejected() {
        String error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize("{\"schemaVersion\":1,"
                        + "\"sort\":{\"enabled\":true,\"prompt\":\"排\",\"items\":["
                        + "{\"id\":\"a\",\"label\":\"甲\"},{\"id\":\"b\",\"label\":\"乙\"}],"
                        + "\"answerOrder\":[\"a\",\"b\"],\"maxAttempts\":0,\"xp\":5},"
                        + "\"variants\":[{\"when\":{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3},"
                        + "\"relax\":{\"sort.maxAttempts\":\"+3\"}}]}")).getMessage();
        assertTrue(error.contains("已经是不限"), error);
    }

    /**
     * ⚠️ 次数类字段**缺省即 0 即不限**(validateSort 是 {@code path("maxAttempts").asInt(0)},
     * 运行期是 {@code if (maxAttempts > 0)})。所以「已经是不限」不能只认显式写着的 0 ——
     * 字段不写时同样是不限,这时 +N 会把「不限」变成「只剩 N 次」,**方向是收紧**,
     * 正是这整个功能要堵的那一类。
     */
    @Test
    void relaxingAnOmittedCountFieldIsRejectedBecauseOmittedMeansUnlimited() {
        String error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize("{\"schemaVersion\":1,"
                        + "\"sort\":{\"enabled\":true,\"prompt\":\"排\",\"items\":["
                        + "{\"id\":\"a\",\"label\":\"甲\"},{\"id\":\"b\",\"label\":\"乙\"}],"
                        + "\"answerOrder\":[\"a\",\"b\"],\"xp\":5},"      // 注意:不写 maxAttempts
                        + "\"variants\":[{\"when\":{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3},"
                        + "\"relax\":{\"sort.maxAttempts\":\"+3\"}}]}")).getMessage();
        assertTrue(error.contains("已经是不限"), "缺省与显式 0 必须同判:" + error);
    }
}

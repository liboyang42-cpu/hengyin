package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R14-PLAY 运行期缺口 2 / 3(剧情侧):ending.dispositionNodeId 必须属于本主题且是分支玩法;
 * endings/exhibits/badges 的 cond 可以引用只读系统变量,但白名单外的 sys.* 与主题外 nodeId 一律拒。
 *
 * <p>负控:dispositionNodeId 指向非 branch 节点不报错 → {@link #dispositionNodeMustBeABranchNode()} 红;
 * 主题外 nodeId 不报错 → {@link #systemVariableNodeMustBelongToTheTheme()} 红。
 */
class JourneyStoryDispositionTest {

    private final JourneyStoryValidator validator = new JourneyStoryValidator();

    private static final Set<Long> NODE_IDS = new LinkedHashSet<Long>(Arrays.asList(5008L, 5009L));
    private static final Set<Long> BRANCH_NODE_IDS = new LinkedHashSet<Long>(Arrays.asList(5009L));

    private static final JourneyStoryValidator.Context PUBLISH_CONTEXT =
            JourneyStoryValidator.Context.of(new LinkedHashSet<Long>(), new LinkedHashSet<Long>(),
                    NODE_IDS, BRANCH_NODE_IDS);

    @Test
    void dispositionNodeDrivesEndingsThroughSystemOutcome() {
        String story = story(5009L, "\"when\":[{\"var\":\"sys.outcome.5009\",\"op\":\"EQ\",\"value\":1}]");

        assertNotNull(validator.validate(story, PUBLISH_CONTEXT));
    }

    /**
     * R22-A6:配了 dispositionNodeId,至少一条非兜底结局的 when 必须引用 sys.outcome.{nodeId}。
     * 作者漏写条件时三个处置结局全走 fallback、静默不生效 —— 发布闸与试检查共用本校验,
     * 与 Context 是否给节点集合无关(试检查走 Context.none)。
     *
     * <p>负控:去掉 requireDispositionReferenced,本用例立刻红。
     */
    @Test
    void dispositionNodeMustBeReferencedByAtLeastOneEndingWhen() {
        String story = story(5009L, "\"when\":[{\"var\":\"clue.page\",\"op\":\"EQ\",\"value\":1}]");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(story, PUBLISH_CONTEXT));
        assertTrue(error.getMessage().contains("sys.outcome.5009"), error.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(story, JourneyStoryValidator.Context.none()),
                "试检查路径(Context.none)同样必须拦下漏写条件的处置结局");
    }

    /** 引用了另一条同样合法的分支节点不算数:引用的必须是 dispositionNodeId 本身。 */
    @Test
    void dispositionNodeReferenceMustMatchTheConfiguredNode() {
        Set<Long> branchNodes = new LinkedHashSet<Long>(Arrays.asList(5008L, 5009L));
        JourneyStoryValidator.Context ctx = JourneyStoryValidator.Context.of(
                new LinkedHashSet<Long>(), new LinkedHashSet<Long>(), NODE_IDS, branchNodes);
        String story = story(5009L, "\"when\":[{\"var\":\"sys.outcome.5008\",\"op\":\"EQ\",\"value\":1}]");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(story, ctx));
        assertTrue(error.getMessage().contains("sys.outcome.5009"), error.getMessage());
    }

    /** 负控:dispositionNodeId 指向没有分支玩法的节点而不报错时,本用例红。 */
    @Test
    void dispositionNodeMustBeABranchNode() {
        String story = story(5008L, "\"when\":[{\"var\":\"sys.outcome.5008\",\"op\":\"EQ\",\"value\":1}]");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(story, PUBLISH_CONTEXT));
        assertTrue(error.getMessage().contains("分支玩法"), error.getMessage());
    }

    /** 负控:dispositionNodeId 指向主题外的节点而不报错时,本用例红。 */
    @Test
    void dispositionNodeMustBelongToTheTheme() {
        String story = story(9999L, "\"when\":[{\"var\":\"sys.outcome.9999\",\"op\":\"EQ\",\"value\":1}]");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(story, PUBLISH_CONTEXT));
        assertTrue(error.getMessage().contains("不属于本主题"), error.getMessage());
    }

    /** 负控:主题外的 sys.* nodeId 不报错时本用例红。 */
    @Test
    void systemVariableNodeMustBelongToTheTheme() {
        String story = story(5009L, "\"when\":[{\"var\":\"sys.outcome.1234\",\"op\":\"EQ\",\"value\":1}]");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(story, PUBLISH_CONTEXT));
        assertTrue(error.getMessage().contains("不属于本主题"), error.getMessage());
    }

    /** 负控:sys.outcome 只有分支 / 随机类玩法会写,引用主题内别的节点同样必须拒。 */
    @Test
    void systemOutcomeNodeMustBeABranchNode() {
        String story = story(5009L, "\"when\":[{\"var\":\"sys.outcome.5008\",\"op\":\"EQ\",\"value\":1}]");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(story, PUBLISH_CONTEXT));
        assertTrue(error.getMessage().contains("不会产生结果序号"), error.getMessage());
    }

    /** 2026-09-23:抛硬币 / 掷骰子 / 抽卡也写 sys.outcome,条件里可以引用它们的节点。 */
    @Test
    void systemOutcomeMayReferenceARandomGameNode() {
        Set<Long> outcomeNodes = new LinkedHashSet<Long>(Arrays.asList(5008L, 5009L));   // 5008 = 抛硬币
        JourneyStoryValidator.Context ctx = JourneyStoryValidator.Context.of(
                null, null, NODE_IDS, BRANCH_NODE_IDS, outcomeNodes);
        // 5009 是处置节点(分支),结局必须引用它;再叠一条引用抛硬币节点 5008
        String story = story(5009L, "\"when\":[{\"var\":\"sys.outcome.5009\",\"op\":\"EQ\",\"value\":1},"
                + "{\"var\":\"sys.outcome.5008\",\"op\":\"EQ\",\"value\":1}]");

        assertNotNull(validator.validate(story, ctx));
        // 对照:同一份剧情在「只认分支节点」的旧口径下必须被拒 —— 证明放开的就是这一条
        assertThrows(IllegalArgumentException.class, () -> validator.validate(story, PUBLISH_CONTEXT));
    }

    @Test
    void systemVariablesOutsideTheWhitelistAreRejected() {
        for (String bad : new String[] { "sys.exhausted", "sys.call", "sys.damage.5009", "sys.asked.5009" }) {
            String story = story(5009L, "\"when\":[{\"var\":\"" + bad + "\",\"op\":\"EQ\",\"value\":1}]");
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validate(story, PUBLISH_CONTEXT), bad);
        }
    }

    @Test
    void hpAndLuckAndAskedAreAccepted() {
        String story = "{\"schemaVersion\":1,\"ending\":{\"exhibits\":["
                + "{\"when\":{\"var\":\"sys.hp\",\"op\":\"LTE\",\"value\":3},\"text\":\"带伤\"},"
                + "{\"when\":{\"var\":\"sys.luck\",\"op\":\"GTE\",\"value\":1},\"text\":\"还有运气\"},"
                + "{\"when\":{\"var\":\"sys.asked.5009.night\",\"op\":\"EQ\",\"value\":1},\"text\":\"问过\"}],"
                + "\"badges\":[{\"when\":[{\"var\":\"sys.mistakes.5009\",\"op\":\"GTE\",\"value\":2}],"
                + "\"title\":\"错过两次\"},{\"title\":\"过客\",\"fallback\":true}],"
                + "\"endings\":[{\"code\":\"done\",\"title\":\"完成\","
                + "\"when\":[{\"var\":\"sys.outcome.5009\",\"op\":\"EQ\",\"value\":1}]},"
                + "{\"code\":\"default\",\"title\":\"暂缓\",\"fallback\":true}]}}";

        assertNotNull(validator.validate(story, PUBLISH_CONTEXT));
    }

    /**
     * 保存期(2 参 Context)不核对节点归属 —— 与章节 / NPC 的分工一致:保存只查自身自洽,
     * 归属留给发布期。老调用方行为一字不变。
     */
    @Test
    void saveTimeContextWithoutNodeSetsKeepsShapeOnly() {
        String story = story(9999L, "\"when\":[{\"var\":\"sys.outcome.9999\",\"op\":\"EQ\",\"value\":1}]");

        assertNotNull(validator.validate(story, JourneyStoryValidator.Context.none()));
    }

    private static String story(Long dispositionNodeId, String whenEntry) {
        return "{\"schemaVersion\":1,\"ending\":{\"dispositionNodeId\":" + dispositionNodeId + ",\"endings\":["
                + "{\"code\":\"a\",\"title\":\"A\"," + whenEntry + "},"
                + "{\"code\":\"default\",\"title\":\"暂缓\",\"fallback\":true}]}}";
    }
}

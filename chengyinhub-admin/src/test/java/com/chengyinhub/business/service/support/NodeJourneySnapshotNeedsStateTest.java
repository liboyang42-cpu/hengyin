package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R17-INT 待办 f:建局口径只有一份 —— {@link NodeJourneySnapshot#needsJourneyState}。
 * 运行期建局(TopicRouteRuntimeServiceImpl.journeyConfigured)与发布前试检查
 * (TopicPublishCheckService)都委托这一个方法(R14 合并了此前两份逐字重复的实现),
 * R16-ARRIVE 不参与建局判定,没有另立第二份口径。
 *
 * <p>本类钉住 R14 扩展的那一半「含选择 effects 也建局」:配置里只有选择结果 effects
 * (分支选项 / 问答选项 / 玩法段级)时也必须建局,否则效果无处可写。
 * 负控:把 needsJourneyState 里的 writesChoiceState 调用去掉,本类立刻红。
 *
 * <p>R19-INT 收口 a 起 roleViews 也计入建局:R16-DUO 的按角色分半要读本局 activityId 定队伍,
 * 只配 roleViews 的线性主题不建局 = Encounter 报「这一局还没开始」,双人视角永远拿不到。
 * 负控:把 needsJourneyState 里的 enabledRoleViews 调用去掉,本类立刻红。
 */
class NodeJourneySnapshotNeedsStateTest {

    @Test
    void requiresEffectsOrCheckAlwaysNeedState() {
        assertTrue(NodeJourneySnapshot.needsJourneyState(
                "{\"schemaVersion\":1,\"requires\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.x\"}]}"));
        assertTrue(NodeJourneySnapshot.needsJourneyState(
                "{\"schemaVersion\":1,\"effects\":[{\"op\":\"SET\",\"var\":\"clue.a\",\"value\":1}]}"));
        assertTrue(NodeJourneySnapshot.needsJourneyState(
                "{\"schemaVersion\":1,\"check\":{\"enabled\":true,\"checkId\":\"ask\"}}"));
    }

    @Test
    void choiceEffectsAloneAlsoNeedState() {
        assertTrue(NodeJourneySnapshot.needsJourneyState("{\"schemaVersion\":1,"
                + "\"branch\":{\"enabled\":true,\"steps\":[{\"id\":\"s1\",\"options\":[{\"id\":\"o1\","
                + "\"effects\":[{\"op\":\"INC\",\"var\":\"counter.trust\",\"value\":1}]}]}]}}"));
        assertTrue(NodeJourneySnapshot.needsJourneyState("{\"schemaVersion\":1,"
                + "\"qa\":{\"enabled\":true,\"options\":[{\"id\":\"o1\",\"correct\":true,"
                + "\"effects\":[{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":1}]}]}}"));
        assertTrue(NodeJourneySnapshot.needsJourneyState("{\"schemaVersion\":1,"
                + "\"sort\":{\"enabled\":true,\"outcomeCode\":\"SORTED\","
                + "\"effects\":[{\"op\":\"INC\",\"var\":\"counter.trust\",\"value\":1}]}}"));
    }

    /**
     * R19-INT 收口 a:只配了 roleViews(双人视角)也要建局 —— 分半要用本局 activityId 定队伍,
     * 没有局时 /api/play/encounter 直接报「这一局还没开始」,视角永远下发不出去。
     */
    @Test
    void roleViewsAloneAlsoNeedsState() {
        assertTrue(NodeJourneySnapshot.needsJourneyState("{\"schemaVersion\":1,"
                + "\"roleViews\":{\"enabled\":true,\"roles\":[{\"id\":\"A\"},{\"id\":\"B\"}]}}"));
    }

    /** 没启用(缺 enabled / enabled=false)的 roleViews 只是死配置,不触发建局。 */
    @Test
    void disabledRoleViewsDoNotNeedState() {
        assertFalse(NodeJourneySnapshot.needsJourneyState("{\"schemaVersion\":1,"
                + "\"roleViews\":{\"roles\":[{\"id\":\"A\"},{\"id\":\"B\"}]}}"));
        assertFalse(NodeJourneySnapshot.needsJourneyState("{\"schemaVersion\":1,"
                + "\"roleViews\":{\"enabled\":false,\"roles\":[]}}"));
    }

    /**
     * 边界:玩法段只是启用、没配任何 effects,或配置里只有随局冻结的只读段
     * (叙事段与 roleViews 之外的段,老线性主题不建局的行为不变),都不触发建局。
     */
    @Test
    void enabledSectionWithoutChoiceEffectsDoesNotNeedState() {
        assertFalse(NodeJourneySnapshot.needsJourneyState(
                "{\"schemaVersion\":1,\"sort\":{\"enabled\":true}}"));
        assertFalse(NodeJourneySnapshot.needsJourneyState(
                "{\"schemaVersion\":1,\"enter\":{\"opener\":\"推门\"}}"));
    }

    @Test
    void blankOrBrokenConfigNeverBuildsSession() {
        assertFalse(NodeJourneySnapshot.needsJourneyState(null));
        assertFalse(NodeJourneySnapshot.needsJourneyState("   "));
        assertFalse(NodeJourneySnapshot.needsJourneyState("{broken"));
        assertFalse(NodeJourneySnapshot.needsJourneyState("[]"));
    }
}

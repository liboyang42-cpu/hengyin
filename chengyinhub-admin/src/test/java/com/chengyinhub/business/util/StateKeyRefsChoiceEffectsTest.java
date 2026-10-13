package com.chengyinhub.business.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R14-PLAY 键收集:选择结果 effects(分支选项 / 问答选项 / 五个通过类玩法)与
 * check 的 critEffects / fumbleEffects 都进发布闸对账;sys.* 一律不进注册表。
 *
 * <p>负控:把 collectOptionEffects 或 critEffects / fumbleEffects 的收集去掉,本类立红。
 */
class StateKeyRefsChoiceEffectsTest {

    @Test
    void collectsChoiceEffectsFromBranchQaAndKits() {
        String json = "{"
                + "\"branch\":{\"steps\":[{\"id\":\"ask\",\"options\":["
                + "{\"id\":\"a\",\"effects\":[{\"op\":\"SET\",\"var\":\"counter.disposition\",\"value\":1}]}]}]},"
                + "\"qa\":{\"options\":[{\"id\":\"one\","
                + "\"effects\":[{\"op\":\"INC\",\"var\":\"counter.attitude\",\"value\":-1}]}]},"
                + "\"sort\":{\"effects\":[{\"op\":\"SET\",\"var\":\"clue.notes_restored\",\"value\":1}]},"
                + "\"match\":{\"effects\":[{\"op\":\"ADD_TAG\",\"value\":\"tag.connected\"}]},"
                + "\"classify\":{\"effects\":[{\"op\":\"INC\",\"var\":\"counter.classified\",\"value\":1}]},"
                + "\"hiddenObject\":{\"effects\":[{\"op\":\"SET\",\"var\":\"clue.all_found\",\"value\":1}]},"
                + "\"pricePair\":{\"effects\":[{\"op\":\"SET\",\"var\":\"clue.guess_hit\",\"value\":1}]}}";

        assertEquals(set("counter.disposition", "counter.attitude", "clue.notes_restored",
                "tag.connected", "counter.classified", "clue.all_found", "clue.guess_hit"),
                StateKeyRefs.collect(json));
    }

    @Test
    void collectsCritAndFumbleEffects() {
        String json = "{\"check\":{\"enabled\":true,\"checkId\":\"c1\",\"tier\":\"easy\","
                + "\"critEffects\":[{\"op\":\"SET\",\"var\":\"clue.crit\",\"value\":1}],"
                + "\"fumbleEffects\":[{\"op\":\"ADD_TAG\",\"value\":\"tag.fumbled\"}]}}";

        assertEquals(set("clue.crit", "tag.fumbled"), StateKeyRefs.collect(json));
    }

    /** sys.* 是服务端保留变量:条件与效果里都不产生注册键。 */
    @Test
    void systemVariablesNeverBecomeRegisteredKeys() {
        String json = "{\"requires\":["
                + "{\"var\":\"sys.hp\",\"op\":\"LTE\",\"value\":3},"
                + "{\"var\":\"sys.outcome.5009\",\"op\":\"EQ\",\"value\":1},"
                + "{\"var\":\"sys.asked.5009.night\",\"op\":\"EQ\",\"value\":1},"
                + "{\"var\":\"sys.mistakes.5009\",\"op\":\"GTE\",\"value\":1}],"
                + "\"effects\":[{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":2},"
                + "{\"op\":\"SET\",\"var\":\"counter.visits\",\"value\":1}]}";

        assertEquals(set("counter.visits"), StateKeyRefs.collect(json));
    }

    /** 主题剧情配置的 endings 里引用 sys.outcome.{dispositionNodeId} 同样不进注册表。 */
    @Test
    void journeyStorySystemVariablesAreNotCollected() {
        String story = "{\"schemaVersion\":1,\"ending\":{\"dispositionNodeId\":5009,\"endings\":["
                + "{\"code\":\"correct\",\"title\":\"更正\",\"when\":["
                + "{\"var\":\"sys.outcome.5009\",\"op\":\"EQ\",\"value\":1}]},"
                + "{\"code\":\"default\",\"title\":\"暂缓\",\"fallback\":true}]}}";

        assertTrue(StateKeyRefs.collectJourneyStory(story).isEmpty());
    }

    private static Set<String> set(String... values) {
        return new LinkedHashSet<String>(Arrays.asList(values));
    }
}

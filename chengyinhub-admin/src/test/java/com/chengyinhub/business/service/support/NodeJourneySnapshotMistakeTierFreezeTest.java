package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 阶段 2:mistakeTier 必须随建局冻结进 nodeJourney 块。
 *
 * <p>为什么单独钉一条:运行期读块配置走 {@code NodeJourneySnapshot.block(快照, nodeId)},
 * 快照里**有**这个节点的块就不再回退实时模板 —— 冻结清单漏收 mistakeTier 的结果不是「读不到新值」,
 * 而是「永远读不到 mistakeTier」(缺省 easy),配了 hard 的模板在全新一局里也只扣 1 血,
 * 且没有任何报错。负控:从 MECHANIC_SEGMENTS 删掉 mistakeTier,本类第一条立刻红。
 */
class NodeJourneySnapshotMistakeTierFreezeTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void mechanicSegmentsCarryMistakeTierIntoFrozenBlock() throws Exception {
        ObjectNode target = json.createObjectNode();

        NodeJourneySnapshot.copyMechanicSegments(
                json.readTree("{\"schemaVersion\":1,\"mistakeTier\":\"hard\"}"), target);

        assertEquals("hard", target.path("mistakeTier").asText());
    }

    /** 没配的段不写:老模板冻结出来的块不许凭空多一个 mistakeTier 键。 */
    @Test
    void absentMistakeTierStaysAbsent() throws Exception {
        ObjectNode target = json.createObjectNode();

        NodeJourneySnapshot.copyMechanicSegments(
                json.readTree("{\"schemaVersion\":1,\"effects\":[]}"), target);

        assertFalse(target.has("mistakeTier"));
    }
}

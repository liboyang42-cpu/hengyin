package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自玩局帮助身份：生成、只写一次、只认合法形状、只给自玩 default 局。 */
class SelfPlayHelpRunTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void newIdentitiesAreWellFormedAndDistinct() {
        String a = SelfPlayHelpRun.newIdentity();
        String b = SelfPlayHelpRun.newIdentity();
        assertTrue(SelfPlayHelpRun.isIdentity(a), a);
        assertNotEquals(a, b);
        assertTrue(a.length() <= 64, "must fit play_help_run_quota.play_instance_id VARCHAR(64)");
    }

    @Test
    void stampWritesOnlyForSelfPlayAndNeverOverwrites() {
        ObjectNode root = JSON.createObjectNode();
        String first = SelfPlayHelpRun.newIdentity();
        assertEquals(first, SelfPlayHelpRun.stamp(root, 0L, first));
        assertEquals(first, SelfPlayHelpRun.stamp(root, null, SelfPlayHelpRun.newIdentity()));
        assertEquals(first, SelfPlayHelpRun.read(root));

        ObjectNode activity = JSON.createObjectNode();
        assertNull(SelfPlayHelpRun.stamp(activity, 44L, SelfPlayHelpRun.newIdentity()));
        assertFalse(activity.has(SelfPlayHelpRun.FIELD));

        assertThrows(IllegalArgumentException.class, () -> SelfPlayHelpRun.stamp(JSON.createObjectNode(), 0L, "default"));
    }

    @Test
    void readRejectsMissingOrMalformedValues() throws Exception {
        assertNull(SelfPlayHelpRun.read(null));
        assertNull(SelfPlayHelpRun.read(JSON.readTree("{}")));
        assertNull(SelfPlayHelpRun.read(JSON.readTree("{\"helpRunInstance\":\"default\"}")));
        assertNull(SelfPlayHelpRun.read(JSON.readTree("{\"helpRunInstance\":\"selfplay:x\"}")));
        assertNull(SelfPlayHelpRun.read(JSON.readTree("{\"helpRunInstance\":42}")));
    }

    @Test
    void helpInstanceFollowsRunKind() throws Exception {
        String id = SelfPlayHelpRun.newIdentity();
        var stamped = JSON.readTree("{\"helpRunInstance\":\"" + id + "\"}");

        assertEquals(id, SelfPlayHelpRun.helpInstance(run(0L, "default"), stamped));
        assertEquals(id, SelfPlayHelpRun.helpInstance(run(null, "default"), stamped));
        // 旧自玩局：无身份 → 不可用。
        assertNull(SelfPlayHelpRun.helpInstance(run(0L, "default"), JSON.readTree("{}")));
        // 活动场次 default 局：即使快照里有身份也不可用。
        assertNull(SelfPlayHelpRun.helpInstance(run(44L, "default"), stamped));
        // 重玩等真实实例：原样沿用，行为不变。
        assertEquals("replay:12", SelfPlayHelpRun.helpInstance(run(0L, "replay:12"), stamped));
        assertNull(SelfPlayHelpRun.helpInstance(run(0L, " "), stamped));
        assertNull(SelfPlayHelpRun.helpInstance(null, stamped));
    }

    private static TopicRouteSession run(Long activityId, String instance) {
        TopicRouteSession session = new TopicRouteSession();
        session.setActivityId(activityId);
        session.setPlayInstanceId(instance);
        return session;
    }
}

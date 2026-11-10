package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 首局帮助身份：生成、只写一次、只认合法形状；自玩与活动场次首局都给，旧 selfplay: 只在自玩局认。 */
class FirstRunHelpIdentityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LEGACY = "selfplay:0b0e6f53-2f1d-4c4a-9a51-3c1b9f7f0a01";

    @Test
    void newIdentitiesAreWellFormedAndDistinct() {
        String a = FirstRunHelpIdentity.newIdentity();
        String b = FirstRunHelpIdentity.newIdentity();
        assertTrue(a.startsWith(FirstRunHelpIdentity.PREFIX), a);
        assertTrue(FirstRunHelpIdentity.isIdentity(a), a);
        assertTrue(FirstRunHelpIdentity.isIdentity(LEGACY));
        assertNotEquals(a, b);
        assertTrue(a.length() <= 64, "must fit play_help_run_quota.play_instance_id VARCHAR(64)");
        // 与 c4 的 selfplay: 同长度：既有事实键/额度键长度口径不变。
        assertEquals(LEGACY.length(), a.length());
    }

    @Test
    void stampWritesOnceAndNeverOverwrites() {
        ObjectNode root = JSON.createObjectNode();
        String first = FirstRunHelpIdentity.newIdentity();
        assertEquals(first, FirstRunHelpIdentity.stamp(root, first));
        assertEquals(first, FirstRunHelpIdentity.stamp(root, FirstRunHelpIdentity.newIdentity()));
        assertEquals(first, FirstRunHelpIdentity.read(root));

        // c4 已落库的自玩局身份不被改写。
        ObjectNode legacy = JSON.createObjectNode().put(FirstRunHelpIdentity.FIELD, LEGACY);
        assertEquals(LEGACY, FirstRunHelpIdentity.stamp(legacy, FirstRunHelpIdentity.newIdentity()));

        assertThrows(IllegalArgumentException.class, () -> FirstRunHelpIdentity.stamp(JSON.createObjectNode(), "default"));
        // 新局只生成 firstrun:，不再写旧前缀。
        assertThrows(IllegalArgumentException.class, () -> FirstRunHelpIdentity.stamp(JSON.createObjectNode(), LEGACY));
    }

    @Test
    void readRejectsMissingOrMalformedValues() throws Exception {
        assertNull(FirstRunHelpIdentity.read(null));
        assertNull(FirstRunHelpIdentity.read(JSON.readTree("{}")));
        assertNull(FirstRunHelpIdentity.read(JSON.readTree("{\"helpRunInstance\":\"default\"}")));
        assertNull(FirstRunHelpIdentity.read(JSON.readTree("{\"helpRunInstance\":\"firstrun:x\"}")));
        assertNull(FirstRunHelpIdentity.read(JSON.readTree("{\"helpRunInstance\":\"replay:12\"}")));
        assertNull(FirstRunHelpIdentity.read(JSON.readTree("{\"helpRunInstance\":42}")));
    }

    @Test
    void helpInstanceCoversSelfPlayAndActivityFirstRuns() throws Exception {
        String id = FirstRunHelpIdentity.newIdentity();
        var stamped = JSON.readTree("{\"helpRunInstance\":\"" + id + "\"}");
        var legacy = JSON.readTree("{\"helpRunInstance\":\"" + LEGACY + "\"}");

        assertEquals(id, FirstRunHelpIdentity.helpInstance(run(0L, "default"), stamped));
        assertEquals(id, FirstRunHelpIdentity.helpInstance(run(null, "default"), stamped));
        // 活动场次首局：同样用冻结身份。
        assertEquals(id, FirstRunHelpIdentity.helpInstance(run(44L, "default"), stamped));
        // 旧 selfplay: 身份：自玩局照读，活动场次局不认。
        assertEquals(LEGACY, FirstRunHelpIdentity.helpInstance(run(0L, "default"), legacy));
        assertNull(FirstRunHelpIdentity.helpInstance(run(44L, "default"), legacy));
        // 旧首局：无身份 → 不可用（自玩与活动场次一样）。
        assertNull(FirstRunHelpIdentity.helpInstance(run(0L, "default"), JSON.readTree("{}")));
        assertNull(FirstRunHelpIdentity.helpInstance(run(44L, "default"), JSON.readTree("{}")));
        // 重玩等真实实例：原样沿用，行为不变。
        assertEquals("replay:12", FirstRunHelpIdentity.helpInstance(run(44L, "replay:12"), stamped));
        assertNull(FirstRunHelpIdentity.helpInstance(run(0L, " "), stamped));
        assertNull(FirstRunHelpIdentity.helpInstance(null, stamped));
    }

    private static TopicRouteSession run(Long activityId, String instance) {
        TopicRouteSession session = new TopicRouteSession();
        session.setActivityId(activityId);
        session.setPlayInstanceId(instance);
        return session;
    }
}

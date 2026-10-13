package com.chengyinhub.business.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故事变量构造函数(StoryVars)本身的契约。
 *
 * <p>它是 /api/play/advanced 会话视图与 /api/play/nodes 进度回包的**唯一**真源 ——
 * 这两条回包对同一个玩家必须得到同一份 map,任何一边自己拼一份都会漂移成两屏不一致。
 */
class PlayProgressVarsSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void 建档答案与作品名一起进map() throws Exception {
        Map<String, Object> vars = StoryVars.fromState(MAPPER.readTree(
                "{\"profile\":{\"done\":true,\"answers\":{\"name\":\"阿猫\",\"job\":\"clerk\"}},"
                        + "\"diyName\":{\"name\":\"窗边的猫\"}}"));

        assertEquals("阿猫", vars.get("name"));
        assertEquals("clerk", vars.get("job"));
        assertEquals("窗边的猫", vars.get("diyName"));
    }

    @Test
    void 负控_空值不进map() throws Exception {
        // 没值不放空串:替换方要区分「没值(保花括号)」与「值就是空串」
        Map<String, Object> vars = StoryVars.fromState(MAPPER.readTree(
                "{\"profile\":{\"answers\":{\"name\":\"\",\"note\":\"有值\"}},\"diyName\":{\"name\":\"\"}}"));

        assertFalse(vars.containsKey("name"), vars.toString());
        assertFalse(vars.containsKey("diyName"), vars.toString());
        assertTrue(vars.containsKey("note"), vars.toString());
    }

    @Test
    void 负控_坏状态跳过_多局同键先到先得() {
        Map<String, Object> vars = StoryVars.fromStateJsons(Arrays.asList(
                "{\"profile\":{\"answers\":{\"name\":\"先填的\"}}}",
                "{坏 json",
                "{\"profile\":{\"answers\":{\"name\":\"后填的\",\"job\":\"clerk\"}}}",
                null));

        assertEquals("先填的", vars.get("name"), "建档先到先得,后填的不覆盖:" + vars);
        assertEquals("clerk", vars.get("job"), "没出现过的键照常收:" + vars);
    }

    @Test
    void 没有会话时返回空map() {
        assertTrue(StoryVars.fromStateJsons(null).isEmpty());
        assertTrue(StoryVars.fromStateJsons(Collections.<String>emptyList()).isEmpty());
    }
}

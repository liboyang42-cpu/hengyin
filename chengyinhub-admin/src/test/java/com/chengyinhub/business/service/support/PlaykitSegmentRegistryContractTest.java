package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * C:U-070 / C:U-079:玩法段名的服务端真源是 GameplaySupportMatrix(types/addons 的 section)。
 * 小程序 playkit-view.js 的 KIT_PRIORITY 必须与它逐项对上,差异只允许下面两张显式例外表。
 * 客户端漏一段 = 服务端下发了玩家也看不到;多一段 = 客户端认了服务端不会发的名字。
 */
class PlaykitSegmentRegistryContractTest {

    /** 服务端有、但不走 cy-playkit 分发的段:check 走 JOURNEY_SEGMENTS,其余是计时/排行/多人附加能力。 */
    private static final Set<String> SERVER_ONLY = new TreeSet<>(Arrays.asList("check", "timer", "leaderboard", "multiplayer"));
    /** bingo 在矩阵里是主题级呈现(section 为空,以 key 计)。 */
    private static final String SECTIONLESS_KEY = "bingo";

    private static Set<String> serverSegments() {
        Set<String> out = new TreeSet<>();
        JsonNode matrix = GameplaySupportMatrix.view();
        for (String group : Arrays.asList("types", "addons")) {
            for (JsonNode row : matrix.path(group)) {
                String section = row.path("section").asText("");
                out.add(section.isEmpty() ? row.path("key").asText() : section);
            }
        }
        return out;
    }

    private static Set<String> clientKitPriority() throws Exception {
        String js = new String(Files.readAllBytes(Paths.get(System.getProperty("user.dir"),
                "../chengyinhub-xcx/pages/play/utils/playkit-view.js")), StandardCharsets.UTF_8);
        int start = js.indexOf("const KIT_PRIORITY = [");
        String block = js.substring(start, js.indexOf("];", start))
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("'([A-Za-z0-9]+)'").matcher(block);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    void clientKitPriorityMatchesServerMatrix() throws Exception {
        Set<String> expected = serverSegments();
        expected.removeAll(SERVER_ONLY);
        assertEquals(true, expected.contains(SECTIONLESS_KEY), "矩阵里应有 bingo");
        assertEquals(expected, new TreeSet<>(clientKitPriority()),
                "KIT_PRIORITY 与 GameplaySupportMatrix 段名不一致;新增玩法须两边同时登记");
    }
}

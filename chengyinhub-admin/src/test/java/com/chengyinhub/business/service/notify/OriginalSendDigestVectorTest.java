package com.chengyinhub.business.service.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.service.support.CanonicalDigest;
import com.chengyinhub.common.utils.html.EscapeUtil;

/**
 * Same SEND vector as ChannelMessageService: TextBoundary.sanitize is EscapeUtil.clean,
 * then Fastjson2 JSON.toJSONString of the argument list, then CanonicalDigest.sha256Hex.
 * Mention ids are sorted and de-duplicated before hashing.
 */
class OriginalSendDigestVectorTest {
    @Test
    void plainHelloMatchesTheOriginalVector() {
        assertEquals("d857aa89c49cc91416ee24a349e362237182d8e056db7eecc49ec1ada36ba7d2",
                digest("hello", 9L, 0L, List.of(), List.of(), false));
    }

    @Test
    void quotedNewlineAndUnsortedMentionsUseTheSanitizedSortedVector() {
        assertEquals("6845f72f85f59d22d45569c721f15f0ed8c38df15c2c8679267e7037194d73b3",
                digest("他说\"你好\"\n下一行", 9L, 15L, Arrays.asList(8L, 8L, 2L), Arrays.asList(3L, 1L), false));
    }

    @Test
    void scriptTagIsRemovedBeforeHashing() {
        String raw = "<script>alert(1)</script>";
        assertEquals("alert(1)", EscapeUtil.clean(raw));
        assertEquals("7a82356a4861c01d805ff17257fac9a081dafeb20f609d5551606f38fcd705bd",
                digest(raw, 9L, 0L, java.util.List.of(), java.util.List.of(), false));
    }

    @Test
    void htmlFilterOutputIsWhatGetsHashed() {
        String raw = "你好\"引号\"\n换行<b>粗</b>";
        assertEquals(raw, EscapeUtil.clean(raw));
        assertEquals("651a54f8ef6847c4167d490003d5b8df49fce9a5546c6fba932adb9532e36311",
                digest(raw, 42L, 0L, Arrays.asList(3L, 1L), List.of(), false));
    }

    private static String digest(String content, long channel, long reply, List<Long> members, List<Long> roles,
            boolean everyone) {
        return CanonicalDigest.sha256Hex(JSON.toJSONString(Arrays.asList(
                "SEND", channel, 1, EscapeUtil.clean(content), null, reply, unique(members), unique(roles), everyone)));
    }

    private static List<Long> unique(List<Long> requested) {
        if (requested == null || requested.isEmpty()) return Collections.emptyList();
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : requested) ids.add(id);
        List<Long> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        return sorted;
    }
}

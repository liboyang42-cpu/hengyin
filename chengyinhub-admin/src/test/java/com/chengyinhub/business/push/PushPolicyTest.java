package com.chengyinhub.business.push;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PushPolicyTest
{
    private static Map<String, Object> row(Object... kv)
    {
        Map<String, Object> m = new HashMap<>();
        m.put("memberMuted", 0);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void noPreferenceMeansAllMessagesForDmsAndPlainGroups()
    {
        assertTrue(PushPolicy.chatAllowed(row(), false, false, null));
    }

    @Test
    void mutesWinEvenOverMentions()
    {
        assertFalse(PushPolicy.chatAllowed(row("memberMuted", 1), true, false, null));
        assertFalse(PushPolicy.chatAllowed(row("convPermanent", 1), true, false, null));
        assertFalse(PushPolicy.chatAllowed(row("convMutedActive", 1L), true, false, null));
    }

    @Test
    void mentionsOnlyLevelPushesMentionsAndRespectsSuppressBroadcast()
    {
        assertFalse(PushPolicy.chatAllowed(row("convLevel", 1), false, false, null));
        assertTrue(PushPolicy.chatAllowed(row("convLevel", 1), true, false, null));
        assertTrue(PushPolicy.chatAllowed(row("convLevel", 1), false, true, null));
        assertFalse(PushPolicy.chatAllowed(row("convLevel", 1, "suppressBroadcast", 1), false, true, null));
        assertFalse(PushPolicy.chatAllowed(row("convLevel", 2), true, false, null));
    }

    @Test
    void levelInheritsConversationThenAccountThenClubDefault()
    {
        assertFalse(PushPolicy.chatAllowed(row("convLevel", 3, "accountLevel", 2), true, false, null));
        assertTrue(PushPolicy.chatAllowed(row("convLevel", 0, "accountLevel", 2), false, false, null));
        // 俱乐部频道：没有个人设置时用俱乐部共享默认（未配置 = 1 仅@）
        assertFalse(PushPolicy.chatAllowed(row(), false, false, 1));
        assertTrue(PushPolicy.chatAllowed(row(), true, false, 1));
        assertTrue(PushPolicy.chatAllowed(row("accountLevel", 0), false, false, 1));
    }

    @Test
    void quietHoursCoveringTheWholeDaySuppress()
    {
        // 00:00–23:59 几乎全天；只有 23:59 这一分钟不在内
        Map<String, Object> quiet = row("convQuietStart", "00:00", "convQuietEnd", "23:59", "convQuietTz", "UTC");
        boolean inLastMinute = java.time.LocalTime.now(java.time.ZoneOffset.UTC).isAfter(java.time.LocalTime.of(23, 58, 59));
        if (!inLastMinute) assertFalse(PushPolicy.chatAllowed(quiet, true, false, null));
    }

    @Test
    void noticesRespectAccountOffAndClubMute()
    {
        assertTrue(PushPolicy.noticeAllowed(null, null));
        assertFalse(PushPolicy.noticeAllowed(Map.of("level", 2), null));
        assertTrue(PushPolicy.noticeAllowed(Map.of("level", 1), null));
        assertFalse(PushPolicy.noticeAllowed(null, Map.of("level", 3, "permanentMuted", 1)));
        assertFalse(PushPolicy.noticeAllowed(null, Map.of("level", 2)));
    }

    @Test
    void previewFollowsConversationThenAccount()
    {
        assertTrue(PushPolicy.showPreview(row()));
        assertFalse(PushPolicy.showPreview(row("accountShowPreview", 0)));
        assertTrue(PushPolicy.showPreview(row("convShowPreview", 1, "accountShowPreview", 0)));
    }
}

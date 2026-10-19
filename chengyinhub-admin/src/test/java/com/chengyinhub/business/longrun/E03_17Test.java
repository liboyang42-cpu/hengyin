package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.service.impl.PlayJourneyStoryServiceImpl;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Existing supplied-session archive evaluation, not HTTP/real-MySQL. All methods NOT_RUN. */
class E03_17Test {
    private PlayJourneyStoryServiceImpl service;

    @BeforeEach
    void setup() {
        service = new PlayJourneyStoryServiceImpl(mock(CmsTopicMapper.class), mock(CmsTopicChapterMapper.class),
                mock(CmsTopicNodeMapper.class), mock(TopicRouteSessionMapper.class),
                mock(ThemeStateKeyService.class), mock(NpcProfileService.class));
        GameRuntimeSessionMapper games = mock(GameRuntimeSessionMapper.class);
        ReflectionTestUtils.setField(service, "gameSessionMapper", games);
        // Old/default rows deliberately precede replay rows, exposing avatar-first and collection merging.
        when(games.selectPlayerSessionsByTopic(0L, 700L, 97L)).thenReturn(Arrays.asList(
                row("default", "default"), row(null, "legacy"), row("replay:41", "current"),
                row("replay:42", "other"), row("", "blank"), row(" default ", "padded")));
    }

    @Test
    void defaultArchiveIncludesOnlyDefaultAndLegacyNullInstances() {
        Map<String, Object> result = archive("default");
        String text = result.toString();
        assertTrue(text.contains("default-identity"));
        assertTrue(text.contains("legacy-identity"));
        assertEquals("default-avatar", result.get("avatarUrl"));
        assertFalse(text.contains("current-"));
        assertFalse(text.contains("other-"));
        assertFalse(text.contains("blank-"));
        assertFalse(text.contains("padded-"));
        assertEquals(archive(null), result, "legacy null route model is exactly default, never a wildcard");
    }

    @Test
    void replayArchiveContainsOnlyItsOwnIdentityAvatarPhotosAndNotes() {
        Map<String, Object> result = archive("replay:41");
        assertEquals("current-avatar", result.get("avatarUrl"));
        assertEquals("[{label=Identity, value=current-identity}]", result.get("identity").toString());
        assertEquals("[{url=current-photo, title=Photo}, {url=current-qa, title=QA}]", result.get("photos").toString());
        assertEquals("[{title=Note, text=current-note}]", result.get("notes").toString());
        assertFalse(result.toString().contains("default-"));
        assertFalse(result.toString().contains("legacy-"));
        assertFalse(result.toString().contains("other-"));
    }

    @Test
    void blankAndPaddedInstancesAreComparedExactlyWithoutTrimming() {
        Map<String, Object> blank = archive("");
        assertEquals("blank-avatar", blank.get("avatarUrl"));
        assertFalse(blank.toString().contains("default-"));
        Map<String, Object> padded = archive(" default ");
        assertEquals("padded-avatar", padded.get("avatarUrl"));
        assertFalse(padded.toString().contains("legacy-"));
        Map<String, Object> absent = archive("replay:missing");
        assertEquals("[]", absent.get("identity").toString());
        assertEquals("[]", absent.get("photos").toString());
        assertEquals("[]", absent.get("notes").toString());
        assertFalse(absent.containsKey("avatarUrl"));
    }

    private Map<String, Object> archive(String instance) {
        TopicRouteSession run = new TopicRouteSession();
        run.setMemberId(97L);
        run.setTopicId(700L);
        run.setActivityId(0L);
        run.setPlayInstanceId(instance);
        // Invoke the existing internal method with the already-selected session; parsing is real.
        return ReflectionTestUtils.invokeMethod(service, "archive", 97L, 700L, run);
    }

    private static GameRuntimeSession row(String instance, String label) {
        GameRuntimeSession row = new GameRuntimeSession();
        row.setPlayInstanceId(instance);
        row.setConfigSnapshotJson("{\"profile\":{\"questions\":[{\"key\":\"who\",\"label\":\"Identity\","
                + "\"options\":[{\"key\":\"chosen\",\"label\":\"" + label + "-identity\"}]}]},"
                + "\"photoCheck\":{\"title\":\"Photo\"},\"qa\":{\"title\":\"QA\"},\"note\":{\"title\":\"Note\"}}");
        row.setStateJson("{\"profile\":{\"done\":true,\"avatarUrl\":\"" + label + "-avatar\",\"answers\":{\"who\":\"chosen\"}},"
                + "\"photoCheck\":{\"lastUrl\":\"" + label + "-photo\"},\"qa\":{\"lastImageUrl\":\"" + label + "-qa\"},"
                + "\"note\":{\"text\":\"" + label + "-note\"}}");
        return row;
    }
}

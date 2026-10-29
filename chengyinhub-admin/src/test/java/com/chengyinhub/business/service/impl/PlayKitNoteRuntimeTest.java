package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.PlayNote;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayNoteMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预制人生 note 留言的运行期契约。
 *
 * 跨玩家存储走 play_note(新表,见 migration_20260917_play_note.sql):
 * 我留的那句是我自己的,后来的人读到的 previous 里不该混进我自己的;
 * 同一个人同一站只能留一句(uk_member_node)。
 */
class PlayKitNoteRuntimeTest {

    private static final long MEMBER_ID = 7L;
    private static final long ACTIVITY_ID = 9L;
    private static final long TOPIC_ID = 19L;
    private static final long NODE_ID = 29L;
    private static final long TEMPLATE_ID = 39L;
    private static final long SESSION_ID = 51L;

    private GameRuntimeSessionMapper sessionMapper;
    private PlayAttemptLogMapper attemptMapper;
    private PlayNoteMapper playNoteMapper;
    private AdvancedGameRuntimeServiceImpl service;
    private Date now = new Date(1789017600000L);

    private static final String NOTE_CONFIG = "{\"schemaVersion\":1,\"note\":{\"enabled\":true,"
            + "\"title\":\"写一句留给下一个人\",\"prompt\":\"你看到窗外什么\",\"maxLength\":40,"
            + "\"presets\":[\"天是灰的\",\"有人在等红灯\"],\"showPrevious\":3,\"xp\":3}}";

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        attemptMapper = mock(PlayAttemptLogMapper.class);
        playNoteMapper = mock(PlayNoteMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setTopicId(TOPIC_ID);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);

        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper",
                mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class));
        ReflectionTestUtils.setField(service, "templateMapper", mock(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "registrationMapper",
                mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(service, "teamMapper", mock(PlayTeamMapper.class));
        ReflectionTestUtils.setField(service, "attemptMapper", attemptMapper);
        ReflectionTestUtils.setField(service, "playNoteMapper", playNoteMapper);
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> now);
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.installOn(service);
    }

    @Test
    void 留言落库_内容归本站且计分一次() {
        GameRuntimeSession session = running(NOTE_CONFIG, "{}");
        arm(session);
        when(playNoteMapper.insert(any())).thenReturn(1);
        when(playNoteMapper.selectRecentByNode(eq(NODE_ID), anyInt())).thenReturn(
                Arrays.asList(note(8L, "上一个人的话", 3000L), note(9L, "再上一个的话", 2000L)));

        Map<String, Object> view = act(session, "note-1", "天是灰的");

        ArgumentCaptor<PlayNote> captor = ArgumentCaptor.forClass(PlayNote.class);
        verify(playNoteMapper).insert(captor.capture());
        PlayNote saved = captor.getValue();
        assertEquals(TOPIC_ID, saved.getTopicId());
        assertEquals(NODE_ID, saved.getNodeId());
        assertEquals(MEMBER_ID, saved.getMemberId());
        assertEquals("天是灰的", saved.getContent());
        assertEquals(3, view.get("score"), "留言完成给一次 xp");

        Map<?, ?> kit = (Map<?, ?>) ((Map<?, ?>) view.get("playKit")).get("note");
        assertEquals(true, kit.get("done"));
        assertEquals(2, ((List<?>) kit.get("previous")).size(), "前几条要给后来的人看");
        assertNull(kit.get("mine"), "自己刚留的这句不该同时出现在 previous 和 mine 里");
    }

    @Test
    void 负控_自己留过的那条从previous剔除并单独给mine() {
        GameRuntimeSession session = running(NOTE_CONFIG, "{\"note\":{\"done\":true,\"text\":\"我先留的\"}}");
        arm(session);
        when(playNoteMapper.selectRecentByNode(eq(NODE_ID), anyInt())).thenReturn(Arrays.asList(
                note(8L, "上一个人的话", 3000L),
                note(MEMBER_ID, "我先留的", 1000L)));

        Map<String, Object> view = service.state(MEMBER_ID, SESSION_ID);

        Map<?, ?> kit = (Map<?, ?>) ((Map<?, ?>) view.get("playKit")).get("note");
        List<?> previous = (List<?>) kit.get("previous");
        assertEquals(1, previous.size(), "previous 里只留别人的话:" + previous);
        assertEquals("上一个人的话", ((Map<?, ?>) previous.get(0)).get("text"));
        Map<?, ?> mine = (Map<?, ?>) kit.get("mine");
        assertNotNull(mine);
        assertEquals("我先留的", mine.get("text"));
        assertEquals(true, kit.get("done"));
    }

    @Test
    void 负控_重复提交必须拒() {
        GameRuntimeSession session = running(NOTE_CONFIG, "{\"note\":{\"done\":true,\"text\":\"我先留的\"}}");
        arm(session);

        assertThrows(IllegalStateException.class, () -> act(session, "note-again", "再留一句"));
    }

    @Test
    void 负控_同一站同一个人不能留第二句() {
        GameRuntimeSession session = running(NOTE_CONFIG, "{}");
        arm(session);
        when(playNoteMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_member_node"));

        assertThrows(IllegalStateException.class, () -> act(session, "note-dup", "又一句"));
    }

    @Test
    void 负控_空留言与超长留言必须拒() {
        GameRuntimeSession session = running(NOTE_CONFIG, "{}");
        arm(session);

        assertThrows(IllegalArgumentException.class, () -> act(session, "note-blank", "   "));
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 41; i++) tooLong.append('字');
        assertThrows(IllegalArgumentException.class, () -> act(session, "note-long", tooLong.toString()));
    }

    @Test
    void 负控_没配留言段时动作必须拒() {
        GameRuntimeSession session = running("{\"schemaVersion\":1,"
                + "\"steps\":{\"enabled\":true,\"goal\":6000}}", "{}");
        arm(session);

        assertThrows(IllegalArgumentException.class, () -> act(session, "note-off", "留一句"));
    }

    // ==================== 脚手架 ====================

    private static PlayNote note(Long memberId, String content, long atMs) {
        PlayNote row = new PlayNote();
        row.setMemberId(memberId);
        row.setContent(content);
        row.setCreateTime(new Date(atMs));
        return row;
    }

    private void arm(GameRuntimeSession session) {
        when(sessionMapper.selectAccessibleById(SESSION_ID, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
    }

    private Map<String, Object> act(GameRuntimeSession session, String key, String text) {
        when(attemptMapper.selectByIdempotencyKey(key)).thenReturn(null);
        Map<String, Object> payload = new HashMap<>();
        payload.put("text", text);
        return service.act(MEMBER_ID, SESSION_ID, session.getVersion(), key, "SUBMIT_NOTE", payload);
    }

    private GameRuntimeSession running(String config, String state) {
        GameRuntimeSession session = new GameRuntimeSession();
        session.setId(SESSION_ID);
        session.setActivityId(ACTIVITY_ID);
        session.setTopicId(TOPIC_ID);
        session.setNodeId(NODE_ID);
        session.setTemplateId(TEMPLATE_ID);
        session.setOwnerType(GameRuntimeSession.OWNER_PLAYER);
        session.setOwnerId(MEMBER_ID);
        session.setConfigSnapshotJson(config);
        session.setStateJson(state);
        session.setStatus(GameRuntimeSession.STATUS_RUNNING);
        session.setVersion(0);
        session.setScore(0);
        session.setStartedAt(now);
        return session;
    }

}

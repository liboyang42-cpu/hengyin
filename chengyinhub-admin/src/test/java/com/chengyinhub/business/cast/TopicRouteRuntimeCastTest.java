package com.chengyinhub.business.cast;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.VoiceCloneClient;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.CastRunRules;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.business.service.support.CastVoiceAudioCache;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 角色 Cast v1 运行时:玩家档案(归属 + 内容安全)、剧情分支信任结算、台词配音缓存命中 / 未命中 / 未开通。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TopicRouteRuntimeCastTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long OWNER = 7L;
    private static final long RUN = 100L;
    private static final long TOPIC = 9L;
    private static final long CHAPTER = 55L;
    private static final String CFG = "{\"enabled\":true,\"playerName\":true,\"playerIdentity\":true,"
            + "\"identities\":[{\"key\":\"detective\",\"title\":\"侦探\",\"sub\":\"\"}],"
            + "\"cast\":[{\"id\":\"c_abc123\",\"name\":\"林姐\",\"identity\":\"\",\"avatarUrl\":\"\",\"color\":\"#E8604C\","
            + "\"voice\":{\"mode\":\"preset\",\"preset\":\"warm_female\"}},"
            + "{\"id\":\"c_xyz789\",\"name\":\"老周\",\"identity\":\"\",\"avatarUrl\":\"\",\"color\":\"#3D8BFD\",\"voice\":{\"mode\":\"none\"}}]}";

    @Mock private TopicRouteSessionMapper sessionMapper;
    @Mock private CmsTopicMapper topicMapper;
    @Mock private CmsTopicChapterMapper chapterMapper;
    @Mock private IWxContentSecurityService contentSecurity;
    @Mock private IUmsMemberService memberService;
    @Mock private VoiceCloneClient voiceClient;
    @Mock private CastVoiceAudioCache voiceCache;
    @Mock private AppMediaReferenceGuard mediaReferences;
    private TopicRouteRuntimeServiceImpl service;

    private TopicRouteSession run;

    @BeforeEach
    void setUp()
    {
        // 角色运行时已并入路线运行时(本局 state_json 的唯一写入方);只装配角色路径用到的依赖。
        service = new TopicRouteRuntimeServiceImpl(sessionMapper, topicMapper, null, null, null, null);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "castContentSecurity", contentSecurity);
        ReflectionTestUtils.setField(service, "castMemberService", memberService);
        ReflectionTestUtils.setField(service, "castVoiceClient", voiceClient);
        ReflectionTestUtils.setField(service, "castVoiceCache", voiceCache);
        ReflectionTestUtils.setField(service, "castMediaReferences", mediaReferences);
        run = new TopicRouteSession();
        run.setId(RUN);
        run.setMemberId(OWNER);
        run.setTopicId(TOPIC);
        run.setActivityId(0L);
        run.setStatus(TopicRouteSession.STATUS_RUNNING);
        run.setStateJson("{}");
        when(sessionMapper.selectAccessibleByIdForUpdate(RUN, OWNER)).thenReturn(run);
        when(sessionMapper.selectAccessibleById(RUN, OWNER)).thenReturn(run);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(topicMapper.selectCastConfigById(TOPIC)).thenReturn(CFG);
        UmsMember member = new UmsMember();
        member.setWxOpenId("openid-7");
        when(memberService.selectUmsMemberById(OWNER)).thenReturn(member);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(OWNER);
        topic.setStatus(1);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(topicMapper.selectTopicIsDraft(TOPIC)).thenReturn(0);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER);
        chapter.setTopicId(TOPIC);
        chapter.setBlocksJson("[{\"type\":\"line\",\"key\":\"l1\",\"speaker\":\"c_abc123\",\"content\":\"{玩家},你来了。\",\"audioUrl\":\"\"},"
                + "{\"type\":\"line\",\"key\":\"l2\",\"speaker\":\"c_abc123\",\"content\":\"自带\",\"audioUrl\":\"https://cdn.example.cn/a.mp3\"},"
                + "{\"type\":\"line\",\"key\":\"l3\",\"speaker\":\"c_xyz789\",\"content\":\"没声音\",\"audioUrl\":\"\"},"
                + "{\"type\":\"branch\",\"key\":\"b1\",\"content\":\"跟谁走\",\"options\":["
                + "{\"text\":\"林姐\",\"to\":\"continue\",\"trust\":[{\"castId\":\"c_abc123\",\"delta\":2},{\"castId\":\"c_xyz789\",\"delta\":-1}]},"
                + "{\"text\":\"老周\",\"to\":\"end\"}]}]");
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter);
    }

    private TopicRouteEngine.State written() throws Exception
    {
        ArgumentCaptor<TopicRouteSession> captor = ArgumentCaptor.forClass(TopicRouteSession.class);
        verify(sessionMapper).updateStateCas(captor.capture());
        return JSON.readValue(captor.getValue().getStateJson(), TopicRouteEngine.State.class);
    }

    // ---- 玩家档案 ----

    @Test
    void 档案_本人可写_名字去空白_身份标题快照() throws Exception
    {
        when(contentSecurity.checkText("小雨", "openid-7")).thenReturn(null);
        Map<String, Object> out = service.saveRunProfile(OWNER, RUN, "  小雨 ", "detective");
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) out.get("playerProfile");
        assertEquals("小雨", profile.get("name"));
        assertEquals("detective", profile.get("identityKey"));
        assertEquals("侦探", profile.get("identityTitle"));
        assertEquals("小雨", written().getPlayerProfile().get("name"));
    }

    @Test
    void 档案_不是本局主人_一律本局不存在_不写()
    {
        when(sessionMapper.selectAccessibleByIdForUpdate(RUN, 8L)).thenReturn(null);
        ServiceException e = assertThrows(ServiceException.class, () -> service.saveRunProfile(8L, RUN, "小雨", null));
        assertEquals(404, e.getCode());
        verify(sessionMapper, never()).updateStateCas(any());
        verify(contentSecurity, never()).checkText(anyString(), any());
    }

    @Test
    void 档案_敏感词被内容安全拦下_不写()
    {
        when(contentSecurity.checkText("坏词", "openid-7")).thenReturn("内容含违规信息");
        ServiceException e = assertThrows(ServiceException.class, () -> service.saveRunProfile(OWNER, RUN, "坏词", null));
        assertEquals("内容含违规信息", e.getMessage());
        verify(sessionMapper, never()).updateStateCas(any());
    }

    @Test
    void 档案_名字长度_身份不存在_未登录_局已结束_功能关闭()
    {
        assertThrows(ServiceException.class, () -> service.saveRunProfile(OWNER, RUN, "   ", null));
        assertThrows(ServiceException.class, () -> service.saveRunProfile(OWNER, RUN, "一二三四五六七八九十一二三", null));
        when(contentSecurity.checkText(anyString(), any())).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.saveRunProfile(OWNER, RUN, "小雨", "wizard"));
        assertEquals(401, assertThrows(ServiceException.class, () -> service.saveRunProfile(0L, RUN, "小雨", null)).getCode());
        run.setStatus(TopicRouteSession.STATUS_COMPLETED);
        assertEquals(409, assertThrows(ServiceException.class, () -> service.saveRunProfile(OWNER, RUN, "小雨", null)).getCode());
        run.setStatus(TopicRouteSession.STATUS_RUNNING);
        when(topicMapper.selectCastConfigById(TOPIC)).thenReturn(null);
        ServiceException off = assertThrows(ServiceException.class, () -> service.saveRunProfile(OWNER, RUN, "小雨", null));
        assertEquals(CastRunRules.ERR_CAST_DISABLED, off.getDetailMessage());
        verify(sessionMapper, never()).updateStateCas(any());
    }

    // ---- 剧情分支 + 信任 ----

    @Test
    void 分支_服务端按块配置结算信任_重复提交不重复结算() throws Exception
    {
        Map<String, Object> out = service.recordStoryBranch(OWNER, RUN, CHAPTER, "b1", 0);
        assertEquals("continue", out.get("to"));
        TopicRouteEngine.State state = written();
        assertEquals(2, state.getTrust().get("c_abc123"));
        assertEquals(-1, state.getTrust().get("c_xyz789"));

        run.setStateJson(JSON.writeValueAsString(state));
        Map<String, Object> again = service.recordStoryBranch(OWNER, RUN, CHAPTER, "b1", 1);
        assertEquals(Boolean.TRUE, again.get("duplicate"));
        assertEquals(0, again.get("optionIndex"));
        verify(sessionMapper).updateStateCas(any());
    }

    @Test
    void 分支_别人的局_越界选项_非分支块被拒()
    {
        assertEquals(404, assertThrows(ServiceException.class, () -> service.recordStoryBranch(8L, RUN, CHAPTER, "b1", 0)).getCode());
        assertThrows(ServiceException.class, () -> service.recordStoryBranch(OWNER, RUN, CHAPTER, "b1", 2));
        assertThrows(ServiceException.class, () -> service.recordStoryBranch(OWNER, RUN, CHAPTER, "l1", 0));
        verify(sessionMapper, never()).updateStateCas(any());
    }

    // ---- 台词配音 ----

    private static String sha(String s) throws Exception
    {
        return String.format("%064x", new BigInteger(1, MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void 配音_缓存命中不合成() throws Exception
    {
        run.setStateJson("{\"playerProfile\":{\"name\":\"小雨\"}}");
        when(voiceClient.usable()).thenReturn(true);
        when(voiceCache.find(sha("warm_female小雨,你来了。"))).thenReturn("https://oss.example.cn/hit.mp3");
        Map<String, Object> out = service.castLineVoice(OWNER, TOPIC, CHAPTER, "l1", RUN);
        assertEquals("https://oss.example.cn/hit.mp3", out.get("url"));
        verify(voiceClient, never()).synthesize(anyString(), anyString());
    }

    @Test
    void 配音_未命中_按预设音色合成并落缓存_没档案时玩家变量为你() throws Exception
    {
        when(voiceClient.usable()).thenReturn(true);
        String key = sha("warm_female你,你来了。");
        when(voiceCache.find(key)).thenReturn(null);
        when(voiceClient.synthesize("longwan_v2", "你,你来了。")).thenReturn("https://dashscope-result.oss-cn-beijing.aliyuncs.com/x.mp3");
        when(voiceCache.store(key, "https://dashscope-result.oss-cn-beijing.aliyuncs.com/x.mp3")).thenReturn("https://oss.example.cn/new.mp3");
        assertEquals("https://oss.example.cn/new.mp3", service.castLineVoice(OWNER, TOPIC, CHAPTER, "l1", RUN).get("url"));
        verify(voiceCache).store(eq(key), anyString());
    }

    @Test
    void 配音_未开通返回可识别错误码_不500()
    {
        when(voiceClient.usable()).thenReturn(false);
        ServiceException e = assertThrows(ServiceException.class, () -> service.castLineVoice(OWNER, TOPIC, CHAPTER, "l1", RUN));
        assertEquals(503, e.getCode());
        assertEquals(CastRunRules.ERR_TTS_NOT_CONFIGURED, e.getDetailMessage());
        verify(voiceClient, never()).synthesize(anyString(), anyString());
    }

    @Test
    void 配音_合成失败返回错误码()
    {
        when(voiceClient.usable()).thenReturn(true);
        when(voiceCache.find(anyString())).thenReturn(null);
        when(voiceClient.synthesize(anyString(), anyString())).thenThrow(new AiServiceException("boom"));
        assertEquals(CastRunRules.ERR_TTS_FAILED,
                assertThrows(ServiceException.class, () -> service.castLineVoice(OWNER, TOPIC, CHAPTER, "l1", RUN)).getDetailMessage());
    }

    @Test
    void 配音_块自带配音直接回_角色无声音回错误码()
    {
        assertEquals("https://cdn.example.cn/a.mp3", service.castLineVoice(OWNER, TOPIC, CHAPTER, "l2", RUN).get("url"));
        verify(voiceClient, never()).synthesize(anyString(), anyString());
        assertEquals(CastRunRules.ERR_VOICE_NONE,
                assertThrows(ServiceException.class, () -> service.castLineVoice(OWNER, TOPIC, CHAPTER, "l3", RUN)).getDetailMessage());
    }

    @Test
    void 配音_草稿只有主人能听_已发布任何登录用户()
    {
        when(topicMapper.selectTopicIsDraft(TOPIC)).thenReturn(1);
        assertEquals(403, assertThrows(ServiceException.class, () -> service.castLineVoice(8L, TOPIC, CHAPTER, "l2", null)).getCode());
        assertEquals("https://cdn.example.cn/a.mp3", service.castLineVoice(OWNER, TOPIC, CHAPTER, "l2", RUN).get("url"));
        when(topicMapper.selectTopicIsDraft(TOPIC)).thenReturn(0);
        assertEquals("https://cdn.example.cn/a.mp3", service.castLineVoice(8L, TOPIC, CHAPTER, "l2", null).get("url"));
        assertEquals(401, assertThrows(ServiceException.class, () -> service.castLineVoice(0L, TOPIC, CHAPTER, "l2", null)).getCode());
    }

    @Test
    void 配音_章节不属于主题被拒()
    {
        CmsTopic other = new CmsTopic();
        other.setId(10L);
        other.setMemberId(OWNER);
        other.setStatus(1);
        when(topicMapper.selectCmsTopicById(10L)).thenReturn(other);
        ServiceException e = assertThrows(ServiceException.class, () -> service.castLineVoice(OWNER, 10L, CHAPTER, "l1", RUN));
        assertEquals(404, e.getCode());
        assertEquals("章节不存在", e.getMessage());
    }

    @Test
    void 试听_固定示例句_按预设音色合成_未知预设被拒_未开通回错误码() throws Exception
    {
        when(voiceClient.usable()).thenReturn(true);
        String text = com.chengyinhub.business.service.support.CastVoicePresets.SAMPLE_LINE;
        when(voiceCache.find(sha("youth" + text))).thenReturn(null);
        when(voiceClient.synthesize("longcheng_v2", text)).thenReturn("https://dashscope-result.oss-cn-beijing.aliyuncs.com/s.mp3");
        when(voiceCache.store(eq(sha("youth" + text)), anyString())).thenReturn("https://oss.example.cn/sample.mp3");
        assertEquals("https://oss.example.cn/sample.mp3", service.castPresetSample(8L, "youth").get("url"));
        assertEquals(400, assertThrows(ServiceException.class, () -> service.castPresetSample(8L, "robot")).getCode());
        assertEquals(401, assertThrows(ServiceException.class, () -> service.castPresetSample(0L, "youth")).getCode());
        when(voiceClient.usable()).thenReturn(false);
        assertEquals(CastRunRules.ERR_TTS_NOT_CONFIGURED,
                assertThrows(ServiceException.class, () -> service.castPresetSample(8L, "youth")).getDetailMessage());
    }

    @Test
    void 玩家角色配置_按本局到过的章节解锁线索_其余只给个数() throws Exception
    {
        com.chengyinhub.business.mapper.CmsTopicNodeMapper nodeMapper = org.mockito.Mockito.mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class);
        TopicRouteRuntimeServiceImpl withNodes = new TopicRouteRuntimeServiceImpl(sessionMapper, topicMapper, nodeMapper, null, null, null);
        ReflectionTestUtils.setField(withNodes, "cmsTopicChapterMapper", chapterMapper);
        String stored = "{\"enabled\":true,\"cast\":[{\"id\":\"c_abc123\",\"name\":\"林姐\",\"color\":\"#E8604C\",\"clues\":["
                + "{\"text\":\"开局\",\"chapterId\":null},{\"text\":\"一章\",\"chapterId\":55},"
                + "{\"text\":\"二章\",\"chapterId\":56},{\"text\":\"三章\",\"chapterId\":57}]}]}";
        java.util.List<CmsTopicChapter> chapters = new java.util.ArrayList<>();
        for (long id : new long[] {55L, 56L, 57L}) { CmsTopicChapter c = new CmsTopicChapter(); c.setId(id); c.setTopicId(TOPIC); chapters.add(c); }
        when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(chapters);
        com.chengyinhub.business.domain.CmsTopicNode n1 = new com.chengyinhub.business.domain.CmsTopicNode();
        n1.setId(301L); n1.setChapterId(55L);
        com.chengyinhub.business.domain.CmsTopicNode n2 = new com.chengyinhub.business.domain.CmsTopicNode();
        n2.setId(302L); n2.setChapterId(56L);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(java.util.Arrays.asList(n1, n2));

        // 游客:只有开局线索
        JsonNode guest = withNodes.castConfigForPlayer(0L, TOPIC, stored).get("cast").get(0);
        assertEquals(1, guest.get("clues").size());
        assertEquals(3, guest.get("lockedCount").asInt());

        // 本局走到第二章的站(当前节点 302)→ 一章、二章解锁,三章只给个数
        run.setStateJson("{\"currentNodeId\":302,\"visitedNodeIds\":[301]}");
        when(sessionMapper.selectMemberTopicSessions(OWNER, TOPIC)).thenReturn(java.util.Collections.singletonList(run));
        when(sessionMapper.selectCurrentByOwner(0L, TOPIC, OWNER)).thenReturn(run);
        JsonNode mine = withNodes.castConfigForPlayer(OWNER, TOPIC, stored).get("cast").get(0);
        assertEquals(3, mine.get("clues").size());
        assertEquals(1, mine.get("lockedCount").asInt());
        assertEquals(false, mine.toString().contains("三章"));

        // 有本局但还没到任何站 = 在第一章
        run.setStateJson("{}");
        JsonNode fresh = withNodes.castConfigForPlayer(OWNER, TOPIC, stored).get("cast").get(0);
        assertEquals(2, fresh.get("clues").size());
        assertEquals(2, fresh.get("lockedCount").asInt());

        assertEquals(null, withNodes.castConfigForPlayer(OWNER, TOPIC, null));
    }
}

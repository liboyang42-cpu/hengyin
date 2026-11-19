package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.VoiceCloneClient;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.business.service.support.CastConfigSupport;
import com.chengyinhub.business.service.support.CastRunRules;
import com.chengyinhub.business.service.support.CastVoiceAudioCache;
import com.chengyinhub.business.service.support.CastVoicePresets;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.business.service.support.TopicRouteSessionScope;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 角色(Cast)v1 的玩家侧运行时:本局玩家档案、剧情分支选择 + 信任结算、台词配音(TTS)。
 * 本局状态只写 topic_route_session.state_json(与路线运行时同一份 State、同一条 CAS 更新),不另建表。
 */
@Service
public class CastRunService
{
    private static final Logger log = LoggerFactory.getLogger(CastRunService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 客户端可识别的错误码(放在 AjaxResult.data.errorCode)。 */
    public static final String ERR_TTS_NOT_CONFIGURED = "CAST_TTS_NOT_CONFIGURED";
    public static final String ERR_CAST_DISABLED = "CAST_DISABLED";
    public static final String ERR_VOICE_NONE = "CAST_VOICE_NONE";
    public static final String ERR_TTS_FAILED = "CAST_TTS_FAILED";

    public static final int MAX_PLAYER_NAME = 12;

    @Autowired
    private TopicRouteSessionMapper sessionMapper;
    @Autowired
    private CmsTopicMapper topicMapper;
    @Autowired
    private CmsTopicChapterMapper chapterMapper;
    @Autowired
    private IWxContentSecurityService contentSecurity;
    @Autowired
    private IUmsMemberService memberService;
    @Autowired(required = false)
    private VoiceCloneClient voiceClient;
    @Autowired(required = false)
    private CastVoiceAudioCache voiceCache;
    @Autowired(required = false)
    private AppMediaReferenceGuard mediaReferences;

    /**
     * POST /api/play/run/profile:只有本局主人能写;名字 1–12 字(去首尾空白)并过内容安全;
     * identityKey 可缺省,给了就必须是 castConfig.identities 里的且主题开了 playerIdentity。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveProfile(Long memberId, Long runId, String rawName, String rawIdentityKey)
    {
        requireLogin(memberId);
        TopicRouteSession session = lockOwnedRunningRun(memberId, runId);
        JsonNode cfg = enabledCastConfig(session.getTopicId());

        String name = rawName == null ? "" : rawName.trim();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > MAX_PLAYER_NAME) throw new ServiceException("名字要 1 至 " + MAX_PLAYER_NAME + " 个字", 400);
        String unsafe = IWxContentSecurityService.checkTextAsMember(contentSecurity, memberService, memberId, name);
        if (unsafe != null) throw new ServiceException(unsafe, 400);

        String identityKey = rawIdentityKey == null || rawIdentityKey.trim().isEmpty() ? null : rawIdentityKey.trim();
        String identityTitle = null;
        if (identityKey != null)
        {
            JsonNode identity = cfg.path("playerIdentity").asBoolean(false) ? CastConfigSupport.findIdentity(cfg, identityKey) : null;
            if (identity == null) throw new ServiceException("身份选项不存在", 400);
            identityTitle = identity.path("title").asText(null);
        }

        TopicRouteEngine.State state = readState(session);
        Map<String, String> profile = new LinkedHashMap<String, String>();
        profile.put("name", name);
        if (identityKey != null) profile.put("identityKey", identityKey);
        if (identityTitle != null) profile.put("identityTitle", identityTitle);
        state.setPlayerProfile(profile);
        writeState(session, state);
        return Collections.<String, Object>singletonMap("playerProfile", CastRunRules.profileView(profile));
    }

    /**
     * POST /api/play/run/branch:记录本局在某个剧情分支块上的选择,并按该选项的 trust 结算信任。
     * 信任值只由这里按服务端存的块配置计算,客户端只报选了第几条。同一块只记第一次(重复提交原样返回,不重复结算)。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> recordBranch(Long memberId, Long runId, Long chapterId, String blockKey, Integer optionIndex)
    {
        requireLogin(memberId);
        if (chapterId == null || chapterId <= 0 || blockKey == null || blockKey.trim().isEmpty() || optionIndex == null)
            throw new ServiceException("参数有误", 400);
        TopicRouteSession session = lockOwnedRunningRun(memberId, runId);
        JsonNode block = requireBlock(session.getTopicId(), chapterId, blockKey.trim(), "branch");
        JsonNode options = block.path("options");
        if (!options.isArray() || optionIndex < 0 || optionIndex >= options.size())
            throw new ServiceException("分支选项不存在", 400);

        TopicRouteEngine.State state = readState(session);
        String choiceKey = chapterId + ":" + blockKey.trim();
        Integer existing = state.getStoryChoices().get(choiceKey);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("chapterId", chapterId);
        out.put("blockKey", blockKey.trim());
        if (existing != null)
        {
            out.put("optionIndex", existing);
            out.put("to", options.path(existing).path("to").asText(null));
            out.put("duplicate", true);
            return out;
        }
        state.getStoryChoices().put(choiceKey, optionIndex);
        JsonNode cfg = CastConfigSupport.parse(topicMapper.selectCastConfigById(session.getTopicId()));
        CastRunRules.applyTrust(state.getTrust(), options.path(optionIndex).path("trust"), CastConfigSupport.castIds(cfg));
        writeState(session, state);
        out.put("optionIndex", optionIndex);
        out.put("to", options.path(optionIndex).path("to").asText(null));
        out.put("duplicate", false);
        return out;
    }

    /**
     * GET /api/topic/cast/voice:台词配音地址。块自带 audioUrl 直接回;否则按说话角色的预设声音合成,
     * 结果按 sha256(preset + text) 缓存,先查缓存再合成。已发布主题登录即可听;草稿只有主题主人。
     */
    public Map<String, Object> lineVoice(Long memberId, Long topicId, Long chapterId, String blockKey, Long runId)
    {
        requireLogin(memberId);
        if (topicId == null || topicId <= 0 || chapterId == null || chapterId <= 0 || blockKey == null || blockKey.trim().isEmpty())
            throw new ServiceException("参数有误", 400);
        CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
        if (topic == null) throw new ServiceException("主题不存在", 404);
        boolean published = Integer.valueOf(1).equals(topic.getStatus())
                && !Integer.valueOf(1).equals(topicMapper.selectTopicIsDraft(topicId));
        if (!published && !memberId.equals(topic.getMemberId())) throw new ServiceException("无权访问", 403);

        JsonNode block = requireBlock(topicId, chapterId, blockKey.trim(), "line");
        String own = block.path("audioUrl").asText("");
        if (!own.isEmpty()) return Collections.<String, Object>singletonMap("url", playableUrl(own, memberId, published));

        JsonNode cfg = CastConfigSupport.parse(topicMapper.selectCastConfigById(topicId));
        if (cfg == null || !cfg.path("enabled").asBoolean(false)) throw coded("本主题没有开启角色", 409, ERR_CAST_DISABLED);
        JsonNode speaker = CastConfigSupport.findCast(cfg, block.path("speaker").asText(null));
        String preset = speaker == null ? null : speaker.path("voice").path("preset").asText(null);
        String voiceId = speaker != null && "preset".equals(speaker.path("voice").path("mode").asText())
                ? CastVoicePresets.voiceId(preset) : null;
        if (voiceId == null) throw coded("这句台词没有配音", 409, ERR_VOICE_NONE);

        String text = CastRunRules.resolveVars(block.path("content").asText(""), callerName(memberId, topicId, runId));
        String sha = sha256Hex(preset + text);
        if (voiceCache == null || voiceClient == null || !voiceClient.usable())
        {
            // 没配 TTS:如实回一个客户端可识别的错误码,不 500。
            throw coded("配音服务未开通", 503, ERR_TTS_NOT_CONFIGURED);
        }
        String hit = voiceCache.find(sha);
        if (hit != null) return Collections.<String, Object>singletonMap("url", hit);
        String vendorUrl;
        try
        {
            vendorUrl = voiceClient.synthesize(voiceId, text);
        }
        catch (AiServiceException e)
        {
            log.warn("角色台词合成失败 topicId={} chapterId={} blockKey={}: {}", topicId, chapterId, blockKey, e.getMessage());
            throw coded("配音暂时生成不了，请稍后再试", 502, ERR_TTS_FAILED);
        }
        return Collections.<String, Object>singletonMap("url", voiceCache.store(sha, vendorUrl));
    }

    private String callerName(Long memberId, Long topicId, Long runId)
    {
        TopicRouteSession session;
        if (runId != null && runId > 0)
        {
            session = sessionMapper.selectAccessibleById(runId, memberId);
            if (session == null || !topicId.equals(session.getTopicId())) throw new ServiceException("本局不存在", 404);
        }
        else
        {
            session = TopicRouteSessionScope.findCurrent(sessionMapper, memberId, topicId, null);
        }
        if (session == null) return null;
        return readState(session).getPlayerProfile().get("name");
    }

    private String playableUrl(String ref, Long memberId, boolean published)
    {
        if (!CastConfigSupport.MEDIA_ASSET.matcher(ref).matches() || mediaReferences == null) return ref;
        String url = published ? mediaReferences.publishedPlayUrl(ref, memberId) : mediaReferences.visibleUrl(ref, memberId);
        if (url == null || url.isEmpty()) throw new ServiceException("配音素材暂不可用", 404);
        return url;
    }

    private JsonNode requireBlock(Long topicId, Long chapterId, String blockKey, String type)
    {
        CmsTopicChapter chapter = chapterMapper.selectCmsTopicChapterById(chapterId);
        if (chapter == null || !topicId.equals(chapter.getTopicId())) throw new ServiceException("章节不存在", 404);
        String raw = chapter.getBlocksJson();
        if (raw != null && !raw.trim().isEmpty())
        {
            try
            {
                for (JsonNode block : JSON.readTree(raw))
                {
                    if (blockKey.equals(block.path("key").asText()) && type.equals(block.path("type").asText())) return block;
                }
            }
            catch (java.io.IOException e)
            {
                throw new ServiceException("章节内容不可用", 409);
            }
        }
        throw new ServiceException("内容块不存在", 404);
    }

    private JsonNode enabledCastConfig(Long topicId)
    {
        JsonNode cfg = CastConfigSupport.parse(topicMapper.selectCastConfigById(topicId));
        if (cfg == null || !cfg.path("enabled").asBoolean(false)) throw coded("本主题没有开启角色", 409, ERR_CAST_DISABLED);
        return cfg;
    }

    /** 只有本局主人能写:按 (runId, memberId) 加行锁读,查不到与「不是你的」同一回包。 */
    private TopicRouteSession lockOwnedRunningRun(Long memberId, Long runId)
    {
        if (runId == null || runId <= 0) throw new ServiceException("参数有误", 400);
        TopicRouteSession session = sessionMapper.selectAccessibleByIdForUpdate(runId, memberId);
        if (session == null || !memberId.equals(session.getMemberId())) throw new ServiceException("本局不存在", 404);
        if (session.getStatus() == null || session.getStatus() != TopicRouteSession.STATUS_RUNNING)
            throw new ServiceException("本局已结束", 409);
        return session;
    }

    private TopicRouteEngine.State readState(TopicRouteSession session)
    {
        String raw = session.getStateJson();
        if (raw == null || raw.trim().isEmpty()) return new TopicRouteEngine.State();
        try
        {
            TopicRouteEngine.State state = JSON.readValue(raw, TopicRouteEngine.State.class);
            return state == null ? new TopicRouteEngine.State() : state;
        }
        catch (java.io.IOException e)
        {
            throw new ServiceException("本局状态不可用", 409);
        }
    }

    private void writeState(TopicRouteSession session, TopicRouteEngine.State state)
    {
        try
        {
            session.setStateJson(JSON.writeValueAsString(state));
        }
        catch (java.io.IOException e)
        {
            throw new IllegalStateException("本局状态序列化失败", e);
        }
        session.setUpdateTime(new Date());
        if (sessionMapper.updateStateCas(session) != 1) throw new ServiceException("本局状态已变化，请刷新后重试", 409);
    }

    private static void requireLogin(Long memberId)
    {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录", 401);
    }

    private static ServiceException coded(String message, int code, String errorCode)
    {
        return new ServiceException(message, code).setDetailMessage(errorCode);
    }

    static String sha256Hex(String text)
    {
        try
        {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        }
        catch (NoSuchAlgorithmException impossible)
        {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}

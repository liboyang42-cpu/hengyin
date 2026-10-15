package com.chengyinhub.business.service.impl;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.NpcChatLog;
import com.chengyinhub.business.domain.NpcChatRequest;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.domain.dto.ai.NpcChatReq;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.mapper.NpcChatLogMapper;
import com.chengyinhub.business.mapper.NpcChatRequestMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.ai.AiGatewayResult;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.VoiceCloneClient;
import com.chengyinhub.common.utils.uuid.IdUtils;

/**
 * 漫游 NPC Chat 编排：普通 POST、服务端 requestId 幂等、一次只允许一个会话内调用。
 *
 * 网关仍是唯一的频控、内容安全、模型路由与审计入口；本类只补齐 Chat 专属的会话归属和
 * 终态重放契约。客户端永远拿不到 provider 原始异常或未经审核的文本。
 */
@Service
public class NpcChatService
{
    private static final Logger log = LoggerFactory.getLogger(NpcChatService.class);

    private static final int REQUEST_ID_MAX = 64;
    private static final int MESSAGE_MAX_CODE_POINTS = 300;
    private static final int SAFE_TEXT_MAX_CODE_POINTS = 160;
    private static final long LEASE_MILLIS = 20_000L;
    private static final long LEASE_HEARTBEAT_MILLIS = LEASE_MILLIS / 4;
    private static final long MAX_CHAT_ENTITY_ID = Long.MAX_VALUE / 2;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    private static final String SAFE_INVALID_REQUEST = "这条问题格式不正确，请重新发送。";
    private static final String SAFE_INVALID_SESSION = "这次漫游已结束，请开启新的漫游再问我。";
    private static final String SAFE_INPUT_BLOCKED = "这条消息暂时无法处理，请换个说法。";
    private static final String SAFE_OUTPUT_BLOCKED = "这次回答暂时无法展示，请换个问题试试。";
    private static final String SAFE_UPSTREAM_FAILED = "现在有点忙，请稍后再试。";
    private static final String SAFE_IN_PROGRESS = "上一条问题还在处理中，请稍后再试。";
    private static final String SAFE_REQUEST_TIMEOUT = "本次请求等待超时，请重新发送。";
    private static final String SAFE_SHOP_NO_PROFILE = "这家店的分身还没上线，去店里问问店主吧。";

    @Autowired
    private AiGatewayService aiGatewayService;

    @Autowired
    private VoiceCloneClient voiceCloneClient;

    @Autowired
    private NpcProfileMapper npcProfileMapper;

    @Autowired
    private NpcMemoryBuilder memoryBuilder;

    @Autowired
    private MerchantNpcFactsBuilder merchantFactsBuilder;

    @Autowired
    private NpcChatLogMapper npcChatLogMapper;

    @Autowired
    private NpcChatRequestMapper npcChatRequestMapper;

    @Autowired
    private RoamMapper roamMapper;

    @Resource(name = "scheduledExecutorService")
    private ScheduledExecutorService scheduledExecutorService;

    public NpcChatResp chat(NpcChatReq req, Long userId)
    {
        String requestId = requestIdOf(req);
        if (userId == null)
        {
            return response(requestId, NpcChatRequest.STATUS_FAILED, "NOT_RUN", "UNAUTHENTICATED", null,
                    false, true, "请重新登录后再试。");
        }
        if (!isRequestEnvelopeValid(req, requestId))
        {
            return response(requestId, NpcChatRequest.STATUS_REJECTED, "NOT_RUN", "INVALID_REQUEST", null,
                    false, true, SAFE_INVALID_REQUEST);
        }

        NpcChatRequest existing;
        try
        {
            existing = npcChatRequestMapper.selectByUserAndRequestId(userId, requestId);
        }
        catch (Exception e)
        {
            log.error("[NpcChat] request state lookup failed userId={} requestId={}", userId, requestId, e);
            return requestStateUnavailable(requestId);
        }
        if (existing != null)
        {
            if(!req.getBizId().equals(existing.getSessionId())) return requestScopeMismatch(requestId);
            return fromStored(existing);
        }

        String message = normalizedMessage(req);
        if (!isMessageValid(message))
        {
            return persistTerminal(newTerminal(userId, req.getBizId(), requestId,
                    NpcChatRequest.STATUS_REJECTED, "NOT_RUN", "INVALID_MESSAGE", null,
                    false, SAFE_INVALID_REQUEST));
        }

        RoamSession session = ownedActiveSession(userId, req.getBizId());
        if (session == null)
        {
            return persistTerminal(newTerminal(userId, req.getBizId(), requestId,
                    NpcChatRequest.STATUS_REJECTED, "NOT_RUN", "INVALID_SESSION", null,
                    false, SAFE_INVALID_SESSION));
        }

        try
        {
            npcChatRequestMapper.expireProcessingByUserAndSession(userId, session.getId(), SAFE_REQUEST_TIMEOUT);
        }
        catch (Exception e)
        {
            // 不在未知的 request-state 下调用模型；否则可能产生不能重放的二次扣费请求。
            log.error("[NpcChat] request lease cleanup failed userId={} sessionId={}", userId, session.getId(), e);
            return requestStateUnavailable(requestId);
        }

        NpcChatRequest processing = newProcessing(userId, session.getId(), requestId);
        try
        {
            npcChatRequestMapper.insertNpcChatRequest(processing);
        }
        catch (DuplicateKeyException e)
        {
            return afterInsertCollision(userId, requestId);
        }
        catch (Exception e)
        {
            log.error("[NpcChat] request state insert failed userId={} requestId={}", userId, requestId, e);
            return requestStateUnavailable(requestId);
        }

        return generateAndFinish(processing, findGlobalProfile(),
                profile -> memoryBuilder.buildRoam(session), "npc_chat", message);
    }

    /**
     * 门店分身自由追问(自由探索卡片详情 → 店铺卡 → 对话)。
     *
     * <p>与漫游 {@link #chat} 走同一套幂等/租约/内容安全机制,差别只有三处:
     * 人设取门店分身(scope_type=2)而不是全局 NPC、记忆是这一家店的现场事实而不是漫游进度、
     * 会话键用 <b>负的 nodeId</b>。
     *
     * <p>⚠️ 会话键为什么是负数:npc_chat_request 的 uk_active_session 是
     * (user_id, session_id, active_slot),漫游会话 id 恒为正。两条链路共用这张表,
     * 用负数就结构性不可能撞上 —— 否则「玩家的漫游会话 id 恰好等于他正在聊的 nodeId」
     * 会让其中一条被误判成「上一条还在处理中」,而且这种碰撞极难复现、零告警。
     *
     * <p>调用方必须先校验玩家确实持有这家店所属主题的有效通行证 —— 本方法不做归属校验,
     * 它只保证「同一个 requestId 不会二次调用模型」。
     */
    public NpcChatResp chatWithShop(String requestId, Long userId, Long nodeId,
                                    Long merchantId, String shopMemory, String rawMessage)
    {
        if (nodeId != null && nodeId > MAX_CHAT_ENTITY_ID)
        {
            return response(requestId, NpcChatRequest.STATUS_REJECTED, "NOT_RUN", "INVALID_REQUEST", null,
                    false, true, SAFE_INVALID_REQUEST);
        }
        return chatWithMerchantTarget(requestId, userId, nodeId, merchantId,
                profile -> shopMemory, "npc_shop_chat", rawMessage);
    }

    /** 店铺主页对话；使用负数空间的下半区，避免与按节点对话的会话键碰撞。 */
    public NpcChatResp chatWithMerchant(NpcChatReq req, Long userId, Long merchantId)
    {
        String requestId = requestIdOf(req);
        if (merchantId == null || merchantId <= 0 || merchantId > MAX_CHAT_ENTITY_ID)
        {
            return response(requestId, NpcChatRequest.STATUS_REJECTED, "NOT_RUN", "INVALID_REQUEST", null,
                    false, true, SAFE_INVALID_REQUEST);
        }
        long syntheticNodeId = MAX_CHAT_ENTITY_ID + merchantId;
        return chatWithMerchantTarget(requestId, userId, syntheticNodeId, merchantId,
                profile -> merchantFactsBuilder.build(profile, merchantId),
                "merchant_npc_chat", normalizedMessage(req));
    }

    private NpcChatResp chatWithMerchantTarget(String requestId, Long userId, Long nodeId,
                                                Long merchantId, Function<NpcProfile, String> memory,
                                                String bizType, String rawMessage)
    {
        String rid = requestId == null ? null : requestId.trim();
        if (userId == null)
        {
            return response(rid, NpcChatRequest.STATUS_FAILED, "NOT_RUN", "UNAUTHENTICATED", null,
                    false, true, "请重新登录后再试。");
        }
        if (!nonBlank(rid) || rid.length() > REQUEST_ID_MAX || !UUID_PATTERN.matcher(rid).matches()
                || nodeId == null || nodeId <= 0 || merchantId == null || merchantId <= 0)
        {
            return response(rid, NpcChatRequest.STATUS_REJECTED, "NOT_RUN", "INVALID_REQUEST", null,
                    false, true, SAFE_INVALID_REQUEST);
        }

        NpcChatRequest existing;
        try
        {
            existing = npcChatRequestMapper.selectByUserAndRequestId(userId, rid);
        }
        catch (Exception e)
        {
            log.error("[NpcChat] shop request state lookup failed userId={} requestId={}", userId, rid, e);
            return requestStateUnavailable(rid);
        }
        if (existing != null)
        {
            if(!Long.valueOf(-nodeId).equals(existing.getSessionId())) return requestScopeMismatch(rid);
            return fromStored(existing);
        }

        Long sessionKey = -nodeId;
        String message = rawMessage == null ? null : rawMessage.trim();
        if (!isMessageValid(message))
        {
            return persistTerminal(newTerminal(userId, sessionKey, rid,
                    NpcChatRequest.STATUS_REJECTED, "NOT_RUN", "INVALID_MESSAGE", null,
                    false, SAFE_INVALID_REQUEST));
        }

        NpcProfile profile = findMerchantProfile(merchantId);
        if (profile == null || !nonBlank(profile.getPersona()))
        {
            // 商家没配人设就别硬答:比让全局 NPC 冒充店主可信
            return persistTerminal(newTerminal(userId, sessionKey, rid,
                    NpcChatRequest.STATUS_FAILED, "NOT_RUN", "NPC_PROFILE_UNAVAILABLE", null,
                    false, SAFE_SHOP_NO_PROFILE));
        }

        try
        {
            npcChatRequestMapper.expireProcessingByUserAndSession(userId, sessionKey, SAFE_REQUEST_TIMEOUT);
        }
        catch (Exception e)
        {
            log.error("[NpcChat] shop lease cleanup failed userId={} nodeId={}", userId, nodeId, e);
            return requestStateUnavailable(rid);
        }

        NpcChatRequest processing = newProcessing(userId, sessionKey, rid);
        try
        {
            npcChatRequestMapper.insertNpcChatRequest(processing);
        }
        catch (DuplicateKeyException e)
        {
            return afterInsertCollision(userId, rid);
        }
        catch (Exception e)
        {
            log.error("[NpcChat] shop request state insert failed userId={} requestId={}", userId, rid, e);
            return requestStateUnavailable(rid);
        }

        return generateAndFinish(processing, profile, memory, bizType, message);
    }

    /** 门店分身(scope_type=2 挂 merchantId)。取不到只让这条对话降级,不回落到全局 NPC。 */
    private NpcProfile findMerchantProfile(Long merchantId)
    {
        try
        {
            java.util.List<NpcProfile> list = npcProfileMapper.selectEnabledByScope(2, merchantId);
            return list == null || list.isEmpty() ? null : list.get(0);
        }
        catch (Exception e)
        {
            log.warn("[NpcChat] merchant profile lookup failed merchantId={}", merchantId, e);
            return null;
        }
    }

    /**
     * 生成 → 收尾。漫游全局 NPC 与门店分身共用这一条 —— 两者的差别只有三处:
     * 用谁的人设、喂什么记忆、记哪种 bizType,其余(租约心跳、三类终态、审计落库)完全同构。
     *
     * @param memory 记忆**延迟求值**:构造记忆本身可能抛异常,必须落在下面的 try 里,
     *               在调用点先算好会让那类异常绕过统一的失败收尾。
     */
    private NpcChatResp generateAndFinish(NpcChatRequest processing, NpcProfile profile,
                                          Function<NpcProfile, String> memory, String bizType, String message)
    {
        long startMs = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicBoolean leaseLost = new java.util.concurrent.atomic.AtomicBoolean(false);
        ScheduledFuture<?> leaseHeartbeat = startLeaseHeartbeat(processing,leaseLost);
        if (leaseHeartbeat == null)
        {
            terminalize(processing, NpcChatRequest.STATUS_FAILED, "NOT_RUN", "REQUEST_STATE_UNAVAILABLE", null,
                    true, SAFE_UPSTREAM_FAILED);
            return finish(processing);
        }

        try
        {
            if (profile == null || !nonBlank(profile.getPersona()))
            {
                terminalize(processing, NpcChatRequest.STATUS_FAILED, "NOT_RUN", "NPC_PROFILE_UNAVAILABLE", null,
                        true, SAFE_UPSTREAM_FAILED);
                return finish(processing);
            }

            Long profileId = profile.getId();
            String mem = memory.apply(profile);
            String system = profile.getPersona() + "\n" + (mem == null ? "" : mem);
            if(leaseLost.get() || npcChatRequestMapper.renewLease(processing.getUserId(),
                    processing.getRequestId(),processing.getLeaseToken(),new Date(System.currentTimeMillis()+LEASE_MILLIS))!=1) {
                return requestStateUnavailable(processing.getRequestId());
            }
            AiGatewayResult result = aiGatewayService.generate(processing.getUserId(), "player", bizType,
                    system, message);
            terminalize(processing, NpcChatRequest.STATUS_SUCCEEDED, "PASS", null, null, false,
                    truncateCodePoints(result.getText(), SAFE_TEXT_MAX_CODE_POINTS));
            NpcChatResp response = finish(processing);
            if (NpcChatRequest.STATUS_SUCCEEDED.equals(response.getOutcomeStatus())
                    && ("npc_shop_chat".equals(bizType) || "merchant_npc_chat".equals(bizType)))
            {
                attachVoice(profile, response);
            }
            saveLog(processing.getUserId(), processing.getSessionId(), profileId, message, result.getText(), 0,
                    result.getModel(), (int) (System.currentTimeMillis() - startMs));
            return response;
        }
        catch (AiServiceException e)
        {
            applyGatewayFailure(processing, e);
            NpcChatResp response = finish(processing);
            saveLog(processing.getUserId(), processing.getSessionId(), profile.getId(), message, null,
                    safetyFlagOf(e), null, (int) (System.currentTimeMillis() - startMs));
            return response;
        }
        catch (Exception e)
        {
            log.error("[NpcChat] unexpected error bizType={} userId={} requestId={}", bizType,
                    processing.getUserId(), processing.getRequestId(), e);
            terminalize(processing, NpcChatRequest.STATUS_FAILED, "NOT_RUN", "AI_UNAVAILABLE", null, true,
                    SAFE_UPSTREAM_FAILED);
            NpcChatResp response = finish(processing);
            saveLog(processing.getUserId(), processing.getSessionId(), profile == null ? null : profile.getId(),
                    message, null, 3, null, (int) (System.currentTimeMillis() - startMs));
            return response;
        }
        finally
        {
            leaseHeartbeat.cancel(false);
        }
    }

    /** 合成失败只降级为文字；已经通过内容安全的回答不能被第二个上游反向打成失败。 */
    private void attachVoice(NpcProfile profile, NpcChatResp response)
    {
        if (profile == null || !Integer.valueOf(2).equals(profile.getVoiceStatus())
                || !nonBlank(profile.getVoiceId()) || response == null || !nonBlank(response.getSafeText()))
        {
            return;
        }
        try
        {
            response.setAudioUrl(voiceCloneClient.synthesize(profile.getVoiceId(), response.getSafeText()));
        }
        catch (RuntimeException e)
        {
            log.warn("[NpcChat] voice synthesis failed profileId={}", profile.getId(), e);
        }
    }

    private ScheduledFuture<?> startLeaseHeartbeat(final NpcChatRequest processing,
            final java.util.concurrent.atomic.AtomicBoolean leaseLost)
    {
        if (scheduledExecutorService == null)
        {
            log.error("[NpcChat] lease scheduler unavailable userId={} requestId={}", processing.getUserId(),
                    processing.getRequestId());
            return null;
        }
        try
        {
            return scheduledExecutorService.scheduleAtFixedRate(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        int updated = npcChatRequestMapper.renewLease(processing.getUserId(),
                                processing.getRequestId(), processing.getLeaseToken(),
                                new Date(System.currentTimeMillis() + LEASE_MILLIS));
                        if (updated != 1)
                        {
                            leaseLost.set(true);
                            log.warn("[NpcChat] lease heartbeat lost userId={} requestId={}",
                                    processing.getUserId(), processing.getRequestId());
                        }
                    }
                    catch (Exception e)
                    {
                        leaseLost.set(true);
                        log.error("[NpcChat] lease heartbeat failed userId={} requestId={}",
                                processing.getUserId(), processing.getRequestId(), e);
                    }
                }
            }, LEASE_HEARTBEAT_MILLIS, LEASE_HEARTBEAT_MILLIS, TimeUnit.MILLISECONDS);
        }
        catch (Exception e)
        {
            log.error("[NpcChat] lease heartbeat scheduling failed userId={} requestId={}", processing.getUserId(),
                    processing.getRequestId(), e);
            return null;
        }
    }

    private void applyGatewayFailure(NpcChatRequest processing, AiServiceException e)
    {
        AiServiceException.Reason reason = e.getReason();
        if (e instanceof com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException)
        {
            terminalize(processing, NpcChatRequest.STATUS_PROCESSING, "UNKNOWN", "REQUEST_OUTCOME_UNKNOWN",
                    null, false, "原请求结果待确认，请稍后查看原请求。");
        }
        else if (reason == AiServiceException.Reason.RATE_LIMITED)
        {
            terminalize(processing, NpcChatRequest.STATUS_REJECTED, "NOT_RUN", "RATE_LIMITED", "DAY", true,
                    "问得有点快，明天再来试试。", secondsUntilTomorrow());
        }
        else if (reason == AiServiceException.Reason.INPUT_BLOCKED)
        {
            terminalize(processing, NpcChatRequest.STATUS_REJECTED, "BLOCKED", "INPUT_BLOCK", null, false,
                    SAFE_INPUT_BLOCKED);
        }
        else if (reason == AiServiceException.Reason.OUTPUT_BLOCKED)
        {
            terminalize(processing, NpcChatRequest.STATUS_REJECTED, "BLOCKED", "OUTPUT_BLOCK", null, false,
                    SAFE_OUTPUT_BLOCKED);
        }
        else
        {
            terminalize(processing, NpcChatRequest.STATUS_FAILED, "NOT_RUN", "AI_UNAVAILABLE", null, true,
                    SAFE_UPSTREAM_FAILED);
        }
    }

    private NpcChatResp finish(NpcChatRequest terminal)
    {
        try
        {
            if (npcChatRequestMapper.finishIfLeaseOwned(terminal) == 1)
            {
                return fromStored(terminal);
            }
            NpcChatRequest stored = npcChatRequestMapper.selectByUserAndRequestId(terminal.getUserId(),
                    terminal.getRequestId());
            return stored != null ? fromStored(stored) : requestStateUnavailable(terminal.getRequestId());
        }
        catch (Exception e)
        {
            log.error("[NpcChat] terminal state write failed userId={} requestId={}", terminal.getUserId(),
                    terminal.getRequestId(), e);
            return requestStateUnavailable(terminal.getRequestId());
        }
    }

    private NpcChatResp persistTerminal(NpcChatRequest terminal)
    {
        try
        {
            npcChatRequestMapper.insertNpcChatRequest(terminal);
            return fromStored(terminal);
        }
        catch (DuplicateKeyException e)
        {
            return afterInsertCollision(terminal.getUserId(), terminal.getRequestId());
        }
        catch (Exception e)
        {
            log.error("[NpcChat] rejected request state insert failed userId={} requestId={}", terminal.getUserId(),
                    terminal.getRequestId(), e);
            return requestStateUnavailable(terminal.getRequestId());
        }
    }

    private NpcChatResp afterInsertCollision(Long userId, String requestId)
    {
        try
        {
            NpcChatRequest stored = npcChatRequestMapper.selectByUserAndRequestId(userId, requestId);
            return stored != null ? fromStored(stored) : inProgress(requestId);
        }
        catch (Exception e)
        {
            log.error("[NpcChat] request state collision lookup failed userId={} requestId={}", userId, requestId, e);
            return requestStateUnavailable(requestId);
        }
    }

    private NpcChatResp fromStored(NpcChatRequest state)
    {
        if (NpcChatRequest.STATUS_PROCESSING.equals(state.getStatus()))
        {
            if("REQUEST_OUTCOME_UNKNOWN".equals(state.getErrorCode())
                    || (state.getLeaseUntil()!=null && !state.getLeaseUntil().after(new Date()))) {
                return response(state.getRequestId(),state.getStatus(),"UNKNOWN","REQUEST_OUTCOME_UNKNOWN",null,
                        false,true,"原请求结果待确认，请稍后查看原请求。");
            }
            return inProgress(state.getRequestId());
        }
        return response(state.getRequestId(), state.getStatus(), state.getSafetyDecision(), state.getErrorCode(),
                state.getLimitType(), Boolean.TRUE.equals(state.getRetryable()), Boolean.TRUE.equals(state.getAi()),
                state.getSafeText(), state.getRetryAfterSeconds());
    }

    private NpcChatResp inProgress(String requestId)
    {
        return response(requestId, NpcChatRequest.STATUS_PROCESSING, "NOT_RUN", "REQUEST_IN_PROGRESS", null,
                true, true, SAFE_IN_PROGRESS, 1);
    }

    /** 原答案只读恢复，不重新检测资格或启动ASR/模型；严格限定本人、原节点、原requestId。 */
    public NpcChatResp storedShopRequest(String requestId,Long userId,Long nodeId) {
        if(userId==null || userId<=0 || nodeId==null || nodeId<=0 || nodeId>MAX_CHAT_ENTITY_ID
                || requestId==null || !UUID_PATTERN.matcher(requestId.trim()).matches()) {
            return requestScopeMismatch(requestId);
        }
        try {
            NpcChatRequest stored=npcChatRequestMapper.selectByUserAndRequestId(userId,requestId.trim());
            if(stored==null) return null;
            if(!Long.valueOf(-nodeId).equals(stored.getSessionId())) return requestScopeMismatch(requestId);
            return fromStored(stored);
        } catch(RuntimeException unavailable) { return requestStateUnavailable(requestId); }
    }

    private NpcChatResp requestScopeMismatch(String requestId) {
        return response(requestId,NpcChatRequest.STATUS_REJECTED,"NOT_RUN","INVALID_REQUEST",null,
                false,true,SAFE_INVALID_REQUEST);
    }

    private NpcChatResp requestStateUnavailable(String requestId)
    {
        return response(requestId, NpcChatRequest.STATUS_FAILED, "NOT_RUN", "REQUEST_STATE_UNAVAILABLE", null,
                true, true, SAFE_UPSTREAM_FAILED);
    }

    private RoamSession ownedActiveSession(Long userId, Long sessionId)
    {
        try
        {
            RoamSession session = roamMapper.selectSessionById(sessionId);
            if (session == null || !userId.equals(session.getMemberId()) || !Integer.valueOf(0).equals(session.getStatus()))
            {
                return null;
            }
            return session;
        }
        catch (Exception e)
        {
            log.warn("[NpcChat] roam session validation failed userId={} sessionId={}", userId, sessionId, e);
            return null;
        }
    }

    private NpcChatRequest newProcessing(Long userId, Long sessionId, String requestId)
    {
        NpcChatRequest state = new NpcChatRequest();
        state.setUserId(userId);
        state.setSessionId(sessionId);
        state.setRequestId(requestId);
        state.setStatus(NpcChatRequest.STATUS_PROCESSING);
        state.setSafetyDecision("NOT_RUN");
        state.setRetryable(false);
        state.setAi(true);
        state.setLeaseToken(IdUtils.simpleUUID());
        state.setLeaseUntil(new Date(System.currentTimeMillis() + LEASE_MILLIS));
        state.setActiveSlot(1);
        return state;
    }

    private NpcChatRequest newTerminal(Long userId, Long sessionId, String requestId, String status,
                                       String safetyDecision, String errorCode, String limitType,
                                       boolean retryable, String safeText)
    {
        NpcChatRequest state = new NpcChatRequest();
        state.setUserId(userId);
        state.setSessionId(sessionId);
        state.setRequestId(requestId);
        terminalize(state, status, safetyDecision, errorCode, limitType, retryable, safeText);
        return state;
    }

    private void terminalize(NpcChatRequest state, String status, String safetyDecision, String errorCode,
                             String limitType, boolean retryable, String safeText)
    {
        terminalize(state, status, safetyDecision, errorCode, limitType, retryable, safeText, null);
    }

    private void terminalize(NpcChatRequest state, String status, String safetyDecision, String errorCode,
                             String limitType, boolean retryable, String safeText, Integer retryAfterSeconds)
    {
        state.setStatus(status);
        state.setSafetyDecision(safetyDecision);
        state.setErrorCode(errorCode);
        state.setLimitType(limitType);
        state.setRetryAfterSeconds(retryAfterSeconds);
        state.setRetryable(retryable);
        state.setSafeText(safeText);
        state.setAi(true);
        state.setActiveSlot(null);
        state.setCompletedAt(new Date());
    }

    private NpcChatResp response(String requestId, String status, String safetyDecision, String errorCode,
                                 String limitType, boolean retryable, boolean ai, String safeText)
    {
        return response(requestId, status, safetyDecision, errorCode, limitType, retryable, ai, safeText, null);
    }

    private NpcChatResp response(String requestId, String status, String safetyDecision, String errorCode,
                                 String limitType, boolean retryable, boolean ai, String safeText,
                                 Integer retryAfterSeconds)
    {
        NpcChatResp resp = new NpcChatResp();
        resp.setRequestId(requestId);
        resp.setOutcomeStatus(status);
        resp.setSafetyDecision(safetyDecision);
        resp.setErrorCode(errorCode);
        resp.setLimitType(limitType);
        resp.setRetryable(retryable);
        resp.setAi(ai);
        resp.setSafeText(safeText);
        resp.setRetryAfterSeconds(retryAfterSeconds);
        return resp;
    }

    private void saveLog(Long userId, Long sessionId, Long profileId, String message, String reply,
                         int safetyFlag, String model, int latencyMs)
    {
        try
        {
            NpcChatLog logEntry = new NpcChatLog();
            logEntry.setUserId(userId);
            // 旧审计表列不可空；人设和 session 都已服务端派生，0 只表示种子尚未落库的兼容态。
            logEntry.setProfileId(profileId == null ? 0L : profileId);
            logEntry.setActivityId(sessionId);
            logEntry.setUserMsg(message);
            logEntry.setNpcReply(reply);
            logEntry.setSafetyFlag(safetyFlag);
            logEntry.setModel(model);
            logEntry.setLatencyMs(latencyMs);
            logEntry.setTokenIn(0);
            logEntry.setTokenOut(0);
            npcChatLogMapper.insertNpcChatLog(logEntry);
        }
        catch (Exception e)
        {
            log.error("[NpcChat] saveLog failed", e);
        }
    }

    private NpcProfile findGlobalProfile()
    {
        try
        {
            return npcProfileMapper.selectGlobalNpc();
        }
        catch (Exception e)
        {
            log.warn("[NpcChat] global profile lookup failed, reject chat", e);
            return null;
        }
    }

    private int safetyFlagOf(AiServiceException e)
    {
        if (e.getReason() == AiServiceException.Reason.INPUT_BLOCKED) return 1;
        if (e.getReason() == AiServiceException.Reason.OUTPUT_BLOCKED) return 2;
        return 3;
    }

    private boolean isRequestEnvelopeValid(NpcChatReq req, String requestId)
    {
        return req != null && nonBlank(requestId) && requestId.length() <= REQUEST_ID_MAX
                && UUID_PATTERN.matcher(requestId).matches()
                && req.getBizId() != null && req.getBizId() > 0;
    }

    private boolean isMessageValid(String message)
    {
        return nonBlank(message) && message.codePointCount(0, message.length()) <= MESSAGE_MAX_CODE_POINTS;
    }

    private String normalizedMessage(NpcChatReq req)
    {
        return req == null || req.getMessage() == null ? null : req.getMessage().trim();
    }

    private String requestIdOf(NpcChatReq req)
    {
        return req == null || req.getRequestId() == null ? null : req.getRequestId().trim();
    }

    private boolean nonBlank(String value)
    {
        return value != null && value.trim().length() > 0;
    }

    private String truncateCodePoints(String value, int maxCodePoints)
    {
        if (value == null) return "";
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private int secondsUntilTomorrow()
    {
        long seconds = Duration.between(LocalDateTime.now(), LocalDate.now().plusDays(1).atStartOfDay()).getSeconds();
        return (int) Math.max(1L, Math.min(86400L, seconds));
    }
}

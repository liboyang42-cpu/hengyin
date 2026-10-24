package com.chengyinhub.web.controller.api;

import java.util.Collections;
import java.util.List;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.dto.ai.NpcChatReq;
import com.chengyinhub.business.domain.vo.ai.NpcLineVO;
import com.chengyinhub.business.domain.vo.ai.NpcProfileVO;
import com.chengyinhub.business.service.impl.NpcChatDataService;
import com.chengyinhub.business.service.impl.NpcChatService;
import com.chengyinhub.business.service.impl.NpcEventService;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ai.SpeechToTextService;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestPart;
import java.io.IOException;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
/**
 * AI NPC 陪伴接口(出场/事件冒泡/自由追问)。
 *
 * @author chengyinhub
 */
@RestController
@Tag(name = "AI NPC 陪伴")
@RequestMapping("/api/ai/npc")
public class ApiAiNpcController extends BaseController
{
    private static final Logger log = LoggerFactory.getLogger(ApiAiNpcController.class);

    @Autowired
    private NpcProfileService npcProfileService;

    @Autowired
    private NpcEventService npcEventService;

    @Autowired
    private NpcChatService npcChatService;

    @Autowired
    private NpcChatDataService npcChatDataService;

    @Autowired
    private NpcFeatureFlags npcFeatureFlags;

    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;

    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;

    @Autowired
    private PlayTeamMapper playTeamMapper;

    @Autowired
    private SpeechToTextService speechToTextService;

    @Autowired
    private com.chengyinhub.business.service.impl.NpcAsrIntentService npcAsrIntentService;

    /**
     * 取出场 NPC。
     * scope=global -> 全局 NPC; scope=activity -> 局内 NPC(活动角色+门店分身);
     * scope=node -> 主题节点 NPC(CR-927,只回已过审版本,且该商家点位必须已过主办方准入)。
     */
    @Operation(summary = "取出场 NPC")
    @RateLimiter(time = 60, count = 60, limitType = LimitType.MEMBER)
    @GetMapping("/profile")
    public AjaxResult profile(
            @RequestParam(defaultValue = "global") String scope,
            @RequestParam(required = false) Long activityId,
            @RequestParam(required = false) Long nodeId)
    {
        try
        {
            if ("global".equals(scope))
            {
                NpcProfileVO vo = npcProfileService.resolveGlobal();
                return success(new NpcProfileList(vo != null ? Collections.singletonList(vo) : Collections.<NpcProfileVO>emptyList()));
            }
            else if ("activity".equals(scope) && activityId != null)
            {
                List<NpcProfileVO> vos = npcProfileService.resolveForActivity(activityId);
                return success(new NpcProfileList(vos));
            }
            else if ("node".equals(scope) && nodeId != null)
            {
                // 双闸:① 这个商家点位必须过了主办方准入(node_audit_status=1 且挂了商家)——
                // 节点还没过审就先把 NPC 文本放出去,等于绕过主办方那一段;② NPC 自身走已过审读闸。
                CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nodeId);
                boolean nodeVisible = node != null && node.getMerchantMemberId() != null
                        && Integer.valueOf(1).equals(node.getNodeAuditStatus());
                NpcProfileVO vo = nodeVisible ? npcProfileService.resolveForNode(nodeId) : null;
                return success(new NpcProfileList(vo != null ? Collections.singletonList(vo) : Collections.<NpcProfileVO>emptyList()));
            }
            return success(new NpcProfileList(Collections.<NpcProfileVO>emptyList()));
        }
        catch (Exception e)
        {
            log.warn("[npc/profile] resolve failed, scope={}, activityId={}, nodeId={}, errorType={}",
                    scope, activityId, nodeId, e.getClass().getName());
            return success(new NpcProfileList(Collections.<NpcProfileVO>emptyList()));
        }
    }

    /**
     * 漫游提示文案（查缓存，<=200ms，只查表不调模型）。
     *
     * @param cacheOnly true = 缓存未命中返回空 line,不回落 greeting。默认 false = 老调用方零影响。
     *                  漫游提示层固定传 true：它靠「line 为空」判静默，回落 greeting 会变成
     *                  开场白复读机(每次还各自扣配额、发 impression 埋点)。
     */
    @Operation(summary = "漫游提示文案")
    @RateLimiter(time = 60, count = 60, limitType = LimitType.MEMBER)
    @GetMapping("/event")
    public AjaxResult event(
            @RequestParam Long profileId,
            @RequestParam String eventType,
            @RequestParam(required = false) Long nodeId,
            @RequestParam(defaultValue = "false") boolean cacheOnly)
    {
        try
        {
            NpcLineVO vo = npcEventService.getEventLine(profileId, eventType, nodeId, cacheOnly);
            return success(vo);
        }
        catch (Exception e)
        {
            NpcLineVO fallback = new NpcLineVO();
            fallback.setProfileId(profileId);
            fallback.setLine("");
            return success(fallback); // 静默降级
        }
    }

    /**
     * 自由追问(普通 POST,一次性返回审核后的完整文本)。
     * 不用 SSE:依据《漫游AI功能_最优架构安全治理与实施补充方案_20260716》§1.1-5,
     * 客户端收到 outcomeStatus=SUCCEEDED 且 safetyDecision=PASS 后自行做本地逐字呈现。
     * 请求体只接收 requestId、bizId(漫游会话)与 message；用户、NPC 人设和漫游摘要均由服务端派生。
     *
     * <p>⚠️ 开关闸门(§13.1):NpcFeatureFlags.chatOn() 非真时拒绝,且不触达 NpcChatService。
     * <b>理由不是「防烧钱」</b> —— PR #75 已把 NpcChatService 收编进 AiGatewayService
     * (频控 + 内容安全 + 审计日志都在网关里),那个洞已经堵上了。这里挡的是**合规**:
     * Chat 轨被 Phase 0 合规签字阻断,签字前不该开放。
     * 本端点需登录(SecurityConfig 的 anyRequest().authenticated(),/api/ai/** 不在 permitAll),
     * 所以受众是「任何已登录用户」而非「任何人」—— 但一个没过合规签字的功能,对已登录用户
     * 开放同样不行。
     * 无形陪伴 V1 中 chatOn() 固定为 false；若未来另立 Chat 合规项目，必须新建 Gate，不能由配置误开。
     */
    @Operation(summary = "自由追问")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    @PostMapping("/chat")
    public AjaxResult chat(@RequestBody NpcChatReq req)
    {
        if (!npcFeatureFlags.chatOn())
        {
            log.warn("[npc/chat] 无形陪伴 V1 未开放自由追问,拒绝请求");
            return AjaxResult.error(HttpStatus.FORBIDDEN, "NPC 自由追问未开放");
        }
        Long userId = getAppUserId();
        // openid 不再由本层硬传空串:内容安全已收编进 AiGatewayService,
        // 由它按 memberId 反查真实 openid(resolveOpenid)。
        // 收编前这里传 "" ⇒ msg_sec_check v2 缺必填 openid ⇒ errcode != 0
        // ⇒ WxContentSecurityServiceImpl fail-close ⇒ NPC 聊天恒被拦。
        return success(npcChatService.chat(req, userId));
    }

    /**
     * 按 requestId 只读回读一条 NPC 对话原请求的状态与回复(关闭/重开后恢复,不重发)。
     *
     * <p>不挂 chatOn()/shopChatOn()/merchantChatOn():开关只拦新提问;回读只返回本人已落库的那一条,
     * 不进模型、不扣额度。归属在 Service 里按 本人 + 原会话/原店 + 原 requestId 限定,
     * 任一不符一律 INVALID_REQUEST,不区分「不存在」与「不是你的」。
     *
     * @param scope roam | merchant | shop,与发起时的端点一一对应
     */
    @Operation(summary = "NPC对话原请求回读")
    @RateLimiter(time = 60, count = 60, limitType = LimitType.MEMBER)
    @GetMapping("/chat/request")
    public AjaxResult chatRequest(@RequestParam(required = false) String requestId,
                                  @RequestParam(required = false) String scope,
                                  @RequestParam(required = false) Long bizId)
    {
        Long userId=getAppUserId();
        com.chengyinhub.business.domain.vo.ai.NpcChatResp original=
                npcChatService.readOriginalRequest(scope,bizId,requestId,userId);
        if (original!=null && NpcChatService.STATUS_NOT_FOUND.equals(original.getOutcomeStatus())) {
            com.chengyinhub.business.domain.vo.ai.NpcChatResp asr=npcAsrIntentService.readOutcome(scope,bizId,requestId,userId);
            if (asr!=null) return success(asr);
        }
        return success(original);
    }

    /**
     * 门店分身自由追问(自由探索:卡片详情 → 店铺卡 → 对话)。
     *
     * <p>与 /chat 的分工:那条是全局 NPC 聊漫游进度,这条是**这一家店的分身**聊这家店。
     *
     * <p>⚠️ 走**独立开关** {@code feature.flag.shopNpcChat},不复用 chatOn():
     * 那一条是「无形陪伴 V1 不提供自由追问」的合规闸(硬编码 false),挂上去这条链路
     * 就是死代码。默认关 —— 开它是一次明确的运营动作,要先过门店分身自己的合规评估。
     *
     * <p><b>归属校验在本层做</b>:玩家必须持有这家店所属主题的有效通行证(自玩)或已付款报名
     * (活动局)。不校验的话,任何登录用户都能拿任意 nodeId 直调,把别人家的分身当免费模型用 ——
     * 这是花钱的接口,越权面必须堵在进模型之前。
     */
    @Operation(summary = "门店分身自由追问")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    @PostMapping("/shop-chat")
    public AjaxResult shopChat(@RequestBody ShopChatReq req)
    {
        if (!npcFeatureFlags.shopChatOn())
        {
            return AjaxResult.error(HttpStatus.FORBIDDEN, "店铺分身对话还没开放");
        }
        if (req == null) return AjaxResult.error("参数有误");
        Long userId = getAppUserId();
        com.chengyinhub.business.domain.vo.ai.NpcChatResp stored=
                npcChatService.storedShopRequest(req.getRequestId(),userId,req.getNodeId());
        if(stored!=null) return success(stored);
        com.chengyinhub.business.domain.vo.ai.NpcChatResp asr=
                npcAsrIntentService.readOutcome("shop",req.getNodeId(),req.getRequestId(),userId);
        if(asr!=null) return success(asr);
        ShopGate gate = gateShopChat(userId, req.getNodeId());
        if (gate.deny != null) return gate.deny;
        return success(npcChatService.chatWithShop(req.getRequestId(), userId, gate.node.getId(),
                gate.node.getMerchantMemberId(), shopMemoryOf(gate.node), req.getMessage()));
    }

    /**
     * 分身对话的**登录 + 参数 + 归属**闸门。打字与语音两条入口共用同一份判定 ——
     * 归属规则将来一改,只有这一处要改;各写一遍的话总会漏掉一边,而漏掉的那一边就是越权面。
     *
     * @return 放行时 node 非空;拒绝时 deny 非空。二者恒有其一。
     */
    private ShopGate gateShopChat(Long userId, Long nodeId)
    {
        if (userId == null || userId <= 0) return ShopGate.deny(AjaxResult.error(HttpStatus.UNAUTHORIZED, "请先登录"));
        if (nodeId == null || nodeId <= 0) return ShopGate.deny(AjaxResult.error("参数有误"));

        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nodeId);
        if (node == null || node.getTopicId() == null || node.getMerchantMemberId() == null)
        {
            return ShopGate.deny(AjaxResult.error("这家店不存在或还没绑定商家"));
        }
        if (playTeamMapper.hasCurrentNpcRegistration(userId, node.getTopicId()) <= 0)
        {
            return ShopGate.deny(AjaxResult.error(HttpStatus.FORBIDDEN, "先拿到这个主题的通行证再来问我"));
        }
        return ShopGate.pass(node);
    }

    /** 闸门结论。放行带节点,拒绝带原因,二者互斥。 */
    private static final class ShopGate
    {
        final CmsTopicNode node;
        final AjaxResult deny;

        private ShopGate(CmsTopicNode node, AjaxResult deny) { this.node = node; this.deny = deny; }

        static ShopGate pass(CmsTopicNode node) { return new ShopGate(node, null); }

        static ShopGate deny(AjaxResult deny) { return new ShopGate(null, deny); }
    }

    /** 语音提问允许的最大字节数(约 60s / 16k 单声道)。超了直接拒,不进 ASR 也不进模型。 */
    private static final int VOICE_MAX_BYTES = 2 * 1024 * 1024;

    /**
     * 门店分身语音提问:录音上传 → 转文字 → 走**和打字完全相同**的那条链路。
     *
     * <p>整条链路只有「转文字」这一环是缺的(见 {@link SpeechToTextService}):
     * 归属校验、功能闸、幂等、租约、内容安全、计费、落库全部复用 shop-chat 的那一套。
     * ASR 没配好时在**进模型之前**就返回,不扣额度、不留假记录。
     *
     * <p>⚠️ 音频只在内存里过一遍,<b>不落盘也不进 OSS</b>。留下来的只有识别出的文字,
     * 转写正文短期保留；到期及 /chat/data/delete 均只擦正文，永久请求身份不释放。
     */
    @Operation(summary = "门店分身语音提问")
    @RateLimiter(time = 60, count = 15, limitType = LimitType.MEMBER)
    @PostMapping("/voice-chat")
    public AjaxResult voiceChat(@RequestPart(value="file",required=false) MultipartFile file,
                                @RequestParam("nodeId") Long nodeId,
                                @RequestParam("requestId") String requestId)
    {
        if (!npcFeatureFlags.shopChatOn())
        {
            return AjaxResult.error(HttpStatus.FORBIDDEN, "店铺分身对话还没开放");
        }
        Long userId = getAppUserId();
        com.chengyinhub.business.domain.vo.ai.NpcChatResp stored=
                npcChatService.storedShopRequest(requestId,userId,nodeId);
        if(stored!=null) return success(stored);
        try {
            com.chengyinhub.business.domain.NpcAsrIntent receipt=npcAsrIntentService.read(userId,requestId,nodeId);
            if (file==null || file.isEmpty()) {
                if (receipt==null) return AjaxResult.error("参数有误");
                // Explicit POST may continue the original saved transcription; GET never takes this branch.
            } else {
                if (receipt==null && !speechToTextService.available())
                    return AjaxResult.error("语音识别还没接入，先打字问我");
                if (file.getSize()>VOICE_MAX_BYTES) return AjaxResult.error("这段录音太长了，说短一点");
                ShopGate before=gateShopChat(userId,nodeId);
                if (before.deny!=null) return before.deny;
                byte[] audio;
                try { audio=file.getBytes(); }
                catch(IOException missing) { return AjaxResult.error("录音没收全，再传一次"); }
                if (audio.length>VOICE_MAX_BYTES) return AjaxResult.error("这段录音太长了，说短一点");
                String format=NpcVoiceAudioFormat.detect(audio);
                if (format==null) return AjaxResult.error(HttpStatus.BAD_REQUEST,"录音格式不支持");
                receipt=npcAsrIntentService.recognize(userId,requestId,nodeId,before.node.getMerchantMemberId(),audio,format);
            }
            stored=npcChatService.storedShopRequest(requestId,userId,nodeId);
            if (stored!=null) return success(stored);
            if (!"RECEIVED".equals(receipt.getState()) || receipt.isBodyErased()
                    || receipt.isHandoffStarted() || receipt.getTranscript()==null || receipt.getTranscript().isEmpty())
                return asrOnly(receipt);
            ShopGate gate=gateShopChat(userId,nodeId);
            if (gate.deny!=null) return gate.deny;
            CmsTopicNode node=gate.node;
            if (!npcAsrIntentService.beginHandoff(userId,requestId,nodeId,node.getMerchantMemberId())) {
                stored=npcChatService.storedShopRequest(requestId,userId,nodeId);
                if (stored!=null) return success(stored);
                return asrOnly(npcAsrIntentService.read(userId,requestId,nodeId));
            }
            AjaxResult ok=success(npcChatService.chatWithShop(requestId,userId,node.getId(),
                    node.getMerchantMemberId(),shopMemoryOf(node),receipt.getTranscript()));
            // Deletion/expiry may have revoked the body while the text request was running.
            // Read its current persisted snapshot, never resurrect the captured pre-handoff transcript.
            com.chengyinhub.business.domain.NpcAsrIntent echo=npcAsrIntentService.read(userId,requestId,nodeId);
            if(echo!=null && !echo.isBodyErased() && echo.getTranscript()!=null)
                ok.put("asr",echo.getTranscript());
            return ok;
        } catch(IllegalArgumentException conflict) {
            return success(com.chengyinhub.business.service.impl.NpcAsrIntentService.unavailable(requestId,"INVALID_REQUEST"));
        } catch(RuntimeException unknown) {
            // Save/claim commit may already have succeeded. Read only; never grant a second paid attempt.
            stored=npcChatService.storedShopRequest(requestId,userId,nodeId);
            if (stored!=null) return success(stored);
            com.chengyinhub.business.domain.vo.ai.NpcChatResp asr=npcAsrIntentService.readOutcome("shop",nodeId,requestId,userId);
            return AjaxResult.success("语音识别没成功，稍后再试",
                    asr!=null?asr:com.chengyinhub.business.service.impl.NpcAsrIntentService.unavailable(requestId,"ASR_STATE_UNAVAILABLE"));
        }
    }

    private AjaxResult asrOnly(com.chengyinhub.business.domain.NpcAsrIntent receipt) {
        if (receipt==null) return success(com.chengyinhub.business.service.impl.NpcAsrIntentService.unavailable(null,"ASR_STATE_UNAVAILABLE"));
        AjaxResult result;
        if ("RECEIVED".equals(receipt.getState()) && !receipt.isBodyErased()
                && !receipt.isHandoffStarted() && "".equals(receipt.getTranscript()))
            result=AjaxResult.error("没听清，再说一次");
        else if ("NOT_SENT".equals(receipt.getState()))
            result=AjaxResult.error("语音识别没成功，稍后再试");
        else if ("UNKNOWN".equals(receipt.getState()))
            result=AjaxResult.success("语音识别没成功，稍后再试");
        else result=AjaxResult.success();
        result.put("data",com.chengyinhub.business.service.impl.NpcAsrIntentService.outcome(receipt));
        return result;
    }

    /**
     * 喂给模型的现场事实。只放店里公开可见的那几项 —— 玩家身份、坐标、别人的进度都不进提示词。
     * 答案类字段(questionAnswer/correctAnswer)更不能进:模型会把它讲出来。
     */
    private String shopMemoryOf(CmsTopicNode node)
    {
        StringBuilder sb = new StringBuilder("[本店事实] 店名:").append(nz(node.getName()));
        if (nz(node.getBusinessTime()).length() > 0) sb.append("；营业时间:").append(node.getBusinessTime());
        if (nz(node.getAddress()).length() > 0) sb.append("；地址:").append(node.getAddress());
        if (node.getTemplateId() != null)
        {
            try
            {
                CmsMemberTemplate tpl = cmsMemberTemplateService.selectCmsMemberTemplateById(node.getTemplateId());
                if (tpl != null && nz(tpl.getTitle()).length() > 0) sb.append("；本站玩法:").append(tpl.getTitle());
                if (tpl != null && nz(tpl.getRuleInstructions()).length() > 0)
                {
                    sb.append("；玩法规则:").append(tpl.getRuleInstructions());
                }
            }
            catch (Exception e)
            {
                // 取不到玩法只让回答少一条事实,不能让整条对话挂掉
                log.debug("[npc/shop-chat] 玩法模板读取失败 nodeId={}", node.getId(), e);
            }
        }
        return sb.append("。只依据以上事实回答，不知道就说不知道，不要编造。").toString();
    }

    private static String nz(String v) { return v == null ? "" : v.trim(); }

    /** 门店分身追问入参。 */
    public static class ShopChatReq
    {
        private String requestId;
        private Long nodeId;
        private String message;

        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
        public Long getNodeId() { return nodeId; }
        public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    /** 店铺主页门店 NPC 对话；bizId 为商家 ID，功能默认关闭。 */
    @Operation(summary = "门店NPC对话")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    @PostMapping("/merchant-chat")
    public AjaxResult merchantChat(@RequestBody NpcChatReq req)
    {
        if (!npcFeatureFlags.merchantChatOn())
        {
            return AjaxResult.error(HttpStatus.FORBIDDEN, "门店对话未开放");
        }
        Long userId = getAppUserId();
        Long merchantId = req == null ? null : req.getBizId();
        return success(npcChatService.chatWithMerchant(req, userId, merchantId));
    }

    /**
     * 导出我的 NPC 聊天记录(《漫游AI方案》§7.4 数据复制/导出义务,可读 JSON)。
     *
     * <p>身份只取登录态(getAppUserId),端点不收任何用户 ID 入参 —— 越权面结构性不存在。
     * <p>不挂 chatOn() 开关:数据权利必须始终可行使 —— 即使 Chat 已关闭,
     * 用户仍可能持有开关开启期间产生的历史记录。
     * <p>审计:走 NpcChatDataService 里的服务端结构化日志(§8.3 最小实现)。
     * 不挂 @Log —— LogAspect 对 app 登录态是静默 no-op(SecurityUtils.getLoginUser()
     * 把 principal 强转 LoginUser,而 app 侧是 LoginAppUser,异常被 handleLog 吞掉,
     * sys_oper_log 零产出),挂了只会制造「已有审计」的假象;修切面属全仓治理任务。
     */
    @Operation(summary = "导出我的NPC聊天记录")
    @GetMapping("/chat/data/export")
    public AjaxResult exportChatData()
    {
        return success(npcChatDataService.exportForUser(getAppUserId()));
    }

    /**
     * 删除我的全部 NPC 聊天记录(用户主动删除,立即硬删;区别于 90 天到期自动清理)。
     * 同 export:身份只取登录态,不挂 chatOn() 开关,审计走服务端日志(@Log 对 app 用户零产出)。
     */
    @Operation(summary = "删除我的NPC聊天记录")
    @PostMapping("/chat/data/delete")
    public AjaxResult deleteChatData()
    {
        int deleted = npcChatDataService.deleteForUser(getAppUserId());
        return success(Collections.singletonMap("deleted", deleted));
    }

    // 内嵌包装: 保持 AjaxResult.data 结构一致
    private static class NpcProfileList
    {
        private List<NpcProfileVO> npcs;
        public NpcProfileList(List<NpcProfileVO> npcs) { this.npcs = npcs; }
        public List<NpcProfileVO> getNpcs() { return npcs; }
        public void setNpcs(List<NpcProfileVO> npcs) { this.npcs = npcs; }
    }
}

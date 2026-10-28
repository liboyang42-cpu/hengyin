package com.chengyinhub.web.controller.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.dto.ai.AiClubDesignReq;
import com.chengyinhub.business.domain.dto.ai.AiMerchantPlanReq;
import com.chengyinhub.business.domain.dto.ai.AiMerchantTaskSuggestReq;
import com.chengyinhub.business.domain.dto.ai.AiNodeGenReq;
import com.chengyinhub.business.domain.dto.ai.AiTemplateFillReq;
import com.chengyinhub.business.domain.dto.ai.AiSafetyPrecheckReq;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftReq;
import com.chengyinhub.business.domain.dto.ai.AiChapterOverviewReq;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftPrepareReq;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftExecuteReq;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftIntentView;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftIntentState;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftRecoveryItem;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.impl.AiThemeDraftRequestService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
/**
 * AI 创作助手接口(商户组题/主题草稿/节点助手/安全预检)。
 * 主题草稿向玩家开放简单创作入口，其余创作能力仅 club/merchant 可用。
 *
 * @author chengyinhub
 */
@RestController
@Tag(name = "AI创作助手")
@RequestMapping("/api/ai")
public class ApiAiController extends BaseController
{
    /** AI 入口向 club/merchant 开放;player 不可用 */
    private static final String PLAYER_BLOCKED = "当前身份暂不支持AI创作,请切换到俱乐部或商家身份";

    /** 角色解析本身炸了 —— 是服务端故障,不是用户身份不对,不能混用 PLAYER_BLOCKED 误导用户 */
    private static final String ROLE_RESOLVE_FAILED = "AI 服务暂时不可用,请稍后重试";

    /** 店铺参谋只对商家开放(数据是本商家的经营明细,club 也不放行) */
    private static final String MERCHANT_ONLY_BLOCKED = "店铺参谋仅商家身份可用";

    @Autowired
    private IAiCreatorService aiCreatorService;

    @Autowired
    private AiThemeDraftRequestService themeDraftRequests;


    @Autowired
    private com.chengyinhub.business.service.IMerchantInsightService merchantInsightService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    /**
     * 身份门禁:取当前角色,非 club/merchant 返回拦截。
     *
     * ⚠️ 2026-08-10:这道闸是 AI 链路上唯一一处「拦下来还不留痕迹」的地方。它排在
     * AiGatewayService 之前,而 AiGenerationLog 是网关写的 —— 被这里拦掉的请求
     * 库里没有行、日志里没有字,事后完全无法回答「用户那次点击到底发生了什么」。
     * 更糟的是原先 catch 把**任何**后端异常(查库超时、NPE)都伪装成
     * 「你身份不对」返回给用户,连异常本身都吞掉。用户报"AI 不响应"而生产日志
     * 零痕迹,这里是最可能的出处。两条路都必须留下可检索的痕迹。
     */
    private String gateRole(Long memberId)
    {
        String role;
        try
        {
            role = aiCreatorService.resolveRoleFor(memberId);
        }
        catch (Exception e)
        {
            // 这不是身份问题,是我们的锅:如实记下来,也别再对用户谎称是身份问题。
            logger.error("[AiGate] 角色解析失败 memberId={},按拒绝处理", memberId, e);
            return ROLE_RESOLVE_FAILED;
        }
        if ("player".equals(role) || role == null)
        {
            logger.info("[AiGate] role-blocked memberId={} role={}", memberId, role);
            return PLAYER_BLOCKED;
        }
        return null;  // null = 放行
    }

    /** 主题草稿是简单创作入口，玩家也可用；仍要求能解析到有效角色。 */
    private String gateThemeDraftRole(Long memberId)
    {
        try
        {
            String role = aiCreatorService.resolveRoleFor(memberId);
            if (role == null)
            {
                logger.info("[AiGate] theme-draft role-blocked memberId={} role=null", memberId);
                return PLAYER_BLOCKED;
            }
            return null;
        }
        catch (Exception e)
        {
            logger.error("[AiGate] 角色解析失败(theme-draft) memberId={},按拒绝处理", memberId, e);
            return ROLE_RESOLVE_FAILED;
        }
    }

    private Long resolveMemberId()
    {
        try
        {
            return getAppUserId();
        }
        catch (Exception e)
        {
            // 同上:登录态解析失败也曾静默返回 null ⇒ 用户看到"请先登录"、日志里什么都没有。
            // 对一个明明已登录的用户来说,这就是"AI 点了没反应"。
            logger.warn("[AiGate] 登录态解析失败,按未登录处理", e);
            return null;
        }
    }

    @Operation(summary = "AI商家组题:附近商家→多套主题方案")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.MEMBER)
    @PostMapping("/theme/merchant-plan")
    public AjaxResult merchantPlan(@RequestBody AiMerchantPlanReq req)
    {
        Long memberId = resolveMemberId();
        if (memberId == null) return error("请先登录");
        String gate = gateRole(memberId);
        if (gate != null) return error(gate);

        try
        {
            return success(aiCreatorService.merchantPlan(req, memberId));
        }
        catch (AiServiceException e)
        {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "主题草稿旧提交入口已关闭")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.MEMBER)
    @PostMapping("/theme/draft")
    public AjaxResult themeDraft(@RequestBody AiThemeDraftReq req)
    {
        if (resolveMemberId() == null) return error("请先登录");
        return draftError(400, "请先准备本次起草", "AI_DRAFT_PREPARE_REQUIRED", null);
    }

    @Operation(summary = "准备主题草稿原请求")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.MEMBER)
    @PostMapping("/theme/draft/prepare")
    public AjaxResult themeDraftPrepare(@RequestBody AiThemeDraftPrepareReq req)
    {
        Long memberId = resolveMemberId();
        if (memberId == null) return error("请先登录");
        try {
            return success(themeDraftRequests.prepare(memberId, req));
        } catch (AiThemeDraftRequestService.IntentRejectedException refused) {
            return draftRejected(refused);
        } catch (AiThemeDraftRequestService.StateUnavailableException unavailable) {
            return draftUnavailable(memberId, unavailable);
        }
    }

    @Operation(summary = "执行已准备的主题草稿原请求")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.MEMBER)
    @PostMapping("/theme/draft/execute")
    public AjaxResult themeDraftExecute(@RequestBody AiThemeDraftExecuteReq req)
    {
        Long memberId = resolveMemberId();
        if (memberId == null) return error("请先登录");
        try {
            AiThemeDraftIntentView view = themeDraftRequests.execute(memberId, req);
            if (view == null) return draftError(500, "暂时无法确认原请求", "AI_DRAFT_STATE_UNAVAILABLE", null);
            if (view.state() == AiThemeDraftIntentState.SUCCEEDED) {
                if (view.resultAvailable() && view.result() != null) return success(view);
                if ("AI_DRAFT_RESULT_EXPIRED".equals(view.reasonCode()))
                    return draftError(409, "原草稿已生成，正文已过期", "AI_DRAFT_RESULT_EXPIRED", view);
                return draftError(503, "原草稿已生成，正文暂时不可用", "AI_DRAFT_RESULT_UNAVAILABLE", view);
            }
            if (view.state() == AiThemeDraftIntentState.FAILED)
                return draftError(500, "AI 起草未成功", "AI_DRAFT_FAILED", view);
            if (view.state() == AiThemeDraftIntentState.UNKNOWN)
                return draftError(409, "原请求结果仍待确认", "AI_DRAFT_OUTCOME_UNKNOWN", view);
            return draftError(409, "原请求尚未完成，请查看原结果", "AI_DRAFT_IN_PROGRESS", view);
        } catch (AiThemeDraftRequestService.IntentRejectedException refused) {
            return draftRejected(refused);
        } catch (AiThemeDraftRequestService.StateUnavailableException unavailable) {
            return draftUnavailable(memberId, unavailable);
        }
    }

    @Operation(summary = "按原请求或准备编号回读本人的草稿事实")
    @RateLimiter(time = 60, count = 60, limitType = LimitType.MEMBER)
    @GetMapping("/theme/draft/request")
    public AjaxResult themeDraftRequest(@RequestParam(required = false) String requestId,
            @RequestParam(required = false) String prepareKey)
    {
        Long memberId = resolveMemberId();
        if (memberId == null) return error("请先登录");
        if ((requestId == null) == (prepareKey == null)
                || (requestId != null && requestId.isBlank()) || (prepareKey != null && prepareKey.isBlank()))
            return draftError(400, "请提供一个原请求编号", "AI_DRAFT_INVALID_REQUEST", null);
        try {
            return success(requestId != null ? themeDraftRequests.readIntent(memberId, requestId)
                    : themeDraftRequests.readPrepared(memberId, prepareKey));
        } catch (AiThemeDraftRequestService.IntentRejectedException refused) {
            return draftRejected(refused);
        } catch (AiThemeDraftRequestService.StateUnavailableException unavailable) {
            return draftUnavailable(memberId, unavailable);
        }
    }

    @Operation(summary = "找回本人原草稿请求及已知结果事实")
    @RateLimiter(time = 60, count = 60, limitType = LimitType.MEMBER)
    @GetMapping("/theme/draft/recovery")
    public AjaxResult themeDraftRecovery(@RequestParam(required = false) Long beforeId)
    {
        Long memberId = resolveMemberId();
        if (memberId == null) return error("请先登录");
        try {
            java.util.List<AiThemeDraftRecoveryItem> pending = themeDraftRequests.readRecovery(memberId, beforeId);
            if (pending == null) return draftError(500, "暂时无法确认原请求", "AI_DRAFT_STATE_UNAVAILABLE", null);
            java.util.List<DraftRecoveryView> items = pending.subList(0, Math.min(20, pending.size())).stream()
                    .map(item -> new DraftRecoveryView(item.cursor().toString(), item.prepareKey(), item.requestId(),
                            item.state(), item.reasonCode(), item.createdAt(), item.executeBefore(), item.resultExpiresAt(),
                            item.productType())).toList();
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("items", items);
            data.put("nextCursor", pending.size() > 20 ? items.get(items.size() - 1).cursor() : null);
            return success(data);
        } catch (AiThemeDraftRequestService.IntentRejectedException refused) {
            return draftRejected(refused);
        } catch (AiThemeDraftRequestService.StateUnavailableException unavailable) {
            return draftUnavailable(memberId, unavailable);
        }
    }

    // Decimal strings retain the full MySQL BIGINT cursor across JavaScript clients.
    private record DraftRecoveryView(String cursor, String prepareKey, String requestId,
            AiThemeDraftIntentState state, String reasonCode, java.time.Instant createdAt,
            java.time.Instant executeBefore, java.time.Instant resultExpiresAt, Integer productType) { }

    private static AjaxResult draftError(int code, String msg, String errorCode, AiThemeDraftIntentView view)
    {
        AjaxResult result = new AjaxResult(code, msg);
        result.put("errorCode", errorCode);
        if (view != null) result.put(AjaxResult.DATA_TAG, view);
        return result;
    }

    private static AjaxResult draftRejected(AiThemeDraftRequestService.IntentRejectedException refused)
    {
        return draftError(refused.getCode(), "本次起草暂不能继续", refused.getErrorCode(), refused.getIntent());
    }

    private AjaxResult draftUnavailable(Long memberId, RuntimeException unavailable)
    {
        logger.error("[AiThemeDraft] state unavailable memberId={} failureType={}", memberId, unavailable.getClass().getSimpleName());
        return draftError(500, "暂时无法确认原请求，请稍后回读", "AI_DRAFT_STATE_UNAVAILABLE", null);
    }

    @Operation(summary = "简单 AI 主题草稿剩余额度")
    @PostMapping("/theme/draft/quota")
    public AjaxResult themeDraftQuota()
    {
        Long memberId = resolveMemberId();
        if (memberId == null) return error("请先登录");
        String gate = gateThemeDraftRole(memberId);
        if (gate != null) return error(gate);
        try
        {
            return success(aiCreatorService.themeDraftQuota(memberId));
        }
        catch (AiServiceException e)
        {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "AI节点助手:为节点生成玩法/答题/提示")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    @PostMapping("/node/generate")
    public AjaxResult nodeGenerate(@RequestBody AiNodeGenReq req)
    {
        Long memberId = resolveMemberId();
        if (memberId == null) return error("请先登录");
        String gate = gateRole(memberId);
        if (gate != null) return error(gate);

        try
        {
            return success(aiCreatorService.nodeGenerate(req, memberId));
        }
        catch (AiServiceException e)
        {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "AI玩法草稿:店名/品类/奖励→模板核心内容(升级1)")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.MEMBER)
    @PostMapping("/template/fill")
    public AjaxResult templateFill(@RequestBody AiTemplateFillReq req)
    {
        Long memberId = resolveMemberId();
        if (memberId == null) return error("请先登录");
        String gate = gateRole(memberId);
        if (gate != null) return error(gate);
        if (req == null || req.getShopName() == null || req.getShopName().trim().isEmpty())
        {
            return error("请先填写店名");
        }
        try
        {
            return success(aiCreatorService.templateFill(req, memberId));
        }
        catch (AiServiceException e)
        {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "AI章节概述:为单个章节生成整体语境概述")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.MEMBER)
    @PostMapping("/chapter/overview")
    public AjaxResult chapterOverview(@RequestBody AiChapterOverviewReq req)
    {
        Long memberId = resolveMemberId();
        if (memberId == null) return error("请先登录");
        String gate = gateRole(memberId);
        if (gate != null) return error(gate);

        try
        {
            return success(aiCreatorService.chapterOverview(req, memberId));
        }
        catch (AiServiceException e)
        {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "AI发布前安全预检:安全+缺口/风险")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    @PostMapping("/safety/precheck")
    public AjaxResult safetyPrecheck(@RequestBody AiSafetyPrecheckReq req)
    {
        Long memberId = resolveMemberId();
        if (memberId == null) return error("请先登录");
        String gate = gateRole(memberId);
        if (gate != null) return error(gate);

        try
        {
            return success(aiCreatorService.safetyPrecheck(req, memberId));
        }
        catch (AiServiceException e)
        {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "M3 商家任务建议: 店铺适合哪些主题/任务角色")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.MEMBER)
    @PostMapping("/merchant/task-suggest")
    public AjaxResult merchantTaskSuggest(@RequestBody AiMerchantTaskSuggestReq req)
    {
        Long memberId = resolveMemberId();
        if (memberId == null) return error("请先登录");
        String gate = gateRole(memberId);
        if (gate != null) return error(gate);

        try
        {
            return success(aiCreatorService.merchantTaskSuggest(req, memberId));
        }
        catch (AiServiceException e)
        {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "商家店铺参谋: 打卡分析/适合人群/活动建议(商家主体取登录态,不收参数)")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.MEMBER)
    @PostMapping("/merchant/insight")
    public AjaxResult merchantInsight()
    {
        Long memberId = resolveMemberId();
        if (memberId == null) return error("请先登录");
        // 店铺参谋看的是本商家经营数据:登录人必须是该商家的成员(店主/员工),
        // 且岗位有 MARKETING_READ;club、玩家、无此权限的岗位一律不放行。
        MerchantAccessContext scope;
        try
        {
            scope = merchantAccessService.require(memberId, MerchantPermission.MARKETING_READ);
        }
        catch (ServiceException blocked)
        {
            logger.info("[AiGate] merchant-insight access-blocked memberId={} reason={}",
                    memberId, blocked.getMessage());
            return error(MERCHANT_ONLY_BLOCKED);
        }
        catch (RuntimeException e)
        {
            logger.error("[AiGate] 商家权限解析失败(merchant-insight) memberId={},按拒绝处理", memberId, e);
            return error(ROLE_RESOLVE_FAILED);
        }

        try
        {
            return success(merchantInsightService.insight(scope.getOwnerMemberId()));
        }
        catch (AiServiceException e)
        {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "M3 俱乐部活动策划: 一句话→方案+商家+文案")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.MEMBER)
    @PostMapping("/club/design")
    public AjaxResult clubDesign(@RequestBody AiClubDesignReq req)
    {
        Long memberId = resolveMemberId();
        if (memberId == null) return error("请先登录");
        String gate = gateRole(memberId);
        if (gate != null) return error(gate);

        try
        {
            return success(aiCreatorService.clubDesign(req, memberId));
        }
        catch (AiServiceException e)
        {
            return error(e.getMessage());
        }
    }
}

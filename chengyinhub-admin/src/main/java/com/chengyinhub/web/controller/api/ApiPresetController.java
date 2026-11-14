package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsPresetRoute;
import com.chengyinhub.business.domain.dto.GameplayPresetDraftCommand;
import com.chengyinhub.business.domain.vo.PresetRouteGuestProjection;
import com.chengyinhub.business.service.IPresetService;
import com.chengyinhub.business.service.support.AuthorWriteReceiptService;
import com.chengyinhub.business.service.support.GameplayPresetCatalog;
import com.chengyinhub.business.service.support.GameplayPresetDraftService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布预设接口：既有「建议版风格/路线」只读预设 + 固定玩法结构预设（T01–T08）。
 *
 * @author chengyin
 */
@RestController
@Tag(name = "建议版预设")
@RequestMapping("/api/preset")
public class ApiPresetController extends BaseController {

    @Autowired
    private IPresetService presetService;

    @Autowired
    private GameplayPresetDraftService presetDraftService;

    @Autowired
    private AuthorWriteReceiptService authorWriteReceipts;

    @Operation(summary = "主题风格列表")
    @GetMapping("/style/list")
    public AjaxResult styleList() {
        return AjaxResult.success(presetService.listStyles());
    }

    @Operation(summary = "城市路线列表")
    @GetMapping("/route/list")
    public AjaxResult routeList(@RequestParam(required = false) String city) {
        List<CmsPresetRoute> routes = presetService.listRoutes(city);
        // 第九轮 m4:游客可读(SecurityConfig 精确放行),游客响应走公开字段投影。
        return AjaxResult.success(isGuest() ? PresetRouteGuestProjection.applyAll(routes) : routes);
    }

    @Operation(summary = "单条路线含站点")
    @GetMapping("/route/info")
    public AjaxResult routeInfo(@RequestParam Long id) {
        CmsPresetRoute route = presetService.getRouteWithNodes(id);
        if (route == null) {
            return AjaxResult.error("路线不存在");
        }
        return AjaxResult.success(isGuest() ? PresetRouteGuestProjection.apply(route) : route);
    }

    /** 未登录(或令牌无效)按游客处理;只决定响应投影,不放宽任何可见性。 */
    private boolean isGuest() {
        try {
            Long uid = getAppUserId();
            return uid == null || uid <= 0;
        } catch (Exception e) {
            return true;
        }
    }

    // ------------------------------------------------------------------ 固定玩法预设（T01–T08）

    /**
     * 作者可选的固定玩法结构。
     *
     * <p>下发的是**声明**：预设标识/版本、用途、用到哪些真实玩法能力、作者必须自己填什么、
     * 换城要重绑什么、还有哪些事得自己去做。结构模板（含答案）永不出现在这里。
     *
     * <p>为什么不是模板市场：这些是平台自带的结构，不是某位作者已审核上架的内容，
     * 所以不进货架、不占 {@code cms_template_library}、没有版权与已售授权语义。
     */
    @Operation(summary = "固定玩法预设列表（只有声明，不含答案）")
    @GetMapping("/gameplay/list")
    public AjaxResult gameplayList() {
        GameplayPresetCatalog catalog = GameplayPresetCatalog.shared();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GameplayPresetCatalog.Preset preset : catalog.presets()) rows.add(preset.publicView());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("catalogVersion", catalog.catalogVersion());
        data.put("presets", rows);
        return AjaxResult.success(data);
    }

    @Operation(summary = "单个固定玩法预设声明（不含答案）")
    @GetMapping("/gameplay/info")
    public AjaxResult gameplayInfo(@RequestParam String presetKey) {
        try {
            return AjaxResult.success(GameplayPresetCatalog.shared().require(presetKey).publicView());
        } catch (IllegalArgumentException unknown) {
            return AjaxResult.error(unknown.getMessage());
        }
    }

    /**
     * 用固定预设创建**自己的个人玩法草稿**。
     *
     * <p>写入走原有 {@code ITemplatePublishService.saveOrUpdateDraft}：同一套保存闸、同一套归属校验、
     * 同一套秘密字段处理。产出的是草稿（draft_status=0 / status=0 / publish_status=0），
     * 公开与可售仍归原来的审核 → 广场提交 → 上架流程，这里一步都不跳。
     *
     * <p>作者没填全就**不建**：返回一次列全的缺失清单，而不是建一份半成品让人以为能用。
     */
    @Operation(summary = "用固定预设创建个人玩法草稿")
    @Log(title = "固定预设创建玩法草稿", businessType = BusinessType.INSERT)
    @RateLimiter(time = 60, count = 20, limitType = LimitType.MEMBER)
    @PostMapping("/gameplay/draft")
    public AjaxResult createGameplayDraft(@RequestBody GameplayPresetDraftCommand command) {
        try {
            Long actor = getAppUserId();
            if (actor == null || actor <= 0) return error("请先登录");
            if (command == null || StringUtils.isBlank(command.getPresetKey())) return error("请选择要用的预设");
            GameplayPresetDraftCommand body = command;
            if (StringUtils.isBlank(command.getRequestId())) {
                return createGameplayDraftOnce(body, actor);
            }
            return authorWriteReceipts.execute(actor, "TEMPLATE_PRESET_DRAFT", command.getRequestId(),
                    body, () -> createGameplayDraftOnce(body, actor));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        } catch (GameplayPresetDraftService.MissingFieldsException missing) {
            return missingFields(missing);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    /** 与发布问题同形：短消息 + data.code + data.requiredFields，客户端逐项标红，不再依赖长消息。 */
    static AjaxResult missingFields(GameplayPresetDraftService.MissingFieldsException missing) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", GameplayPresetDraftService.MissingFieldsException.CODE);
        data.put("requiredFields", missing.getRequiredFields());
        return AjaxResult.error(missing.getMessage(), data);
    }

    private AjaxResult createGameplayDraftOnce(GameplayPresetDraftCommand command, Long actor) {
        try {
            return success(presetDraftService.create(actor, getAppUserName(),
                    command.getPresetKey(), command.getFields()));
        } catch (GameplayPresetDraftService.MissingFieldsException missing) {
            return missingFields(missing);
        } catch (IllegalArgumentException e) {
            // 预设不存在、字段没填全、字段值越界都在这里：原样把作者要补的东西说清楚。
            return error(e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }
}

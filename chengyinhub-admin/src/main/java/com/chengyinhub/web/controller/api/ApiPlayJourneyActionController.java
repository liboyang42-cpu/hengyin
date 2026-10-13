package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.IWeChatMiniSessionService;
import com.chengyinhub.business.service.support.WeRunDecryptor;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 旅程运行期动作:问问题 / 回应开场通话(R9-ACTIONS)。
 *
 * <p>入参只有 topicId、nodeId、questionId / optionId;一个会员一个主题只有一局,不需要 activityId。
 * 动作 id 由服务端生成:ask:{nodeId}:{questionId} 与 call,重放返回原回执,不二次写状态。
 */
@RestController
@Tag(name = "旅程运行期动作")
@RequestMapping("/api/play/journey")
public class ApiPlayJourneyActionController extends BaseController {

    @Autowired(required = false)
    private ITopicRouteRuntimeService topicRouteRuntimeService;
    @Autowired(required = false)
    private IWeChatMiniSessionService weChatMiniSessionService;

    @Operation(summary = "问问题")
    @PostMapping("/ask")
    public AjaxResult ask(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        if (topicRouteRuntimeService == null) return error("参数有误");
        Map<String, Object> input = body == null ? Collections.<String, Object>emptyMap() : body;
        try {
            return success(topicRouteRuntimeService.ask(memberId, longOrZero(input.get("topicId")), optionalActivityId(input),
                    longOrZero(input.get("nodeId")), Convert.toStr(input.get("questionId"), "")));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "回应开场通话")
    @PostMapping("/call")
    public AjaxResult call(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        if (topicRouteRuntimeService == null) return error("参数有误");
        Map<String, Object> input = body == null ? Collections.<String, Object>emptyMap() : body;
        try {
            return success(topicRouteRuntimeService.call(memberId, longOrZero(input.get("topicId")), optionalActivityId(input),
                    Convert.toStr(input.get("optionId"), "")));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    private Long optionalActivityId(Map<String, Object> input) {
        Object value = input.get("activityId");
        if (value == null) return null;
        try {
            long id = Long.parseLong(value.toString());
            if (id < 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("场次参数有误");
        }
    }

    private Long longOrZero(Object value) { return Convert.toLong(value, 0L); }

    /**
     * 思维阁:到站同步一次步数。
     *
     * ⚠️ 不开 @Transactional —— syncThoughts 自带事务,controller 再包一层的话,捕获它抛出的
     * 业务异常会在外层提交期变成 UnexpectedRollbackException(开局端点踩过,
     * 负控见 ApiPlayOpeningTransactionTest)。
     *
     * 入参里**没有步数**:客户端报的步数一概不采信,只收 {code, encryptedData, iv},
     * 当场换 session_key 当场解密(与 playkit-walk「步数只能同步不能填」同一条口径)。
     * 没带密文(玩家没授权微信运动)也照常认领念头,只是这次不算步数。
     */
    @Operation(summary = "同步步数并推进念头")
    @PostMapping("/thought/sync")
    public AjaxResult syncThoughts(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        if (topicRouteRuntimeService == null || body == null) return error("参数有误");
        try {
            WeRunDecryptor.WeRunResult reading = hasWeRunCipher(body) ? decryptTodaySteps(body) : null;
            long day = reading == null ? 0 : java.time.Instant.ofEpochSecond(reading.getTimestamp())
                    .atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toEpochDay();
            return success(topicRouteRuntimeService.syncThoughts(memberId, longOrZero(body.get("topicId")),
                    longOrZero(body.get("activityId")), claimOf(body), reading == null ? -1 : reading.getTodaySteps(), day));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    /** 玩家刚在故事里读到的念头 key(thought 块);非字符串项直接丢掉。 */
    private List<String> claimOf(Map<String, Object> body) {
        Object raw = body.get("claim");
        List<String> claim = new ArrayList<String>();
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                String key = Convert.toStr(item, "").trim();
                if (!key.isEmpty()) claim.add(key);
            }
        }
        return claim;
    }

    /**
     * 有没有带微信运动密文。2026-09-22 用户裁决:伏笔不能只靠步数 —— 没授权微信运动也要能认领念头,
     * 此时交 -1(没有读数)给 service:只处理认领与条件,不碰步数基线。步数仍只认服务端解密。
     */
    private boolean hasWeRunCipher(Map<String, Object> body) {
        return !Convert.toStr(body.get("encryptedData"), "").trim().isEmpty();
    }

    /** 解密微信运动的今日步数。单独抽出来是为了让「步数来自服务端」这件事可被测试钉住。 */
    protected WeRunDecryptor.WeRunResult decryptTodaySteps(Map<String, Object> body) {
        IWeChatMiniSessionService.WeChatMiniSession wx =
                weChatMiniSessionService.exchange(Convert.toStr(body.get("code"), ""));
        WeRunDecryptor.WeRunResult reading = WeRunDecryptor.decrypt(wx.getSessionKey(),
                Convert.toStr(body.get("encryptedData"), ""), Convert.toStr(body.get("iv"), ""),
                weChatMiniSessionService.miniAppId());
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate day = java.time.Instant.ofEpochSecond(reading.getTimestamp()).atZone(zone).toLocalDate();
        if (!java.time.LocalDate.now(zone).equals(day)) throw new IllegalArgumentException("微信运动读数已过期，请重新同步");
        return reading;
    }

    // 这里不开事务:completeOpening 自带事务,controller 再包一层的话,捕获它抛出的业务异常
    // 会在外层提交期变成 UnexpectedRollbackException(客户端看到裸 500)。通关发奖同在 service 内,
    // 与开局状态共用一个事务。
    @Operation(summary = "完成开局流程")
    @PostMapping("/opening/complete")
    public AjaxResult completeOpening(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        if (topicRouteRuntimeService == null || body == null) return error("参数有误");
        try {
            return success(topicRouteRuntimeService.completeOpening(memberId, longOrZero(body.get("topicId")),
                    longOrZero(body.get("activityId")), longOrZero(body.get("chapterId")),
                    Convert.toInt(body.get("expectedVersion"))));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    private Long currentMemberId() {
        try {
            Long id = getAppUserId();
            return id == null || id <= 0L ? null : id;
        } catch (Exception e) {
            return null;
        }
    }
}

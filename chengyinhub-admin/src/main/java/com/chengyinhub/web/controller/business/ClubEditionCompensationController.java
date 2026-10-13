package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.service.IClubEditionCompensationService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.core.text.Convert;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

/**
 * A1/A2 管理端：计划工时、确认实际工时、EXTRA 预审批/更正、Q 一次确认。
 *
 * <p>四个动作四种权限，互不代替：宽泛的 business:topic:edit 只够登记开售前的计划工时，
 * 确认实际工时 / 批额外工时 / 定质量奖励都要各自的专用权限。
 */
@RestController
@RequestMapping("/business/club-compensation")
public class ClubEditionCompensationController extends BaseController {

    private final IClubEditionCompensationService compensationService;
    private final ITopicOperationsScopeAuthorizer scopeAuthorizer;

    public ClubEditionCompensationController(IClubEditionCompensationService compensationService,
                                             ITopicOperationsScopeAuthorizer scopeAuthorizer) {
        this.compensationService = compensationService;
        this.scopeAuthorizer = scopeAuthorizer;
    }

    @PreAuthorize("@ss.hasPermi('business:topic:edit')")
    @Log(title = "探店日俱乐部计划工时", businessType = BusinessType.UPDATE)
    @PutMapping("/{topicId}/hours/plan")
    public AjaxResult planHours(@PathVariable Long topicId, @RequestBody Map<String, Object> body) {
        if (!scopeAuthorizer.canEditTopic(topicId)) return error("主题不存在或当前账号无修改范围");
        compensationService.planHours(topicId, longOf(body, "clubId"), text(body, "hourKind"),
                decimal(body, "plannedHours"), getUserId());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('business:clubhours:confirm')")
    @Log(title = "探店日俱乐部实际工时确认", businessType = BusinessType.UPDATE)
    @PutMapping("/{topicId}/hours/confirm")
    public AjaxResult confirmHours(@PathVariable Long topicId, @RequestBody Map<String, Object> body) {
        if (!scopeAuthorizer.canEditTopic(topicId)) return error("主题不存在或当前账号无修改范围");
        compensationService.confirmActualHours(topicId, longOf(body, "clubId"), text(body, "hourKind"),
                decimal(body, "confirmedActualHours"), getUserId());
        return success();
    }

    /**
     * 已确认工时的更正入口：把旧事实留痕并退回 REPORTED，重新确认仍走上面那条唯一 CAS。
     *
     * <p>为什么要单独一个权限位（{@code business:clubhours:correct}）而不是复用
     * {@code business:clubhours:confirm}：更正推翻的是<b>确认人自己定下的事实</b>，
     * 复用同一条权限等于「谁确认谁就能悄悄改」，那正是这条链要避免的形状。
     * ⚠️ 它<b>不是</b>第二条写已确认工时的路 —— 它只清空并退回状态，金额永远由 confirm 那条 CAS 写。
     */
    @PreAuthorize("@ss.hasPermi('business:clubhours:correct')")
    @Log(title = "探店日俱乐部已确认工时更正", businessType = BusinessType.UPDATE)
    @PostMapping("/{topicId}/hours/correct")
    public AjaxResult correctConfirmedHours(@PathVariable Long topicId, @RequestBody Map<String, Object> body) {
        if (!scopeAuthorizer.canEditTopic(topicId)) return error("主题不存在或当前账号无修改范围");
        compensationService.correctConfirmedHours(topicId, longOf(body, "clubId"), text(body, "hourKind"),
                text(body, "reason"), getUserId());
        return success();
    }

    /**
     * ★这两个 EXTRA 端点<b>不能</b>再过 {@code canEditTopic}：它恒等于
     * {@code SecurityUtils.isAdmin}（userId == 1），而本期的审批人是开售时冻进
     * {@code club_edition_compensation_terms.extra_approver_user_id} 的<b>项目负责人</b>，
     * 那是一个普通 sys_user，userId 必然 ≠ 1。挂着这道闸的后果是双向死锁：
     * 负责人自己来点被 scope 拒、超管来点被 {@code EDITION_EXTRA_APPROVER_NOT_FROZEN} 拒，
     * 而 {@code freezeTermsForSale} 幂等读回原件、全仓没有 terms 更正方法 ⇒ 只能改库。
     *
     * <p>归属改由 service 侧承重，且比 scope 更严：{@code requireExecutingClub}（clubId 必须等于
     * 冻结条款里的执行俱乐部）+ {@code requireFrozenApprover}（动手人必须<b>就是</b>开售冻的那一个）。
     * ⚠️ 这不放宽 {@code TopicOperationsScopeAuthorizer} —— 那个类一行未动，同控制器另外四个端点
     * （计划工时 / 确认实际工时 / 质量一次确认 / 过错排除）的 scope 闸原样保留：
     * 它们的 service 侧没有把操作人钉到某个具体身份上。
     */
    @PreAuthorize("@ss.hasPermi('business:clubextra:approve')")
    @Log(title = "探店日额外工时事前预审批", businessType = BusinessType.INSERT)
    @PostMapping("/{topicId}/extra/preapprove")
    public AjaxResult preapproveExtra(@PathVariable Long topicId, @RequestBody Map<String, Object> body) {
        compensationService.preapproveExtraHours(topicId, longOf(body, "clubId"), text(body, "category"),
                decimal(body, "approvedHours"), dateOf(body, "workStartedAt"),
                text(body, "idempotencyKey"), getUserId());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('business:clubextra:approve')")
    @Log(title = "探店日额外工时审批更正", businessType = BusinessType.INSERT)
    @PostMapping("/{topicId}/extra/correct")
    public AjaxResult correctExtra(@PathVariable Long topicId, @RequestBody Map<String, Object> body) {
        compensationService.correctExtraHours(topicId, longOf(body, "clubId"), longOf(body, "correctionOf"),
                decimal(body, "approvedHours"), text(body, "idempotencyKey"), getUserId());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('business:clubquality:confirm')")
    @Log(title = "探店日质量奖励一次确认", businessType = BusinessType.UPDATE)
    @PostMapping("/{topicId}/quality/confirm")
    public AjaxResult confirmQuality(@PathVariable Long topicId, @RequestBody Map<String, Object> body) {
        if (!scopeAuthorizer.canEditTopic(topicId)) return error("主题不存在或当前账号无修改范围");
        compensationService.confirmQualityOnce(topicId, longOf(body, "clubId"), text(body, "dimension"),
                requireBoolean(body, "pass"), text(body, "reason"), getUserId());
        return success();
    }

    /**
     * 已证明由商家或平台造成的过错 ⇒ 该维排除计酬。
     *
     * <p>独立于「一次确认」的<b>专用权限</b>：它能推翻一个已确认的 200，比确认本身更重。
     * 写的是新版本（correction_of 指回被推翻的那一版），历史判定不被改写。
     *
     * <p>★faultParty 与 <b>reason</b> 都<b>落进 club_quality_decision 的列</b>，不指望 {@code @Log}：
     * 本仓 {@code LogAspect} 已被加固到不写请求体（全文 grep {@code setOperParam}/
     * {@code setJsonResult} 为 0），{@code sys_oper_log} 只记 title/method/url/操作人/耗时。
     *
     * <p>★方向按总纲 §2.1.2 字面：商家/平台故障「从俱乐部绩效责任中<b>剔除</b>」——
     * 剔除的是失分事件不是报酬，所以该维不扣分、照贡献满额。
     */
    @PreAuthorize("@ss.hasPermi('business:clubquality:exclude')")
    @Log(title = "探店日质量奖励过错排除", businessType = BusinessType.UPDATE)
    @PostMapping("/{topicId}/quality/exclude")
    public AjaxResult excludeQuality(@PathVariable Long topicId, @RequestBody Map<String, Object> body) {
        if (!scopeAuthorizer.canEditTopic(topicId)) return error("主题不存在或当前账号无修改范围");
        compensationService.excludeQualityByFault(topicId, longOf(body, "clubId"), text(body, "dimension"),
                text(body, "faultParty"), text(body, "reason"), getUserId());
        return success();
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long longOf(Map<String, Object> body, String key) {
        return body == null ? null : Convert.toLong(body.get(key));
    }

    private static BigDecimal decimal(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    private static Date dateOf(Map<String, Object> body, String key) {
        Long millis = longOf(body, key);
        return millis == null ? null : new Date(millis);
    }

    /**
     * 显式解析布尔，非布尔一律报错。
     *
     * <p>{@code Boolean.TRUE.equals(map.get(k))} 对 JSON 里的字符串 "true" 会静默变 false ——
     * 而「质量奖励一次确认」是不可逆动作，静默判不过 = 该维永久锁死 0 元。
     */
    private static boolean requireBoolean(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        throw new IllegalArgumentException("EDITION_QUALITY_PASS_MUST_BE_BOOLEAN:" + value);
    }
}

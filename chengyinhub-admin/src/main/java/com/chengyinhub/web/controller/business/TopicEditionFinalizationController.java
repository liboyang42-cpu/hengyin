package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.service.IClubEditionCompensationService;
import com.chengyinhub.business.service.ITopicEditionFinalizationService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * F3 管理端：FINAL 结算、应付撤销、批次确认、付款登记。
 *
 * <p>批次确认与付款登记是两种权限，且 service 侧还会拒绝「同一个人两头都签」——
 * 权限分派只是第一道，人员分离由数据事实兜底。这两种权限已在
 * {@code migration_20260816_tandianri_batch3_funds_club_compensation.sql} 里分派给
 * <b>不同的</b>仓库权威角色（确认 → cy_finance，付款登记 → cy_operator），并在同一份迁移里
 * 用回读断言钉住互斥；否则「两头不同人」在单超管环境下恒不可满足。
 *
 * <p>★所有端点路径都带 {@code topicId}，但<b>两种承重方式</b>，别混：
 * <ul>
 *   <li>FINAL / 红冲 / 重计提：路径 topicId 就是写入对象本身，且授权用的是
 *       {@code business:editionfinal:execute}（今天没授给任何非超管角色），过
 *       {@code canEditTopic} 的对象级 scope。</li>
 *   <li><b>建批次</b> / 批次<b>确认 / 付款登记 / 开票 / 红字发票</b>：授权由各自的专用权限承担，
 *       归属由 service 侧的回读承担 —— 建批次是 {@code selectUnbatchedPayables} 的
 *       {@code topic_id/club_id} 谓词 + 逐行 {@code CLUB_PAYABLE_EDITION_LEAK}；另外四个是
 *       「批次自报的 topic_id == 路径 topicId」（{@code CLUB_PAYABLE_BATCH_TOPIC_MISMATCH}）。
 *       ⚠️ 这五个<b>不能</b>再过 {@code canEditTopic}：它恒等于 {@code SecurityUtils.isAdmin}
 *       （userId == 1），而 cy_finance / cy_operator 的 userId 必然 ≠ 1 ⇒ 两人都进不来 ⇒
 *       confirmed_by 与 paid_by 恒为 1 ⇒ 三层分离闸同时恒不可满足 ⇒ 没有任何一行能进 PAID，
 *       而「建付款批次(本期)」按钮对唯一持有该权限的 cy_finance 也恒是死的。
 *       修的是可达性，<b>不是</b>放宽 {@code TopicOperationsScopeAuthorizer} —— 那个类一行没动。</li>
 * </ul>
 */
@RestController
@RequestMapping("/business/edition-final")
public class TopicEditionFinalizationController extends BaseController {

    private final ITopicEditionFinalizationService finalizationService;
    private final IClubEditionCompensationService compensationService;
    private final ITopicOperationsScopeAuthorizer scopeAuthorizer;

    public TopicEditionFinalizationController(ITopicEditionFinalizationService finalizationService,
                                              IClubEditionCompensationService compensationService,
                                              ITopicOperationsScopeAuthorizer scopeAuthorizer) {
        this.finalizationService = finalizationService;
        this.compensationService = compensationService;
        this.scopeAuthorizer = scopeAuthorizer;
    }

    /**
     * ★只读操作面：本期 FINAL 头 + 应付原始行 + 批次（付款轴/发票轴）+ 三维质量决定 + EXTRA 审批链 + 四类工时。
     *
     * <p>没有它，红冲 / 更正 / 批次四个动作都要求操作者<b>先连生产库</b>把 id 捞出来，
     * 与「后台是这套动作的唯一操作面」直接冲突；叠加「输错一个 id」就是把钱记到别的期。
     *
     * <p>权限：任一资金域动作权限即可读（读的正是他要动的那批钱）。
     * 对象 scope：所有查询按路径 {@code topicId} 收窄，service 侧再逐行回读核对
     * （{@code CLUB_PAYABLE_EDITION_LEAK}），响应里不可能出现别的期。
     * ⚠️ 不挂 {@code canEditTopic} —— 它恒等于「是不是超管」，挂上就等于让真正要操作的
     * cy_finance / cy_operator 依旧只能去连库猜 id，本条修复也就白做了。
     */
    @PreAuthorize("@ss.hasPermi('business:editionfinal:execute') "
            + "or @ss.hasPermi('business:clubpayable:confirm') or @ss.hasPermi('business:clubpayable:paid')")
    @GetMapping("/{topicId}")
    public AjaxResult editionLedger(@PathVariable Long topicId) {
        Map<String, Object> view = new LinkedHashMap<String, Object>(
                finalizationService.editionLedgerView(topicId));
        view.putAll(compensationService.compensationView(topicId));
        return success(view);
    }

    @PreAuthorize("@ss.hasPermi('business:editionfinal:execute')")
    @Log(title = "探店日期级FINAL结算", businessType = BusinessType.INSERT)
    @PostMapping("/{topicId}")
    public AjaxResult finalizeEdition(@PathVariable Long topicId) {
        if (!scopeAuthorizer.canEditTopic(topicId)) return error("主题不存在或当前账号无修改范围");
        return success(finalizationService.finalizeEdition(topicId, new Date(), getUserId()));
    }

    /**
     * 应付撤销。路径带 topicId 是有意的：撤销写的是一条负向资金行，
     * 必须和 FINAL 一样过对象级 scope，不能拿一个裸 payableId 就动钱。
     * service 侧还会把 topicId 和台账行自报的 topic_id 对一遍。
     */
    @PreAuthorize("@ss.hasPermi('business:editionfinal:execute')")
    @Log(title = "俱乐部应付撤销", businessType = BusinessType.INSERT)
    @PostMapping("/{topicId}/payable/{payableId}/reverse")
    public AjaxResult reversePayable(@PathVariable Long topicId, @PathVariable Long payableId,
                                     @RequestBody Map<String, Object> body) {
        if (!scopeAuthorizer.canEditTopic(topicId)) return error("主题不存在或当前账号无修改范围");
        return success(finalizationService.reversePayable(topicId, payableId, getUserId(),
                text(body, "reason")));
    }

    /**
     * 红冲之后按 append-only 重新计提。
     *
     * <p>没有这条端点，红冲就是死胡同：FINAL 头一期一行、幂等键恒 {@code FINAL:topicId}，
     * 重跑 finalizeEdition 只会读回旧头，正确金额永远进不了台账。理由必填并<b>落库</b>。
     */
    @PreAuthorize("@ss.hasPermi('business:editionfinal:execute')")
    @Log(title = "俱乐部应付重计提", businessType = BusinessType.INSERT)
    @PostMapping("/{topicId}/reaccrue")
    public AjaxResult reaccrue(@PathVariable Long topicId, @RequestBody Map<String, Object> body) {
        if (!scopeAuthorizer.canEditTopic(topicId)) return error("主题不存在或当前账号无修改范围");
        return success(finalizationService.reaccrueAfterReversal(topicId, new Date(), getUserId(),
                text(body, "reason")));
    }

    /**
     * 建批次。★路径带 topicId：一期一批次（E4）。
     *
     * <p>与下面四个批次动作同一口径，<b>不</b>挂 {@code canEditTopic}：这个按钮挂的是
     * {@code business:clubpayable:confirm}，而那条权限只授给 cy_finance（同一份迁移还把它从
     * cy_operator 上窄范围删掉）。scope 闸恒等于「是不是超管」⇒ 唯一持有它的角色恒得
     * 「无修改范围」—— 一颗看得见点不动的死按钮。
     *
     * <p>归属由 service 侧承重且比 scope 更细：{@code selectUnbatchedPayables} 的
     * {@code where topic_id / club_id} 谓词 + 对每一行的 {@code CLUB_PAYABLE_EDITION_LEAK} /
     * {@code CLUB_PAYABLE_SCOPE_LEAK} 逐行回读 + {@code CLUB_PAYABLE_CLUB_UNKNOWN}。
     */
    @PreAuthorize("@ss.hasPermi('business:clubpayable:confirm')")
    @Log(title = "俱乐部应付批次建立", businessType = BusinessType.INSERT)
    @PostMapping("/{topicId}/batch")
    public AjaxResult createBatch(@PathVariable Long topicId, @RequestBody Map<String, Object> body) {
        return success(finalizationService.createBatch(topicId, longOf(body, "clubId"), getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('business:clubpayable:confirm')")
    @Log(title = "俱乐部应付批次确认", businessType = BusinessType.UPDATE)
    @PostMapping("/{topicId}/batch/{batchId}/confirm")
    public AjaxResult confirmBatch(@PathVariable Long topicId, @PathVariable Long batchId) {
        return success(finalizationService.confirmBatch(topicId, batchId, getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('business:clubpayable:paid')")
    @Log(title = "俱乐部应付付款登记", businessType = BusinessType.UPDATE)
    @PostMapping("/{topicId}/batch/{batchId}/paid")
    public AjaxResult markBatchPaid(@PathVariable Long topicId, @PathVariable Long batchId,
                                    @RequestBody Map<String, Object> body) {
        return success(finalizationService.markBatchPaid(topicId, batchId, getUserId(),
                text(body, "paymentReference")));
    }

    /** 发票轴：开票。与付款状态无关，「已打款未开票」是合法状态。 */
    @PreAuthorize("@ss.hasPermi('business:clubpayable:confirm')")
    @Log(title = "俱乐部应付批次开票", businessType = BusinessType.UPDATE)
    @PostMapping("/{topicId}/batch/{batchId}/invoice")
    public AjaxResult markInvoiceIssued(@PathVariable Long topicId, @PathVariable Long batchId,
                                        @RequestBody Map<String, Object> body) {
        return success(finalizationService.markBatchInvoiceIssued(topicId, batchId,
                text(body, "invoiceNo"), getUserId()));
    }

    /** 发票轴：红冲。必须先 ISSUED 才能 RED。 */
    @PreAuthorize("@ss.hasPermi('business:clubpayable:confirm')")
    @Log(title = "俱乐部应付批次红字发票", businessType = BusinessType.UPDATE)
    @PostMapping("/{topicId}/batch/{batchId}/invoice/red")
    public AjaxResult markInvoiceRed(@PathVariable Long topicId, @PathVariable Long batchId,
                                     @RequestBody Map<String, Object> body) {
        return success(finalizationService.markBatchInvoiceRed(topicId, batchId,
                text(body, "redInvoiceNo"), getUserId()));
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long longOf(Map<String, Object> body, String key) {
        return body == null ? null : Convert.toLong(body.get(key));
    }
}

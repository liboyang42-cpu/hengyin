package com.chengyinhub.web.controller.business;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.VideoBountyAccrual;
import com.chengyinhub.business.domain.VideoBountySubmission;
import com.chengyinhub.business.domain.dto.VideoBountyEvidenceRequest;
import com.chengyinhub.business.domain.dto.VideoBountyReviewRequest;
import com.chengyinhub.business.domain.dto.VideoCampaignRequest;
import com.chengyinhub.business.exception.BountyTipException;
import com.chengyinhub.business.service.IVideoBountyService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 视频内容悬赏(运营侧):活动配置、审核、证据录入、应计查看。
 *
 * <p><b>权限</b>:沿用既有 RBAC 权限串机制({@code @ss.hasPermi}),<b>不新增角色、不 seed 菜单</b>。
 * 现有超管的 {@code *:*:*} 直接满足这些权限串 ⇒ 超管可立即操作;
 * <b>未单独授权的普通运营账号会被拒(403)</b>,这是刻意的 default-closed。
 * 菜单行与普通角色授权由 root 决定,只进唯一 UI 文档;代码里不给任何账号开后门。
 *
 * <p><b>钱边界</b>:没有"打款/兑付"接口。应计只能看,不能在此变成支付。
 *
 * @author chengyinhub
 */
@Tag(name = "视频内容悬赏(运营)")
@RestController
@RequestMapping("/business/videobounty")
public class VideoBountyAdminController extends BaseController
{
    /** 权限串:活动配置与读取。菜单/角色未配置时默认拒绝(不自动授权)。 */
    private static final String PERM_CONFIG = "@ss.hasPermi('business:videobounty:config')";
    /** 权限串:审核 + 证据录入 + 应计触发。 */
    private static final String PERM_REVIEW = "@ss.hasPermi('business:videobounty:review')";

    private final IVideoBountyService bountyService;

    public VideoBountyAdminController(IVideoBountyService bountyService) {
        this.bountyService = bountyService;
    }

    // ------------------------------------------------------------------ 活动配置

    @Operation(summary = "活动列表")
    @PreAuthorize(PERM_CONFIG)
    @GetMapping("/campaign/list")
    public AjaxResult listCampaigns(@RequestParam(value = "status", required = false) Integer status) {
        return success(bountyService.listCampaigns(status));
    }

    @Operation(summary = "新建活动(默认草稿 + 现金兑付默认关)")
    @PreAuthorize(PERM_CONFIG)
    @PostMapping("/campaign")
    public AjaxResult createCampaign(@RequestBody(required = false) VideoCampaignRequest request) {
        return success(bountyService.createCampaign(operatorRef(), request));
    }

    /**
     * 更新活动。已有应计时费率/封顶/窗口被锁死(见服务层),不会追溯改写历史口径。
     * 现金兑付开关任何非 0 值都会被拒绝 —— 本域没有兑付实现。
     */
    @Operation(summary = "更新活动配置(已有应计时规则锁定)")
    @PreAuthorize(PERM_CONFIG)
    @PutMapping("/campaign/{id}")
    public AjaxResult updateCampaign(@PathVariable("id") Long id,
                                     @RequestBody(required = false) VideoCampaignRequest request) {
        return success(bountyService.updateCampaign(operatorRef(), id, request));
    }

    @Operation(summary = "活动状态流转(草稿/进行中/暂停/关闭)")
    @PreAuthorize(PERM_CONFIG)
    @PostMapping("/campaign/{id}/status")
    public AjaxResult changeStatus(@PathVariable("id") Long id, @RequestParam("status") int status) {
        return success(bountyService.changeCampaignStatus(operatorRef(), id, status));
    }

    // ------------------------------------------------------------------ 审核

    @Operation(summary = "待审投稿列表")
    @PreAuthorize(PERM_REVIEW)
    @GetMapping("/submission/list")
    public AjaxResult listSubmissions(@RequestParam(value = "campaignId", required = false) Long campaignId,
                                      @RequestParam(value = "status", required = false) Integer status,
                                      @RequestParam(value = "offset", defaultValue = "0") int offset,
                                      @RequestParam(value = "limit", defaultValue = "20") int limit) {
        List<VideoBountySubmission> rows = bountyService.adminListSubmissions(campaignId, status, offset, limit);
        return success(rows);
    }

    @Operation(summary = "审核投稿(APPROVE 需发布时间;REJECT 需理由)")
    @PreAuthorize(PERM_REVIEW)
    @PostMapping("/submission/{id}/review")
    public AjaxResult review(@PathVariable("id") Long id,
                             @RequestBody(required = false) VideoBountyReviewRequest request) {
        return success(bountyService.review(reviewerRef(), id, request));
    }

    @Operation(summary = "撤销已通过投稿(应计置为已撤销,不删除历史)")
    @PreAuthorize(PERM_REVIEW)
    @PostMapping("/submission/{id}/revoke")
    public AjaxResult revoke(@PathVariable("id") Long id,
                             @RequestBody(required = false) Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        return success(bountyService.revoke(reviewerRef(), id, note));
    }

    /**
     * 核验原视频组：封顶与应计只认这个结论，作者自报组键只作建议。
     * 必须给证据引用与理由；已有应计后组键锁定，不可改组绕上限。
     */
    @Operation(summary = "核验原视频组(封顶/应计只认核验结论;已有应计时锁定)")
    @PreAuthorize(PERM_REVIEW)
    @PostMapping("/submission/{id}/verify-group")
    public AjaxResult verifyOriginalGroup(@PathVariable("id") Long id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> safe = body == null ? Map.of() : body;
        String groupKey = safe.get("verifiedGroupKey") == null
                ? null : String.valueOf(safe.get("verifiedGroupKey"));
        String sourceRef = safe.get("sourceRef") == null
                ? null : String.valueOf(safe.get("sourceRef"));
        String reason = safe.get("reason") == null
                ? null : String.valueOf(safe.get("reason"));
        Integer expectedVersion = safe.get("expectedVersion") == null
                ? null : Integer.valueOf(String.valueOf(safe.get("expectedVersion")));
        return success(bountyService.verifyOriginalGroup(
                reviewerRef(), id, groupKey, sourceRef, expectedVersion, reason));
    }

    // ------------------------------------------------------------------ 证据与应计

    /**
     * 录入外部平台播放证据。{@code sourceRef} 必填 —— 我方设备/账号数据不能自证播放。
     */
    @Operation(summary = "录入外部平台播放证据(必须有可追溯来源)")
    @PreAuthorize(PERM_REVIEW)
    @PostMapping("/submission/{id}/evidence")
    public AjaxResult recordEvidence(@PathVariable("id") Long id,
                                     @RequestBody(required = false) VideoBountyEvidenceRequest request) {
        return success(bountyService.recordEvidence(getUsername(), id, request));
    }

    /**
     * 按现有证据重算应计。<b>只记录,不支付</b>。
     * 同一证据水位与规则版本只会产生一行;旧行标记为已被替代。
     */
    @Operation(summary = "重算应计(只记录,不支付)")
    @PreAuthorize(PERM_REVIEW)
    @PostMapping("/submission/{id}/accrual/recompute")
    public AjaxResult recompute(@PathVariable("id") Long id) {
        return success(bountyService.recomputeAccrual(id));
    }

    @Operation(summary = "应计记录列表(只读)")
    @PreAuthorize(PERM_CONFIG)
    @GetMapping("/accrual/list")
    public AjaxResult listAccruals(@RequestParam(value = "campaignId", required = false) Long campaignId,
                                   @RequestParam(value = "offset", defaultValue = "0") int offset,
                                   @RequestParam(value = "limit", defaultValue = "20") int limit) {
        List<VideoBountyAccrual> rows = bountyService.adminListAccruals(campaignId, offset, limit);
        return success(rows);
    }

    /**
     * 查看某条投稿带来的成交归因(只回计数与合计,不返回任何买家 id/订单 id)。
     *
     * <p>聚合主体由服务端从投稿行读取;<b>不接受</b>客户端传 sharer/owner 参数,
     * 因此无法用同名字符串把别人的单算进来。未绑定时返回 {@code status=UNBOUND} 且数字为 null。
     */
    @Operation(summary = "投稿成交归因(只读;未绑定显示 UNBOUND 而非 0 成交)")
    @PreAuthorize(PERM_REVIEW)
    @GetMapping("/submission/{id}/attribution")
    public AjaxResult submissionAttribution(@PathVariable("id") Long id) {
        return success(bountyService.reviewedAttribution(reviewerRef(), id));
    }

    /** 理由码随响应返回,前端按码分支。 */
    @ExceptionHandler(BountyTipException.class)
    public AjaxResult handleRejection(BountyTipException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        return result;
    }

    /**
     * 审核人标识:{@code sys:<sys_user_id>}。
     *
     * <p>带 realm 前缀是刻意的 —— 会员 id 与后台用户 id 是两套空间,
     * 只存数字会让"1 号审核人"同时指两个人。
     */
    private String reviewerRef() {
        return "sys:" + getUserId();
    }

    /**
     * 运营人标识:{@code sys:<sys_user_id>}。
     *
     * <p>与 {@link #reviewerRef()} 同源但语义不同:这个用于配置类操作留痕,
     * 那个用于审核决定留痕。两者都<b>不会</b>被当作成交归因的聚合主体 ——
     * 归因主体永远由服务端从投稿行/campaign owner 读取。
     */
    private String operatorRef() {
        return "sys:" + getUserId();
    }
}

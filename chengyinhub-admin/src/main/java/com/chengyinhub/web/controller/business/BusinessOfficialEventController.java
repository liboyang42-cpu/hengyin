package com.chengyinhub.web.controller.business;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.OfficialBroadcast;
import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.domain.dto.OfficialEventV2DraftRequest;
import com.chengyinhub.business.domain.dto.OfficialEventV2OperatorAuthorizationRequest;
import com.chengyinhub.business.service.IOfficialBroadcastService;
import com.chengyinhub.business.service.IOfficialEventService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.BusinessType;

/**
 * 官方活动 后台管理 Controller(Web 后台)。复用小程序端同一套 service。
 *
 * @author cy
 * @date 2026-07-08
 */
@RestController
@RequestMapping("/business/officialevent")
public class BusinessOfficialEventController extends BaseController {

    private static final String V2_OPERATOR_UNAUTHORIZED =
            "当前后台账号未绑定有效官方会员授权；请由具备主办授权权限的管理员建立可审计映射";

    @Autowired
    private IOfficialEventService officialEventService;
    @Autowired
    private IOfficialEventV2Service officialEventV2Service;
    @Autowired
    private IOfficialBroadcastService officialBroadcastService;

    @PreAuthorize("@ss.hasPermi('business:officialevent:list')")
    @GetMapping("/list")
    public TableDataInfo list(OfficialEvent criteria) {
        startPage();
        List<OfficialEvent> list = officialEventService.adminList(criteria);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:officialevent:query')")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(officialEventService.adminGet(id));
    }

    /** 数据/复盘:参与人数/完成人数/集体进度。 */
    @PreAuthorize("@ss.hasPermi('business:officialevent:list')")
    @GetMapping("/{id}/stats")
    public AjaxResult stats(@PathVariable("id") Long id) {
        return success(officialEventService.progress(id));
    }

    @PreAuthorize("@ss.hasPermi('business:officialevent:add')")
    @Log(title = "官方活动", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody OfficialEvent event) {
        OfficialEvent saved = officialEventService.adminSave(event, null);
        return AjaxResult.success("新增成功", saved.getId());
    }

    @PreAuthorize("@ss.hasPermi('business:officialevent:edit')")
    @Log(title = "官方活动", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody OfficialEvent event) {
        officialEventService.adminSave(event, null);
        return success();
    }

    /** 上线(status=2 报名中)/ 下线(status=9)。 */
    @PreAuthorize("@ss.hasPermi('business:officialevent:edit')")
    @Log(title = "官方活动状态", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/status")
    public AjaxResult setStatus(@PathVariable("id") Long id, @RequestParam Integer status) {
        OfficialEvent event = officialEventService.adminGet(id);
        if (event != null && event.getContractVersion() != null && event.getContractVersion() >= 2) {
            return error("V2 活动禁止直接写状态，请使用发布、暂停或恢复命令");
        }
        officialEventService.adminSetStatus(id, status);
        return success();
    }

    /** 手动触发结算发奖。 */
    @PreAuthorize("@ss.hasPermi('business:officialevent:settle')")
    @Log(title = "官方活动结算", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/settle")
    public AjaxResult settle(@PathVariable("id") Long id) {
        OfficialEvent event = officialEventService.adminGet(id);
        if (event != null && event.getContractVersion() != null && event.getContractVersion() >= 2) {
            int granted = officialEventV2Service.settle(id);
            return AjaxResult.success("V2 资格结算已执行，投递 " + granted + " 人", granted);
        }
        int granted = officialEventService.settle(id);
        return AjaxResult.success("已结算,发奖 " + granted + " 人", granted);
    }

    // ============ V2 制作台与活动 360 ============

    /** 当前登录后台账号的 V2 代理身份，不返回其他账号的映射。 */
    @PreAuthorize("@ss.hasPermi('business:officialevent:list')")
    @GetMapping("/v2/operator-identity")
    public AjaxResult operatorIdentity() {
        return success(officialEventV2Service.operatorIdentity(getUserId()));
    }

    /** 高风险的 sys_user -> ums_member 授权，独立于普通活动编辑权限。 */
    @PreAuthorize("@ss.hasPermi('business:officialevent:authorize')")
    @Log(title = "官方活动主办授权", businessType = BusinessType.UPDATE)
    @PostMapping("/v2/operator-authorizations")
    public AjaxResult authorizeOperator(@RequestBody OfficialEventV2OperatorAuthorizationRequest request) {
        String err = officialEventV2Service.authorizeOperator(request == null ? null : request.getSysUserId(),
                request == null ? null : request.getMemberId(), getUserId(), request == null ? null : request.getReason());
        return err == null ? success("后台主办授权已生效") : error(err);
    }

    @PreAuthorize("@ss.hasPermi('business:officialevent:authorize')")
    @Log(title = "官方活动主办授权", businessType = BusinessType.UPDATE)
    @DeleteMapping("/v2/operator-authorizations/{sysUserId}")
    public AjaxResult revokeOperatorAuthorization(@PathVariable Long sysUserId, @RequestParam String reason) {
        String err = officialEventV2Service.revokeOperatorAuthorization(sysUserId, getUserId(), reason);
        return err == null ? success("后台主办授权已撤销") : error(err);
    }

    @PreAuthorize("@ss.hasPermi('business:officialevent:add')")
    @Log(title = "官方活动V2草稿", businessType = BusinessType.INSERT)
    @PostMapping("/v2/draft")
    public AjaxResult saveV2Draft(@RequestBody OfficialEventV2DraftRequest request) {
        Long officialMemberId = resolveV2OfficialMember();
        if (officialMemberId == null) return error(V2_OPERATOR_UNAUTHORIZED);
        OfficialEvent saved = officialEventV2Service.saveDraft(request, officialMemberId);
        return AjaxResult.success("V2 草稿已保存", saved.getId());
    }

    @PreAuthorize("@ss.hasPermi('business:officialevent:query')")
    @GetMapping("/v2/{id}/draft")
    public AjaxResult getV2Draft(@PathVariable("id") Long id) {
        Long officialMemberId = resolveV2OfficialMember();
        if (officialMemberId == null) return error(V2_OPERATOR_UNAUTHORIZED);
        if (!officialEventV2Service.canReadControlledEvent(id, officialMemberId)) return error("无权读取该活动草稿");
        return success(officialEventV2Service.draftForOperator(id, officialMemberId));
    }

    @PreAuthorize("@ss.hasPermi('business:officialevent:query')")
    @GetMapping("/v2/{id}/publish-check")
    public AjaxResult publishCheck(@PathVariable("id") Long id) {
        Long officialMemberId = resolveV2OfficialMember();
        if (officialMemberId == null) return error(V2_OPERATOR_UNAUTHORIZED);
        if (!officialEventV2Service.canReadControlledEvent(id, officialMemberId)) return error("无权读取该活动发布检查");
        return success(officialEventV2Service.checkPublishableForOperator(id, officialMemberId));
    }

    @PreAuthorize("@ss.hasPermi('business:officialevent:edit')")
    @Log(title = "官方活动V2发布", businessType = BusinessType.UPDATE)
    @PostMapping("/v2/{id}/schedule")
    public AjaxResult scheduleV2(@PathVariable("id") Long id) {
        Long officialMemberId = resolveV2OfficialMember();
        if (officialMemberId == null) return error(V2_OPERATOR_UNAUTHORIZED);
        String err = officialEventV2Service.schedule(id, officialMemberId);
        return err == null ? success("活动已进入已排期") : error(err);
    }

    @PreAuthorize("@ss.hasPermi('business:officialevent:list')")
    @GetMapping("/v2/{id}/360")
    public AjaxResult activity360(@PathVariable("id") Long id) {
        Long officialMemberId = resolveV2OfficialMember();
        if (officialMemberId == null) return error(V2_OPERATOR_UNAUTHORIZED);
        if (!officialEventV2Service.canReadControlledEvent(id, officialMemberId)) return error("无权读取该活动 360");
        return success(officialEventV2Service.activity360ForOperator(id, officialMemberId));
    }

    @PreAuthorize("@ss.hasPermi('business:officialevent:edit')")
    @Log(title = "官方活动V2暂停", businessType = BusinessType.UPDATE)
    @PostMapping("/v2/{id}/pause")
    public AjaxResult pauseV2(@PathVariable("id") Long id, @RequestParam String reason,
                              @RequestParam String impactScope, @RequestParam String alternativePlan,
                              @RequestParam String notificationPlan) {
        Long officialMemberId = resolveV2OfficialMember();
        if (officialMemberId == null) return error(V2_OPERATOR_UNAUTHORIZED);
        String err = officialEventV2Service.pause(id, officialMemberId, reason, impactScope, alternativePlan, notificationPlan);
        return err == null ? success("活动已暂停") : error(err);
    }

    @PreAuthorize("@ss.hasPermi('business:officialevent:edit')")
    @Log(title = "官方活动V2恢复", businessType = BusinessType.UPDATE)
    @PostMapping("/v2/{id}/resume")
    public AjaxResult resumeV2(@PathVariable("id") Long id, @RequestParam(required = false) String reason) {
        Long officialMemberId = resolveV2OfficialMember();
        if (officialMemberId == null) return error(V2_OPERATOR_UNAUTHORIZED);
        String err = officialEventV2Service.resume(id, officialMemberId, reason);
        return err == null ? success("活动已恢复") : error(err);
    }

    @PreAuthorize("@ss.hasPermi('business:officialevent:audit')")
    @Log(title = "官方活动V2证据撤销", businessType = BusinessType.UPDATE)
    @PostMapping("/v2/evidence/{evidenceId}/revoke")
    public AjaxResult revokeEvidence(@PathVariable Long evidenceId, @RequestParam String reason) {
        String err = officialEventV2Service.revokeEvidence(evidenceId, getUserId(), reason);
        return err == null ? success() : error(err);
    }

    @PreAuthorize("@ss.hasPermi('business:officialevent:audit')")
    @Log(title = "官方活动V2参与资格", businessType = BusinessType.UPDATE)
    @PostMapping("/v2/{id}/participations/{memberId}/invalidate")
    public AjaxResult invalidateParticipation(@PathVariable("id") Long id, @PathVariable Long memberId,
                                              @RequestParam String state, @RequestParam String reason) {
        String err = officialEventV2Service.invalidateParticipation(id, memberId, state, getUserId(), reason);
        return err == null ? success() : error(err);
    }

    // ============ 官方通知 ============

    @PreAuthorize("@ss.hasPermi('business:officialevent:notify')")
    @Log(title = "官方通知", businessType = BusinessType.INSERT)
    @PostMapping("/broadcast")
    public AjaxResult broadcast(@RequestBody OfficialBroadcast bc) {
        if (bc == null) return error("通知不能为空");
        bc.setRequestOwner("ADMIN:"+getUserId());
        OfficialBroadcast saved;
        if (bc.getEventId() != null) {
            OfficialEvent event = officialEventService.adminGet(bc.getEventId());
            if (event == null) return error("活动不存在");
            if (event.getContractVersion() != null && event.getContractVersion() >= 2) {
                Long officialMemberId = resolveV2OfficialMember();
                if (officialMemberId == null) return error(V2_OPERATOR_UNAUTHORIZED);
                saved = officialEventV2Service.publishBroadcastForOperator(bc, officialMemberId);
                return AjaxResult.success("通知已提交", saved.getId());
            }
        }
        saved = officialBroadcastService.publish(bc, null);
        return AjaxResult.success("通知已提交", saved.getId());
    }

    @PreAuthorize("@ss.hasPermi('business:officialevent:notify')")
    @PostMapping("/broadcast-request/cancel")
    public AjaxResult cancelBroadcastCreation(@RequestBody java.util.Map<String,String> input){
        return success(officialBroadcastService.cancelCreation("ADMIN:"+getUserId(),input.get("requestId")));
    }

    @PreAuthorize("@ss.hasPermi('business:officialevent:notify')")
    @GetMapping("/broadcast-request")
    public AjaxResult broadcastRequest(@RequestParam String requestId){return success(officialBroadcastService.creationReceipt("ADMIN:"+getUserId(),requestId));}

    @PreAuthorize("@ss.hasPermi('business:officialevent:notify')")
    @PostMapping("/broadcast/{id}/recover")
    public AjaxResult recoverBroadcast(@PathVariable Long id){
        Long member=resolveV2OfficialMember();
        return success(officialBroadcastService.recoverOwned(id,member,"ADMIN:"+getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('business:officialevent:list')")
    @GetMapping("/broadcast/{id}/stats")
    public AjaxResult broadcastStats(@PathVariable("id") Long id) {
        OfficialBroadcast broadcast = officialBroadcastService.getById(id, resolveV2OfficialMember());
        if (broadcast == null) return error("通知不存在");
        if (broadcast.getEventId() != null) {
            OfficialEvent event = officialEventService.adminGet(broadcast.getEventId());
            if (event == null) return error("活动不存在");
            if (event.getContractVersion() != null && event.getContractVersion() >= 2) {
                Long officialMemberId = resolveV2OfficialMember();
                if (officialMemberId == null) return error(V2_OPERATOR_UNAUTHORIZED);
                return success(officialEventV2Service.broadcastStatsForOperator(id, officialMemberId));
            }
        }
        return success(officialBroadcastService.reachStats(id, resolveV2OfficialMember()));
    }

    private Long resolveV2OfficialMember() {
        return officialEventV2Service.resolveAuthorizedOfficialMember(getUserId());
    }
}

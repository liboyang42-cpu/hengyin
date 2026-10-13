package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.MerchantCrmNoteRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmBatchTagRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmContactRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmNoteHideRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmCustomerQuery;
import com.chengyinhub.business.domain.dto.MerchantCrmTagRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmTagRemoveRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmSegmentRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmExportRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmCampaignPreviewRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmCampaignRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmBroadcastRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmMarketingConsentRequest;
import com.chengyinhub.business.domain.vo.MerchantCrmExportFile;
import com.chengyinhub.business.service.IMerchantCrmSegmentService;
import com.chengyinhub.business.service.IMerchantCrmExportService;
import com.chengyinhub.business.service.IMerchantCrmAppService;
import com.chengyinhub.business.service.IMerchantCrmCampaignService;
import com.chengyinhub.business.service.IMerchantCrmBroadcastService;
import com.chengyinhub.business.service.IMerchantCrmMarketingConsentService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.file.FileUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@RestController
@Tag(name = "商家客户 CRM")
@RequestMapping("/api/merchant/crm")
public class ApiMerchantCrmController extends BaseController {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired private IMerchantCrmAppService merchantCrmAppService;
    @Autowired private IMerchantCrmSegmentService merchantCrmSegmentService;
    @Autowired private IMerchantCrmExportService merchantCrmExportService;
    @Autowired private IMerchantCrmCampaignService merchantCrmCampaignService;
    @Autowired private IMerchantCrmBroadcastService merchantCrmBroadcastService;
    @Autowired private IMerchantCrmMarketingConsentService marketingConsentService;

    @Operation(summary = "玩家查看有真实报名关系商家的营销同意（默认拒绝）")
    @GetMapping("/marketing-consents")
    public AjaxResult marketingConsents() {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(marketingConsentService.list(principal)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "玩家显式同意或立即退订单一商家、单一渠道触达")
    @PostMapping("/marketing-consents")
    public AjaxResult updateMarketingConsent(@RequestBody(required = false) MerchantCrmMarketingConsentRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(marketingConsentService.update(principal, request)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "列出当前商家自有、有效且有库存的营销券")
    @GetMapping("/campaigns/coupons")
    public AjaxResult campaignCoupons() {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmCampaignService.coupons(principal)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "服务端重算保存分群的合规触达人数预览")
    @PostMapping("/campaigns/preview")
    public AjaxResult campaignPreview(@RequestBody(required = false) MerchantCrmCampaignPreviewRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmCampaignService.preview(principal, request)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "创建并冻结服务端分群触达名单")
    @PostMapping("/campaigns")
    public AjaxResult createCampaign(@RequestBody(required = false) MerchantCrmCampaignRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmCampaignService.create(principal, request)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "真实逐人投递并返回消息/优惠券回执")
    @PostMapping("/campaigns/{campaignId}/dispatch")
    public AjaxResult dispatchCampaign(@PathVariable Long campaignId) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmCampaignService.dispatch(principal, campaignId)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "仅重试未同意、频控或失败的收件人；已成功者不重复投递")
    @PostMapping("/campaigns/{campaignId}/retry")
    public AjaxResult retryCampaign(@PathVariable Long campaignId) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmCampaignService.retry(principal, campaignId)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "当前商家的触达任务列表")
    @GetMapping("/campaigns")
    public AjaxResult campaigns() {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmCampaignService.list(principal)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "当前商家的逐人脱敏触达回执")
    @GetMapping("/campaigns/{campaignId}")
    public AjaxResult campaignDetail(@PathVariable Long campaignId) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmCampaignService.detail(principal, campaignId)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "定向广播预览:服务端重算当前筛选人群与已同意客户数")
    @PostMapping("/broadcast/preview")
    public AjaxResult broadcastPreview(@RequestBody(required = false) MerchantCrmBroadcastRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmBroadcastService.preview(principal, request)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "定向广播:只发给已同意客户;商家每天最多3条、客户每天最多1条")
    @PostMapping("/broadcast")
    public AjaxResult broadcast(@RequestBody(required = false) MerchantCrmBroadcastRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmBroadcastService.send(principal, request)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "商家客户真实后端分页与筛选")
    @PostMapping("/customers/list")
    public AjaxResult customers(@RequestBody(required = false) MerchantCrmCustomerQuery query) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmAppService.listCustomers(principal, query)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "增量重建并对账商家客户画像缓存")
    @PostMapping("/profiles/reconcile")
    public AjaxResult reconcileProfiles() {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmAppService.reconcileProfiles(principal)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "商家客户详情、标签与跟进时间线")
    @PostMapping("/customers/{customerMemberId}/detail")
    public AjaxResult detail(@PathVariable Long customerMemberId) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmAppService.detail(principal, customerMemberId)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "获取客户联系号码（明文仅本次下发，写访问审计，按操作员日上限频控）")
    @PostMapping("/customers/{customerMemberId}/contact")
    public AjaxResult revealContact(@PathVariable Long customerMemberId,
                                    @RequestBody(required = false) MerchantCrmContactRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try {
            return success(merchantCrmAppService.revealContact(principal, customerMemberId,
                    request == null ? null : request.getPurpose()));
        } catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "新增商家客户跟进备注")
    @PostMapping("/customers/{customerMemberId}/notes")
    public AjaxResult addNote(@PathVariable Long customerMemberId,
                              @RequestBody(required = false) MerchantCrmNoteRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmAppService.addNote(principal, customerMemberId, request)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "隐藏商家客户跟进备注（CAS，不改原文）")
    @PostMapping("/customers/{customerMemberId}/notes/hide")
    public AjaxResult hideNote(@PathVariable Long customerMemberId,
                               @RequestBody(required = false) MerchantCrmNoteHideRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try {
            merchantCrmAppService.hideNote(principal, customerMemberId,
                    request == null ? null : request.getNoteId(),
                    request == null ? null : request.getExpectedVersion(),
                    request == null ? null : request.getRequestId());
            return success("备注已隐藏");
        } catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "为商家客户设置自有标签")
    @PostMapping("/customers/{customerMemberId}/tags")
    public AjaxResult assignTag(@PathVariable Long customerMemberId,
                                @RequestBody(required = false) MerchantCrmTagRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmAppService.assignTag(principal, customerMemberId, request)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "批量为商家客户设置自有标签（最多100人）")
    @PostMapping("/customers/tags/batch")
    public AjaxResult batchAssignTag(@RequestBody(required = false) MerchantCrmBatchTagRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmAppService.batchAssignTag(principal, request)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "移除商家客户标签关系")
    @PostMapping("/customers/{customerMemberId}/tags/remove")
    public AjaxResult removeTag(@PathVariable Long customerMemberId,
                                @RequestBody(required = false) MerchantCrmTagRemoveRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try {
            merchantCrmAppService.removeTag(principal, customerMemberId,
                    request == null ? null : request.getTagId(),
                    request == null ? null : request.getRequestId());
            return success("标签已移除");
        } catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "保存白名单客户分群")
    @PostMapping("/segments")
    public AjaxResult saveSegment(@RequestBody(required = false) MerchantCrmSegmentRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmSegmentService.save(principal, request)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "列出当前商家的客户分群")
    @GetMapping("/segments")
    public AjaxResult segments() {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmSegmentService.list(principal)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "创建异步脱敏Excel导出任务")
    @PostMapping("/exports")
    public AjaxResult createExport(@RequestBody(required = false) MerchantCrmExportRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmExportService.create(principal, request)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "查询异步脱敏Excel导出任务")
    @PostMapping("/exports/{taskId}/status")
    public AjaxResult exportStatus(@PathVariable Long taskId) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantCrmExportService.status(principal, taskId)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @Operation(summary = "下载异步脱敏Excel；登录、owner、CRM_EXPORT与短时token缺一不可")
    @GetMapping("/exports/{taskId}/download")
    public void downloadExport(@PathVariable Long taskId,
                               @RequestHeader(value = "X-CRM-Export-Token", required = false) String token,
                               HttpServletResponse response) throws IOException {
        Long principal = principal();
        if (principal == null) {
            writeDownloadError(response, 401, "请先登录");
            return;
        }
        try {
            MerchantCrmExportFile export = merchantCrmExportService.authorizeDownload(principal, taskId, token);
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            FileUtils.setAttachmentResponseHeader(response, export.getDownloadName());
            FileUtils.writeBytes(export.getFile().getAbsolutePath(), response.getOutputStream());
            merchantCrmExportService.completeDownload(export);
        } catch (ServiceException e) {
            writeDownloadError(response, e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
    }

    private Long principal() {
        try {
            Long principal = getAppUserId();
            return principal != null && principal > 0 ? principal : null;
        } catch (Exception e) { return null; }
    }

    private AjaxResult serviceError(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
    }

    private void writeDownloadError(HttpServletResponse response, int code, String message) throws IOException {
        if (response.isCommitted()) return;
        response.reset();
        response.setStatus(code);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        JSON.writeValue(response.getOutputStream(), AjaxResult.error(code, message));
    }
}

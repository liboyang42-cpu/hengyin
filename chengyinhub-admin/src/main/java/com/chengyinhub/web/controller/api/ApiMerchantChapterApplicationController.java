package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantOperationScope;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.service.IMerchantChapterApplicationService;
import com.chengyinhub.business.service.MerchantChapterApplicationExistsException;
import com.chengyinhub.business.service.MerchantChapterApplicationWithdrawException;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 商家章节承接申请：申请人提交，主题原发布者审核。 */
@RestController
@Tag(name = "商家章节承接申请")
@RequestMapping("/api/merchant/chapter-application")
public class ApiMerchantChapterApplicationController extends BaseController {

    @Autowired
    private IMerchantChapterApplicationService merchantChapterApplicationService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    @Operation(summary = "申请承接一个章节（商家）")
    @PostMapping("/apply")
    public AjaxResult apply(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        Long chapterId = toLong(body == null ? null : body.get("chapterId"));
        if (chapterId == null) return error("缺少章节");
        String message = toText(body == null ? null : body.get("message"));
        try {
            Long ownerMemberId = merchantOwnerMemberId(memberId);
            MerchantChapterApplication application = merchantChapterApplicationService.apply(
                    chapterId, ownerMemberId, message);
            return AjaxResult.success("已提交，等待主题发布者审核", application);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (MerchantChapterApplicationExistsException e) {
            AjaxResult result = error(e.getMessage());
            result.put("errorCode", e.getErrorCode());
            return result;
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "撤回待审章节承接申请（商家）")
    @PostMapping("/withdraw")
    public AjaxResult withdraw(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        Long applicationId = toLong(body == null ? null : body.get("applicationId"));
        if (applicationId == null) return error("缺少申请");
        try {
            MerchantChapterApplication application = merchantChapterApplicationService.withdraw(
                    applicationId, merchantOwnerMemberId(memberId));
            return AjaxResult.success("已撤回，可重新申请", application);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (MerchantChapterApplicationWithdrawException e) {
            AjaxResult result = error(e.getMessage());
            result.put("errorCode", e.getErrorCode());
            return result;
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "本主题可邀请的商家（仅主题原发布者;章节按商家品类推导，不用先选）")
    @PostMapping("/invitable")
    public AjaxResult invitable(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        Long topicId = toLong(body == null ? null : body.get("topicId"));
        if (topicId == null) return error("缺少主题");
        // 定位可选:给了就按距离从近到远排,没给(拒绝授权/取不到)仍按原顺序返回,不当错误。
        Double lat = toDouble(body == null ? null : body.get("latitude"));
        Double lng = toDouble(body == null ? null : body.get("longitude"));
        try {
            Long publisherMemberId = publisherMemberId(memberId, body);
            return AjaxResult.success(merchantChapterApplicationService.invitableMerchants(
                    topicId, publisherMemberId, lat, lng));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "邀请商家承接本章节（仅主题原发布者;商家接受后才占名额，主办可撤回）")
    @PostMapping("/invite")
    public AjaxResult invite(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        Long chapterId = toLong(body == null ? null : body.get("chapterId"));
        Long merchantMemberId = toLong(body == null ? null : body.get("merchantMemberId"));
        Long replacementOfOfferId = toLong(body == null ? null : body.get("replacementOfOfferId"));
        if (chapterId == null) return error("缺少章节");
        if (merchantMemberId == null) return error("请选择商家");
        try {
            Long publisherMemberId = publisherMemberId(memberId, body);
            MerchantChapterApplication invited =
                    merchantChapterApplicationService.invite(chapterId, publisherMemberId, merchantMemberId,
                            toText(body == null ? null : body.get("message")), replacementOfOfferId);
            return AjaxResult.success("邀请已发出，等待商家接受后占用名额", invited);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @PostMapping("/invite/respond")
    public AjaxResult respondInvite(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId = currentMemberId();
        Long applicationId = toLong(body == null ? null : body.get("applicationId"));
        Boolean accepted = toBoolean(body == null ? null : body.get("accepted"));
        if (memberId == null) return error("请先登录");
        if (applicationId == null || accepted == null) return error("请选择邀请及处理方式");
        try {
            Long owner = merchantOwnerMemberId(memberId);
            return success(merchantChapterApplicationService.respondInvite(applicationId, owner, accepted));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException | IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @PostMapping("/invite/revoke")
    public AjaxResult revokeInvite(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId = currentMemberId();
        Long applicationId = toLong(body == null ? null : body.get("applicationId"));
        if (memberId == null) return error("请先登录");
        if (applicationId == null) return error("请选择邀请");
        try {
            Long owner = publisherMemberId(memberId, body);
            return success(merchantChapterApplicationService.revokeInvite(applicationId, owner));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException | IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "审核章节承接申请（仅主题原发布者）")
    @PostMapping("/audit")
    public AjaxResult audit(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        Long applicationId = toLong(body == null ? null : body.get("applicationId"));
        Boolean approved = toBoolean(body == null ? null : body.get("approved"));
        if (applicationId == null) return error("缺少申请");
        if (approved == null) return error("缺少审核结果");
        String auditRemark = toText(body == null ? null : body.get("auditRemark"));
        try {
            MerchantChapterApplication application = merchantChapterApplicationService.audit(
                    applicationId, publisherMemberId(memberId, body), approved, auditRemark);
            return AjaxResult.success(approved ? "已通过申请" : "已拒绝申请", application);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "我发布的主题下的章节申请（谁发布谁审核）")
    @PostMapping("/owner-list")
    public AjaxResult ownerList(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        Long topicId = toLong(body == null ? null : body.get("topicId"));
        if (topicId == null) return error("缺少主题");
        try {
            List<MerchantChapterApplication> rows = merchantChapterApplicationService.selectForTopic(
                    topicId, publisherMemberId(memberId, body));
            return AjaxResult.success(rows);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "我发布的主题下的章节申请")
    @PostMapping("/owner-mine")
    public AjaxResult ownerMine(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        try {
            return AjaxResult.success(merchantChapterApplicationService.selectForPublisher(
                    publisherMemberId(memberId, body)));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "我的章节承接申请（商家）")
    @PostMapping("/mine")
    public AjaxResult mine() {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        try {
            return AjaxResult.success(merchantChapterApplicationService.selectMine(
                    merchantOwnerMemberId(memberId)));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    private Long currentMemberId() {
        try {
            Long memberId = getAppUserId();
            return memberId != null && memberId > 0 ? memberId : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Long merchantOwnerMemberId(Long principalMemberId) {
        return merchantAccessService.require(principalMemberId, MerchantPermission.COOP_MANAGE)
                .getOwnerMemberId();
    }

    private Long publisherMemberId(Long principalMemberId, Map<String, Object> body) {
        String scope = toText(body == null ? null : body.get("scope"));
        if (!MerchantOperationScope.isMerchant(scope)) return principalMemberId;
        return merchantAccessService.require(principalMemberId, MerchantPermission.PROJECT_MANAGE)
                .getOwnerMemberId();
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        try {
            Long parsed = Long.valueOf(String.valueOf(value).trim());
            return parsed > 0 ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 坐标可以是负数也可以是 0,不能套用 toLong 的 {@code > 0} 判据。 */
    private static Double toDouble(Object value) {
        if (value == null) return null;
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean toBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value == null) return null;
        String normalized = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(normalized) || "1".equals(normalized)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(normalized) || "0".equals(normalized)) return Boolean.FALSE;
        return null;
    }

    private static String toText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

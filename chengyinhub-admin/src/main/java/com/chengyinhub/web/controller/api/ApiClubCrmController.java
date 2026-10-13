package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IClubCrmAppService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 俱乐部客户(按人聚合)。
 *
 * 与 {@link ApiClubController#topicRegistrations} 的分工:
 *   · topic-registrations = 报名名册,按团 → 票种 → 人,配套 owner-only 的清退退款。
 *     它刻意只回 id/memberId/avatar/nickname/paymentStatus/verificationStatus,**不含手机号**,
 *     这份克制保留 —— 本 controller 是新接口,没有去放宽它。
 *   · 本 controller = 客户,按人聚合,回答"这个人买过几次、什么时候来的",能打标签写备注。
 *
 * 权限与手机号脱敏都在 Service 层(ClubCrmAppServiceImpl),这里只负责取参数和翻译异常。
 */
@RestController
@Tag(name = "俱乐部客户")
@RequestMapping("/api/club/crm")
public class ApiClubCrmController extends BaseController {

    @Autowired
    private IClubCrmAppService clubCrmAppService;

    @Operation(summary = "客户列表:按人聚合本俱乐部的购票客户")
    @PostMapping("/customers/list")
    public AjaxResult customers(@RequestBody(required = false) Map<String, Object> body) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        Map<String, Object> req = body == null ? new java.util.HashMap<String, Object>() : body;
        try {
            return AjaxResult.success(clubCrmAppService.listCustomers(principal,
                    Convert.toLong(req.get("clubId")),
                    Convert.toStr(req.get("filter")),
                    Convert.toStr(req.get("keyword"))));
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "客户人数:管理弹窗那一行右侧的数,不端列表")
    @PostMapping("/customers/count")
    public AjaxResult customerCount(@RequestBody(required = false) Map<String, Object> body) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        Map<String, Object> req = body == null ? new java.util.HashMap<String, Object>() : body;
        try {
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<String, Object>();
            data.put("total", clubCrmAppService.customerCount(principal, Convert.toLong(req.get("clubId"))));
            return AjaxResult.success(data);
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "客户详情:概览、参与记录、标签与备注")
    @PostMapping("/customers/detail")
    public AjaxResult customerDetail(@RequestBody(required = false) Map<String, Object> body) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        Map<String, Object> req = body == null ? new java.util.HashMap<String, Object>() : body;
        try {
            return AjaxResult.success(clubCrmAppService.customerDetail(principal,
                    Convert.toLong(req.get("clubId")),
                    Convert.toLong(req.get("memberId"))));
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "保存客户标签与备注(覆盖式)")
    @PostMapping("/customers/tag-remark")
    public AjaxResult saveTagRemark(@RequestBody(required = false) Map<String, Object> body) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        Map<String, Object> req = body == null ? new java.util.HashMap<String, Object>() : body;
        try {
            clubCrmAppService.saveTagRemark(principal,
                    Convert.toLong(req.get("clubId")),
                    Convert.toLong(req.get("memberId")),
                    stringList(req.get("tags")),
                    Convert.toStr(req.get("remark")),
                    Convert.toStr(req.get("requestId")));
            return AjaxResult.success("已保存");
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "核销详情(Figma K3;手机号按岗位权限由服务端裁)")
    @PostMapping("/checkin/detail")
    public AjaxResult checkinDetail(@RequestBody(required = false) Map<String, Object> body) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        Map<String, Object> req = body == null ? new java.util.HashMap<String, Object>() : body;
        try {
            return AjaxResult.success(clubCrmAppService.checkinDetail(principal,
                    Convert.toLong(req.get("clubId")),
                    Convert.toLong(req.get("registrationId"))));
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "主题内客户:按场次列出这个主题的购票人与核销状态")
    @PostMapping("/topic-customers")
    public AjaxResult topicCustomers(@RequestBody(required = false) Map<String, Object> body) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        Map<String, Object> req = body == null ? new java.util.HashMap<String, Object>() : body;
        try {
            return AjaxResult.success(clubCrmAppService.topicCustomers(principal,
                    Convert.toLong(req.get("clubId")),
                    Convert.toLong(req.get("topicId")),
                    Convert.toStr(req.get("filter"))));
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "主题管理向统计:站数/本场人数/待核销/我核销的/能否管理活动(拍板1)")
    @PostMapping("/topic-manage-stats")
    public AjaxResult topicManageStats(@RequestBody(required = false) Map<String, Object> body) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        Map<String, Object> req = body == null ? new java.util.HashMap<String, Object>() : body;
        try {
            return AjaxResult.success(clubCrmAppService.topicManageStats(principal,
                    Convert.toLong(req.get("clubId")),
                    Convert.toLong(req.get("topicId")),
                    Convert.toLong(req.get("activityId"))));
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    private Long principal() {
        try {
            Long id = getAppUserId();
            return id != null && id > 0 ? id : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (!(value instanceof List)) return out;
        for (Object item : (List<?>) value) {
            if (item == null) continue;
            out.add(String.valueOf(item));
        }
        return out;
    }
}

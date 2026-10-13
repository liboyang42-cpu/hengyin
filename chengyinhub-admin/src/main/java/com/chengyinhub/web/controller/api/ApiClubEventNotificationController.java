package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.ClubCampaignRetryRequest;
import com.chengyinhub.business.domain.dto.ClubEventCampaignRequest;
import com.chengyinhub.business.service.IClubEventNotificationService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "俱乐部活动通知")
@RequestMapping("/api/club/event-notification")
public class ApiClubEventNotificationController extends BaseController {
    @Autowired private IClubEventNotificationService notificationService;

    @PostMapping("/preview")
    public AjaxResult preview(@RequestBody(required = false) ClubEventCampaignRequest request) {
        return invoke(request, false);
    }

    @PostMapping("/send")
    public AjaxResult send(@RequestBody(required = false) ClubEventCampaignRequest request) {
        return invoke(request, true);
    }

    /** 稿 R4 290:526 群发通知:各受众分组各多少人。页面进来先调它,每一行右侧就有数了。 */
    @PostMapping("/audience-counts")
    public AjaxResult audienceCounts(@RequestBody(required = false) ClubEventCampaignRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少通知范围参数");
        try {
            return success(notificationService.audienceCounts(
                    principal, request.getClubId(), request.getActivityId()));
        } catch (ServiceException e) {
            return new AjaxResult(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * 投递状态回读(CU-C-135)。群发是后台投递的:send 返回时常常还是 PROCESSING,
     * 页面只存那一次响应就永远停在「总 4 / 送达 0 / 失败 0」。这是那条刷新链路的只读端点,
     * 鉴权与 /retry 同一道闸(能对这个俱乐部发通知的人才读得到任务回执)。
     */
    @PostMapping("/status")
    public AjaxResult status(@RequestBody(required = false) ClubCampaignRetryRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少通知任务参数");
        try {
            return success(notificationService.campaign(principal, request.getCampaignId()));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/retry")
    public AjaxResult retry(@RequestBody(required = false) ClubCampaignRetryRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少通知任务参数");
        try {
            return success(notificationService.retryFailed(principal, request.getCampaignId()));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    private AjaxResult invoke(ClubEventCampaignRequest request, boolean sending) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少通知参数");
        try {
            return success(sending ? notificationService.send(principal, request)
                    : notificationService.preview(principal, request));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    private Long principal() {
        try { return getAppUserId(); } catch (Exception ignored) { return null; }
    }

    private AjaxResult serviceError(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
    }
}

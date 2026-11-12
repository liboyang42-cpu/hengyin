package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.support.PlayRunFeedbackService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * qx-c1 #7 (U08-02) 完局问题反馈。身份只看登录态;原局归属、节点范围、修订追溯都在
 * {@link PlayRunFeedbackService} 里按原局事实判定,客户端只给 runId/nodeId/category/content/requestId。
 * 这不是举报:不进审核队列,不影响内容可见性。
 */
@RestController
@Tag(name = "完局问题反馈")
@RequestMapping("/api/play/feedback")
public class ApiPlayFeedbackController extends BaseController {
    private final PlayRunFeedbackService feedback;

    public ApiPlayFeedbackController(PlayRunFeedbackService feedback) {
        this.feedback = feedback;
    }

    @Operation(summary = "提交完局问题反馈(地点/题目/其他;requestId 幂等)")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.MEMBER)
    @PostMapping("/submit")
    public AjaxResult submit(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception anonymous) {
            memberId = null;
        }
        if (memberId == null || memberId <= 0) return AjaxResult.error(401, "请先登录");
        if (body == null) return AjaxResult.error(400, "反馈内容不能为空");
        try {
            return success(feedback.submit(memberId, new PlayRunFeedbackService.Submission(
                    Convert.toLong(body.get("runId")), Convert.toLong(body.get("nodeId")),
                    Convert.toStr(body.get("category")), Convert.toStr(body.get("content")),
                    Convert.toStr(body.get("requestId")))));
        } catch (ServiceException refused) {
            return AjaxResult.error(refused.getCode() == null ? 400 : refused.getCode(), refused.getMessage());
        }
    }
}

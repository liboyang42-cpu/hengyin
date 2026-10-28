package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.support.MemberAccountService;
import com.chengyinhub.business.service.support.PlayContactService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档二 L8 玩家端只读接口:游玩菜单的联系主办 / 联系商家(B6 / B7 / B9)、
 * 我的申诉跨局列表(C1)、账号信誉(X7)。契约见 docs/design/doc2-ui-impl-20261028/api-contracts.md §4 / §5。
 */
@RestController
@Tag(name = "玩家账号与联系")
public class ApiMemberAccountController extends BaseController {

    @Autowired
    private PlayContactService playContactService;

    @Autowired
    private MemberAccountService memberAccountService;

    @Operation(summary = "游玩菜单联系电话:俱乐部主题 / 赛事 = 主办俱乐部,自由探索 = 当前站商家")
    @GetMapping("/api/play/contact")
    public AjaxResult contact(Long topicId, Long nodeId, Long activityId) {
        if (memberId() == null) {
            return error("请先登录");
        }
        if (activityId != null && activityId > 0L) {
            return success(playContactService.forActivity(activityId));
        }
        return success(playContactService.forTopic(topicId, nodeId));
    }

    @Operation(summary = "我的申诉:本人的计分申诉与成绩争议(跨局,新的在前)")
    @GetMapping("/api/orient/scoring/mine")
    public AjaxResult myAppeals(String status, Integer pageNum, Integer pageSize) {
        Long memberId = memberId();
        if (memberId == null) {
            return error("请先登录");
        }
        try {
            return success(memberAccountService.myAppeals(memberId, status, pageNum, pageSize));
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "账号信誉:正常 / 受限(不返回分数与原因)")
    @GetMapping("/api/member/reputation")
    public AjaxResult reputation() {
        Long memberId = memberId();
        if (memberId == null) {
            return error("请先登录");
        }
        return success(memberAccountService.reputation(memberId));
    }

    private Long memberId() {
        try {
            Long id = getAppUserId();
            return id == null || id <= 0L ? null : id;
        } catch (Exception e) {
            return null;
        }
    }
}

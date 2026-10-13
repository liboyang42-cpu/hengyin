package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IPlayJourneyStoryService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主题级剧情只读接口(R8-STORY):阶段结算与结局。
 *
 * <p>登录会员只读自己的局;一局状态只在 topic_route_session.state_json,这里不写任何表。
 * R8-CASEBOOK 的 casebook / backpack 另建 ApiPlayJourneyReadController,不共用本文件。
 */
@RestController
@RequestMapping("/api/play/journey")
public class ApiPlayJourneyController extends BaseController {

    @Autowired
    private IPlayJourneyStoryService playJourneyStoryService;

    @Operation(summary = "本章节阶段结算:本章节点全部完成后可取")
    @GetMapping("/stage-end")
    public AjaxResult stageEnd(Long topicId, Long activityId, Long chapterId) {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        if (topicId == null || topicId <= 0L || chapterId == null || chapterId <= 0L) return error("参数有误");
        try {
            return success(playJourneyStoryService.stageEnd(memberId, topicId, activityId, chapterId));
        } catch (ServiceException | IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "本局结局:本局完成后可取")
    @GetMapping("/ending")
    public AjaxResult ending(Long topicId, Long activityId) {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        if (topicId == null || topicId <= 0L) return error("参数有误");
        try {
            return success(playJourneyStoryService.ending(memberId, topicId, activityId));
        } catch (ServiceException | IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    private Long currentMemberId() {
        try {
            Long id = getAppUserId();
            return id == null ? 0L : id;
        } catch (Exception e) {
            return 0L;
        }
    }
}

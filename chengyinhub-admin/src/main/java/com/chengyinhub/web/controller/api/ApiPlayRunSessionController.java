package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.mapper.PlayerRunSessionMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第二轮拍板 22:游戏进度跨设备续玩。
 *
 * <p>节点完成本来就在服务端;这里只记「进行中的会话 + 已用时 + 暂停态」,与小程序
 * utils/play-run-session.js 的本机快照同形。客户端离页/暂停时双写,结束/通关/服务端判终态时本机清、服务端落 ENDED 墓碑。
 * 恢复前仍由 /api/play/nodes 的权威态(时间窗/路线终态/全部完成)把门,这里不重复判定。
 */
@RestController
@Tag(name = "玩家进行中的游戏会话")
@RequestMapping("/api/play/run-session")
public class ApiPlayRunSessionController extends BaseController {

    /** 与客户端 MAX_PAUSED_SECONDS 同值:一次步行行程不可能按周计。 */
    static final int MAX_PAUSED_SECONDS = 7 * 24 * 3600;
    static final int LIST_LIMIT = 20;

    @Autowired
    private PlayerRunSessionMapper runSessionMapper;

    @Autowired
    private ICmsActivityService cmsActivityService;

    Long uid() {
        try { return getAppUserId(); } catch (Exception e) { return 0L; }
    }

    @Operation(summary = "落盘暂停中的会话(已用时)")
    @PostMapping("/save")
    public AjaxResult save(Long activityId, Long topicId, Integer elapsedSeconds, Long savedAt) {
        Long memberId = uid();
        if (memberId == null || memberId <= 0) return error("请先登录");
        if (elapsedSeconds == null || elapsedSeconds < 0 || elapsedSeconds > MAX_PAUSED_SECONDS) return error("用时无效");
        if (savedAt == null || savedAt <= 0) return error("落盘时间无效");
        long actId = positive(activityId) ? activityId : 0L;
        Long scopeTopicId;
        if (actId > 0) {
            // 活动场次的主题由服务端按活动回填,不信客户端带来的 topicId。
            CmsActivity activity = cmsActivityService.selectCmsActivityById(actId);
            if (activity == null || !positive(activity.getTopicId())) return error("活动不存在");
            scopeTopicId = activity.getTopicId();
        } else if (positive(topicId)) {
            scopeTopicId = topicId;
        } else {
            return error("参数有误");
        }
        runSessionMapper.upsertPaused(memberId, actId, scopeTopicId, elapsedSeconds, savedAt);
        return success();
    }

    @Operation(summary = "读取某场的进行中会话(没有 = data 为空)")
    @GetMapping
    public AjaxResult read(Long activityId, Long topicId) {
        Long memberId = uid();
        if (memberId == null || memberId <= 0) return error("请先登录");
        if (!positive(activityId) && !positive(topicId)) return error("参数有误");
        return success(runSessionMapper.selectScope(memberId, positive(activityId) ? activityId : 0L, topicId));
    }

    @Operation(summary = "作废某场的进行中会话(结束/通关/服务端判终态),留 ENDED 墓碑")
    @PostMapping("/clear")
    public AjaxResult clear(Long activityId, Long topicId, Long savedAt) {
        Long memberId = uid();
        if (memberId == null || memberId <= 0) return error("请先登录");
        if (!positive(activityId) && !positive(topicId)) return error("参数有误");
        if (savedAt == null || savedAt <= 0) return error("落盘时间无效");
        runSessionMapper.markEnded(memberId, positive(activityId) ? activityId : 0L, topicId, savedAt);
        return success();
    }

    @Operation(summary = "我仍可继续的游戏会话(首页「继续游戏」)")
    @GetMapping("/list")
    public AjaxResult list() {
        Long memberId = uid();
        if (memberId == null || memberId <= 0) return error("请先登录");
        return success(runSessionMapper.selectContinuableByMember(memberId, LIST_LIMIT));
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }
}

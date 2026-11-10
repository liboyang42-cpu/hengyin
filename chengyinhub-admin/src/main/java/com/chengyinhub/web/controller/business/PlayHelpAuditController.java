package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CmsTopicContentRevision;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsTopicContentRevisionMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayHelpAuditMapper;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.service.play.PlayHelpApplication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * qx-c1 #4 (UG-12) 后台只读:提示阶梯配置摘要与单局帮助/跳过/净分明细。
 *
 * <p>只读、不可导出:不返回答案、别名、提示正文;不改写任何对局事实,也不代玩家使用提示。
 * 数字全部来自玩家侧同一份计算({@link PlayHelpApplication#auditReceipt}/{@link PlayHelpApplication#auditConfig}),
 * 因此后台明细与玩家回执一致。权限 {@code business:playhelp:query} 由迁移建为按钮权限,默认不授予任何角色。
 */
@Tag(name = "玩法帮助明细(后台只读)")
@RestController
@RequestMapping("/business/playhelp")
public class PlayHelpAuditController extends BaseController {
    private static final String PERM = "@ss.hasPermi('business:playhelp:query')";

    private final PlayHelpApplication help;
    private final PlayHelpAuditMapper runs;
    private final CmsTopicNodeMapper nodes;
    private final CmsTopicContentRevisionMapper revisions;

    public PlayHelpAuditController(PlayHelpApplication help, PlayHelpAuditMapper runs, CmsTopicNodeMapper nodes,
                                   CmsTopicContentRevisionMapper revisions) {
        this.help = help;
        this.runs = runs;
        this.nodes = nodes;
        this.revisions = revisions;
    }

    @Operation(summary = "主题提示阶梯配置摘要(最新发布修订;层数/代价/整局免费次数)")
    @PreAuthorize(PERM)
    @GetMapping("/topic/{topicId}/config")
    public AjaxResult topicConfig(@PathVariable("topicId") Long topicId) {
        if (topicId == null || topicId <= 0) return AjaxResult.error(400, "参数有误");
        CmsTopicContentRevision revision = revisions.selectLatestByTopicId(topicId);
        if (revision == null) return AjaxResult.error(404, "该主题还没有发布修订");
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("topicId", topicId);
        view.put("contentRevisionId", revision.getId());
        view.put("revisionNo", revision.getRevisionNo());
        view.putAll(help.auditConfig(revision.getSnapshotJson()));
        return success(view);
    }

    @Operation(summary = "单局帮助/跳过/净分明细(按原局冻结配置;含回执号)")
    @PreAuthorize(PERM)
    @GetMapping("/run/{runId}")
    public AjaxResult run(@PathVariable("runId") Long runId) {
        if (runId == null || runId <= 0) return AjaxResult.error(400, "参数有误");
        TopicRouteSession run = runs.selectRunById(runId);
        if (run == null) return AjaxResult.error(404, "原局不存在");
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", run.getId());
        view.put("topicId", run.getTopicId());
        view.put("activityId", run.getActivityId());
        view.put("memberId", run.getMemberId());
        view.put("playInstanceId", run.getPlayInstanceId());
        view.put("configVersion", run.getConfigVersion());
        view.put("status", run.getStatus());
        view.put("completedAt", run.getCompletedAt());
        Map<String, Object> config = help.auditConfig(run.getConfigSnapshotJson());
        view.put("config", config);
        List<Map<String, Object>> lines = new ArrayList<>();
        Object configured = config.get("nodes");
        if (configured instanceof List<?> list) {
            for (Object entry : list) {
                Long nodeId = (Long) ((Map<?, ?>) entry).get("nodeId");
                CmsTopicNode node = nodes.selectCmsTopicNodeById(nodeId);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("nodeId", nodeId);
                if (node == null || !java.util.Objects.equals(node.getTopicId(), run.getTopicId())) {
                    line.put("state", "NODE_UNAVAILABLE");
                } else {
                    try {
                        Map<String, Object> receipt = help.auditReceipt(run, node);
                        line.put("state", "OK");
                        line.put("helpLines", receipt.get("helpLines"));
                        line.put("receipt", receipt.get("receipt"));
                    } catch (RuntimeException unavailable) {
                        line.put("state", "UNAVAILABLE");
                        line.put("reason", com.chengyinhub.common.utils.UserSafeMessage.of(unavailable.getMessage()));
                    }
                }
                lines.add(line);
            }
        }
        view.put("nodes", lines);
        return success(view);
    }
}

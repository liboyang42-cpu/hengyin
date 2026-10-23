package com.chengyinhub.web.controller.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.StringUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 勋章（跟随模板：俱乐部在模板配置里上传勋章图，完成绑定该模板的节点即获得）。
 * 勋章墙直接从 player_node_progress 推导，不维护单独发放表。
 */
@RestController
@Tag(name = "勋章")
@RequestMapping("/api/medal")
public class ApiMedalController extends BaseController {

    private static final String CITY_MEDAL_CONDITION = "完成绑定此勋章模板的城市节点";

    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;

    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;

    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;

    // R8:成就徽章(player_badge)合并进勋章墙,修"成就徽章发了却无处展示"
    @Autowired
    private com.chengyinhub.business.mapper.PlayerBadgeMapper playerBadgeMapper;

    @Operation(summary = "我的勋章墙")
    @PostMapping("/wall")
    public AjaxResult wall() {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }

        // 玩家全部已完成节点(R10-09 收尾:只认 status==1 正式完成;status=0 到店/凭证行只作到达展示)
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(memberId);
        List<PlayerNodeProgress> progs = playerNodeProgressService.selectPlayerNodeProgressList(probe);

        // 节点 → 模板 → 有勋章图则计入；按模板去重
        Map<Long, Map<String, Object>> medalsByTpl = new LinkedHashMap<>();
        for (PlayerNodeProgress p : progs) {
            if (!Integer.valueOf(1).equals(p.getStatus())) continue;
            CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(p.getNodeId());
            if (node == null || node.getTemplateId() == null || node.getTemplateId() <= 0) continue;
            Long tplId = node.getTemplateId();
            if (medalsByTpl.containsKey(tplId)) continue;
            CmsMemberTemplate tpl = cmsMemberTemplateService.selectCmsMemberTemplateById(tplId);
            if (tpl == null || StringUtils.isBlank(tpl.getMedalImg())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("templateId", tplId);
            m.put("medalImg", tpl.getMedalImg());
            m.put("medalName", tpl.getMedalName());
            m.put("style", tpl.getMedalStyle() == null ? "glow" : tpl.getMedalStyle());   // 勋章双样式:详情按此分流
            m.put("topicId", p.getTopicId());
            m.put("getTime", p.getCheckinTime());
            m.put("condition", CITY_MEDAL_CONDITION);
            medalsByTpl.put(tplId, m);
        }

        List<Map<String, Object>> medals = new ArrayList<>(medalsByTpl.values());

        // R8 合并成就徽章(player_badge:首步/连击/里程/通关/漫游里程碑),此前已发放却不在墙上展示。
        // 注:部分成就徽章图标(BADGE_META)暂为空,待设计补齐;前端对空 medalImg 用默认徽章底图。
        // 同一 badge_code 可按来源(如每个主题一枚 TOPIC_CLEAR)多次发放,墙上按 code 只展示一枚,与徽章墙 v2 同口径。
        java.util.Set<String> shownBadgeCodes = new java.util.HashSet<>();
        for (com.chengyinhub.business.domain.PlayerBadge b : playerBadgeMapper.selectListByMember(memberId)) {
            if (b.getBadgeCode() != null && !shownBadgeCodes.add(b.getBadgeCode())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("badgeCode", b.getBadgeCode());
            m.put("medalName", b.getBadgeName());
            m.put("medalImg", b.getIconUrl());
            // 成就徽章 v1 固定发光样式:样式定义位在 growth_badge.badge_style,发放快照(player_badge)未带;
            // 要按定义分流时在此 join growth_badge 或发放时写入快照,别在前端猜
            m.put("style", "glow");
            m.put("getTime", b.getObtainTime());
            m.put("kind", "achievement");   // 前端区分:成就徽章 vs 模板勋章
            medals.add(m);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", medals.size());
        data.put("medals", medals);
        return AjaxResult.success(data);
    }
}

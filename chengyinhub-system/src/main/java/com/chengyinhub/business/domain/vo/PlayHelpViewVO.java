package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.service.support.PlayHelpUsagePolicy.LevelView;

import java.util.List;

/**
 * GET /api/play/help 的回包:本局(原局)可用的帮助层级、每层免费/代价与不可用原因。
 *
 * <p>★ 不含任何提示正文、答案文本或答案表;内容只能由 POST /api/play/help/use 在获准使用后返回。
 * {@code enabled=false}(层级列表为空)表示该原局冻结配置里没有 {@code hintLadder},继续走既有
 * hint1/hint2 与揭示入口 —— BE-16「无阶梯配置即保持旧行为」。
 */
public record PlayHelpViewVO(
        long runId,
        long nodeId,
        boolean enabled,
        List<LevelView> layers) {
}

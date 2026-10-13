package com.chengyinhub.business.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 玩家预览三段 —— 07-23 P0 字段字典验收口径:<b>「开始、进行中、完成后三态齐全」</b>。
 *
 * <p>⚠️ 这是<b>体验描述</b>,不是玩法内容。指南第十四节明令「不得公开谜底、暗号或反作弊规则」,
 * 所以这三段的数据来源是运营录入的 cms_topic_template_meta.preview_*,
 * <b>绝不从 cms_member_template 的题目/答案字段派生</b>。
 *
 * @author chengyinhub
 * @date 2026-07-26
 */
public class TopicTemplatePlayerPreviewVO {

    @Schema(description = "开始前:玩家看到什么、怎么进入")
    private String before;

    @Schema(description = "进行中:过程是什么样")
    private String during;

    @Schema(description = "完成后:拿到什么、留下什么")
    private String after;

    public String getBefore() { return before; }
    public void setBefore(String before) { this.before = before; }
    public String getDuring() { return during; }
    public void setDuring(String during) { this.during = during; }
    public String getAfter() { return after; }
    public void setAfter(String after) { this.after = after; }
}

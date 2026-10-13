package com.chengyinhub.business.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 章节排他规则 —— 07-23 P0 字段字典验收口径:<b>「必须写区域、期限和原因」</b>。
 *
 * <p>⚠️ 这是<b>模板层的规则声明</b>,不是运行时的排他闸。当前没有任何代码依据本对象拒绝商家申请;
 * 章节招商的实际闸仍在 {@code ChapterRecruitmentRules} 与名额统计里。
 * 别把它当成已生效的约束读。
 *
 * @author chengyinhub
 * @date 2026-07-26
 */
public class TopicTemplateExclusivityVO {

    @Schema(description = "排他强度:HARD硬排他 / SOFT软排他 / NONE不限")
    private String mode;

    @Schema(description = "排他区域")
    private String scope;

    @Schema(description = "排他期限")
    private String period;

    @Schema(description = "排他原因")
    private String reason;

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}

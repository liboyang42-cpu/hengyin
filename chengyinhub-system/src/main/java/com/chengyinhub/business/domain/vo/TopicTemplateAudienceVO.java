package com.chengyinhub.business.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 核心人群与触发 —— 07-23 P0 字段字典验收口径:<b>「必须同时写人群、时刻和需求」</b>。
 *
 * <p>拆成三个字段而不是一段自由文本,正是为了让「只写了人群」这种半成品在接口上就看得出来。
 *
 * @author chengyinhub
 * @date 2026-07-26
 */
public class TopicTemplateAudienceVO {

    @Schema(description = "核心人群:是谁")
    private String who;

    @Schema(description = "核心触发:什么时刻")
    private String moment;

    @Schema(description = "核心需求:要解决什么")
    private String need;

    public String getWho() { return who; }
    public void setWho(String who) { this.who = who; }
    public String getMoment() { return moment; }
    public void setMoment(String moment) { this.moment = moment; }
    public String getNeed() { return need; }
    public void setNeed(String need) { this.need = need; }
}

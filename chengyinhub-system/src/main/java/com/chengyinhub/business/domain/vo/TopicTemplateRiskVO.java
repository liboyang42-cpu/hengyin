package com.chengyinhub.business.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 风险与资质 —— 07-23 P0 字段字典:可见范围「合作方,必要项对玩家公开」。
 *
 * <p>本轮整段随详情页下发。这些内容是模板的责任边界声明(取消规则、责任划分、安全提示),
 * 对玩家公开正是指南要求的「必要项」;不含任何商家身份或谈判金额。
 *
 * @author chengyinhub
 * @date 2026-07-26
 */
public class TopicTemplateRiskVO {

    @Schema(description = "风险标签列表")
    private List<String> tags;

    @Schema(description = "所需资质材料说明")
    private String qualifications;

    @Schema(description = "取消规则")
    private String cancellation;

    @Schema(description = "责任划分")
    private String liability;

    @Schema(description = "安全提示(对玩家公开)")
    private String safetyNotice;

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public String getQualifications() { return qualifications; }
    public void setQualifications(String qualifications) { this.qualifications = qualifications; }
    public String getCancellation() { return cancellation; }
    public void setCancellation(String cancellation) { this.cancellation = cancellation; }
    public String getLiability() { return liability; }
    public void setLiability(String liability) { this.liability = liability; }
    public String getSafetyNotice() { return safetyNotice; }
    public void setSafetyNotice(String safetyNotice) { this.safetyNotice = safetyNotice; }
}

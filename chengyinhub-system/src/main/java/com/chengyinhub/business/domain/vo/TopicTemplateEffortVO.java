package com.chengyinhub.business.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * 章节运行投入 —— 07-23 P0 字段字典验收口径:<b>「必须能判断最低开场条件」</b>。
 *
 * <p>可见范围按字典是「合作方」。本 VO 挂在章节的招商段上,与 {@code TopicTemplateRecruitVO}
 * 同一道闸(登录商家或本主题原发布者),不对匿名玩家下发 —— 这些是商家的成本口径,
 * 不是玩家该看的内容。
 *
 * <p>⚠️ 不含任何谈判金额(perHeadFee / quotaTotal 是 offer 侧字段,永不进模板 VO)。
 *
 * @author chengyinhub
 * @date 2026-07-26
 */
public class TopicTemplateEffortVO {

    @Schema(description = "准备工时(小时)")
    private BigDecimal prepHours;

    @Schema(description = "每场店员投入(分钟)")
    private Integer staffMinutes;

    @Schema(description = "场地要求")
    private String venue;

    @Schema(description = "物料要求")
    private String materials;

    @Schema(description = "最大同时接待人数")
    private Integer maxConcurrent;

    @Schema(description = "可用时段")
    private String availableSlots;

    @Schema(description = "是否需预约")
    private Boolean needsBooking;

    @Schema(description = "取消代价")
    private String cancelCost;

    @Schema(description = "资质要求")
    private String qualifications;

    public BigDecimal getPrepHours() { return prepHours; }
    public void setPrepHours(BigDecimal prepHours) { this.prepHours = prepHours; }
    public Integer getStaffMinutes() { return staffMinutes; }
    public void setStaffMinutes(Integer staffMinutes) { this.staffMinutes = staffMinutes; }
    public String getVenue() { return venue; }
    public void setVenue(String venue) { this.venue = venue; }
    public String getMaterials() { return materials; }
    public void setMaterials(String materials) { this.materials = materials; }
    public Integer getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(Integer maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    public String getAvailableSlots() { return availableSlots; }
    public void setAvailableSlots(String availableSlots) { this.availableSlots = availableSlots; }
    public Boolean getNeedsBooking() { return needsBooking; }
    public void setNeedsBooking(Boolean needsBooking) { this.needsBooking = needsBooking; }
    public String getCancelCost() { return cancelCost; }
    public void setCancelCost(String cancelCost) { this.cancelCost = cancelCost; }
    public String getQualifications() { return qualifications; }
    public void setQualifications(String qualifications) { this.qualifications = qualifications; }
}

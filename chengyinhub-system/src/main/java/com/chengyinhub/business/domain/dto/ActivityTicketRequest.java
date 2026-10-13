package com.chengyinhub.business.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import java.util.Objects;

@Schema(description = "活动票务请求参数")
public class ActivityTicketRequest implements Serializable {

    @Schema(description = "已有票种ID（更新时传入；已删除的票种会被忽略并新建）")
    private Long id;

    @Schema(description = "票务名称",  example = "早鸟票")
    @NotBlank(message = "票务名称不能为空")
    private String name;

    @Schema(description = "价格",  example = "199.00")
    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.00", message = "票务价格不能为负数")
    private BigDecimal price;

    @Schema(description = "票种履约/集合开始时间", example = "2024-06-01 09:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @NotNull(message = "票种履约开始时间不能为空")
    private Date startTime;

    @Schema(description = "票种履约结束时间（排他上界）",  example = "2025-08-15 09:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @NotNull(message = "票种履约结束时间不能为空")
    private Date endTime;

    @Schema(description = "售票开始时间（发布页填写；购票闸未接）", example = "2025-08-01 00:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date saleStartTime;

    @Schema(description = "售票结束时间（发布页填写；购票闸未接）", example = "2025-08-10 23:59:59")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date saleEndTime;

    @Schema(description = "总库存数量", example = "1000")
    @NotNull(message = "总库存不能为空")
    @Min(value = 0, message = "票务总库存不能为负数")
    private Integer totalStock;

    @Schema(description = "票务描述", example = "早鸟优惠，限量抢购")
    private String description;

    @Schema(description = "票种模式 1-经典定向 2-自由定向", example = "1")
    private Integer mode;

    @Schema(description = "集合地点（经典定向）", example = "人民广场")
    private String meetingPoint;

    @Schema(description = "集合点地址（地图选点文本）", example = "上海市黄浦区人民大道 1 号")
    private String meetingPointAddress;

    @Schema(description = "建议组队人数", example = "4")
    private Integer teamSize;

    @Schema(description = "最低成团人数(经典定向团;达到则成团)", example = "6")
    private Integer minPeople;

    @Schema(description = "成团人数上限")
    private Integer maxPeople;

    @Schema(description = "报名截止时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date signupDeadline;

    @Schema(description = "集合点经度")
    private BigDecimal gatherLng;

    @Schema(description = "集合点纬度")
    private BigDecimal gatherLat;

    @Schema(description = "退款规则(JSON/文本)")
    private String refundRule;

    @Schema(description = "支持随时退款（发布页填写；退款规则未接）")
    private Boolean refundSupported;

    @Schema(description = "售票时间与主题日期同步（发布页填写；同步逻辑未接）")
    private Boolean syncWithTheme;

    @Schema(description = "报名是否需主理人确认 0否1是")
    private Integer needAudit;

    // Getter 和 Setter 方法
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Date getStartTime() {
        return startTime;
    }

    public void setStartTime(Date startTime) {
        this.startTime = startTime;
    }

    public Date getEndTime() {
        return endTime;
    }

    public void setEndTime(Date endTime) {
        this.endTime = endTime;
    }

    public Date getSaleStartTime() {
        return saleStartTime;
    }

    public void setSaleStartTime(Date saleStartTime) {
        this.saleStartTime = saleStartTime;
    }

    public Date getSaleEndTime() {
        return saleEndTime;
    }

    public void setSaleEndTime(Date saleEndTime) {
        this.saleEndTime = saleEndTime;
    }

    public Integer getTotalStock() {
        return totalStock;
    }

    public void setTotalStock(Integer totalStock) {
        this.totalStock = totalStock;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getMode() {
        return mode;
    }

    public void setMode(Integer mode) {
        this.mode = mode;
    }

    public String getMeetingPoint() {
        return meetingPoint;
    }

    public void setMeetingPoint(String meetingPoint) {
        this.meetingPoint = meetingPoint;
    }

    public String getMeetingPointAddress() {
        return meetingPointAddress;
    }

    public void setMeetingPointAddress(String meetingPointAddress) {
        this.meetingPointAddress = meetingPointAddress;
    }

    public Integer getTeamSize() {
        return teamSize;
    }

    public void setTeamSize(Integer teamSize) {
        this.teamSize = teamSize;
    }

    public Integer getMinPeople() { return minPeople; }
    public void setMinPeople(Integer minPeople) { this.minPeople = minPeople; }
    public Integer getMaxPeople() { return maxPeople; }
    public void setMaxPeople(Integer maxPeople) { this.maxPeople = maxPeople; }
    public Date getSignupDeadline() { return signupDeadline; }
    public void setSignupDeadline(Date signupDeadline) { this.signupDeadline = signupDeadline; }
    public BigDecimal getGatherLng() { return gatherLng; }
    public void setGatherLng(BigDecimal gatherLng) { this.gatherLng = gatherLng; }
    public BigDecimal getGatherLat() { return gatherLat; }
    public void setGatherLat(BigDecimal gatherLat) { this.gatherLat = gatherLat; }
    public String getRefundRule() { return refundRule; }
    public void setRefundRule(String refundRule) { this.refundRule = refundRule; }
    public Boolean getRefundSupported() { return refundSupported; }
    public void setRefundSupported(Boolean refundSupported) { this.refundSupported = refundSupported; }
    public Boolean getSyncWithTheme() { return syncWithTheme; }
    public void setSyncWithTheme(Boolean syncWithTheme) { this.syncWithTheme = syncWithTheme; }
    public Integer getNeedAudit() { return needAudit; }
    public void setNeedAudit(Integer needAudit) { this.needAudit = needAudit; }

    @AssertTrue(message = "票务结束时间必须晚于开始时间")
    public boolean isTicketTimeWindowValid() {
        return startTime == null || endTime == null || endTime.after(startTime);
    }

    // equals 方法
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ActivityTicketRequest that = (ActivityTicketRequest) o;
        return Objects.equals(name, that.name) &&
                Objects.equals(price, that.price) &&
                Objects.equals(startTime, that.startTime) &&
                Objects.equals(endTime, that.endTime) &&
                Objects.equals(totalStock, that.totalStock) &&
                Objects.equals(description, that.description) &&
                Objects.equals(mode, that.mode) &&
                Objects.equals(meetingPoint, that.meetingPoint) &&
                Objects.equals(teamSize, that.teamSize);
    }

    // hashCode 方法
    @Override
    public int hashCode() {
        return Objects.hash(name, price, startTime, endTime, totalStock, description, mode, meetingPoint, teamSize);
    }

    // toString 方法
    @Override
    public String toString() {
        return "ActivityTicketRequest{" +
                "name='" + name + '\'' +
                ", price=" + price +
                ", startTime=" + startTime +
                ", endTime=" + endTime +
                ", totalStock=" + totalStock +
                ", description='" + description + '\'' +
                ", mode=" + mode +
                ", meetingPoint='" + meetingPoint + '\'' +
                ", teamSize=" + teamSize +
                '}';
    }

    // 无参构造函数
    public ActivityTicketRequest() {
    }

    // 全参构造函数（可选）
    public ActivityTicketRequest(String name, BigDecimal price, Date startTime,
                                 Date endTime, Integer totalStock, String description) {
        this.name = name;
        this.price = price;
        this.startTime = startTime;
        this.endTime = endTime;
        this.totalStock = totalStock;
        this.description = description;
    }
}

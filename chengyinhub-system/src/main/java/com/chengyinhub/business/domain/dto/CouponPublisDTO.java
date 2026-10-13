package com.chengyinhub.business.domain.dto;

import com.chengyinhub.common.annotation.Excel;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Date;

/**
 * 优惠券发布-请求参数
 */
public class CouponPublisDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "优惠券名称", example = "优惠券名称")
    @NotBlank(message = "优惠券名称不能为空")
    private String name;

    /** 开始时间 */
    @Schema(description = "开始时间", example = "")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @NotNull(message = "开始时间不能为空")
    private Date startTime;

    /** 结束时间 */
    @Schema(description = "结束时间", example = "")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @NotNull(message = "结束时间不能为空")
    private Date endTime;

    /** 发行数量 */
    @Schema(description = "发行数量", example = "")
    @NotNull(message = "发行数量不能为空")
    private Long publishCount;

    /** 优惠券类型：0礼品券 1、9折券  2、8折券 3、体验卡 */
    @Schema(description = "优惠券类型：0礼品券 1、9折券  2、8折券 3、体验卡", example = "0")
    @NotNull(message = "优惠券类型不能为空")
    private Integer couponType;

    @Schema(description = "优惠券描述", example = "优惠券描述")
    private String description;

    /** 显式业务作用域；商家工作台固定传 MERCHANT，空值保留历史俱乐部/个人链路。 */
    @Schema(description = "业务作用域，商家工作台传 MERCHANT", example = "MERCHANT")
    private String scope;


    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public Long getPublishCount() {
        return publishCount;
    }

    public void setPublishCount(Long publishCount) {
        this.publishCount = publishCount;
    }

    public Integer getCouponType() {
        return couponType;
    }

    public void setCouponType(Integer couponType) {
        this.couponType = couponType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

}

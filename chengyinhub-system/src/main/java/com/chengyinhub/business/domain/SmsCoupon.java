package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 优惠券对象 sms_coupon
 * 
 * @author webxh
 * @date 2025-09-01
 */
public class SmsCoupon extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 优惠券名称 */
    @Excel(name = "优惠券名称")
    private String name;

    /** 优惠券类型 */
    @Excel(name = "优惠券类型")
    private Integer couponType;

    /** 使用平台：0-&gt;全部；1-&gt;移动；2-&gt;PC */
    @Excel(name = "使用平台：0-&gt;全部；1-&gt;移动；2-&gt;PC")
    private Integer platform;

    /** 金额 / 折扣系数 */
    @Excel(name = "金额 / 折扣系数")
    private BigDecimal amount;

    /** 使用门槛（0.00表示无门槛） */
    @Excel(name = "使用门槛", readConverterExp = "0=.00表示无门槛")
    private BigDecimal minPoint;

    /** 开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "开始时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    /** 结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "结束时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    /** 使用类型：0-&gt;全场通用；1-&gt;指定分类；2-&gt;指定商品 */
    @Excel(name = "使用类型：0-&gt;全场通用；1-&gt;指定分类；2-&gt;指定商品")
    private Integer useType;

    /** 发行数量 */
    @Excel(name = "发行数量")
    private Long publishCount;

    /** 已使用数量 */
    @Excel(name = "已使用数量")
    private Long useCount;

    /** 领取数量 */
    @Excel(name = "领取数量")
    private Long receiveCount;

    /** 可以领取的日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "可以领取的日期", width = 30, dateFormat = "yyyy-MM-dd")
    private Date enableTime;

    /** 优惠码 */
    @Excel(name = "优惠码")
    private String code;

    /** 适用范围：0-&gt;平台优惠券；1-&gt;商家优惠券 */
    @Excel(name = "适用范围：0-&gt;平台优惠券；1-&gt;商家优惠券")
    private Integer scopeType;


    @Excel(name = "发布用户ID")
    private Long memberId;

    /** 如果scope_type=1（商家券），此字段记录所属商家ID */
    @Excel(name = "如果scope_type=1", readConverterExp = "商=家券")
    private Long merchantId;

    /** 状态：0-&gt;未开始；1-&gt;进行中；2-&gt;已结束；3-&gt;手动失效(平台,已领的也不可核销)；4-&gt;已停发(商家,不再新增发放,已领的照常可用可核销) */
    @Excel(name = "状态：0-&gt;未开始；1-&gt;进行中；2-&gt;已结束；3-&gt;手动失效；4-&gt;已停发")
    private Integer status;

    /** 删除标记 0 代表存在  2删除 */
    private Integer delFlag;

    /** 说明 */
    @Excel(name = "说明")
    private String description;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setName(String name) 
    {
        this.name = name;
    }

    public String getName() 
    {
        return name;
    }

    public void setCouponType(Integer couponType) 
    {
        this.couponType = couponType;
    }

    public Integer getCouponType() 
    {
        return couponType;
    }

    public void setPlatform(Integer platform) 
    {
        this.platform = platform;
    }

    public Integer getPlatform() 
    {
        return platform;
    }

    public void setAmount(BigDecimal amount) 
    {
        this.amount = amount;
    }

    public BigDecimal getAmount() 
    {
        return amount;
    }

    public void setMinPoint(BigDecimal minPoint) 
    {
        this.minPoint = minPoint;
    }

    public BigDecimal getMinPoint() 
    {
        return minPoint;
    }

    public void setStartTime(Date startTime) 
    {
        this.startTime = startTime;
    }

    public Date getStartTime() 
    {
        return startTime;
    }

    public void setEndTime(Date endTime) 
    {
        this.endTime = endTime;
    }

    public Date getEndTime() 
    {
        return endTime;
    }

    public void setUseType(Integer useType) 
    {
        this.useType = useType;
    }

    public Integer getUseType() 
    {
        return useType;
    }

    public void setPublishCount(Long publishCount) 
    {
        this.publishCount = publishCount;
    }

    public Long getPublishCount() 
    {
        return publishCount;
    }

    public void setUseCount(Long useCount) 
    {
        this.useCount = useCount;
    }

    public Long getUseCount() 
    {
        return useCount;
    }

    public void setReceiveCount(Long receiveCount) 
    {
        this.receiveCount = receiveCount;
    }

    public Long getReceiveCount() 
    {
        return receiveCount;
    }

    public void setEnableTime(Date enableTime) 
    {
        this.enableTime = enableTime;
    }

    public Date getEnableTime() 
    {
        return enableTime;
    }

    public void setCode(String code) 
    {
        this.code = code;
    }

    public String getCode() 
    {
        return code;
    }

    public void setScopeType(Integer scopeType) 
    {
        this.scopeType = scopeType;
    }

    public Integer getScopeType() 
    {
        return scopeType;
    }

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public void setMerchantId(Long merchantId) 
    {
        this.merchantId = merchantId;
    }

    public Long getMerchantId() 
    {
        return merchantId;
    }

    public void setStatus(Integer status) 
    {
        this.status = status;
    }

    public Integer getStatus() 
    {
        return status;
    }

    public void setDelFlag(Integer delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Integer getDelFlag() 
    {
        return delFlag;
    }

    public void setDescription(String description) 
    {
        this.description = description;
    }

    public String getDescription() 
    {
        return description;
    }

    /**
     * 是否「此刻在投放」：商家仍在发（status=1）且当前时间落在有效期内。
     *
     * <p>CU-M-51 / CU-M-86（2026-09-24 走查）：商家侧两处聚合计数原来只剔 {@code del_flag=2}，
     * 已停发（4）与未开始（0）的券也被算进卡面上那句「N 张在投放」。状态语义见 status 字段：
     * 2 已结束 / 3 手动失效 / 4 已停发都不该再算在投放。</p>
     *
     * @param now 判定时刻；传 null 取当前时间
     */
    public boolean onAir(Date now)
    {
        if (!Integer.valueOf(1).equals(status)) return false;
        Date at = now == null ? new Date() : now;
        if (startTime != null && at.before(startTime)) return false;
        return endTime == null || at.before(endTime);
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("name", getName())
            .append("couponType", getCouponType())
            .append("platform", getPlatform())
            .append("amount", getAmount())
            .append("minPoint", getMinPoint())
            .append("startTime", getStartTime())
            .append("endTime", getEndTime())
            .append("useType", getUseType())
            .append("publishCount", getPublishCount())
            .append("useCount", getUseCount())
            .append("receiveCount", getReceiveCount())
            .append("enableTime", getEnableTime())
            .append("code", getCode())
            .append("scopeType", getScopeType())
                .append("memberId", getMemberId())
            .append("merchantId", getMerchantId())
            .append("status", getStatus())
            .append("delFlag", getDelFlag())
            .append("description", getDescription())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}

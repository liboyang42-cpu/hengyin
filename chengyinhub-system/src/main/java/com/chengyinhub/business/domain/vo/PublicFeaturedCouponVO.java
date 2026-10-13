package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.SmsCoupon;

import java.math.BigDecimal;
import java.util.Date;

/** 商家公开主页的精选优惠券白名单。 */
public class PublicFeaturedCouponVO {

    private Long id;
    /** 卡片类型:2=优惠券。前端据此跳券夹(S19-d 同一份合同)。 */
    private Integer featuredType;
    /** 卡片指向的主体 ID(券 ID),与 id 同值,供前端按统一合同取用。 */
    private Long featuredId;
    private String name;
    private Integer couponType;
    private BigDecimal amount;
    private Date startTime;
    private Date endTime;
    private String description;

    public static PublicFeaturedCouponVO from(SmsCoupon coupon) {
        PublicFeaturedCouponVO vo = new PublicFeaturedCouponVO();
        vo.setId(coupon.getId());
        vo.setFeaturedType(2);
        vo.setFeaturedId(coupon.getId());
        vo.setName(coupon.getName());
        vo.setCouponType(coupon.getCouponType());
        vo.setAmount(coupon.getAmount());
        vo.setStartTime(coupon.getStartTime());
        vo.setEndTime(coupon.getEndTime());
        vo.setDescription(coupon.getDescription());
        return vo;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getFeaturedType() { return featuredType; }
    public void setFeaturedType(Integer featuredType) { this.featuredType = featuredType; }
    public Long getFeaturedId() { return featuredId; }
    public void setFeaturedId(Long featuredId) { this.featuredId = featuredId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getCouponType() { return couponType; }
    public void setCouponType(Integer couponType) { this.couponType = couponType; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Date getStartTime() { return startTime; }
    public void setStartTime(Date startTime) { this.startTime = startTime; }
    public Date getEndTime() { return endTime; }
    public void setEndTime(Date endTime) { this.endTime = endTime; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}

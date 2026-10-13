package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.SmsCoupon;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

/** 主题节点中的公开商家报名摘要，不包含答案、审核、分账和联系方式。 */
public class PublicMerchantRegistrationVO {
    private Long memberId;
    private String picUrl;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date startDate;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date endDate;
    private PublicMerchantSummaryVO mmsMerchant;
    /**
     * 这家商家在本节点给出的奖励券摘要。
     * 为空 = 没配券 / 券已结束失效 ⇒ 前端整块不渲染,不写「有优惠券」这种说不出内容的话。
     * 券实体由调用方查好传进来:本 VO 在 system 层,不该自己去碰 service。
     */
    private PublicCouponSummaryVO coupon;

    public static PublicMerchantRegistrationVO from(CmsRegistrationMerchant source) {
        return from(source, null);
    }

    /** @param coupon 调用方按 source.getCouponId() 查好传入(现调用点在循环内逐条查,与既有逐条查商家/模板同量级),没有就传 null */
    public static PublicMerchantRegistrationVO from(CmsRegistrationMerchant source, SmsCoupon coupon) {
        PublicMerchantRegistrationVO out = new PublicMerchantRegistrationVO();
        if (source != null) {
            out.setMemberId(source.getMemberId());
            out.setPicUrl(source.getPicUrl());
            out.setStartDate(source.getStartDate());
            out.setEndDate(source.getEndDate());
            out.setMmsMerchant(PublicMerchantSummaryVO.from(source.getMmsMerchant()));
            out.setCoupon(PublicCouponSummaryVO.from(coupon));
        }
        return out;
    }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getPicUrl() { return picUrl; }
    public void setPicUrl(String picUrl) { this.picUrl = picUrl; }
    public Date getStartDate() { return startDate; }
    public void setStartDate(Date startDate) { this.startDate = startDate; }
    public Date getEndDate() { return endDate; }
    public void setEndDate(Date endDate) { this.endDate = endDate; }
    public PublicMerchantSummaryVO getMmsMerchant() { return mmsMerchant; }
    public void setMmsMerchant(PublicMerchantSummaryVO mmsMerchant) { this.mmsMerchant = mmsMerchant; }
    public PublicCouponSummaryVO getCoupon() { return coupon; }
    public void setCoupon(PublicCouponSummaryVO coupon) { this.coupon = coupon; }
}

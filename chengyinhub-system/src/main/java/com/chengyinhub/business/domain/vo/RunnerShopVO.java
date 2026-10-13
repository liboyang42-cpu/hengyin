package com.chengyinhub.business.domain.vo;

/** 「TA 点亮的店」那一排照片。店名与封面都由服务端从来源表读,不采信客户端。 */
public class RunnerShopVO {
    private Long memberId;
    private String shopName;
    private String shopImage;

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }
    public String getShopImage() { return shopImage; }
    public void setShopImage(String shopImage) { this.shopImage = shopImage; }
}

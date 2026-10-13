package com.chengyinhub.business.domain.vo;

/** 「谁在这儿打过卡」的一张头像。只带头像,不带 memberId / 昵称 —— 商家卡上那一行不是社交入口。 */
public class ShopVisitorAvatarVO {
    private Long sourceId;
    private String avatar;

    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
}

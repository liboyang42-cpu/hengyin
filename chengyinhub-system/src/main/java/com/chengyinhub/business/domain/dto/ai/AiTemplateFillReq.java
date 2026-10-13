package com.chengyinhub.business.domain.dto.ai;

/**
 * AI 自动填表请求(玩法模板配置库 §10 升级1):
 * 商家输入店名/品类/奖励,生成玩法标题/规则/问题/剧情/配图提示等模板全字段。
 */
public class AiTemplateFillReq
{
    /** 店名(必填) */
    private String shopName;
    /** 品类(咖啡店/酒吧/书店…) */
    private String category;
    /** 奖励说明(隐藏饮品/8折券/勋章…) */
    private String reward;
    /** 可选:参考玩法(隐藏菜单/店内寻宝/拍照挑战…,来自模板库标题) */
    private String playStyle;
    /** 可选:验证方式 1文字 2拍照 3选项 0/null=让AI选 */
    private Integer validationMethod;
    /** 可选:商家补充(店内特色细节等) */
    private String extraNote;

    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getReward() { return reward; }
    public void setReward(String reward) { this.reward = reward; }
    public String getPlayStyle() { return playStyle; }
    public void setPlayStyle(String playStyle) { this.playStyle = playStyle; }
    public Integer getValidationMethod() { return validationMethod; }
    public void setValidationMethod(Integer validationMethod) { this.validationMethod = validationMethod; }
    public String getExtraNote() { return extraNote; }
    public void setExtraNote(String extraNote) { this.extraNote = extraNote; }
}

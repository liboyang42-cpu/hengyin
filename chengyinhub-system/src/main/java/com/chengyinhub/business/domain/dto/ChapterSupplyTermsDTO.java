package com.chengyinhub.business.domain.dto;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * [D21] ③ 章节的**供给条款配置** —— 后台运营配这一章「商家怎么参与、平台掏不掏钱、掏多少封顶」。
 *
 * <p><b>为什么这四个字段必须一起配</b>:它们不是四个独立开关,是**一套形状**。
 * {@code terms_mode} 一变,另外几个的合法性跟着变(计酬档没有上限 = 商家入驻恒被拒;
 * 引流档挂着权益门槛 = 一行说不清它到底承诺没承诺权益的数据)。分开配就必然配出
 * enroll 不认的组合,而那时报错的是**商家**,不是配错的运营。
 *
 * <p>⚠️ <b>三个字段的「空」含义刻意不同</b>,别统一(与 {@code MerchantOfferServiceImpl} 的
 * 三道校验逐字对齐 —— 后台的口径与 enroll 的口径必须是同一份,漂了就是后台配得进、商家进不来):
 * <ul>
 *   <li>{@link #perkMinValue} 空 = <b>本章不设门槛</b>(放行)。它是**质量门槛**:
 *       商家承诺自己的东西,不设 = 不挑。</li>
 *   <li>{@link #maxPerHeadFee} 空 = <b>拒</b>(fail-closed)。它是**平台成本上界**:
 *       留空 = 敞口无限。计酬档是平台按核销真金白银付,而 per_head_fee 由商家 enroll 时**自报**,
 *       P0-50 必亏闸只挂在定价时、对事后新增/改价的 offer 不复查 ⇒ 没有它,中标商家可自挂天价,
 *       平台照付且零告警。</li>
 *   <li>{@link #maxMerchant} 0 = <b>不限家数</b>（必填，负数非法，最大127）。它与档位**正交**(enroll 里
 *       assertMaxMerchant 在档位分支**之外**),三档都能配。</li>
 * </ul>
 */
public class ChapterSupplyTermsDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** PERK 权益档 / REVSHARE 计酬档 / TRAFFIC 引流档。必填 */
    private String termsMode;

    /** 权益档的零售价门槛;空 = 不设门槛。⚠️ 非权益档必须为空 */
    private BigDecimal perkMinValue;

    /** 计酬档的每人次上限;⚠️ <b>计酬档必填且 >0</b>(空 = 商家入驻恒被拒);非计酬档必须为空 */
    private BigDecimal maxPerHeadFee;

    /** 本章承接商家数上限;必填，0=不限，取值1到127。三档通用 */
    private Integer maxMerchant;

    public String getTermsMode() {
        return termsMode;
    }

    public void setTermsMode(String termsMode) {
        this.termsMode = termsMode;
    }

    public BigDecimal getPerkMinValue() {
        return perkMinValue;
    }

    public void setPerkMinValue(BigDecimal perkMinValue) {
        this.perkMinValue = perkMinValue;
    }

    public BigDecimal getMaxPerHeadFee() {
        return maxPerHeadFee;
    }

    public void setMaxPerHeadFee(BigDecimal maxPerHeadFee) {
        this.maxPerHeadFee = maxPerHeadFee;
    }

    public Integer getMaxMerchant() {
        return maxMerchant;
    }

    public void setMaxMerchant(Integer maxMerchant) {
        this.maxMerchant = maxMerchant;
    }
}

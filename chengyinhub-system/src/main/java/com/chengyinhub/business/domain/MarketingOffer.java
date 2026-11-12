package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 促销供给:组合包(COMBO)。表 marketing_offer。
 *
 * <p><b>形状</b>:一个价买一组票。打包价替换成员票面合计;成员构成由服务端逐项与购物车比对,
 * 不接受客户端自报"这单属于某组合包"。
 *
 * <p><b>礼品不在这里</b>(2026-11-08 root 裁定):礼品独立售价由 M3 的真实礼品 SKU
 * ({@code MarketingProduct.KIND_GIFT_PASS} + {@code giftAllowed}/{@code giftClaimDays}/{@code cashPrice})
 * 唯一提供。M4 只做促销,不另留一个"报名链没有收礼人来源"的票级礼品变体 ——
 * 那会是同一件事的第二真源。促销可以作用在 M3 礼品 SKU 的报价上,但仍走本表的组合包/阶梯/触发优惠。
 *
 * <p><b>不做什么</b>:不新建钱包/币种(金额一律人民币元);不承接 pass/城市包/年卡的商品定义
 * (那是 M3 的商品域,M3 把 pass 作为票种售出时本表可把那张票当成员);不参与分润与退款政策。
 */
public class MarketingOffer extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 组合包:一组票的打包价。 */
    public static final int KIND_COMBO = 1;

    /** 0=停用 1=启用 2=下架(历史单不受影响)。 */
    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_RETIRED = 2;

    private Long id;
    /** 稳定业务码:客户端只回传它,不回传价格。 */
    private String offerCode;
    private Integer offerKind;
    private String name;
    private String description;
    /** 适用范围:1=主题 2=活动(与 cms_registration.owner_type 对齐)。 */
    private Integer ownerType;
    private Long ownerId;
    /** 打包价。 */
    private BigDecimal priceAmount;
    /** 配置时冻结的成员标价合计:只用于校验"打包价不高于分买"和展示省多少,不参与报价计算。 */
    private BigDecimal listAmount;
    private Integer status;
    private Date validFrom;
    private Date validTo;
    /** 同一会员可购买的次数上限。 */
    private Integer maxPerMember;
    /** 规则版本:报价签名与订单快照都带它,配置改版不会把旧单读成新价。 */
    private Integer ruleVersion;
    private Integer delFlag;

    /** 瞬态:组合包的成员行,仅回读时填充。 */
    private List<MarketingOfferItem> items;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOfferCode() { return offerCode; }
    public void setOfferCode(String offerCode) { this.offerCode = offerCode; }

    public Integer getOfferKind() { return offerKind; }
    public void setOfferKind(Integer offerKind) { this.offerKind = offerKind; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getOwnerType() { return ownerType; }
    public void setOwnerType(Integer ownerType) { this.ownerType = ownerType; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public BigDecimal getPriceAmount() { return priceAmount; }
    public void setPriceAmount(BigDecimal priceAmount) { this.priceAmount = priceAmount; }

    public BigDecimal getListAmount() { return listAmount; }
    public void setListAmount(BigDecimal listAmount) { this.listAmount = listAmount; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Date getValidFrom() { return validFrom; }
    public void setValidFrom(Date validFrom) { this.validFrom = validFrom; }

    public Date getValidTo() { return validTo; }
    public void setValidTo(Date validTo) { this.validTo = validTo; }

    public Integer getMaxPerMember() { return maxPerMember; }
    public void setMaxPerMember(Integer maxPerMember) { this.maxPerMember = maxPerMember; }

    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }

    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }

    public List<MarketingOfferItem> getItems() { return items; }
    public void setItems(List<MarketingOfferItem> items) { this.items = items; }
}

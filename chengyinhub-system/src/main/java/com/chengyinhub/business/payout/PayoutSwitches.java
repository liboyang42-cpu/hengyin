package com.chengyinhub.business.payout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * l-payout 资金开关。全部默认关闭；关闭时本包任何代码都不会向微信发起请求，也不改变现有下单、退款、结算结果。
 *
 * <ul>
 *   <li>{@code payout.ecommerce.enabled}：电商收付通总开关（二级商户进件与状态刷新）。</li>
 *   <li>{@code payout.ecommerce.seller-routing-enabled}：商家/俱乐部卖出的门票以卖家 sub_mchid 下单。
 *       需要总开关同时打开。</li>
 *   <li>{@code payout.ecommerce.profit-sharing-enabled}：结算后按内部账本向微信请求分账、退款前分账回退。
 *       需要总开关同时打开。</li>
 *   <li>{@code payout.creator.enabled} + {@code payout.creator.provider}：创作者经外部打款服务商（灵活用工）打款。</li>
 *   <li>{@code payout.ecommerce.combined-enabled} + {@code payout.ecommerce.platform-seller-sub-mchid}（l-payout3）：
 *       钱属于多个商家 / 俱乐部的门票改用「合单支付」，按收款方拆成子单；结算后才知道的份额放在平台自营子单里，
 *       经供应商结算付给对方（不做商家之间的直接分账）。需要总开关与下单路由开关同时打开。</li>
 * </ul>
 */
@Component
public class PayoutSwitches {

    private final boolean ecommerceEnabled;
    private final boolean sellerRoutingEnabled;
    private final boolean profitSharingEnabled;
    private final String spMchid;
    private final String spAppid;
    private final String platformReceiverMchid;
    private final int maxSharePercent;
    private final boolean creatorEnabled;
    private final String creatorProvider;
    private final boolean combinedEnabled;
    private final String platformSellerSubMchid;
    @Value("${payout.ecommerce.personal-club-enabled:false}")
    private boolean personalClubEnabled;

    /** l-payout / l-payout2 的原构造（不含 l-payout3 合单开关，合单视为关闭）。 */
    public PayoutSwitches(boolean ecommerceEnabled, boolean sellerRoutingEnabled, boolean profitSharingEnabled,
                          String spMchid, String spAppid, String platformReceiverMchid, int maxSharePercent,
                          boolean creatorEnabled, String creatorProvider) {
        this(ecommerceEnabled, sellerRoutingEnabled, profitSharingEnabled, spMchid, spAppid, platformReceiverMchid,
                maxSharePercent, creatorEnabled, creatorProvider, false, "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PayoutSwitches(
            @Value("${payout.ecommerce.enabled:false}") boolean ecommerceEnabled,
            @Value("${payout.ecommerce.seller-routing-enabled:false}") boolean sellerRoutingEnabled,
            @Value("${payout.ecommerce.profit-sharing-enabled:false}") boolean profitSharingEnabled,
            @Value("${payout.ecommerce.sp-mchid:}") String spMchid,
            @Value("${payout.ecommerce.sp-appid:}") String spAppid,
            @Value("${payout.ecommerce.platform-receiver-mchid:}") String platformReceiverMchid,
            @Value("${payout.ecommerce.max-share-percent:30}") int maxSharePercent,
            @Value("${payout.creator.enabled:false}") boolean creatorEnabled,
            @Value("${payout.creator.provider:none}") String creatorProvider,
            @Value("${payout.ecommerce.combined-enabled:false}") boolean combinedEnabled,
            @Value("${payout.ecommerce.platform-seller-sub-mchid:}") String platformSellerSubMchid) {
        this.ecommerceEnabled = ecommerceEnabled;
        this.sellerRoutingEnabled = sellerRoutingEnabled;
        this.profitSharingEnabled = profitSharingEnabled;
        this.spMchid = trim(spMchid);
        this.spAppid = trim(spAppid);
        this.platformReceiverMchid = trim(platformReceiverMchid);
        this.maxSharePercent = Math.max(0, Math.min(100, maxSharePercent));
        this.creatorEnabled = creatorEnabled;
        this.creatorProvider = trim(creatorProvider).isEmpty() ? "none" : trim(creatorProvider);
        this.combinedEnabled = combinedEnabled;
        this.platformSellerSubMchid = trim(platformSellerSubMchid);
    }

    /** 全部关闭（测试与默认装配用）。 */
    public static PayoutSwitches allOff() {
        return new PayoutSwitches(false, false, false, "", "", "", 30, false, "none");
    }

    public boolean ecommerceEnabled() { return ecommerceEnabled && !spMchid.isEmpty(); }
    public boolean sellerRoutingEnabled() { return ecommerceEnabled() && sellerRoutingEnabled && !spAppid.isEmpty(); }
    public boolean profitSharingEnabled() { return ecommerceEnabled() && profitSharingEnabled; }
    public String spMchid() { return spMchid; }
    public boolean personalClubEnabled() { return profitSharingEnabled() && personalClubEnabled && !spAppid.isEmpty(); }
    /** Explicit opt-in for isolated wiring tests; production uses the default-OFF property. */
    public PayoutSwitches withPersonalClubEnabled(boolean enabled) { this.personalClubEnabled = enabled; return this; }
    public String spAppid() { return spAppid; }
    /** 平台佣金的分账接收方；未配置时回落到 sp_mchid（电商平台自身）。 */
    public String platformReceiverMchid() { return platformReceiverMchid.isEmpty() ? spMchid : platformReceiverMchid; }
    public int maxSharePercent() { return maxSharePercent; }
    public boolean creatorEnabled() { return creatorEnabled && !"none".equals(creatorProvider); }
    public String creatorProvider() { return creatorProvider; }
    /**
     * l-payout3 合单开关：需要下单路由与分账开关（卖家子单靠完结分账解冻），并配置了平台自营二级商户号（与 sp_mchid 不同、格式合法）。
     * 为假时不读合单表、不调合单接口，所有单照 l-payout2 / 平台路径处理。
     */
    public boolean combinedEnabled() {
        return sellerRoutingEnabled() && profitSharingEnabled() && combinedEnabled && platformSellerSubMchid.matches("[0-9]{6,32}")
                && !platformSellerSubMchid.equals(spMchid);
    }
    /** 平台自营二级商户号：合单里「平台为卖家」子单的收款主体。 */
    public String platformSellerSubMchid() { return platformSellerSubMchid; }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
}

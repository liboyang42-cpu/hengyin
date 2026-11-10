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

    public PayoutSwitches(
            @Value("${payout.ecommerce.enabled:false}") boolean ecommerceEnabled,
            @Value("${payout.ecommerce.seller-routing-enabled:false}") boolean sellerRoutingEnabled,
            @Value("${payout.ecommerce.profit-sharing-enabled:false}") boolean profitSharingEnabled,
            @Value("${payout.ecommerce.sp-mchid:}") String spMchid,
            @Value("${payout.ecommerce.sp-appid:}") String spAppid,
            @Value("${payout.ecommerce.platform-receiver-mchid:}") String platformReceiverMchid,
            @Value("${payout.ecommerce.max-share-percent:30}") int maxSharePercent,
            @Value("${payout.creator.enabled:false}") boolean creatorEnabled,
            @Value("${payout.creator.provider:none}") String creatorProvider) {
        this.ecommerceEnabled = ecommerceEnabled;
        this.sellerRoutingEnabled = sellerRoutingEnabled;
        this.profitSharingEnabled = profitSharingEnabled;
        this.spMchid = trim(spMchid);
        this.spAppid = trim(spAppid);
        this.platformReceiverMchid = trim(platformReceiverMchid);
        this.maxSharePercent = Math.max(0, Math.min(100, maxSharePercent));
        this.creatorEnabled = creatorEnabled;
        this.creatorProvider = trim(creatorProvider).isEmpty() ? "none" : trim(creatorProvider);
    }

    /** 全部关闭（测试与默认装配用）。 */
    public static PayoutSwitches allOff() {
        return new PayoutSwitches(false, false, false, "", "", "", 30, false, "none");
    }

    public boolean ecommerceEnabled() { return ecommerceEnabled && !spMchid.isEmpty(); }
    public boolean sellerRoutingEnabled() { return ecommerceEnabled() && sellerRoutingEnabled && !spAppid.isEmpty(); }
    public boolean profitSharingEnabled() { return ecommerceEnabled() && profitSharingEnabled; }
    public String spMchid() { return spMchid; }
    public String spAppid() { return spAppid; }
    /** 平台佣金的分账接收方；未配置时回落到 sp_mchid（电商平台自身）。 */
    public String platformReceiverMchid() { return platformReceiverMchid.isEmpty() ? spMchid : platformReceiverMchid; }
    public int maxSharePercent() { return maxSharePercent; }
    public boolean creatorEnabled() { return creatorEnabled && !"none".equals(creatorProvider); }
    public String creatorProvider() { return creatorProvider; }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
}

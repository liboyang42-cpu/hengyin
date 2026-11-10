package com.chengyinhub.business.payout;

/**
 * 谁是这笔订单的卖家（收款主体）。纯函数，不读库、不调微信。
 *
 * <p>规则（DECISIONS-payout.md，2026-11-10 业主）：
 * <ul>
 *   <li>官方主题、通行证、平台商品、玩法模板、商家采购、协作保证金：平台是卖家，照旧走平台商户号。</li>
 *   <li>商家或俱乐部卖出的门票（主题报名/活动报名）以及俱乐部会费：卖家已完成二级商户进件（FINISH 且有 sub_mchid）
 *       且下单路由开关打开时，以卖家 sub_mchid 下单；否则照旧平台下单。</li>
 * </ul>
 * 任何不确定都回落到平台下单（即现有行为），绝不猜测卖家。
 */
public final class SellerOfRecordPolicy {

    public enum ProductKind {
        TOPIC_TICKET, ACTIVITY_TICKET, CLUB_DUES,
        PLATFORM_PASS, MARKETING_PRODUCT, WORKSHOP_TEMPLATE, MERCHANT_PURCHASE, COOP_DEPOSIT
    }

    public enum SellerType { PLATFORM, MERCHANT, CLUB, MEMBER, UNKNOWN }

    /** 下单时能拿到的卖家事实。 */
    public record Facts(ProductKind kind, boolean platformOwned, SellerType sellerType, Long sellerId,
                        String onboardingState, String subMchid) { }

    public record Decision(boolean subMerchant, String subMchid, SellerType sellerType, Long sellerId, String reason) {
        static Decision platform(String reason) { return new Decision(false, null, SellerType.PLATFORM, null, reason); }
    }

    private SellerOfRecordPolicy() { }

    public static Decision decide(Facts facts, PayoutSwitches switches) {
        if (switches == null || !switches.sellerRoutingEnabled()) return Decision.platform("SWITCH_OFF");
        if (facts == null || facts.kind() == null) return Decision.platform("NO_FACTS");
        switch (facts.kind()) {
            case TOPIC_TICKET: case ACTIVITY_TICKET: case CLUB_DUES: break;
            default: return Decision.platform("PLATFORM_PRODUCT");
        }
        if (facts.platformOwned()) return Decision.platform("OFFICIAL_THEME");
        if (facts.sellerType() != SellerType.MERCHANT && facts.sellerType() != SellerType.CLUB)
            return Decision.platform("SELLER_NOT_MERCHANT_OR_CLUB");
        if (facts.sellerId() == null || facts.sellerId() <= 0) return Decision.platform("SELLER_UNKNOWN");
        if (!ApplymentState.FINISH.name().equals(facts.onboardingState())
                || facts.subMchid() == null || !facts.subMchid().matches("[0-9]{6,32}"))
            return Decision.platform("SELLER_NOT_ONBOARDED");
        if (facts.subMchid().equals(switches.spMchid())) return Decision.platform("SUB_MCHID_IS_PLATFORM");
        return new Decision(true, facts.subMchid(), facts.sellerType(), facts.sellerId(), "SUB_MERCHANT");
    }
}

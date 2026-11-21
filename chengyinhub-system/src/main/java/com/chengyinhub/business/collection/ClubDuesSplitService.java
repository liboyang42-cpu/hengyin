package com.chengyinhub.business.collection;

import com.chengyinhub.business.collection.mapper.ClubDuesSplitMapper;
import com.chengyinhub.business.collection.mapper.CollectionModelMapper;
import com.chengyinhub.business.domain.ClubMembershipInvoice;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.merchantplans.TakeRateService;
import com.chengyinhub.business.payout.EcommerceProfitShareService;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.business.service.PaymentPreorderException;
import com.chengyinhub.business.service.impl.EcommercePartnerPaymentAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

/**
 * K1 club dues through split settlement (CLB-07, MON-11). Mirrors the S2 ticket path:
 * <ol>
 *   <li><b>Order.</b> While the collection model is active and the club has a finished sub-merchant, a dues invoice is
 *       paid by the member directly to the club's own sub-merchant (service-provider JSAPI, profit sharing on). The ledger
 *       row ({@code cy_collection_dues_order}) with the platform take rate frozen from {@link TakeRateService#clubDuesRate}
 *       is written before any WeChat call. Nothing is ever credited to a member balance.</li>
 *   <li><b>Share.</b> After the existing hold ({@code FundsHoldPolicy.WITHDRAWABLE_AFTER_END_DAYS} after payment, no
 *       pending refund review, no open refund), one profit-sharing order takes the platform's take as the only receiver;
 *       the rest stays with the club (RETAINED).</li>
 *   <li><b>Refund.</b> The existing partner refund path ({@code EcommercePartnerPaymentAdapter#submitRefund}) returns the
 *       shared take first, then refunds from the club's sub-merchant.</li>
 * </ol>
 * With the model off nothing here runs: the membership service keeps today's path, and {@link #planDue} reads nothing
 * unless the profit-sharing rails are on.
 */
@Service
public class ClubDuesSplitService {
    private static final Logger log = LoggerFactory.getLogger(ClubDuesSplitService.class);

    public static final String OPEN = "OPEN";
    public static final String PLANNED = "PLANNED";
    public static final String REFUNDED = "REFUNDED";
    /** Profit-sharing source id for dues ({@code ecom_profit_share_order.topic_id}); out_trade_no (CLM…) is unique. */
    public static final long DUES_SOURCE_ID = 0L;

    private final CollectionModelService model;
    private final ClubDuesSplitMapper mapper;
    private final CollectionModelMapper refunds;
    private final PayoutSwitches switches;
    private final EcommerceProfitShareService profitSharing;
    private final TakeRateService takeRates;

    @Autowired(required = false) private EcommercePartnerPaymentAdapter partner;
    /** Platform merchant id, only to detect an attempt already started on the platform account (never to collect). */
    @Autowired(required = false) private com.chengyinhub.common.config.WxPayConfig wxPayConfig;

    public ClubDuesSplitService(CollectionModelService model, ClubDuesSplitMapper mapper, CollectionModelMapper refunds,
                                PayoutSwitches switches, EcommerceProfitShareService profitSharing,
                                TakeRateService takeRates) {
        this.model = model;
        this.mapper = mapper;
        this.refunds = refunds;
        this.switches = switches;
        this.profitSharing = profitSharing;
        this.takeRates = takeRates;
    }

    void setPartner(EcommercePartnerPaymentAdapter partner) { this.partner = partner; }

    /**
     * Places one dues invoice on the club's sub-merchant. The caller has already passed
     * {@link CollectionModelService#requireClubDuesCollectible} and got a SPLIT decision. The take rate is frozen on the
     * ledger row before WeChat is called; a missing rate is stored as null and the share goes to manual review later.
     */
    public Map<String, String> createOrder(ClubMembershipInvoice invoice, CollectionDecision split, String openid) {
        if (invoice == null || split == null || !split.split() || partner == null || !partner.enabled())
            throw PaymentPreorderException.definiteFailure("卖家收款暂不可用，请稍后再试", null);
        long totalFen = invoice.getAmountFen() == null ? 0L : invoice.getAmountFen();
        BigDecimal rate = frozenRate();
        return partner.createDuesOrder(invoice.getOutTradeNo(), totalFen, "俱乐部会费-" + invoice.getPlanNameSnapshot(),
                openid, split, wxPayConfig == null ? null : wxPayConfig.getMerchantId(), seller -> mapper.insertOrder(invoice.getOutTradeNo(), invoice.getId(),
                        invoice.getClubId(), seller.getSubMchid(), totalFen, rate));
    }

    private BigDecimal frozenRate() {
        try {
            return takeRates == null ? null : takeRates.clubDuesRate();
        } catch (RuntimeException unreadable) {
            log.warn("club dues take rate unreadable type={}", unreadable.getClass().getSimpleName());
            return null;
        }
    }

    /** Was this dues invoice paid to a club sub-merchant? False, with no read, while neither the model nor the rails are on. */
    public boolean isSplitCollected(String outTradeNo) {
        boolean guard = (model != null && model.active()) || (switches != null && switches.ecommerceEnabled());
        if (!guard || outTradeNo == null) return false;
        return mapper.countByOutTradeNo(outTradeNo) > 0;
    }

    /** Plans profit sharing for dues past the hold. Returns how many were planned. Reads nothing while sharing is off. */
    public int planDue(int limit) {
        if (switches == null || !switches.profitSharingEnabled()) return 0;
        int planned = 0;
        for (Map<String, Object> row : mapper.selectDueOrders(new Date(), Math.max(1, Math.min(limit, 200)))) {
            try {
                if (planOne(row)) planned++;
            } catch (RuntimeException e) {
                log.warn("club dues split plan failed outTradeNo={} type={}", safe(str(row.get("outTradeNo"))),
                        e.getClass().getSimpleName());
            }
        }
        return planned;
    }

    boolean planOne(Map<String, Object> row) {
        String no = str(row.get("outTradeNo"));
        long total = num(row.get("totalFen"));
        if (refunds.countOpenRefunds(no) > 0) return false; // refunds first: plan after the refund settles
        Long refunded = refunds.sumSucceededRefundFen(no);
        if (refunded != null && refunded >= total) {
            mapper.updateOrderState(no, OPEN, REFUNDED, null, "FULLY_REFUNDED");
            return false;
        }
        BigDecimal rate = rate(row.get("takeRate"));
        long take = CollectionPolicyEngine.platformTakeFen(total, rate);
        Long shareOrderId = profitSharing.planSingleSeller(DUES_SOURCE_ID, trade(row), Math.max(take, 0),
                take < 0 ? "TAKE_RATE_MISSING" : null);
        if (shareOrderId == null) return false;
        return mapper.updateOrderState(no, OPEN, PLANNED, shareOrderId, null) == 1;
    }

    /** Operations: re-plan a dues share order in manual review with the take recomputed from the frozen rate. */
    public String replan(String outTradeNo) {
        Map<String, Object> row = outTradeNo == null ? null : mapper.selectOrder(outTradeNo);
        if (row == null || row.get("shareOrderId") == null) return "NOT_A_SPLIT_PLAN";
        long take = CollectionPolicyEngine.platformTakeFen(num(row.get("totalFen")), rate(row.get("takeRate")));
        if (take < 0) return "TAKE_RATE_MISSING";
        EcomOrderSeller trade = trade(row);
        trade.setTransactionId(refunds.selectTransactionId(outTradeNo));
        return profitSharing.replanSingleSeller(num(row.get("shareOrderId")), trade, take);
    }

    static EcomOrderSeller trade(Map<String, Object> row) {
        EcomOrderSeller trade = new EcomOrderSeller();
        trade.setOutTradeNo(str(row.get("outTradeNo")));
        trade.setSubMchid(str(row.get("subMchid")));
        trade.setSellerType("CLUB");
        trade.setSellerId(num(row.get("clubId")));
        trade.setTotalFen(num(row.get("totalFen")));
        trade.setTransactionId(str(row.get("transactionId")));
        return trade;
    }

    private static BigDecimal rate(Object v) {
        if (v instanceof BigDecimal r) return r;
        return v == null ? null : new BigDecimal(v.toString());
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }

    private static long num(Object v) {
        if (v instanceof Number n) return n.longValue();
        return v == null ? 0L : Long.parseLong(v.toString());
    }

    private static String safe(String v) { return v != null && v.matches("[A-Za-z0-9_-]{1,64}") ? v : "UNKNOWN"; }
}

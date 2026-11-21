package com.chengyinhub.business.collection;

import com.chengyinhub.business.collection.mapper.CollectionModelMapper;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.payout.EcommerceProfitShareService;
import com.chengyinhub.business.payout.PayoutSwitches;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

/**
 * S2 collection model, settlement side. A ticket collected by the seller's sub-merchant ({@code cy_collection_order}):
 * <ul>
 *   <li>is left out of every member-balance (wallet) settlement: {@link #splitSalesForActivity} is subtracted by the
 *       activity settlement, and {@link #isSplitCollected} keeps the refund claw-back off the wallet;</li>
 *   <li>after the existing T+N hold, gets one profit-sharing order whose only receiver is the platform's take
 *       ({@link #planDue}); the same request unfreezes the rest for the seller;</li>
 *   <li>on refund, the existing partner refund path returns the shared take first, then refunds
 *       ({@code EcommercePartnerPaymentAdapter#submitRefund}).</li>
 * </ul>
 * Reads nothing while both the model and the 收付通 rails are off (no split order can exist then).
 */
@Service
public class CollectionSplitSettlementService {
    private static final Logger log = LoggerFactory.getLogger(CollectionSplitSettlementService.class);

    public static final String OPEN = "OPEN";
    public static final String PLANNED = "PLANNED";
    public static final String REFUNDED = "REFUNDED";
    public static final String COOP = "COOP";

    private final CollectionModelService model;
    private final CollectionModelMapper mapper;
    private final PayoutSwitches switches;
    private final EcommerceProfitShareService profitSharing;
    private final CoopOrderMapper coopOrders;

    public CollectionSplitSettlementService(CollectionModelService model, CollectionModelMapper mapper, PayoutSwitches switches,
                                            EcommerceProfitShareService profitSharing, CoopOrderMapper coopOrders) {
        this.model = model;
        this.mapper = mapper;
        this.switches = switches;
        this.profitSharing = profitSharing;
        this.coopOrders = coopOrders;
    }

    /** True when split orders may exist: the model is active, or the 收付通 rails are on (orders placed earlier). */
    public boolean guardActive() {
        return (model != null && model.active()) || (switches != null && switches.ecommerceEnabled());
    }

    /**
     * Activity ticket money the seller's sub-merchant collected: paid-not-held plus retained after partial refunds,
     * on the same terms as the wallet settlement sums. Zero, with no read, while {@link #guardActive()} is false.
     */
    public BigDecimal splitSalesForActivity(Long activityId) {
        if (!guardActive() || activityId == null) return BigDecimal.ZERO;
        BigDecimal paid = mapper.sumSplitPaidSalesForActivity(activityId);
        BigDecimal retained = mapper.sumSplitRetainedSalesForActivity(activityId);
        return (paid == null ? BigDecimal.ZERO : paid).add(retained == null ? BigDecimal.ZERO : retained);
    }

    /** Was this registration collected by a seller sub-merchant under the model? False, with no read, while inactive. */
    public boolean isSplitCollected(Long registrationId) {
        if (!guardActive() || registrationId == null) return false;
        return mapper.countByRegistrationId(registrationId) > 0;
    }

    /**
     * Plans profit sharing for split orders past the T+N hold. Topic tickets of a theme with an effective co-op order are
     * left to the co-op ledger planner ({@code EcommerceProfitShareService#planTopic}). Returns the number planned.
     */
    public int planDue(int limit) {
        if (switches == null || !switches.profitSharingEnabled()) return 0;
        int planned = 0;
        for (Map<String, Object> row : mapper.selectDueOrders(new Date(), Math.max(1, Math.min(limit, 200)))) {
            String no = str(row.get("registrationNo"));
            try {
                if (planOne(row)) planned++;
            } catch (RuntimeException e) {
                log.warn("collection split plan failed registrationNo={} type={}", safe(no), e.getClass().getSimpleName());
            }
        }
        return planned;
    }

    boolean planOne(Map<String, Object> row) {
        String no = str(row.get("registrationNo"));
        long ownerType = num(row.get("ownerType"));
        long ownerId = num(row.get("ownerId"));
        long total = num(row.get("totalFen"));
        if (mapper.countOpenRefunds(no) > 0) return false; // a refund is in flight: plan after it settles
        Long refunded = mapper.sumSucceededRefundFen(no);
        if (refunded != null && refunded >= total) {
            mapper.updateOrderState(no, OPEN, REFUNDED, null, "FULLY_REFUNDED");
            return false;
        }
        if (ownerType == 1L && !coopOrders.selectEffectiveOrdersByTopicId(ownerId).isEmpty()) {
            mapper.updateOrderState(no, OPEN, COOP, null, "COOP_LEDGER");
            return false;
        }
        EcomOrderSeller trade = trade(row);
        BigDecimal rate = row.get("takeRate") instanceof BigDecimal r ? r
                : row.get("takeRate") == null ? null : new BigDecimal(row.get("takeRate").toString());
        long take = CollectionPolicyEngine.platformTakeFen(total, rate);
        Long shareOrderId = profitSharing.planSingleSeller(sourceId(ownerType, ownerId), trade, Math.max(take, 0),
                take < 0 ? "TAKE_RATE_MISSING" : null);
        if (shareOrderId == null) return false;
        return mapper.updateOrderState(no, OPEN, PLANNED, shareOrderId, null) == 1;
    }

    /** Operations: re-plan a split share order in manual review, with the take recomputed from the frozen rate. */
    public String replan(String registrationNo) {
        Map<String, Object> row = registrationNo == null ? null : mapper.selectOrder(registrationNo);
        if (row == null || row.get("shareOrderId") == null) return "NOT_A_SPLIT_PLAN";
        BigDecimal rate = row.get("takeRate") instanceof BigDecimal r ? r : null;
        long take = CollectionPolicyEngine.platformTakeFen(num(row.get("totalFen")), rate);
        if (take < 0) return "TAKE_RATE_MISSING";
        EcomOrderSeller trade = trade(row);
        trade.setTransactionId(mapper.selectTransactionId(registrationNo));
        return profitSharing.replanSingleSeller(num(row.get("shareOrderId")), trade, take);
    }

    static long sourceId(long ownerType, long ownerId) {
        return ownerType == 2L ? -ownerId : ownerId;
    }

    private static EcomOrderSeller trade(Map<String, Object> row) {
        EcomOrderSeller trade = new EcomOrderSeller();
        trade.setOutTradeNo(str(row.get("registrationNo")));
        trade.setSubMchid(str(row.get("subMchid")));
        trade.setSellerType(str(row.get("sellerType")));
        trade.setSellerId(num(row.get("sellerId")));
        trade.setTotalFen(num(row.get("totalFen")));
        trade.setTransactionId(str(row.get("transactionId")));
        trade.setRegistrationId(row.get("registrationId") == null ? null : num(row.get("registrationId")));
        return trade;
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }

    private static long num(Object v) {
        if (v instanceof Number n) return n.longValue();
        return v == null ? 0L : Long.parseLong(v.toString());
    }

    private static String safe(String v) { return v != null && v.matches("[A-Za-z0-9_-]{1,64}") ? v : "UNKNOWN"; }
}

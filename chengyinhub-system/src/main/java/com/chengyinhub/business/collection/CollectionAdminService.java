package com.chengyinhub.business.collection;

import com.chengyinhub.business.collection.mapper.CollectionModelMapper;
import com.chengyinhub.business.payout.EcommerceProfitShareService;
import com.chengyinhub.business.payout.PayoutSwitches;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * S2 collection model, admin console (分账收款): the model's state, per-seller collection status, the split ledger, the
 * failures queue and the refusal list. Reads only; the two operations (re-plan, retry a failed return) go through the
 * existing profit-sharing service, whose own switches still apply. No merchant id is ever returned: sub-merchant ids
 * stay on the 收款账户 page that already shows them to operations.
 */
@Service
public class CollectionAdminService {
    private static final Set<String> SELLER_TYPES = Set.of("MERCHANT", "CLUB");
    private static final Set<String> STATES = Set.of(CollectionSplitSettlementService.OPEN, CollectionSplitSettlementService.PLANNED,
            CollectionSplitSettlementService.REFUNDED, CollectionSplitSettlementService.COOP);

    private final CollectionModelService model;
    private final CollectionSplitSettlementService split;
    private final CollectionModelMapper mapper;
    private final PayoutSwitches switches;
    private final EcommerceProfitShareService profitSharing;

    public CollectionAdminService(CollectionModelService model, CollectionSplitSettlementService split, CollectionModelMapper mapper,
                                  PayoutSwitches switches, EcommerceProfitShareService profitSharing) {
        this.model = model;
        this.split = split;
        this.mapper = mapper;
        this.switches = switches;
        this.profitSharing = profitSharing;
    }

    /** Model state: whether it acts, the configured policy per seller type, and which rails are on. */
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelActive", model.active());
        out.put("policies", model.policies());
        out.put("splitRailsReady", model.splitRailsReady());
        out.put("ecommerceEnabled", switches.ecommerceEnabled());
        out.put("sellerRoutingEnabled", switches.sellerRoutingEnabled());
        out.put("profitSharingEnabled", switches.profitSharingEnabled());
        out.put("splitLedgerReadable", split.guardActive());
        return out;
    }

    public List<Map<String, Object>> sellers(String sellerType, Boolean bound, Integer limit) {
        return mapper.selectSellerStatus(sellerType(sellerType), bound, limit(limit));
    }

    public List<Map<String, Object>> ledger(String sellerType, Long sellerId, String state, Integer limit) {
        String s = state == null || state.isBlank() ? null : state.trim().toUpperCase(Locale.ROOT);
        return mapper.selectLedger(sellerType(sellerType), sellerId, s != null && STATES.contains(s) ? s : null, limit(limit));
    }

    public List<Map<String, Object>> failures(Integer limit) {
        return mapper.selectFailures(limit(limit));
    }

    public List<Map<String, Object>> refusals(Integer limit) {
        return mapper.selectRefusals(limit(limit));
    }

    /** Re-plan a split share order in manual review (take recomputed from the rate frozen on the order). */
    public String replan(String registrationNo) {
        if (registrationNo == null || !registrationNo.matches("[A-Za-z0-9_-]{1,64}")) return "NOT_A_SPLIT_PLAN";
        return split.replan(registrationNo);
    }

    /** Retry one failed profit-sharing return of a split order (the refund waits for it). */
    public String retryReturn(Long returnId) {
        if (returnId == null || returnId <= 0) return "NOT_FOUND";
        return profitSharing.retryReturn(returnId).name();
    }

    /** Plan the split orders that are due now (the scheduled task does the same each run). */
    public int planDue() {
        return split.planDue(50);
    }

    static String sellerType(String raw) {
        if (raw == null) return null;
        String t = raw.trim().toUpperCase(Locale.ROOT);
        return SELLER_TYPES.contains(t) ? t : null;
    }

    static int limit(Integer limit) {
        return limit == null ? 50 : Math.max(1, Math.min(limit, 200));
    }
}

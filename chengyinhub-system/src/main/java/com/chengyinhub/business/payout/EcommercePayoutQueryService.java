package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.EcomProfitShareLine;
import com.chengyinhub.business.domain.EcomProfitShareOrder;
import com.chengyinhub.business.domain.EcomProfitShareReturn;
import com.chengyinhub.business.domain.EcomRefund;
import com.chengyinhub.business.domain.EcomSubMerchant;
import com.chengyinhub.business.mapper.EcomPayoutMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** l-payout2：运营端只读列表（进件、分账、回退、退款）。只读新表，不调微信、不改状态。 */
@Service
public class EcommercePayoutQueryService {
    private static final Set<String> REFUND_STATES = Set.of("RETURNING", "RETURN_FAILED", "SUBMITTING", "SUBMITTED",
            "SUCCESS", "CLOSED", "ABNORMAL");
    private static final int MAX = 200;

    private final EcomPayoutMapper mapper;
    private final PayoutSwitches switches;

    public EcommercePayoutQueryService(EcomPayoutMapper mapper, PayoutSwitches switches) {
        this.mapper = mapper;
        this.switches = switches;
    }

    /** 开关现状（只有布尔与非密配置，绝不含证书、密钥）。 */
    public Map<String, Object> switches() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ecommerceEnabled", switches.ecommerceEnabled());
        out.put("sellerRoutingEnabled", switches.sellerRoutingEnabled());
        out.put("profitSharingEnabled", switches.profitSharingEnabled());
        out.put("maxSharePercent", switches.maxSharePercent());
        out.put("creatorEnabled", switches.creatorEnabled());
        return out;
    }

    public List<EcomSubMerchant> subMerchants(String state, Integer limit) {
        return mapper.selectSubMerchantsForAdmin(blankToNull(state), cap(limit));
    }

    public List<EcomProfitShareOrder> shareOrders(String state, Long topicId, Integer limit) {
        return mapper.selectShareOrdersForAdmin(blankToNull(state), topicId, cap(limit));
    }

    public List<EcomProfitShareLine> shareLines(Long shareOrderId) {
        return shareOrderId == null ? List.of() : mapper.selectShareLines(shareOrderId);
    }

    public List<EcomProfitShareReturn> returns(String state, Integer limit) {
        return mapper.selectReturnsByState(blankToNull(state), cap(limit));
    }

    /** 退款记录；不传状态时列出全部状态。CLOSED / ABNORMAL 且已回退分账的，是「退款失败待补偿」。 */
    public List<EcomRefund> refunds(String state, Integer limit) {
        String s = blankToNull(state);
        if (s != null && !REFUND_STATES.contains(s)) return List.of();
        return mapper.selectRefundsByStates(s == null ? List.copyOf(REFUND_STATES) : List.of(s), cap(limit));
    }

    private static int cap(Integer limit) { return limit == null ? 50 : Math.max(1, Math.min(MAX, limit)); }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}

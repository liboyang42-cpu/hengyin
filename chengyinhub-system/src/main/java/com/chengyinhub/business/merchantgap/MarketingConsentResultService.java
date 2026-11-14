package com.chengyinhub.business.merchantgap;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.MerchantCrmMarketingConsent;
import com.chengyinhub.business.mapper.MerchantCrmMarketingConsentMapper;

/**
 * GET /api/merchant/crm/marketing-consents/result: the original-result read of one consent write
 * (POST /api/merchant/crm/marketing-consents) by the player's own request id. RECORDED carries the appended
 * event; NOT_OBSERVED means no row with that request id exists for this player, merchant and owner.
 */
@Service
public class MarketingConsentResultService {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private final MerchantCrmMarketingConsentMapper consents;

    public MarketingConsentResultService(MerchantCrmMarketingConsentMapper consents) {
        this.consents = consents;
    }

    public Map<String, Object> result(Long principal, Long merchantRowId, Long ownerMemberId, String channel, String requestId) {
        long member = MerchantGapSupport.member(principal);
        if (merchantRowId == null || merchantRowId <= 0 || ownerMemberId == null || ownerMemberId <= 0
                || !(MerchantCrmMarketingConsent.IN_APP.equals(channel) || MerchantCrmMarketingConsent.COUPON.equals(channel))
                || requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        }
        MerchantCrmMarketingConsent row = consents.selectByRequestId(member, merchantRowId, requestId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("receiptVersion", 1);
        out.put("actorId", member);
        out.put("merchantRowId", merchantRowId);
        out.put("merchantOwnerMemberId", ownerMemberId);
        out.put("channel", channel);
        out.put("requestId", requestId);
        if (row == null) {
            out.put("outcome", "NOT_OBSERVED");
            out.put("consentId", null);
            out.put("eventType", null);
            out.put("recordedAt", null);
            return out;
        }
        if (!channel.equals(row.getChannel()) || !ownerMemberId.equals(row.getMerchantOwnerMemberId())) {
            throw new MerchantGapException(409, "REQUEST_ID_CONFLICT", "请求编号属于其他授权操作");
        }
        out.put("outcome", "RECORDED");
        out.put("consentId", row.getId());
        out.put("eventType", row.getEventType());
        out.put("recordedAt", row.getCreateTime() == null ? null : row.getCreateTime().getTime());
        return out;
    }
}

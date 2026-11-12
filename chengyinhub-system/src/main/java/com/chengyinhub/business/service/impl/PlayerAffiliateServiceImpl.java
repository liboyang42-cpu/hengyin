package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MarketingReferralCredential;
import com.chengyinhub.business.domain.dto.PlayerAffiliateBucketRow;
import com.chengyinhub.business.domain.dto.PlayerAffiliateCampaignRow;
import com.chengyinhub.business.domain.dto.PlayerAffiliateCredentialRow;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocation;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocationLine;
import com.chengyinhub.business.domain.vo.PlayerAffiliateSummaryVO;
import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.mapper.PlayerAffiliateMapper;
import com.chengyinhub.business.service.IPlayerAffiliateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 玩家推广收入只读汇总实现。
 *
 * <p>口径：
 * <ul>
 *   <li>成交/退款/待应计：来自 {@code marketing_attribution} + {@code cms_registration.payment_status}；</li>
 *   <li>收入桶：只取本人作为 {@code line_role=referrer} 的分录，金额以整数分为真源；</li>
 *   <li>{@code held}：已支付且已归因、但还没有分配行的单 —— 不猜金额，明确是"尚未应计"。</li>
 * </ul>
 *
 * <p><b>不提现</b>：分配域没有把玩家份额转入钱包/标记已打款的通道，
 * 所以 payout 能力固定为不可用并给出原因码，不返回 availableToWithdraw。
 */
@Service
public class PlayerAffiliateServiceImpl implements IPlayerAffiliateService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    static final String BUCKET_PENDING = "pending";
    static final String BUCKET_CONFIRMED = "confirmed";
    static final String BUCKET_PAID = "paid";
    static final String BUCKET_REVERSED = "reversed";

    static final String PAYOUT_UNAVAILABLE_REASON = "NO_PLAYER_PAYOUT_PATH";
    static final String HELD_REASON = "ALLOCATION_NOT_YET_ACCRUED";

    private final PlayerAffiliateMapper mapper;

    public PlayerAffiliateServiceImpl(PlayerAffiliateMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public PlayerAffiliateSummaryVO summary(long callerMemberId, Integer limit) {
        if (callerMemberId <= 0) {
            throw new MarketingRevenueException("LOGIN_REQUIRED", 401, "请先登录");
        }
        int size = normalizeLimit(limit);
        PlayerAffiliateSummaryVO out = new PlayerAffiliateSummaryVO();
        out.setProvenance(new PlayerAffiliateSummaryVO.Provenance());

        Date now = new Date();
        out.setCredentials(credentials(mapper.listOwnCredentials(callerMemberId, size), now));
        out.setCampaigns(campaigns(mapper.listOwnCampaigns(callerMemberId, size)));
        out.setAllocations(buckets(mapper.listReferrerBuckets(callerMemberId)));
        out.setHeld(held(out.getCampaigns()));
        out.setPayout(payoutCapability());
        return out;
    }

    // ------------------------------------------------------------------ projections

    private List<PlayerAffiliateSummaryVO.Credential> credentials(List<PlayerAffiliateCredentialRow> rows,
                                                                   Date now) {
        List<PlayerAffiliateSummaryVO.Credential> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (PlayerAffiliateCredentialRow row : rows) {
            PlayerAffiliateSummaryVO.Credential view = new PlayerAffiliateSummaryVO.Credential();
            view.setCampaignKey(row.getCampaignKey());
            view.setTargetType(row.getTargetType());
            view.setTargetId(row.getTargetId());
            view.setTargetTitle(row.getTargetTitle());
            view.setStatus(row.getStatus());
            view.setIssuedAt(row.getIssuedAt());
            view.setExpiresAt(row.getExpiresAt());
            boolean active = Integer.valueOf(MarketingReferralCredential.STATUS_ACTIVE).equals(row.getStatus())
                    && row.getExpiresAt() != null && row.getExpiresAt().after(now);
            view.setActive(active);
            view.setStatusText(credentialStatusText(row.getStatus(), active));
            out.add(view);
        }
        return out;
    }

    private List<PlayerAffiliateSummaryVO.Campaign> campaigns(List<PlayerAffiliateCampaignRow> rows) {
        List<PlayerAffiliateSummaryVO.Campaign> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (PlayerAffiliateCampaignRow row : rows) {
            PlayerAffiliateSummaryVO.Campaign view = new PlayerAffiliateSummaryVO.Campaign();
            view.setCampaignKey(row.getCampaignKey());
            view.setTargetType(row.getTargetType());
            view.setTargetId(row.getTargetId());
            view.setTargetTitle(row.getTargetTitle());
            view.setAttributedOrders(row.getAttributedOrders());
            view.setPaidOrders(row.getPaidOrders());
            view.setPaidAmount(row.getPaidAmount() == null ? BigDecimal.ZERO : row.getPaidAmount());
            view.setRefundedOrders(row.getRefundedOrders());
            view.setRefundedAmount(row.getRefundedAmount() == null ? BigDecimal.ZERO : row.getRefundedAmount());
            view.setHeldOrders(row.getHeldOrders());
            out.add(view);
        }
        return out;
    }

    private Map<String, PlayerAffiliateSummaryVO.Bucket> buckets(List<PlayerAffiliateBucketRow> rows) {
        Map<String, PlayerAffiliateSummaryVO.Bucket> out = new LinkedHashMap<>();
        out.put(BUCKET_PENDING, emptyBucket("accrued"));
        out.put(BUCKET_CONFIRMED, emptyBucket("settleable"));
        out.put(BUCKET_PAID, emptyBucket("paid"));
        out.put(BUCKET_REVERSED, emptyBucket("reversed"));
        Map<String, Set<String>> currencies = new LinkedHashMap<>();
        for (String key : out.keySet()) {
            currencies.put(key, new LinkedHashSet<String>());
        }
        if (rows == null) {
            return out;
        }
        for (PlayerAffiliateBucketRow row : rows) {
            String key = bucketKey(row.getAllocationStatus());
            if (key == null) {
                continue;
            }
            PlayerAffiliateSummaryVO.Bucket bucket = out.get(key);
            long amountFen = row.getAmountFen();
            if (Integer.valueOf(MarketingRevenueAllocationLine.ENTRY_REVERSE).equals(row.getEntryType())) {
                bucket.setReversedFen(bucket.getReversedFen() - amountFen);
            } else {
                bucket.setAccruedFen(bucket.getAccruedFen() + amountFen);
                // 每笔分配只在原始应计组计数，冲销组不重复计数；状态/币种组互不重叠。
                bucket.setAllocationCount(bucket.getAllocationCount() + row.getAllocationCount());
            }
            if (row.getCurrency() != null) {
                currencies.get(key).add(row.getCurrency());
            }
        }
        for (Map.Entry<String, PlayerAffiliateSummaryVO.Bucket> entry : out.entrySet()) {
            PlayerAffiliateSummaryVO.Bucket bucket = entry.getValue();
            Set<String> bucketCurrencies = currencies.get(entry.getKey());
            if (bucketCurrencies.size() > 1) {
                bucket.setAccruedFen(null);
                bucket.setReversedFen(null);
                bucket.setNetFen(null);
                bucket.setAccruedAmount(null);
                bucket.setReversedAmount(null);
                bucket.setNetAmount(null);
                bucket.setAmountUnavailableReason("MULTIPLE_CURRENCIES");
                continue;
            }
            bucket.setCurrency(bucketCurrencies.isEmpty() ? null : bucketCurrencies.iterator().next());
            bucket.setNetFen(bucket.getAccruedFen() - bucket.getReversedFen());
            bucket.setAccruedAmount(amount(bucket.getAccruedFen()));
            bucket.setReversedAmount(amount(bucket.getReversedFen()));
            bucket.setNetAmount(amount(bucket.getNetFen()));
        }
        return out;
    }

    private PlayerAffiliateSummaryVO.Held held(List<PlayerAffiliateSummaryVO.Campaign> campaigns) {
        long orders = 0L;
        for (PlayerAffiliateSummaryVO.Campaign campaign : campaigns) {
            orders += campaign.getHeldOrders();
        }
        PlayerAffiliateSummaryVO.Held held = new PlayerAffiliateSummaryVO.Held();
        held.setOrders(orders);
        // 没有分配行就没有可信金额依据：不给数字，只给原因。
        held.setAmountFen(null);
        held.setReason(HELD_REASON);
        return held;
    }

    private PlayerAffiliateSummaryVO.Capability payoutCapability() {
        PlayerAffiliateSummaryVO.Capability capability = new PlayerAffiliateSummaryVO.Capability();
        capability.setAvailable(false);
        capability.setReason(PAYOUT_UNAVAILABLE_REASON);
        capability.setEndpoint(null);
        return capability;
    }

    // ------------------------------------------------------------------ helpers

    private static String bucketKey(Integer allocationStatus) {
        if (allocationStatus == null) {
            return null;
        }
        switch (allocationStatus) {
            case MarketingRevenueAllocation.STATUS_ACCRUED:
                return BUCKET_PENDING;
            case MarketingRevenueAllocation.STATUS_SETTLEABLE:
                return BUCKET_CONFIRMED;
            case MarketingRevenueAllocation.STATUS_PAID:
                return BUCKET_PAID;
            case MarketingRevenueAllocation.STATUS_PARTIALLY_REVERSED:
            case MarketingRevenueAllocation.STATUS_REVERSED:
                return BUCKET_REVERSED;
            default:
                return null;
        }
    }

    private static String credentialStatusText(Integer status, boolean active) {
        if (Integer.valueOf(MarketingReferralCredential.STATUS_REVOKED).equals(status)) {
            return "revoked";
        }
        if (active) {
            return "active";
        }
        return status == null ? "unknown" : "expired";
    }

    private static PlayerAffiliateSummaryVO.Bucket emptyBucket(String statusText) {
        PlayerAffiliateSummaryVO.Bucket bucket = new PlayerAffiliateSummaryVO.Bucket();
        bucket.setStatusText(statusText);
        bucket.setAccruedFen(0L);
        bucket.setReversedFen(0L);
        bucket.setNetFen(0L);
        bucket.setAccruedAmount(BigDecimal.ZERO);
        bucket.setReversedAmount(BigDecimal.ZERO);
        bucket.setNetAmount(BigDecimal.ZERO);
        return bucket;
    }

    private static BigDecimal amount(long fen) {
        return fen == 0L ? BigDecimal.ZERO : BigDecimal.valueOf(fen, 2);
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}

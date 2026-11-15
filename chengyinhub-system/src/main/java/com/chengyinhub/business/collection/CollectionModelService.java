package com.chengyinhub.business.collection;

import com.chengyinhub.business.collection.mapper.CollectionModelMapper;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.vo.PlatformSettlementAccountVO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import com.chengyinhub.business.payout.EcommerceSellerResolver;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.business.payout.SellerOfRecordPolicy;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.business.service.PaymentPreorderException;
import com.chengyinhub.common.exception.BusinessRefusal;
import com.chengyinhub.common.exception.ErrorCode;
import com.chengyinhub.common.exception.ErrorContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S2 collection model (owner ruling 2026-09-15, confirmed 2026-11-15): the platform collects only for its own official
 * tickets; every third-party seller is paid through single-seller split settlement, never collect-then-redistribute.
 *
 * <p><b>Gate.</b> The model acts only while the money switch's server ceiling ({@code chengyin.money-switch.server-ceiling},
 * false in production) is on. While it is off, {@link #active()} is false, nothing here reads a table, every method
 * returns "platform, as today" and no caller changes behaviour.
 *
 * <p><b>Policy per seller type</b> (configuration, see {@code application.yml}):
 * {@code chengyin.collection-model.policy.merchant|club|member} = {@code SPLIT} (default) or {@code REFUSE}. A third
 * party is never collected by the platform: any other value is read as {@code REFUSE}. Club dues have no split path yet
 * and are refused while the model is active ({@link CollectionDecision#DUES_SPLIT_NOT_BUILT}).
 */
@Service
public class CollectionModelService {
    private static final Logger log = LoggerFactory.getLogger(CollectionModelService.class);

    private final MoneySwitchService moneySwitch;
    private final PayoutSwitches switches;
    private final EcommerceSellerResolver sellers;
    private final CmsActivityMapper activities;
    private final ISettlementRuleService settlementRules;
    private final CollectionModelMapper mapper;
    private final Map<SellerOfRecordPolicy.SellerType, CollectionPolicy> thirdParty;

    public CollectionModelService(MoneySwitchService moneySwitch, PayoutSwitches switches, EcommerceSellerResolver sellers,
                                  CmsActivityMapper activities, ISettlementRuleService settlementRules,
                                  CollectionModelMapper mapper,
                                  @Value("${chengyin.collection-model.policy.merchant:SPLIT}") String merchantPolicy,
                                  @Value("${chengyin.collection-model.policy.club:SPLIT}") String clubPolicy,
                                  @Value("${chengyin.collection-model.policy.member:SPLIT}") String memberPolicy) {
        this.moneySwitch = moneySwitch;
        this.switches = switches;
        this.sellers = sellers;
        this.activities = activities;
        this.settlementRules = settlementRules;
        this.mapper = mapper;
        this.thirdParty = new EnumMap<>(SellerOfRecordPolicy.SellerType.class);
        this.thirdParty.put(SellerOfRecordPolicy.SellerType.MERCHANT, thirdPartyPolicy("merchant", merchantPolicy));
        this.thirdParty.put(SellerOfRecordPolicy.SellerType.CLUB, thirdPartyPolicy("club", clubPolicy));
        this.thirdParty.put(SellerOfRecordPolicy.SellerType.MEMBER, thirdPartyPolicy("member", memberPolicy));
    }

    /** Only SPLIT or REFUSE is accepted for a third party; anything else (including PLATFORM) is REFUSE. */
    static CollectionPolicy thirdPartyPolicy(String type, String raw) {
        CollectionPolicy parsed = CollectionPolicy.parse(raw);
        if (parsed == CollectionPolicy.SPLIT || parsed == CollectionPolicy.REFUSE) return parsed;
        log.warn("collection-model policy for {} is '{}': a third party can only be SPLIT or REFUSE; using REFUSE", type, raw);
        return CollectionPolicy.REFUSE;
    }

    /** True only while the money switch's server ceiling is on (TEST / Staging). Production: false. */
    public boolean active() {
        return moneySwitch != null && moneySwitch.serverCeiling();
    }

    /** The configured third-party policy per seller type (admin view). */
    public Map<String, String> policies() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("PLATFORM", CollectionPolicy.PLATFORM.name());
        thirdParty.forEach((k, v) -> out.put(k.name(), v.name()));
        out.put("CLUB_DUES", CollectionPolicy.REFUSE.name());
        return out;
    }

    /** Split rails = seller routing and profit sharing both on (and the service-provider ids configured). */
    public boolean splitRailsReady() {
        return switches != null && switches.sellerRoutingEnabled() && switches.profitSharingEnabled();
    }

    /** Decision for a ticket order (ownerType 1 topic, 2 activity). Model off → PLATFORM / MODEL_OFF, no reads. */
    public CollectionDecision decide(Long ownerType, Long ownerId) {
        if (!active()) return CollectionDecision.platform(CollectionDecision.MODEL_OFF);
        SellerOfRecordPolicy.Facts facts = sellers.factsFor(ownerType, ownerId);
        return CollectionPolicyEngine.decide(facts, officialActivity(ownerType, ownerId, facts),
                new CollectionPolicyEngine.Rules(thirdParty, splitRailsReady(), switches.spMchid()));
    }

    public CollectionDecision decide(CmsRegistration registration) {
        if (registration == null) return decide(null, null);
        return decide(registration.getOwnerType(), registration.getOwnerId());
    }

    /**
     * An independent activity (no theme, no club) run by the platform's own settlement account is an official ticket.
     * Uses the existing platform account setting; any read problem → not official (the seller is then a third party
     * and must be split or refused, never silently collected).
     */
    private boolean officialActivity(Long ownerType, Long ownerId, SellerOfRecordPolicy.Facts facts) {
        if (!Long.valueOf(2L).equals(ownerType) || ownerId == null || facts == null
                || facts.sellerType() != SellerOfRecordPolicy.SellerType.MEMBER) return false;
        try {
            CmsActivity activity = activities.selectCmsActivityById(ownerId);
            if (activity == null || activity.getTopicId() != null || (activity.getClubId() != null && activity.getClubId() > 0))
                return false;
            PlatformSettlementAccountVO account = settlementRules.getPlatformSettlementAccount();
            return account != null && account.getMemberId() != null && account.getMemberId().equals(activity.getMemberId());
        } catch (RuntimeException unreadable) {
            log.warn("collection-model official check failed ownerId={} type={}", ownerId, unreadable.getClass().getSimpleName());
            return false;
        }
    }

    // ------------------------------------------------------------------ order creation

    /**
     * Order creation and payment retry: refuse a paid third-party ticket the platform may not collect, before any
     * stock, entitlement or points are taken. No-op while the model is off or the order is free.
     */
    public void requireCollectible(Long ownerType, Long ownerId, BigDecimal payable) {
        if (payable == null || payable.signum() <= 0 || !active()) return;
        CollectionDecision decision = decide(ownerType, ownerId);
        if (decision.refused()) throw refusal(decision);
    }

    /**
     * Last line before the platform merchant account is asked to collect (mini-program JSAPI fallback and the app's
     * APP channel). While the model is active, only a PLATFORM decision may reach the platform merchant account.
     */
    public void requirePlatformMayCollect(CmsRegistration registration, boolean appChannel) {
        if (!active()) return;
        CollectionDecision decision = decide(registration);
        if (decision.platform()) return;
        record(decision.split() && appChannel
                ? new CollectionDecision(CollectionPolicy.REFUSE, decision.sellerType(), decision.sellerId(), null,
                        CollectionDecision.APP_CHANNEL_SPLIT_NOT_BUILT)
                : decision);
        if (decision.split() && appChannel) {
            throw PaymentPreorderException.definiteFailure(ErrorCode.APP_CHANNEL_UNAVAILABLE.defaultMessage(), null);
        }
        if (decision.split()) {
            // A split order must go through the seller's sub-merchant; reaching the platform path means the partner
            // adapter did not take it (rails off between checks). Never fall back to the platform account.
            throw PaymentPreorderException.definiteFailure(ErrorCode.SELLER_COLLECTION_UNAVAILABLE.defaultMessage(), null);
        }
        throw PaymentPreorderException.definiteFailure(message(decision), null);
    }

    /** Club dues: no split path yet, so the platform may not collect them while the model is active. */
    public void requireClubDuesCollectible(Long clubId) {
        if (!active()) return;
        recordRefusal("DUES", clubId == null ? 0L : clubId, CollectionDecision.DUES_SPLIT_NOT_BUILT);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", CollectionDecision.DUES_SPLIT_NOT_BUILT);
        data.put("nextStep", "CONTACT_SELLER");
        ErrorCode code = ErrorCode.CLUB_DUES_COLLECTION_UNAVAILABLE;
        throw new BusinessRefusal(code, code.defaultMessage(), ErrorContract.LEGACY_ERROR_CODE, code.httpStatus(), data);
    }

    /** Remembers a split order so settlement leaves it out of every wallet and plans its profit sharing. Idempotent. */
    public void recordSplitOrder(CmsRegistration registration, CollectionDecision decision, long totalFen) {
        if (registration == null || decision == null || !decision.split()) return;
        mapper.insertOrder(registration.getRegistrationNo(), registration.getId(), registration.getOwnerType(),
                registration.getOwnerId(), decision.sellerType().name(), decision.sellerId(), decision.subMchid(), totalFen,
                registration.getFeePlatformRate());
    }

    /** What a buyer or seller may see about one product: can it be bought online, and if not, why. No merchant ids. */
    public Map<String, Object> productStatus(Long ownerType, Long ownerId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelActive", active());
        CollectionDecision decision = decide(ownerType, ownerId);
        out.put("policy", decision.policy().name());
        out.put("collectible", !decision.refused());
        out.put("reason", decision.reason());
        if (decision.refused()) {
            out.put("message", message(decision));
            out.put("nextStep", nextStep(decision));
        }
        return out;
    }

    // ------------------------------------------------------------------ internals

    private BusinessRefusal refusal(CollectionDecision decision) {
        record(decision);
        ErrorCode code = errorCode(decision);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", decision.reason());
        data.put("nextStep", nextStep(decision));
        return new BusinessRefusal(code, code.defaultMessage(), ErrorContract.LEGACY_ERROR_CODE, code.httpStatus(), data);
    }

    static ErrorCode errorCode(CollectionDecision decision) {
        if (CollectionDecision.SPLIT_RAILS_OFF.equals(decision.reason())) return ErrorCode.SELLER_COLLECTION_UNAVAILABLE;
        if (CollectionDecision.APP_CHANNEL_SPLIT_NOT_BUILT.equals(decision.reason())) return ErrorCode.APP_CHANNEL_UNAVAILABLE;
        return ErrorCode.SELLER_NOT_BOUND;
    }

    static String message(CollectionDecision decision) {
        return errorCode(decision).defaultMessage();
    }

    /**
     * Who can fix it. Players are told to contact the seller; the seller binds a collection account: merchants see the
     * state on the mini-program page 收款账户 (pages/merchant/payout-account), and onboarding itself is submitted by
     * platform operations from the admin console (分账收款 / 收款账户).
     */
    static String nextStep(CollectionDecision decision) {
        if (CollectionDecision.SPLIT_RAILS_OFF.equals(decision.reason())) return "WAIT_FOR_PLATFORM";
        if (CollectionDecision.APP_CHANNEL_SPLIT_NOT_BUILT.equals(decision.reason())) return "USE_MINI_PROGRAM";
        return "CONTACT_SELLER";
    }

    private void record(CollectionDecision decision) {
        if (decision == null || !decision.refused()) return;
        recordRefusal(decision.sellerType() == null ? "UNKNOWN" : decision.sellerType().name(),
                decision.sellerId() == null ? 0L : decision.sellerId(), decision.reason());
    }

    private void recordRefusal(String sellerType, long sellerId, String reason) {
        try {
            mapper.recordRefusal(sellerType, sellerId, reason);
        } catch (RuntimeException ignored) {
            // The refusal itself must still reach the buyer; the counter is an ops aid only.
            log.warn("collection-model refusal counter write failed type={}", ignored.getClass().getSimpleName());
        }
    }
}

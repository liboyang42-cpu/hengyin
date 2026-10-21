package com.chengyinhub.business.service.rewardpledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Award;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.CaseRow;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Intent;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Pledge;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Policy;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Stock;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Version;
import com.chengyinhub.business.mapper.rewardpledge.RewardPledgeMapper;
import com.chengyinhub.business.service.rewardpledge.PledgeCouponPort.CouponView;

/** Pledge, claim, fulfil and recover one non-cash batch.
 * Not a points, wallet or sellable-SKU ledger. Policy rows that are missing stay closed. */
@Service
public class RewardPledgeService {
    private final TransactionTemplate tx;
    private final RewardPledgeMapper mapper;
    private final PledgeCouponPort coupons;
    private final Clock clock;
    private final WriteGate gate;

    @Autowired
    public RewardPledgeService(PlatformTransactionManager manager, RewardPledgeMapper mapper, PledgeCouponPort coupons) {
        this(manager, mapper, coupons, Clock.systemUTC(), null);
    }

    public RewardPledgeService(PlatformTransactionManager manager, RewardPledgeMapper mapper, PledgeCouponPort coupons,
                               Clock clock, WriteGate gate) {
        this.tx = new TransactionTemplate(manager);
        this.mapper = mapper;
        this.coupons = coupons;
        this.clock = clock;
        this.gate = gate;
    }

    public Map<String, Object> draft(Draft draft) {
        validateSupply(draft);
        String pledgeId = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        long now = clock.millis();
        Pledge pledge = pledgeFrom(draft, pledgeId, now);
        Version version = new Version();
        version.versionId = versionId;
        version.pledgeId = pledgeId;
        version.versionNo = 1;
        version.payloadHash = payloadHash(pledge);
        version.state = "DRAFT";
        snapshot(version, pledge);
        tx.executeWithoutResult(status -> {
            gate("draft-pledge");
            mapper.insertPledge(pledge);
            gate("draft-version");
            mapper.insertVersion(version);
        });
        return readPledge(pledgeId);
    }

    public Map<String, Object> submit(long actorId, String pledgeId) {
        return tx.execute(status -> {
            Pledge pledge = owned(actorId, pledgeId);
            Version version = current(pledgeId);
            if (!"DRAFT".equals(version.state)) throw new PledgeRejection("CONFLICT");
            mapper.updatePledgeState(pledgeId, "SUBMITTED");
            mapper.insertVersion(next(version, "SUBMITTED"));
            return viewPledge(pledgeId);
        });
    }

    public Map<String, Object> confirm(long actorId, String pledgeId) {
        return tx.execute(status -> {
            Pledge pledge = owned(actorId, pledgeId);
            Version version = current(pledgeId);
            String hash = sha(version.payloadHash + ":" + actorId);
            if (mapper.confirmVersion(version.versionId, actorId, hash, clock.millis()) != 1) {
                throw new PledgeRejection("CONFLICT");
            }
            mapper.updatePledgeState(pledge.pledgeId, "CONFIRMED");
            return viewPledge(pledgeId);
        });
    }

    public Map<String, Object> freeze(long reviewerId, String pledgeId, String ruleVersion) {
        requireText(ruleVersion);
        if (!open("REVIEWER", "", reviewerId)) throw new PledgeRejection("POLICY_CLOSED");
        if (!open("RULE", ruleVersion, null)) throw new PledgeRejection("POLICY_CLOSED");
        return tx.execute(status -> {
            mustPledge(pledgeId);
            Version version = current(pledgeId);
            if (version.merchantMemberId != null && version.merchantMemberId == reviewerId) {
                throw new PledgeRejection("FORBIDDEN");
            }
            String hash = sha(version.payloadHash + ":review:" + reviewerId);
            if (mapper.freezeVersion(version.versionId, reviewerId, hash, ruleVersion, clock.millis()) != 1) {
                throw new PledgeRejection("CONFLICT");
            }
            Stock stock = new Stock();
            stock.versionId = version.versionId;
            stock.quantity = version.quantity;
            stock.available = version.quantity;
            gate("freeze-stock");
            mapper.insertStock(stock);
            mapper.updatePledgeState(pledgeId, "FROZEN");
            return viewPledge(pledgeId);
        });
    }

    public Map<String, Object> withdraw(long actorId, String versionId) {
        return tx.execute(status -> {
            Version version = mapper.findVersion(versionId);
            if (version == null) throw new PledgeRejection("NOT_FOUND");
            owned(actorId, version.pledgeId);
            Stock stock = mapper.lockStock(versionId);
            if (stock == null || mapper.withdrawVersion(versionId) != 1) throw new PledgeRejection("CONFLICT");
            if (mapper.withdrawAvailable(versionId, stock.versionNo) != 1) throw new PledgeRejection("CONFLICT");
            mapper.updatePledgeState(version.pledgeId, "WITHDRAWN");
            return viewPledge(version.pledgeId);
        });
    }

    /** A new draft version. The frozen version and its awards stay. */
    public Map<String, Object> revise(long actorId, String pledgeId, Draft draft) {
        validateSupply(draft);
        return tx.execute(status -> {
            Pledge pledge = owned(actorId, pledgeId);
            if (pledge.couponId != draft.couponId || pledge.storeId != draft.storeId) throw new PledgeRejection("CONFLICT");
            Version latest = current(pledgeId);
            if (!"FROZEN".equals(latest.state) && !"WITHDRAWN".equals(latest.state) && !"DRAFT".equals(latest.state)) {
                throw new PledgeRejection("CONFLICT");
            }
            pledge.quantity = draft.quantity;
            pledge.serviceStart = draft.serviceStart;
            pledge.serviceEnd = draft.serviceEnd;
            pledge.redeemStart = draft.redeemStart;
            pledge.redeemEnd = draft.redeemEnd;
            pledge.terms = draft.terms;
            pledge.minorRestricted = draft.minorRestricted ? 1 : 0;
            mapper.updatePledgePayload(pledge);
            Version created = next(latest, "DRAFT");
            snapshot(created, pledge);
            created.payloadHash = payloadHash(pledge);
            mapper.insertVersion(created);
            return viewPledge(pledgeId);
        });
    }

    public Map<String, Object> hold(long actorId, String versionId) {
        if (!open("STORE_HOLD", "", null)) throw new PledgeRejection("POLICY_CLOSED");
        return tx.execute(status -> {
            Version version = mapper.findVersion(versionId);
            if (version == null) throw new PledgeRejection("NOT_FOUND");
            owned(actorId, version.pledgeId);
            Stock stock = mapper.lockStock(versionId);
            if (stock == null || !"FROZEN".equals(version.state)) throw new PledgeRejection("CONFLICT");
            if (mapper.holdAvailable(versionId, stock.versionNo) != 1) throw new PledgeRejection("CONFLICT");
            return viewPledge(version.pledgeId);
        });
    }

    public Map<String, Object> claim(Claim claim) {
        requireText(claim.requestId);
        requireText(claim.versionId);
        requireText(claim.branch);
        requireText(claim.subjectId);
        String hash = claimHash(claim);
        Intent existing = tx.execute(status -> mapper.lockIntent(claim.ownerId, claim.requestId));
        if (existing != null) {
            if (!hash.equals(existing.payloadHash)) throw new PledgeRejection("CONFLICT");
            if (!"UNKNOWN".equals(existing.state)) return intentResult(existing);
        } else {
            Intent created = new Intent();
            created.ownerId = claim.ownerId;
            created.requestId = claim.requestId;
            created.payloadHash = hash;
            created.versionId = claim.versionId;
            created.branch = claim.branch;
            created.subjectId = claim.subjectId;
            created.state = "UNKNOWN";
            tx.executeWithoutResult(status -> {
                gate("intent");
                mapper.insertIntent(created);
            });
        }
        return finish(claim, hash);
    }

    /** Reads the original intent. Does not accept a replacement request id. */
    public Map<String, Object> recover(long ownerId, String requestId) {
        requireText(requestId);
        Intent existing = tx.execute(status -> mapper.lockIntent(ownerId, requestId));
        if (existing == null) throw new PledgeRejection("NOT_FOUND");
        if (!"UNKNOWN".equals(existing.state)) return intentResult(existing);
        Claim claim = new Claim();
        claim.ownerId = existing.ownerId;
        claim.requestId = existing.requestId;
        claim.versionId = existing.versionId;
        claim.branch = existing.branch;
        claim.subjectId = existing.subjectId;
        return finish(claim, existing.payloadHash);
    }

    public Map<String, Object> preview(long storeId, String awardId) {
        return tx.execute(status -> fulfilmentView(storeId, awardId, false));
    }

    public Map<String, Object> fulfil(long storeId, long actorId, String actorRole, String awardId) {
        requireText(actorRole);
        return tx.execute(status -> {
            Map<String, Object> view = fulfilmentView(storeId, awardId, true);
            Award award = mapper.findAward(awardId);
            String result = coupons.redeem(award.historyId, merchantOf(award).merchantMemberId, storeId, actorId, actorRole);
            if ("REDEEMED".equals(result)) {
                mapper.markRedeemed(award.versionId);
                mapper.updateIntent(award.beneficiaryId, award.requestId, "REDEEMED", award.awardId, null);
            } else if ("EXPIRED".equals(result)) {
                mapper.markExpired(award.versionId);
            } else if (!"ALREADY_USED".equals(result)) {
                throw new PledgeRejection(result);
            }
            view.put("redemption", result);
            view.put("stock", mapper.lockStock(award.versionId));
            return view;
        });
    }

    public Map<String, Object> expire(String awardId, long now) {
        return tx.execute(status -> {
            Award award = mapper.findAward(awardId);
            if (award == null || award.historyId == null) throw new PledgeRejection("NOT_FOUND");
            boolean moved = coupons.expireUnused(award.historyId, new Date(now));
            if (moved) mapper.markExpired(award.versionId);
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("awardId", awardId);
            body.put("expiredNow", moved);
            body.put("useStatus", coupons.useStatus(award.historyId));
            body.put("stock", mapper.lockStock(award.versionId));
            return body;
        });
    }

    public Map<String, Object> returnToPool(long actorId, String awardId, long now) {
        if (!open("RETURN_TO_POOL", "", null)) throw new PledgeRejection("POLICY_CLOSED");
        return tx.execute(status -> {
            Award award = mapper.findAward(awardId);
            if (award == null || award.historyId == null) throw new PledgeRejection("NOT_FOUND");
            Version version = mapper.findVersion(award.versionId);
            Pledge pledge = version == null ? null : mapper.findPledge(version.pledgeId);
            if (pledge == null) throw new PledgeRejection("NOT_FOUND");
            if (pledge.merchantMemberId != actorId) throw new PledgeRejection("FORBIDDEN");
            if (!"FROZEN".equals(version.state)) throw new PledgeRejection("CONFLICT");
            if (now < version.serviceStart || now >= version.serviceEnd) throw new PledgeRejection("WINDOW_CLOSED");
            int use = coupons.useStatus(award.historyId);
            if (use == 1) throw new PledgeRejection("ALREADY_USED");
            if (use != 2) throw new PledgeRejection("CONFLICT");
            Stock stock = mapper.lockStock(version.versionId);
            if (stock == null || stock.frozenUnits > 0) throw new PledgeRejection("CONFLICT");
            if (mapper.markAwardReturned(award.awardId) != 1) throw new PledgeRejection("CONFLICT");
            if (mapper.returnExpired(version.versionId) != 1) throw new PledgeRejection("CONFLICT");
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("awardId", awardId);
            body.put("state", "RETURNED");
            body.put("historyStillPresent", coupons.useStatus(award.historyId) == 2);
            body.put("stock", mapper.lockStock(version.versionId));
            return body;
        });
    }

    public Map<String, Object> openCase(long ownerId, String versionId, String awardId, String reason) {
        requireText(reason);
        if ("CASH_REFUND".equals(reason)) throw new PledgeRejection("CASH_CLOSED");
        CaseRow row = new CaseRow();
        row.caseId = UUID.randomUUID().toString();
        row.awardId = awardId;
        row.versionId = versionId;
        row.ownerId = ownerId;
        row.reason = reason;
        row.state = "OPEN";
        row.createdAt = clock.millis();
        tx.executeWithoutResult(status -> mapper.insertCase(row));
        return caseView(row.caseId);
    }

    public Map<String, Object> compensate(long actorId, Claim claim, String originalAwardId, String compensationKey) {
        requireText(compensationKey);
        requireText(originalAwardId);
        requireText(claim.requestId);
        requireText(claim.versionId);
        if (compensationKey.length() > 64) throw new PledgeRejection("INVALID_INPUT");
        if (!open("COMPENSATION", "", null)) throw new PledgeRejection("POLICY_CLOSED");
        Award original = tx.execute(status -> mapper.findAward(originalAwardId));
        if (original == null) throw new PledgeRejection("NOT_FOUND");
        if (claim.versionId.equals(original.versionId)) throw new PledgeRejection("NOT_NEW_SUPPLY");
        Version version = tx.execute(status -> mapper.findVersion(claim.versionId));
        Pledge pledge = version == null ? null : tx.execute(status -> mapper.findPledge(version.pledgeId));
        if (pledge == null || pledge.merchantMemberId != actorId) throw new PledgeRejection("FORBIDDEN");
        claim.ownerId = original.beneficiaryId;
        claim.branch = "COMPENSATION";
        claim.subjectId = originalAwardId + ":" + compensationKey;
        return claim(claim);
    }

    public Map<String, Object> reverse(long operatorId, String awardId, String reason) {
        requireText(reason);
        if (!open("REVERSE", "", null)) throw new PledgeRejection("POLICY_CLOSED");
        return tx.execute(status -> {
            Award award = mapper.findAward(awardId);
            if (award == null || award.historyId == null) throw new PledgeRejection("NOT_FOUND");
            boolean reversed = coupons.reverse(award.historyId, reason, operatorId);
            CaseRow row = new CaseRow();
            row.caseId = UUID.randomUUID().toString();
            row.awardId = awardId;
            row.versionId = award.versionId;
            row.ownerId = award.beneficiaryId;
            row.reason = "REVERSE";
            row.state = reversed ? "REVERSED" : "UNCHANGED";
            row.createdAt = clock.millis();
            mapper.insertCase(row);
            Map<String, Object> body = caseView(row.caseId);
            body.put("awardStillPresent", mapper.findAward(awardId) != null);
            return body;
        });
    }

    public Map<String, Object> dispatchOutbox(String awardId) {
        return tx.execute(status -> {
            Integer attempt = mapper.outboxAttempt(awardId);
            if (attempt == null) throw new PledgeRejection("NOT_FOUND");
            mapper.markOutboxSent(awardId);
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("awardId", awardId);
            body.put("attempt", mapper.outboxAttempt(awardId));
            body.put("stock", mapper.lockStock(mapper.findAward(awardId).versionId));
            return body;
        });
    }

    public Map<String, Object> readPledge(String pledgeId) {
        return tx.execute(status -> viewPledge(pledgeId));
    }

    public Map<String, Object> readPledge(long storeId, String pledgeId) {
        return tx.execute(status -> {
            if (mustPledge(pledgeId).storeId != storeId) throw new PledgeRejection("FORBIDDEN");
            return viewPledge(pledgeId);
        });
    }

    public Map<String, Object> stats(String pledgeId) {
        return statsFor(null, pledgeId);
    }

    public Map<String, Object> stats(long storeId, String pledgeId) {
        return statsFor(storeId, pledgeId);
    }

    private Map<String, Object> statsFor(Long storeId, String pledgeId) {
        return tx.execute(status -> {
            Pledge pledge = mustPledge(pledgeId);
            if (storeId != null && pledge.storeId != storeId) throw new PledgeRejection("FORBIDDEN");
            Version version = current(pledgeId);
            Stock stock = mapper.lockStock(version.versionId);
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("pledgeId", pledgeId);
            body.put("versionId", version.versionId);
            body.put("versionNo", stock == null ? null : stock.versionNo);
            body.put("asOf", clock.millis());
            body.put("awardCount", mapper.countAwards(version.versionId));
            body.put("stock", stock);
            body.put("conserved", stock != null && stock.sum() == stock.quantity);
            return body;
        });
    }

    public List<Award> listMine(long ownerId) {
        return tx.execute(status -> mapper.listAwards(ownerId));
    }

    public Map<String, Object> detail(long ownerId, String awardId) {
        return tx.execute(status -> {
            Award award = mapper.findAward(awardId);
            if (award == null || award.beneficiaryId != ownerId) throw new PledgeRejection("NOT_FOUND");
            return awardView(award);
        });
    }

    private Map<String, Object> finish(Claim claim, String hash) {
        return tx.execute(status -> {
            Intent intent = mapper.lockIntent(claim.ownerId, claim.requestId);
            if (intent == null || !hash.equals(intent.payloadHash)) throw new PledgeRejection("CONFLICT");
            if (!"UNKNOWN".equals(intent.state)) return intentResult(intent);
            Version version = mapper.findVersion(claim.versionId);
            Pledge pledge = version == null ? null : mapper.findPledge(version.pledgeId);
            if (version == null || pledge == null) throw new PledgeRejection("NOT_FOUND");
            if (!"FROZEN".equals(version.state) || version.ruleVersion == null) {
                return refuse(intent, "PLEDGE_NOT_FROZEN");
            }
            if (!open("CLAIM", version.ruleVersion, null)) return refuse(intent, "POLICY_CLOSED");
            if (version.minorRestricted == 1 && !open("MINOR", version.ruleVersion, null)) {
                return refuse(intent, "MINOR_CLOSED");
            }
            String scope = "PLEDGE:" + version.versionId;
            String factRef;
            if ("COMPENSATION".equals(claim.branch)) {
                if (!open("COMPENSATION", "", null)) return refuse(intent, "POLICY_CLOSED");
                int split = claim.subjectId.indexOf(':');
                if (split <= 0 || split >= claim.subjectId.length() - 1) throw new PledgeRejection("INVALID_INPUT");
                Award original = mapper.findAward(claim.subjectId.substring(0, split));
                if (original == null) throw new PledgeRejection("NOT_FOUND");
                if (original.beneficiaryId != claim.ownerId) throw new PledgeRejection("FORBIDDEN");
                if (original.versionId.equals(version.versionId)) return refuse(intent, "NOT_NEW_SUPPLY");
                factRef = "COMPENSATION:" + claim.subjectId;
            } else if (!"THEME".equals(claim.branch)) {
                return refuse(intent, "NO_AUTHORITATIVE_FACT");
            } else {
                long topicId;
                try {
                    topicId = Long.parseLong(claim.subjectId);
                } catch (NumberFormatException bad) {
                    throw new PledgeRejection("INVALID_INPUT");
                }
                Long sessionId = mapper.completedThemeSession(claim.ownerId, topicId);
                if (sessionId == null) throw new PledgeRejection("NO_AUTHORITATIVE_FACT");
                factRef = "THEME:" + topicId + ":session:" + sessionId;
            }
            Award existing = mapper.findAwardByKey(version.ruleVersion, claim.ownerId, scope, factRef);
            if (existing != null) {
                mapper.updateIntent(claim.ownerId, claim.requestId, "REPLAY", existing.awardId, null);
                return awardView(existing);
            }
            Stock stock = mapper.lockStock(version.versionId);
            gate("reserve");
            if (stock == null || mapper.reserveOne(version.versionId, stock.versionNo) != 1) {
                return refuse(intent, "STOCK_EXHAUSTED");
            }
            Award award = new Award();
            award.awardId = UUID.randomUUID().toString();
            award.versionId = version.versionId;
            award.ruleVersion = version.ruleVersion;
            award.beneficiaryId = claim.ownerId;
            award.rewardScope = scope;
            award.factRef = factRef;
            award.state = "AWARDING";
            award.requestId = claim.requestId;
            award.createdAt = clock.millis();
            gate("award");
            mapper.insertAward(award);
            gate("coupon");
            Long historyId = coupons.grantOnce(claim.ownerId, pledge.couponId);
            if (historyId == null) throw new PledgeRejection("COUPON_NOT_ISSUED");
            if (mapper.bindHistory(award.awardId, historyId) != 1) throw new PledgeRejection("COUPON_NOT_ISSUED");
            gate("receipt");
            mapper.insertReceipt(award.awardId, historyId, version.payloadHash, clock.millis());
            gate("outbox");
            mapper.insertOutbox(award.awardId);
            gate("settle");
            if (mapper.settleAward(version.versionId, stock.versionNo + 1) != 1) throw new PledgeRejection("STOCK_CONFLICT");
            gate("intent-update");
            mapper.updateIntent(claim.ownerId, claim.requestId, "AWARDED", award.awardId, null);
            award.historyId = historyId;
            award.state = "AWARDED";
            return awardView(award);
        });
    }

    private Map<String, Object> refuse(Intent intent, String code) {
        mapper.updateIntent(intent.ownerId, intent.requestId, "REFUSED", null, code);
        if ("STOCK_EXHAUSTED".equals(code)) {
            CaseRow row = new CaseRow();
            row.caseId = UUID.randomUUID().toString();
            row.versionId = intent.versionId;
            row.ownerId = intent.ownerId;
            row.reason = code;
            row.state = "OPEN";
            row.createdAt = clock.millis();
            mapper.insertCase(row);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("requestId", intent.requestId);
        body.put("state", "REFUSED");
        body.put("errorCode", code);
        body.put("guaranteed", false);
        body.put("blocked", "W06_RESERVATION_ABSENT");
        return body;
    }

    private Map<String, Object> fulfilmentView(long storeId, String awardId, boolean redeeming) {
        Award award = mapper.findAward(awardId);
        if (award == null || award.historyId == null) throw new PledgeRejection("NOT_FOUND");
        Pledge pledge = merchantOf(award);
        if (pledge.storeId != storeId) throw new PledgeRejection("FORBIDDEN");
        Map<String, Object> body = awardView(award);
        body.put("previewOnly", !redeeming);
        body.put("storeId", storeId);
        return body;
    }

    private Pledge merchantOf(Award award) {
        Version version = mapper.findVersion(award.versionId);
        return mapper.findPledge(version.pledgeId);
    }

    private Map<String, Object> intentResult(Intent intent) {
        if (intent.awardId != null) return awardView(mapper.findAward(intent.awardId));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("requestId", intent.requestId);
        body.put("state", intent.state);
        body.put("errorCode", intent.errorCode);
        body.put("guaranteed", false);
        return body;
    }

    private Map<String, Object> awardView(Award award) {
        Version version = mapper.findVersion(award.versionId);
        Pledge pledge = mapper.findPledge(version.pledgeId);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("awardId", award.awardId);
        body.put("requestId", award.requestId);
        body.put("state", award.state);
        body.put("historyId", award.historyId);
        body.put("factRef", award.factRef);
        body.put("terms", version.terms);
        body.put("termsHash", version.payloadHash);
        body.put("ruleVersion", award.ruleVersion);
        body.put("redeemStart", version.redeemStart);
        body.put("redeemEnd", version.redeemEnd);
        body.put("guaranteed", false);
        body.put("blocked", "W06_RESERVATION_ABSENT");
        if (award.historyId != null) body.put("useStatus", coupons.useStatus(award.historyId));
        return body;
    }

    private Map<String, Object> viewPledge(String pledgeId) {
        Pledge pledge = mustPledge(pledgeId);
        Version version = current(pledgeId);
        Stock stock = version == null ? null : mapper.lockStock(version.versionId);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("pledge", pledge);
        body.put("version", version);
        body.put("stock", stock);
        body.put("conserved", stock == null || stock.sum() == stock.quantity);
        body.put("guaranteed", false);
        return body;
    }

    private Map<String, Object> caseView(String caseId) {
        CaseRow row = mapper.findCase(caseId);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("caseId", row.caseId);
        body.put("awardId", row.awardId);
        body.put("state", row.state);
        body.put("reason", row.reason);
        return body;
    }

    private Pledge owned(long actorId, String pledgeId) {
        Pledge pledge = mustPledge(pledgeId);
        if (pledge.merchantMemberId != actorId) throw new PledgeRejection("FORBIDDEN");
        return pledge;
    }

    private Pledge mustPledge(String pledgeId) {
        Pledge pledge = mapper.findPledge(pledgeId);
        if (pledge == null) throw new PledgeRejection("NOT_FOUND");
        return pledge;
    }

    private Version current(String pledgeId) {
        Version version = mapper.latestVersion(pledgeId);
        if (version == null) throw new PledgeRejection("NOT_FOUND");
        return version;
    }

    private Version next(Version previous, String state) {
        Version version = new Version();
        version.versionId = UUID.randomUUID().toString();
        version.pledgeId = previous.pledgeId;
        version.versionNo = previous.versionNo + 1;
        version.payloadHash = previous.payloadHash;
        version.state = state;
        snapshot(version, previous);
        return version;
    }

    private void validateSupply(Draft draft) {
        requireText(draft.supplyRef);
        requireText(draft.terms);
        if (draft.quantity <= 0 || draft.serviceStart <= 0 || draft.serviceEnd <= draft.serviceStart
                || draft.redeemStart <= 0 || draft.redeemEnd <= draft.redeemStart) {
            throw new PledgeRejection("INVALID_INPUT");
        }
        if (!"NON_CASH".equals(draft.rewardKind)) throw new PledgeRejection("CASH_CLOSED");
        CouponView coupon = coupons.load(draft.couponId);
        if (coupon == null || coupon.merchantId == null || coupon.merchantId != draft.storeId
                || coupon.memberId == null || coupon.memberId != draft.merchantMemberId) {
            throw new PledgeRejection("NOT_FOUND");
        }
        if (coupon.startTime == null || coupon.endTime == null
                || coupon.startTime.getTime() != draft.redeemStart || coupon.endTime.getTime() != draft.redeemEnd) {
            throw new PledgeRejection("COUPON_WINDOW_MISMATCH");
        }
    }

    private static Pledge pledgeFrom(Draft draft, String pledgeId, long now) {
        Pledge pledge = new Pledge();
        pledge.pledgeId = pledgeId;
        pledge.storeId = draft.storeId;
        pledge.merchantMemberId = draft.merchantMemberId;
        pledge.couponId = draft.couponId;
        pledge.supplyRef = draft.supplyRef;
        pledge.quantity = draft.quantity;
        pledge.serviceStart = draft.serviceStart;
        pledge.serviceEnd = draft.serviceEnd;
        pledge.redeemStart = draft.redeemStart;
        pledge.redeemEnd = draft.redeemEnd;
        pledge.terms = draft.terms;
        pledge.minorRestricted = draft.minorRestricted ? 1 : 0;
        pledge.state = "DRAFT";
        pledge.createdAt = now;
        return pledge;
    }

    private static void snapshot(Version version, Pledge pledge) {
        version.quantity = pledge.quantity;
        version.serviceStart = pledge.serviceStart;
        version.serviceEnd = pledge.serviceEnd;
        version.redeemStart = pledge.redeemStart;
        version.redeemEnd = pledge.redeemEnd;
        version.terms = pledge.terms;
        version.minorRestricted = pledge.minorRestricted;
    }

    private static void snapshot(Version version, Version previous) {
        version.quantity = previous.quantity;
        version.serviceStart = previous.serviceStart;
        version.serviceEnd = previous.serviceEnd;
        version.redeemStart = previous.redeemStart;
        version.redeemEnd = previous.redeemEnd;
        version.terms = previous.terms;
        version.minorRestricted = previous.minorRestricted;
    }

    private boolean open(String code, String ruleVersion, Long actorId) {
        Policy policy = mapper.findPolicy(code, ruleVersion == null ? "" : ruleVersion);
        if (policy == null || !"OPEN".equals(policy.decision)) return false;
        return actorId == null || actorId.equals(policy.actorMemberId);
    }

    private void gate(String point) {
        if (gate != null) gate.before(point);
    }

    private static void requireText(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 128) throw new PledgeRejection("INVALID_INPUT");
    }

    private static String claimHash(Claim claim) {
        return sha(claim.versionId + "|" + claim.branch + "|" + claim.subjectId + "|" + claim.ownerId);
    }

    private static String payloadHash(Pledge pledge) {
        return sha(pledge.couponId + "|" + pledge.quantity + "|" + pledge.serviceStart + "|" + pledge.serviceEnd
                + "|" + pledge.redeemStart + "|" + pledge.redeemEnd + "|" + pledge.terms + "|" + pledge.minorRestricted
                + "|" + pledge.supplyRef);
    }

    private static String sha(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xf, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        } catch (Exception failed) {
            throw new IllegalStateException("SHA-256", failed);
        }
    }

    public static final class Draft {
        public long storeId, merchantMemberId, couponId, serviceStart, serviceEnd, redeemStart, redeemEnd;
        public int quantity;
        public boolean minorRestricted;
        public String supplyRef, terms, rewardKind;
    }

    public static final class Claim {
        public long ownerId;
        public String requestId, versionId, branch, subjectId;
    }
}

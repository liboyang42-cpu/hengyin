package com.chengyinhub.business.service.rewardpledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Award;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.CaseRow;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Intent;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.OpReceipt;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Pledge;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Policy;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Stock;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Version;
import com.chengyinhub.business.mapper.rewardpledge.RewardPledgeMapper;
import com.chengyinhub.business.service.rewardpledge.PledgeCouponPort.CouponView;

/** Pledge, claim, fulfil and recover one non-cash batch.
 * Not a points, wallet or sellable-SKU ledger. Policy rows that are missing stay closed.
 * Store writes (draft, submit, confirm, hold, withdraw, expire, return) leave one receipt per (store, op, key) that the
 * app reads back through {@link #readOp} when it lost the response: APPLIED with the same digest replays without a
 * second write, a different digest is CONFLICT, and a stored REJECTED is replaced by a retry. */
@Service
public class RewardPledgeService {
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    static final int MAX_PLEDGE_QUANTITY = 1_000_000;
    static final List<String> OPS = List.of("draft", "submit", "confirm", "hold", "withdraw", "expire", "return");
    private final TransactionTemplate tx;
    private final TransactionTemplate fresh;
    private final RewardPledgeMapper mapper;
    private final PledgeCouponPort coupons;
    private final Clock clock;
    private final WriteGate gate;
    private final CashPayoutSwitch cash;

    @Autowired
    public RewardPledgeService(PlatformTransactionManager manager, RewardPledgeMapper mapper, PledgeCouponPort coupons,
                               CashPayoutSwitch cash) {
        this(manager, mapper, coupons, Clock.systemUTC(), null, cash);
    }

    public RewardPledgeService(PlatformTransactionManager manager, RewardPledgeMapper mapper, PledgeCouponPort coupons,
                               Clock clock, WriteGate gate) {
        this(manager, mapper, coupons, clock, gate, CashPayoutSwitch.off());
    }

    public RewardPledgeService(PlatformTransactionManager manager, RewardPledgeMapper mapper, PledgeCouponPort coupons,
                               Clock clock, WriteGate gate, CashPayoutSwitch cash) {
        this.tx = new TransactionTemplate(manager);
        this.fresh = new TransactionTemplate(manager);
        this.fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mapper = mapper;
        this.coupons = coupons;
        this.clock = clock;
        this.gate = gate;
        this.cash = cash;
    }

    /** The app's draft: {@code requestId} is the pledge id, so a lost response is replayed, not duplicated. */
    public Map<String, Object> draft(Draft draft) {
        String pledgeId = draft.requestId;
        if (pledgeId == null || !ID.matcher(pledgeId).matches()) throw new PledgeRejection("INVALID_INPUT");
        cashGate(draft.rewardKind);
        Pledge pledge = pledgeFrom(draft, pledgeId, clock.millis());
        String digest = payloadHash(pledge);
        return receipted(draft.storeId, draft.actorMemberId(), "draft", pledgeId, digest, () -> {
            validateSupply(draft);
            if (mapper.findPledge(pledgeId) != null) throw new PledgeRejection("CONFLICT");
            Version version = new Version();
            version.versionId = newId();
            version.pledgeId = pledgeId;
            version.versionNo = 1;
            version.payloadHash = digest;
            version.state = "DRAFT";
            snapshot(version, pledge);
            gate("draft-pledge");
            mapper.insertPledge(pledge);
            gate("draft-version");
            mapper.insertVersion(version);
            return new Done(pledgeId, version.versionId, null);
        });
    }

    /** {@code key} is the version the merchant saw; the receipt names it, the response carries the new SUBMITTED version. */
    public Map<String, Object> submit(long storeId, long actorId, String pledgeId) {
        Version seen = tx.execute(status -> current(inStore(storeId, pledgeId).pledgeId));
        return receipted(storeId, actorId, "submit", seen.versionId, sha("submit|" + pledgeId + "|" + seen.versionId), () -> {
            Pledge pledge = inStore(storeId, pledgeId);
            Version version = current(pledge.pledgeId);
            if (!version.versionId.equals(seen.versionId) || !"DRAFT".equals(version.state)) throw new PledgeRejection("CONFLICT");
            mapper.updatePledgeState(pledgeId, "SUBMITTED");
            mapper.insertVersion(next(version, "SUBMITTED"));
            return new Done(pledgeId, seen.versionId, null);
        });
    }

    /** The confirming person (principal) is recorded; the reviewer who freezes must be someone else. */
    public Map<String, Object> confirm(long storeId, long actorId, String pledgeId) {
        Version seen = tx.execute(status -> current(inStore(storeId, pledgeId).pledgeId));
        return receipted(storeId, actorId, "confirm", seen.versionId, sha("confirm|" + pledgeId + "|" + seen.versionId), () -> {
            Pledge pledge = inStore(storeId, pledgeId);
            Version version = current(pledgeId);
            if (!version.versionId.equals(seen.versionId)) throw new PledgeRejection("CONFLICT");
            String hash = sha(version.payloadHash + ":" + actorId);
            if (mapper.confirmVersion(version.versionId, actorId, hash, clock.millis()) != 1) {
                throw new PledgeRejection("CONFLICT");
            }
            mapper.updatePledgeState(pledge.pledgeId, "CONFIRMED");
            return new Done(pledgeId, seen.versionId, null);
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

    public Map<String, Object> withdraw(long storeId, long actorId, String versionId) {
        requireId(versionId);
        return receipted(storeId, actorId, "withdraw", versionId, sha("withdraw|" + versionId), () -> {
            Version version = mapper.findVersion(versionId);
            if (version == null) throw new PledgeRejection("NOT_FOUND");
            inStore(storeId, version.pledgeId);
            Stock stock = mapper.lockStock(versionId);
            if (stock == null || mapper.withdrawVersion(versionId) != 1) throw new PledgeRejection("CONFLICT");
            if (mapper.withdrawAvailable(versionId, stock.versionNo) != 1) throw new PledgeRejection("CONFLICT");
            mapper.updatePledgeState(version.pledgeId, "WITHDRAWN");
            return new Done(version.pledgeId, versionId, null);
        });
    }

    /** A new draft version. The frozen version and its awards stay. */
    public Map<String, Object> revise(long storeId, String pledgeId, Draft draft) {
        cashGate(draft.rewardKind);
        validateSupply(draft);
        return tx.execute(status -> {
            Pledge pledge = inStore(storeId, pledgeId);
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

    public Map<String, Object> hold(long storeId, long actorId, String versionId) {
        requireId(versionId);
        if (!open("STORE_HOLD", "", null)) throw new PledgeRejection("POLICY_CLOSED");
        return receipted(storeId, actorId, "hold", versionId, sha("hold|" + versionId), () -> {
            Version version = mapper.findVersion(versionId);
            if (version == null) throw new PledgeRejection("NOT_FOUND");
            inStore(storeId, version.pledgeId);
            Stock stock = mapper.lockStock(versionId);
            if (stock == null || !"FROZEN".equals(version.state)) throw new PledgeRejection("CONFLICT");
            if (mapper.holdAvailable(versionId, stock.versionNo) != 1) throw new PledgeRejection("CONFLICT");
            return new Done(version.pledgeId, versionId, null);
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

    /** The coupon's existing dynamic code (POST /api/coupon/qr-token), resolved to this store's award. Read-only. */
    public Map<String, Object> resolve(long storeId, String code) {
        if (code == null || code.trim().isEmpty() || code.length() > 512) throw new PledgeRejection("INVALID_INPUT");
        Long historyId = coupons.resolveCode(code.trim());
        if (historyId == null) throw new PledgeRejection("NOT_FOUND");
        return tx.execute(status -> {
            Award award = mapper.findAwardByHistory(historyId);
            if (award == null) throw new PledgeRejection("NOT_FOUND");
            return fulfilmentView(storeId, award.awardId, false);
        });
    }

    public List<Map<String, Object>> storeAwards(long storeId) {
        return tx.execute(status -> {
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            for (Award award : mapper.listStoreAwards(storeId)) rows.add(awardRow(award));
            return rows;
        });
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
            view.put("useStatus", coupons.useStatus(award.historyId));
            view.put("stock", mapper.lockStock(award.versionId));
            return view;
        });
    }

    public Map<String, Object> expire(long storeId, long actorId, String awardId, long now) {
        requireId(awardId);
        return receipted(storeId, actorId, "expire", awardId, sha("expire|" + awardId), () -> {
            Award award = mapper.findAward(awardId);
            if (award == null || award.historyId == null) throw new PledgeRejection("NOT_FOUND");
            if (merchantOf(award).storeId != storeId) throw new PledgeRejection("FORBIDDEN");
            boolean moved = coupons.expireUnused(award.historyId, new Date(now));
            if (moved) mapper.markExpired(award.versionId);
            Done done = new Done(null, award.versionId, awardId);
            done.extra.put("expiredNow", moved);
            return done;
        });
    }

    public Map<String, Object> returnToPool(long storeId, long actorId, String awardId, long now) {
        requireId(awardId);
        if (!open("RETURN_TO_POOL", "", null)) throw new PledgeRejection("POLICY_CLOSED");
        return receipted(storeId, actorId, "return", awardId, sha("return|" + awardId), () -> {
            Award award = mapper.findAward(awardId);
            if (award == null || award.historyId == null) throw new PledgeRejection("NOT_FOUND");
            Version version = mapper.findVersion(award.versionId);
            Pledge pledge = version == null ? null : mapper.findPledge(version.pledgeId);
            if (pledge == null) throw new PledgeRejection("NOT_FOUND");
            if (pledge.storeId != storeId) throw new PledgeRejection("FORBIDDEN");
            if (!"FROZEN".equals(version.state)) throw new PledgeRejection("CONFLICT");
            if (now < version.serviceStart || now >= version.serviceEnd) throw new PledgeRejection("WINDOW_CLOSED");
            int use = coupons.useStatus(award.historyId);
            if (use == 1) throw new PledgeRejection("ALREADY_USED");
            if (use != 2) throw new PledgeRejection("CONFLICT");
            Stock stock = mapper.lockStock(version.versionId);
            if (stock == null || stock.frozenUnits > 0) throw new PledgeRejection("CONFLICT");
            if (mapper.markAwardReturned(award.awardId) != 1) throw new PledgeRejection("CONFLICT");
            if (mapper.returnExpired(version.versionId) != 1) throw new PledgeRejection("CONFLICT");
            Done done = new Done(null, version.versionId, awardId);
            done.extra.put("historyStillPresent", coupons.useStatus(award.historyId) == 2);
            return done;
        });
    }

    /** A player's case on their own award. A cash refund case only opens when moneyPayments and payout are on;
     * it is then routed to the existing refund desk, nothing here moves money. */
    public Map<String, Object> openCase(long ownerId, String versionId, String awardId, String reason) {
        requireText(reason);
        requireId(awardId);
        requireId(versionId);
        if ("CASH_REFUND".equals(reason.trim()) && !cash.open()) throw new PledgeRejection("CASH_CLOSED");
        return tx.execute(status -> {
            Award award = mapper.findAward(awardId);
            if (award == null || award.beneficiaryId != ownerId || !award.versionId.equals(versionId)) {
                throw new PledgeRejection("NOT_FOUND");
            }
            CaseRow row = new CaseRow();
            row.caseId = newId();
            row.awardId = awardId;
            row.versionId = versionId;
            row.ownerId = ownerId;
            row.reason = reason.trim();
            row.state = "OPEN";
            row.createdAt = clock.millis();
            mapper.insertCase(row);
            return caseView(row.caseId);
        });
    }

    public Map<String, Object> readCase(long ownerId, String caseId) {
        requireId(caseId);
        return tx.execute(status -> {
            CaseRow row = mapper.findCase(caseId);
            if (row == null || row.ownerId != ownerId) throw new PledgeRejection("NOT_FOUND");
            return caseView(caseId);
        });
    }

    public Map<String, Object> compensate(long storeId, long actorId, Claim claim, String originalAwardId, String compensationKey) {
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
        if (pledge == null || pledge.storeId != storeId) throw new PledgeRejection("FORBIDDEN");
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
            row.caseId = newId();
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

    /** This store's pledges, newest first, each with its versions (newest first). */
    public Map<String, Object> ownedPledges(long storeId) {
        return tx.execute(status -> {
            List<Map<String, Object>> pledges = new ArrayList<Map<String, Object>>();
            for (Pledge pledge : mapper.listStorePledges(storeId)) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("pledge", pledge);
                item.put("versions", mapper.listVersions(pledge.pledgeId));
                pledges.add(item);
            }
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("store", storeId);
            body.put("pledges", pledges);
            body.put("guaranteed", false);
            return body;
        });
    }

    /** Merchant-confirmed versions waiting for a reviewer. Only the REVIEWER policy actor reads it, and never sees
     * a version it confirmed itself. */
    public Map<String, Object> reviewQueue(long reviewerId) {
        if (!open("REVIEWER", "", reviewerId)) throw new PledgeRejection("POLICY_CLOSED");
        return tx.execute(status -> {
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            for (Version version : mapper.reviewQueue()) {
                if (version.merchantMemberId != null && version.merchantMemberId == reviewerId) continue;
                Pledge pledge = mapper.findPledge(version.pledgeId);
                Map<String, Object> item = reviewItem(version);
                item.put("storeId", pledge == null ? null : pledge.storeId);
                item.put("couponId", pledge == null ? null : pledge.couponId);
                items.add(item);
            }
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("store", "PLATFORM_REVIEW");
            body.put("items", items);
            return body;
        });
    }

    public Map<String, Object> reviewVersion(long reviewerId, String versionId) {
        requireId(versionId);
        if (!open("REVIEWER", "", reviewerId)) throw new PledgeRejection("POLICY_CLOSED");
        return tx.execute(status -> {
            Version version = mapper.findVersion(versionId);
            if (version == null) throw new PledgeRejection("NOT_FOUND");
            Map<String, Object> body = reviewItem(version);
            body.put("ruleVersion", version.ruleVersion);
            body.put("frozenAt", version.frozenAt);
            body.put("stock", mapper.lockStock(versionId));
            return body;
        });
    }

    /** The stored receipt for one store write, with the current object. 404 until a receipt exists. */
    public Map<String, Object> readOp(long storeId, String op, String key) {
        if (!OPS.contains(op)) throw new PledgeRejection("NOT_FOUND");
        requireId(key);
        return tx.execute(status -> {
            OpReceipt receipt = mapper.findOpReceipt(storeId, op, key);
            if (receipt == null) throw new PledgeRejection("NOT_FOUND");
            return withReceipt(receipt);
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

    /** {@code GET /api/rewards/noncash}: one array of the owner's awards (RewardPledgeAwardRow). */
    public List<Map<String, Object>> listMine(long ownerId) {
        return tx.execute(status -> {
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            for (Award award : mapper.listAwards(ownerId)) rows.add(awardRow(award));
            return rows;
        });
    }

    /** {@code GET /api/rewards/noncash?limit&cursor}: the NonCashRewardService page. Granted awards only, by award id. */
    public Map<String, Object> pageMine(long ownerId, int limit, String cursor) {
        if (limit < 1 || limit > 50 || (cursor != null && !NonCashWire.isAwardId(cursor))) throw new PledgeRejection("INVALID_INPUT");
        return tx.execute(status -> {
            long asOf = clock.millis();
            List<Award> rows = mapper.pageAwards(ownerId, cursor, limit + 1);
            boolean more = rows.size() > limit;
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            for (Award award : rows.subList(0, Math.min(limit, rows.size()))) items.add(nonCash(award, asOf));
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("items", items);
            body.put("nextCursor", more ? rows.get(limit - 1).awardId : null);
            body.put("asOf", asOf);
            return body;
        });
    }

    public Map<String, Object> detail(long ownerId, String awardId) {
        return tx.execute(status -> {
            Award award = mapper.findAward(awardId);
            if (award == null || award.beneficiaryId != ownerId) throw new PledgeRejection("NOT_FOUND");
            return awardView(award);
        });
    }

    /** NonCashRewardService.reward: the frozen scope must match the reference the app holds, or 404. */
    public Map<String, Object> nonCashDetail(long ownerId, String awardId, String contextType, String contextId,
                                             String releaseId, String instanceId) {
        return tx.execute(status -> {
            Award award = mapper.findAward(awardId);
            if (award == null || award.beneficiaryId != ownerId || award.historyId == null) throw new PledgeRejection("NOT_FOUND");
            Map<String, Object> body = nonCash(award, clock.millis());
            if (!String.valueOf(body.get("contextType")).equals(contextType) || !String.valueOf(body.get("contextId")).equals(contextId)
                    || !String.valueOf(body.get("releaseId")).equals(releaseId) || !String.valueOf(body.get("instanceId")).equals(instanceId)) {
                throw new PledgeRejection("NOT_FOUND");
            }
            return body;
        });
    }

    /** One THEME subject (topic): the newest frozen pledge version a store participating in that topic offers. */
    public Map<String, Object> catalog(long ownerId, String subjectId) {
        long topicId = topic(subjectId);
        return tx.execute(status -> {
            List<Version> versions = mapper.subjectVersions(topicId);
            if (versions.isEmpty()) throw new PledgeRejection("NOT_FOUND");
            Version chosen = null;
            String error = null;
            for (Version version : versions) {
                String problem = offerProblem(version);
                if (problem == null) { chosen = version; error = null; break; }
                if (chosen == null) { chosen = version; error = problem; }
            }
            Pledge pledge = mapper.findPledge(chosen.pledgeId);
            Stock stock = mapper.lockStock(chosen.versionId);
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("subject", subjectId);
            body.put("versionId", chosen.versionId);
            body.put("ruleVersion", chosen.ruleVersion);
            body.put("termsHash", chosen.payloadHash);
            body.put("terms", chosen.terms);
            body.put("coupon", pledge.couponId);
            body.put("store", pledge.storeId);
            body.put("redeemStart", chosen.redeemStart);
            body.put("redeemEnd", chosen.redeemEnd);
            body.put("available", stock == null ? 0 : stock.available);
            body.put("guaranteed", false);
            body.put("blocked", "W06_RESERVATION_ABSENT");
            if (error != null) body.put("errorCode", error);
            return body;
        });
    }

    /** The offer for one version and the caller's own completion fact. Refusals are data (errorCode), not errors. */
    public Map<String, Object> offer(long ownerId, String versionId, String branch, String subjectId) {
        requireId(versionId);
        if (!"THEME".equals(branch)) throw new PledgeRejection("INVALID_INPUT");
        long topicId = topic(subjectId);
        return tx.execute(status -> {
            Version version = mapper.findVersion(versionId);
            Pledge pledge = version == null ? null : mapper.findPledge(version.pledgeId);
            if (pledge == null) throw new PledgeRejection("NOT_FOUND");
            Stock stock = mapper.lockStock(versionId);
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("versionId", versionId);
            body.put("subject", subjectId);
            body.put("ruleVersion", version.ruleVersion);
            body.put("terms", version.terms);
            body.put("termsHash", version.payloadHash);
            body.put("rewardScope", "PLEDGE:" + versionId);
            body.put("storeId", pledge.storeId);
            body.put("serviceStart", version.serviceStart);
            body.put("serviceEnd", version.serviceEnd);
            body.put("redeemStart", version.redeemStart);
            body.put("redeemEnd", version.redeemEnd);
            body.put("minorRestricted", version.minorRestricted);
            body.put("available", stock == null ? 0 : stock.available);
            body.put("guaranteed", false);
            body.put("blocked", "W06_RESERVATION_ABSENT");
            String error = offerProblem(version);
            if (error == null && mapper.subjectOffered(pledge.merchantMemberId, topicId) == 0) error = "SUBJECT_NOT_OFFERED";
            Long sessionId = mapper.completedThemeSession(ownerId, topicId);
            if (sessionId != null) body.put("factRef", "THEME:" + topicId + ":session:" + sessionId);
            else if (error == null) error = "NO_AUTHORITATIVE_FACT";
            if (error != null) body.put("errorCode", error);
            return body;
        });
    }

    private String offerProblem(Version version) {
        if (!"FROZEN".equals(version.state) || version.ruleVersion == null) return "PLEDGE_NOT_FROZEN";
        if (!open("CLAIM", version.ruleVersion, null)) return "POLICY_CLOSED";
        Stock stock = mapper.lockStock(version.versionId);
        if (stock == null || stock.available < 1) return "STOCK_EXHAUSTED";
        return null;
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
                if (mapper.subjectOffered(pledge.merchantMemberId, topicId) == 0) return refuse(intent, "SUBJECT_NOT_OFFERED");
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
            award.awardId = NonCashWire.newAwardId();
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
            row.caseId = newId();
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

    /** Runs one store write once per (store, op, key) and returns the body with its receipt. */
    private Map<String, Object> receipted(long storeId, long actorId, String op, String key, String digest, Supplier<Done> work) {
        OpReceipt prior = tx.execute(status -> mapper.findOpReceipt(storeId, op, key));
        if (prior != null && "APPLIED".equals(prior.state)) {
            if (!digest.equals(prior.payloadHash)) throw new PledgeRejection("CONFLICT");
            return tx.execute(status -> withReceipt(prior));
        }
        try {
            return tx.execute(status -> {
                mapper.deleteRejectedOpReceipt(storeId, op, key);
                Done done = work.get();
                OpReceipt receipt = receipt(storeId, actorId, op, key, digest, "APPLIED", null, done);
                mapper.insertOpReceipt(receipt);
                Map<String, Object> body = withReceipt(receipt);
                body.putAll(done.extra);
                return body;
            });
        } catch (DuplicateKeyException raced) {
            OpReceipt stored = tx.execute(status -> mapper.findOpReceipt(storeId, op, key));
            if (stored == null || !"APPLIED".equals(stored.state) || !digest.equals(stored.payloadHash)) throw new PledgeRejection("CONFLICT");
            return tx.execute(status -> withReceipt(stored));
        } catch (PledgeRejection rejected) {
            try {
                fresh.executeWithoutResult(status -> {
                    if (mapper.findOpReceipt(storeId, op, key) == null) {
                        mapper.insertOpReceipt(receipt(storeId, actorId, op, key, digest, "REJECTED", rejected.code, new Done(
                                "draft".equals(op) ? key : null, null, null)));
                    }
                });
            } catch (DuplicateKeyException ignored) {
                // A concurrent write stored its own receipt first; that receipt stands.
            }
            throw rejected;
        }
    }

    private OpReceipt receipt(long storeId, long actorId, String op, String key, String digest, String state, String error, Done done) {
        OpReceipt receipt = new OpReceipt();
        receipt.storeId = storeId;
        receipt.actorMemberId = actorId;
        receipt.op = op;
        receipt.opKey = key;
        receipt.payloadHash = digest;
        receipt.state = state;
        receipt.errorCode = error;
        receipt.pledgeId = done.pledgeId;
        receipt.versionId = done.versionId;
        receipt.awardId = done.awardId;
        receipt.createdAt = clock.millis();
        return receipt;
    }

    private Map<String, Object> withReceipt(OpReceipt receipt) {
        Map<String, Object> body;
        if (receipt.awardId != null && "APPLIED".equals(receipt.state)) {
            Award award = mapper.findAward(receipt.awardId);
            body = award == null ? new LinkedHashMap<String, Object>() : awardView(award);
            if (award != null) body.put("stock", mapper.lockStock(award.versionId));
        } else if (receipt.pledgeId != null && mapper.findPledge(receipt.pledgeId) != null) {
            body = viewPledge(receipt.pledgeId);
        } else {
            body = new LinkedHashMap<String, Object>();
        }
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("state", receipt.state);
        view.put("op", receipt.op);
        view.put("key", receipt.opKey);
        view.put("payloadHash", receipt.payloadHash);
        view.put("pledgeId", receipt.pledgeId);
        view.put("versionId", receipt.versionId);
        view.put("awardId", receipt.awardId);
        view.put("errorCode", receipt.errorCode);
        view.put("createdAt", receipt.createdAt);
        body.put("receipt", view);
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

    private Map<String, Object> awardRow(Award award) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("awardId", award.awardId);
        row.put("versionId", award.versionId);
        row.put("ruleVersion", award.ruleVersion);
        row.put("rewardScope", award.rewardScope);
        row.put("factRef", award.factRef);
        row.put("state", award.state);
        row.put("requestId", award.requestId);
        row.put("historyId", award.historyId == null ? null : String.valueOf(award.historyId));
        return row;
    }

    private Map<String, Object> awardView(Award award) {
        Version version = mapper.findVersion(award.versionId);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("awardId", award.awardId);
        body.put("versionId", award.versionId);
        body.put("requestId", award.requestId);
        body.put("state", award.state);
        body.put("historyId", award.historyId);
        body.put("factRef", award.factRef);
        body.put("rewardScope", award.rewardScope);
        body.put("terms", version.terms);
        body.put("termsHash", version.payloadHash);
        body.put("ruleVersion", award.ruleVersion);
        body.put("serviceStart", version.serviceStart);
        body.put("serviceEnd", version.serviceEnd);
        body.put("redeemStart", version.redeemStart);
        body.put("redeemEnd", version.redeemEnd);
        body.put("guaranteed", false);
        body.put("blocked", "W06_RESERVATION_ABSENT");
        if (award.historyId != null) body.put("useStatus", coupons.useStatus(award.historyId));
        return body;
    }

    private Map<String, Object> nonCash(Award award, long asOf) {
        Version version = mapper.findVersion(award.versionId);
        Pledge pledge = mapper.findPledge(version.pledgeId);
        int use = award.historyId == null ? -1 : coupons.useStatus(award.historyId);
        return NonCashWire.reward(award, version, pledge, use, asOf);
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

    private static Map<String, Object> reviewItem(Version version) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("versionId", version.versionId);
        item.put("pledgeId", version.pledgeId);
        item.put("versionNo", version.versionNo);
        item.put("state", version.state);
        item.put("payloadHash", version.payloadHash);
        item.put("quantity", version.quantity);
        item.put("terms", version.terms);
        item.put("serviceStart", version.serviceStart);
        item.put("serviceEnd", version.serviceEnd);
        item.put("redeemStart", version.redeemStart);
        item.put("redeemEnd", version.redeemEnd);
        item.put("minorRestricted", version.minorRestricted);
        item.put("merchantConfirmedAt", version.merchantConfirmedAt);
        return item;
    }

    private Map<String, Object> caseView(String caseId) {
        CaseRow row = mapper.findCase(caseId);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("caseId", row.caseId);
        body.put("awardId", row.awardId);
        body.put("versionId", row.versionId);
        body.put("state", row.state);
        body.put("reason", row.reason);
        body.put("createdAt", row.createdAt);
        return body;
    }

    private Pledge inStore(long storeId, String pledgeId) {
        requireId(pledgeId);
        Pledge pledge = mustPledge(pledgeId);
        if (pledge.storeId != storeId) throw new PledgeRejection("FORBIDDEN");
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
        version.versionId = newId();
        version.pledgeId = previous.pledgeId;
        version.versionNo = previous.versionNo + 1;
        version.payloadHash = previous.payloadHash;
        version.state = state;
        snapshot(version, previous);
        return version;
    }

    private void cashGate(String rewardKind) {
        if ("NON_CASH".equals(rewardKind)) return;
        if (!cash.open()) throw new PledgeRejection("CASH_CLOSED");
        throw new PledgeRejection("CASH_REWARD_UNSUPPORTED");
    }

    private void validateSupply(Draft draft) {
        requireText(draft.supplyRef);
        requireText(draft.terms);
        if (draft.quantity <= 0 || draft.quantity > MAX_PLEDGE_QUANTITY || draft.serviceStart <= 0 || draft.serviceEnd <= draft.serviceStart
                || draft.redeemStart <= 0 || draft.redeemEnd <= draft.redeemStart) {
            throw new PledgeRejection("INVALID_INPUT");
        }
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

    private static long topic(String subjectId) {
        if (subjectId == null || !subjectId.matches("[1-9][0-9]{0,18}")) throw new PledgeRejection("INVALID_INPUT");
        try {
            return Long.parseLong(subjectId);
        } catch (NumberFormatException bad) {
            throw new PledgeRejection("INVALID_INPUT");
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static void requireId(String value) {
        if (value == null || !ID.matcher(value).matches()) throw new PledgeRejection("INVALID_INPUT");
    }

    private static void requireText(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 128) throw new PledgeRejection("INVALID_INPUT");
    }

    private static String claimHash(Claim claim) {
        return sha(claim.versionId + "|" + claim.branch + "|" + claim.subjectId + "|" + claim.ownerId);
    }

    /** Same canonical string the app digests for a draft (RewardPledgeRequests.digest, op "draft"). */
    static String payloadHash(Pledge pledge) {
        return sha(pledge.couponId + "|" + pledge.quantity + "|" + pledge.serviceStart + "|" + pledge.serviceEnd
                + "|" + pledge.redeemStart + "|" + pledge.redeemEnd + "|" + pledge.terms + "|" + pledge.minorRestricted
                + "|" + pledge.supplyRef);
    }

    static String sha(String canonical) {
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

    private static final class Done {
        final String pledgeId, versionId, awardId;
        final Map<String, Object> extra = new LinkedHashMap<String, Object>();

        Done(String pledgeId, String versionId, String awardId) {
            this.pledgeId = pledgeId;
            this.versionId = versionId;
            this.awardId = awardId;
        }
    }

    public static final class Draft {
        public long storeId, merchantMemberId, principalMemberId, couponId, serviceStart, serviceEnd, redeemStart, redeemEnd;
        public int quantity;
        public boolean minorRestricted;
        public String requestId, supplyRef, terms, rewardKind;

        long actorMemberId() { return principalMemberId > 0 ? principalMemberId : merchantMemberId; }
    }

    public static final class Claim {
        public long ownerId;
        public String requestId, versionId, branch, subjectId;
    }
}

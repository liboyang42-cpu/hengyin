package com.chengyinhub.business.reward;

import java.time.Clock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.chengyinhub.business.reward.NonCashRewardContracts.*;
import static com.chengyinhub.business.reward.NonCashRewardRows.*;

/**
 * Isolated persistence evaluator, deliberately no @Service, controller or production factory.
 * A committed projection leaves TransactionTemplate only after successful commit.
 * Lock order: activation product -> pledge; lifecycle pledge -> reservation/award -> audit.
 * Source authority must share this transaction and honor compatible locks. No remote calls in a transaction.
 */
public final class NonCashRewardService {
    public enum Mode { DISABLED, ISOLATED_PERSISTENCE_EVALUATION }
    private final Mode mode;
    private final NonCashRewardMapper store;
    private final Authority authority;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final NonCashRewardCodec codec = new NonCashRewardCodec();
    public NonCashRewardService() { mode=Mode.DISABLED; store=null; authority=null; transaction=null; clock=null; }
    public NonCashRewardService(Mode mode, NonCashRewardMapper store, Authority authority,
                               PlatformTransactionManager manager, Clock clock) {
        require(mode!=null && store!=null && authority!=null && manager!=null && clock!=null,"MISSING_DEPENDENCY");
        this.mode=mode;this.store=store;this.authority=authority;this.transaction=new TransactionTemplate(manager);this.clock=clock;
    }
    private interface Work<T> { T run(long now); }
    private <T> T atomic(Work<T> work) {
        require(mode==Mode.ISOLATED_PERSISTENCE_EVALUATION,"NONCASH_REWARDS_DISABLED");
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"NESTED_TRANSACTION_FORBIDDEN");
        return transaction.execute(status -> work.run(clock.millis()));
    }
    private static void changed(int rows) { require(rows==1,"REWARD_WRITE_CONFLICT"); }
    /** Dedicated supply import, not merchant self-approval. Canonical SKU units can no longer be sold. */
    public void importApprovedPledge(String pledgeId) {
        atomic(now -> {
            Pledge p=authority.approvedPledge(id(pledgeId)); require(p!=null && p.pledgeId.equals(pledgeId),"PLEDGE_NOT_APPROVED");
            authority.requireCurrentFulfillment(p,now); require(now<p.validUntil,"PLEDGE_EXPIRED");
            require(store.lockProduct(p.productId,p.merchantId)!=null,"PRODUCT_NOT_OWNED_OR_SELLABLE");
            now=clock.millis();authority.requireCurrentFulfillment(p,now);require(now<p.validUntil,"PLEDGE_EXPIRED");
            PledgeRow existing=store.lockPledge(pledgeId);
            if(existing!=null) { require(existing.fingerprint.equals(p.fingerprint()),"PLEDGE_REPLAY_CONFLICT"); return null; }
            changed(store.deductStock(p.productId,p.skuId,p.quantity));
            PledgeRow r=new PledgeRow();r.pledgeId=p.pledgeId;r.fingerprint=p.fingerprint();r.payload=codec.write(p);
            r.total=p.quantity;r.available=p.quantity;changed(store.insertPledge(r));
            audit(p,null,"PLEDGE_IMPORTED",p.approvalId,now);return null;
        });
    }
    /** Returns a durable dedicated reservation ONLY after its real quota has been removed from available. */
    public String reserveGuarantee(String pledgeId,long memberId,String eligibilityId) {
        return atomic(now -> {
            Pledge p=load(pledgeId);now=clock.millis(); current(p,now);
            require(p.allocation==Allocation.RESERVED_GUARANTEE,"GUARANTEE_NOT_APPROVED");
            Qualification q=authority.guaranteeEligibility(positive(memberId),id(eligibilityId)); match(p,q,memberId,now);
            String key=q.reservationKey();ReservationRow old=store.reservation(key);
            if(old!=null) { require(old.pledgeId.equals(pledgeId)&&old.memberId==memberId&&"RESERVED".equals(old.state),"RESERVATION_CONFLICT");return key; }
            changed(store.move(pledgeId,-1,1,0,0,0));
            ReservationRow r=new ReservationRow();r.reservationId=key;r.pledgeId=pledgeId;r.memberId=memberId;r.state="RESERVED";
            changed(store.insertReservation(r));audit(p,null,"REWARD_RESERVED",key,now);return key;
        });
    }
    public Award award(String pledgeId,long memberId,String sourceEventId) {
        return atomic(now -> {
            Pledge p=load(pledgeId);now=clock.millis();current(p,now);
            Qualification q=authority.qualifyingEvent(positive(memberId),id(sourceEventId));match(p,q,memberId,now);
            AwardRow old=store.award(q.awardKey());
            if(old!=null) { require(old.pledgeId.equals(pledgeId)&&old.memberId==memberId,"AWARD_REPLAY_CONFLICT");return new Award(old,p,now); }
            String reservationId=null;
            if(p.allocation==Allocation.RESERVED_GUARANTEE) {
                reservationId=q.reservationKey();ReservationRow r=store.reservation(reservationId);
                require(r!=null&&r.pledgeId.equals(pledgeId)&&r.memberId==memberId&&"RESERVED".equals(r.state),"DEDICATED_RESERVATION_REQUIRED");
                changed(store.finishReservation(reservationId,"AWARDED"));changed(store.move(pledgeId,0,-1,1,0,0));
            } else { changed(store.move(pledgeId,-1,0,1,0,0)); }
            AwardRow r=new AwardRow();r.awardId=q.awardKey();r.pledgeId=pledgeId;r.memberId=memberId;
            r.sourceEventId=sourceEventId;r.reservationId=reservationId;r.state="AWARDED";r.awardedAt=now;
            changed(store.insertAward(r));audit(p,r.awardId,"REWARD_AWARDED",sourceEventId,now);return new Award(r,p,now);
        });
    }
    /** This is NOT QR verification. The source must resolve an authoritative one-use fulfillment receipt. */
    public Award recordConsumption(String pledgeId,String awardId,long staffActorId,String sourceReceiptId) {
        return atomic(now -> {
            Pledge p=load(pledgeId);now=clock.millis();current(p,now);AwardRow r=ownedAward(p,awardId,null);
            authority.requireConsumption(positive(staffActorId),id(sourceReceiptId),new Award(r,p,now),now);
            String bound=store.consumption(sourceReceiptId);
            require(bound==null||bound.equals(r.awardId),"CONSUMPTION_RECEIPT_ALREADY_USED");
            if("REDEEMED".equals(r.state)) { require(r.awardId.equals(bound),"CONSUMPTION_REPLAY_CONFLICT");return new Award(r,p,now); }
            require("AWARDED".equals(r.state),"AWARD_NOT_REDEEMABLE");
            require(bound==null,"CONSUMPTION_STATE_CONFLICT");changed(store.insertConsumption(sourceReceiptId,r.awardId,staffActorId));
            changed(store.finishAward(r.awardId,"REDEEMED"));changed(store.move(pledgeId,0,0,-1,1,0));
            audit(p,r.awardId,"VOUCHER_REDEEMED",sourceReceiptId,now);r.state="REDEEMED";return new Award(r,p,now);
        });
    }
    /** Retire only. A future approved return-to-pool adapter needs current capacity/merchant evidence. */
    public Award expire(String pledgeId,String awardId) {
        return atomic(now -> {
            Pledge p=load(pledgeId);now=clock.millis();AwardRow r=ownedAward(p,awardId,null);require(now>=p.validUntil,"NOT_EXPIRED");
            if("AWARDED".equals(r.state)) {
                changed(store.finishAward(r.awardId,"EXPIRED"));changed(store.move(pledgeId,0,0,-1,0,1));
                audit(p,r.awardId,"VOUCHER_EXPIRED",r.awardId,now);r.state="EXPIRED";
            }
            return new Award(r,p,now);
        });
    }
    public void expireReservation(String pledgeId,String reservationId) {
        atomic(now -> {
            Pledge p=load(pledgeId);now=clock.millis();require(now>=p.validUntil,"NOT_EXPIRED");ReservationRow r=store.reservation(id(reservationId));
            require(r!=null&&r.pledgeId.equals(pledgeId),"RESERVATION_NOT_FOUND");
            if("RESERVED".equals(r.state)) {
                changed(store.finishReservation(reservationId,"EXPIRED"));changed(store.move(pledgeId,0,-1,0,0,1));
                audit(p,null,"RESERVATION_EXPIRED",reservationId,now);
            }
            return null;
        });
    }
    /** Append-only aftercare audit. Consumed remains consumed; never adds SKU or pledge stock. */
    public Award reverse(String pledgeId,String awardId,String caseId) {
        return atomic(now -> {
            Pledge p=load(pledgeId);now=clock.millis();AwardRow r=ownedAward(p,awardId,null);
            authority.requireReversal(id(caseId),new Award(r,p,now),now);
            if("AWARDED".equals(r.state)) {
                changed(store.finishAward(r.awardId,"REVERSED"));changed(store.move(pledgeId,0,0,-1,0,1));r.state="REVERSED";
            }
            audit(p,r.awardId,"REWARD_REVERSED",caseId,now);return new Award(r,p,now);
        });
    }
    /** Existing ownership/history remains queryable after closure, freeze, revocation and expiry. */
    public Award read(String pledgeId,String awardId,long memberId) {
        return atomic(now -> { Pledge p=load(pledgeId);now=clock.millis();return new Award(ownedAward(p,awardId,positive(memberId)),p,now); });
    }
    private Pledge load(String pledgeId) {
        PledgeRow row=store.lockPledge(id(pledgeId));require(row!=null,"PLEDGE_NOT_FOUND");
        Pledge p=codec.read(row.payload);require(p.pledgeId.equals(pledgeId)&&p.fingerprint().equals(row.fingerprint),"CORRUPT_SNAPSHOT");
        require(row.total==p.quantity&&row.available>=0&&row.reserved>=0&&row.awarded>=0&&row.consumed>=0&&row.retired>=0,"CORRUPT_SUPPLY");
        require(row.total==Math.addExact(Math.addExact(row.available,row.reserved),Math.addExact(row.awarded,Math.addExact(row.consumed,row.retired))),"SUPPLY_NOT_CONSERVED");return p;
    }
    private void current(Pledge p,long now) { authority.requireCurrentFulfillment(p,now);require(now>=p.validFrom&&now<p.validUntil,"OUTSIDE_VALIDITY"); }
    private void match(Pledge p,Qualification q,long memberId,long now) {
        require(q!=null&&q.memberId==memberId&&q.scope.key().equals(p.scope.key())&&q.rulesVersion.equals(p.rulesVersion)
                &&q.awardSlot.equals(p.awardSlot)&&q.occurredAt>=p.validFrom&&q.occurredAt<=now&&q.occurredAt<p.validUntil,"QUALIFICATION_MISMATCH");
    }
    private AwardRow ownedAward(Pledge p,String awardId,Long memberId) {
        AwardRow r=store.award(id(awardId));require(r!=null&&r.pledgeId.equals(p.pledgeId)&&(memberId==null||r.memberId==memberId),"AWARD_NOT_FOUND");return r;
    }
    private void audit(Pledge p,String awardId,String kind,String sourceId,long now) {
        String key=hash(kind,p.pledgeId,awardId==null?sourceId:awardId);
        if(store.audit(key)==null) changed(store.insertAudit(key,p.pledgeId,awardId,kind,sourceId,now));
    }
}

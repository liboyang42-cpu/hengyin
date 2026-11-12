package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.GiftPlayFact;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.domain.SeatRecipientFact;
import com.chengyinhub.business.service.IRegistrationSeatRecipientEligibility;
import com.chengyinhub.business.service.IMarketingGiftPlayEligibility;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 新玩法使用的当前资格；调用方在原事务中锁 activity→registration，历史读取不调用它。
 *
 * <p><b>礼品（自玩域）</b>：买家已付行仍是唯一可玩真源。礼品来源存在时，
 * 待领取 ⇒ 任何人都不可玩；已领取 ⇒ 只有领取人可玩（同人是允许的自领，不多发第二份）。
 * 该事实只读自 {@code gift_entitlement}，按 owner 域**一次**查询；不新增锁、不扫全表。
 * 未传 eligibility 的旧重载保持原行为（供历史/测试调用）。
 */
public final class CurrentPlayRegistration {
    private CurrentPlayRegistration() { }
    /**
     * @deprecated 不带礼品 eligibility 的重载<b>不得用于任何可玩入口</b>：它会让
     * 待领取礼品的买家、或已领取给他人后的买家继续按 {@code member_id} 放行。
     * 生产入口一律用带 {@code IMarketingGiftPlayEligibility} 的重载。
     */
    @Deprecated
    public static CmsRegistration require(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Long requestedId,Date now) {
        return require(activities,registrations,memberId,ownerType,ownerId,requestedId,now,false);
    }
    /** @deprecated 同 {@link #require(CmsActivityMapper,CmsRegistrationMapper,Long,long,Long,Long,Date)}：缺礼品 eligibility。 */
    @Deprecated
    public static CmsRegistration require(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Long requestedId,Date now,boolean originallyRequiresPaid) {
        return require(activities,registrations,memberId,ownerType,ownerId,requestedId,now,originallyRequiresPaid,null);
    }
    public static CmsRegistration require(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Long requestedId,Date now,boolean originallyRequiresPaid,
            IMarketingGiftPlayEligibility gift) {
        return require(activities,registrations,memberId,ownerType,ownerId,requestedId,now,originallyRequiresPaid,gift,null);
    }
    /**
     * 带「付费席位受让人」事实的可玩入口。受让人不是买家：返回的仍是原已付行，
     * 财务/退款归属不变；只有 status=CLAIMED 的邀请事实会被追加，且原行必须仍是
     * 成功报名+已支付（退款/撤销立即失效）。待领取/已取消/已过期/已释放一律不放行。
     */
    public static CmsRegistration require(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Long requestedId,Date now,boolean originallyRequiresPaid,
            IMarketingGiftPlayEligibility gift,IRegistrationSeatRecipientEligibility seatRecipient) {
        return collect(activities,registrations,memberId,ownerType,ownerId,requestedId,now,originallyRequiresPaid,true,false,gift,seatRecipient).get(0);
    }
    /**
     * 继续已开局的玩法：自玩通行证在本局有效期内开局后，本局可打完(不因通行证中途到期打断)。
     * 退款/归属变化/非成功报名仍拒绝；活动场次与探索票照原规则(活动结束即止)。
     */
    /** @deprecated 缺礼品 eligibility；可玩入口请用带 {@code IMarketingGiftPlayEligibility} 的重载。 */
    @Deprecated
    public static CmsRegistration requireToContinue(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Long requestedId,Date now) {
        return requireToContinue(activities,registrations,memberId,ownerType,ownerId,requestedId,now,null);
    }
    public static CmsRegistration requireToContinue(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Long requestedId,Date now,
            IMarketingGiftPlayEligibility gift) {
        return requireToContinue(activities,registrations,memberId,ownerType,ownerId,requestedId,now,gift,null);
    }
    public static CmsRegistration requireToContinue(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Long requestedId,Date now,
            IMarketingGiftPlayEligibility gift,IRegistrationSeatRecipientEligibility seatRecipient) {
        return collect(activities,registrations,memberId,ownerType,ownerId,requestedId,now,false,true,true,gift,seatRecipient).get(0);
    }
    /** @deprecated 缺礼品 eligibility；可玩入口请用带 {@code IMarketingGiftPlayEligibility} 的重载。 */
    @Deprecated
    public static List<CmsRegistration> requireAllToContinue(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Date now) {
        return requireAllToContinue(activities,registrations,memberId,ownerType,ownerId,now,null);
    }
    public static List<CmsRegistration> requireAllToContinue(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Date now,IMarketingGiftPlayEligibility gift) {
        return requireAllToContinue(activities,registrations,memberId,ownerType,ownerId,now,gift,null);
    }
    public static List<CmsRegistration> requireAllToContinue(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Date now,IMarketingGiftPlayEligibility gift,
            IRegistrationSeatRecipientEligibility seatRecipient) {
        return collect(activities,registrations,memberId,ownerType,ownerId,null,now,false,false,true,gift,seatRecipient);
    }
    /** 全部当前仍可用的原报名(逐行同一锁读规则)；多票/多报名原「任一成功报名可授权」的链用它，不只取第一张。 */
    /** @deprecated 缺礼品 eligibility；可玩入口请用带 {@code IMarketingGiftPlayEligibility} 的重载。 */
    @Deprecated
    public static List<CmsRegistration> requireAll(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Date now) {
        return requireAll(activities,registrations,memberId,ownerType,ownerId,now,null);
    }
    public static List<CmsRegistration> requireAll(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Date now,IMarketingGiftPlayEligibility gift) {
        return requireAll(activities,registrations,memberId,ownerType,ownerId,now,gift,null);
    }
    public static List<CmsRegistration> requireAll(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Date now,IMarketingGiftPlayEligibility gift,
            IRegistrationSeatRecipientEligibility seatRecipient) {
        return collect(activities,registrations,memberId,ownerType,ownerId,null,now,false,false,false,gift,seatRecipient);
    }
    private static List<CmsRegistration> collect(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Long requestedId,Date now,boolean originallyRequiresPaid,boolean firstOnly,
            boolean continuing,IMarketingGiftPlayEligibility gift,IRegistrationSeatRecipientEligibility seatRecipient) {
        List<CmsRegistration> valid=new ArrayList<>();
        CmsActivity activity=null;
        if(ownerType==2L) {
            activity=activities.selectCmsActivityByIdForUpdate(ownerId);
            if(activity==null || activity.getCancelTime()!=null || Integer.valueOf(2).equals(activity.getDelFlag())) {
                throw new IllegalStateException("活动不存在或已取消");
            }
        }
        // 礼品事实：按 owner 域一次查询（活动域返回空）。不加锁、不按行再查。
        Map<Long, GiftPlayFact> giftFacts = giftFacts(gift, memberId, ownerType, ownerId);
        CmsRegistration probe=new CmsRegistration();probe.setMemberId(memberId);probe.setOwnerType(ownerType);
        probe.setOwnerId(ownerId);probe.setRegistrationStatus(2);
        if(originallyRequiresPaid) probe.setPaymentStatus(2);
        List<CmsRegistration> rows=registrations.selectCmsRegistrationList(probe);
        boolean candidate=false;
        if(rows!=null) for(CmsRegistration snapshot:rows) {
            if(snapshot==null || snapshot.getId()==null || (requestedId!=null && !requestedId.equals(snapshot.getId()))) continue;
            candidate=true;
            // 礼品扣住：待领取(无人) 或 已领取给他人(买家/其他人) ⇒ 不可玩；同人自领照常放行。
            GiftPlayFact fact=giftFacts.get(snapshot.getId());
            if(fact!=null && fact.withholds(memberId)) continue;
            CmsRegistration current=registrations.selectCmsRegistrationByIdForUpdate(snapshot.getId());
            if(!currentOwner(current,memberId,ownerType,ownerId)) continue;
            if(!passesValueRules(current,activity,ownerType,now,originallyRequiresPaid,continuing)) continue;
            valid.add(current);
            if(firstOnly) return valid;
        }
        // 领取人路径：只补「已领取给本人」的行；按 id 单条读，不扫全表、不加新锁。
        if(!giftFacts.isEmpty()) for(GiftPlayFact fact:giftFacts.values()) {
            if(!fact.claimedBy(memberId)) continue;
            if(requestedId!=null && !requestedId.equals(fact.getRegistrationId())) continue;
            candidate=true;
            if(containsId(valid,fact.getRegistrationId())) continue;
            CmsRegistration current=registrations.selectCmsRegistrationById(fact.getRegistrationId());
            if(!ownedScope(current,ownerType,ownerId)) continue;
            if(!passesValueRules(current,activity,ownerType,now,originallyRequiresPaid,continuing)) continue;
            valid.add(current);
            if(firstOnly) return valid;
        }
        // 付费席位受让人：只补 status=CLAIMED 的邀请；原行仍须成功报名+已支付（退款/撤销即失效）。
        if(seatRecipient!=null) for(SeatRecipientFact fact:seatRecipient.playFacts(memberId,ownerType,ownerId)) {
            if(fact==null || fact.getRegistrationId()==null || fact.getInviteeMemberId()==null
                    || !fact.getInviteeMemberId().equals(memberId)) continue;
            if(requestedId!=null && !requestedId.equals(fact.getRegistrationId())) continue;
            candidate=true;
            if(containsId(valid,fact.getRegistrationId())) continue;
            // 与 release 共用原单锁；候选查询早于锁，锁后必须重读领取事实。
            CmsRegistration current=registrations.selectCmsRegistrationByIdForUpdate(fact.getRegistrationId());
            if(!ownedScope(current,ownerType,ownerId)
                    || !seatRecipient.claimedUnderRegistrationLock(fact,memberId,current)) continue;
            if(!Integer.valueOf(2).equals(current.getPaymentStatus())) continue;
            GiftPlayFact giftFact=giftFacts.get(fact.getRegistrationId());
            if(giftFact!=null && !giftFact.claimedBy(current.getMemberId())) continue;
            if(!passesValueRules(current,activity,ownerType,now,originallyRequiresPaid,continuing)) continue;
            valid.add(current);
            if(firstOnly) return valid;
        }
        if(!valid.isEmpty()) return valid;
        // 从未有成功报名/通行证时沿用原入口文案；有原对象但当前已不可用才提示失效。
        if(!candidate) throw new IllegalStateException(ownerType==1L ? "请先购买自玩通行证" : "请先报名并完成支付");
        throw new IllegalStateException(ownerType==1L ? "当前自玩资格已失效" : "当前报名资格已失效");
    }
    /** 原口径的值/时效/撤销判定，行归属另判（礼品行 member_id 仍是买家）。 */
    private static boolean passesValueRules(CmsRegistration current,CmsActivity activity,long ownerType,Date now,
            boolean originallyRequiresPaid,boolean continuing) {
        if(current==null) return false;
        if((originallyRequiresPaid || Integer.valueOf(3).equals(current.getPurchaseKind()))
                && !Integer.valueOf(2).equals(current.getPaymentStatus())) return false;
        if(ownerType==1L && !continuing && current.getExpiresAt()!=null && !current.getExpiresAt().after(now)) return false;
        String deny=ExploreTicketUsePolicy.rejection(current.getPurchaseKind(),activity==null?null:activity.getEndDate(),now);
        if(deny!=null) throw new IllegalStateException(deny);
        return true;
    }
    private static Map<Long, GiftPlayFact> giftFacts(IMarketingGiftPlayEligibility gift,Long memberId,
            long ownerType,Long ownerId) {
        Map<Long, GiftPlayFact> out=new HashMap<>();
        if(gift==null || memberId==null || ownerId==null) return out;
        List<GiftPlayFact> facts=gift.playFacts(memberId,ownerType,ownerId);
        if(facts==null) return out;
        for(GiftPlayFact fact:facts) {
            if(fact!=null && fact.getRegistrationId()!=null) out.put(fact.getRegistrationId(),fact);
        }
        return out;
    }
    private static boolean containsId(List<CmsRegistration> rows,Long id) {
        for(CmsRegistration row:rows) if(row!=null && id.equals(row.getId())) return true;
        return false;
    }
    public static boolean currentOwner(CmsRegistration current,Long memberId,long ownerType,Long ownerId) {
        return current!=null && memberId!=null && memberId.equals(current.getMemberId())
                && ownedScope(current,ownerType,ownerId);
    }
    /** 只判范围与成功态，不判 member_id（礼品行仍属买家）。 */
    public static boolean ownedScope(CmsRegistration current,long ownerType,Long ownerId) {
        return current!=null && Long.valueOf(ownerType).equals(current.getOwnerType())
                && ownerId!=null && ownerId.equals(current.getOwnerId())
                && Integer.valueOf(2).equals(current.getRegistrationStatus());
    }
}

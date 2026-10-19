package com.chengyinhub.business.service.coupon;

import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.mapper.TopicCouponRewardSupplyMapper;
import java.time.Clock;
import java.util.*;

/**
 * Existing topic completion COUPON options, not a new physical pool or a guarantee.
 * Uses the real current merchant authority, independently of AI subscriptions.
 * The existing topic write contract permits only the configuring member's own coupons;
 * this reader does not invent cross-merchant sponsorship or employee publishing authority.
 */
public final class TopicCouponRewardSupplyService {
    private final IMerchantAccessService access;
    private final TopicCouponRewardSupplyMapper mapper;
    private final Clock clock;
    public TopicCouponRewardSupplyService(IMerchantAccessService access, TopicCouponRewardSupplyMapper mapper, Clock clock) {
        this.access=Objects.requireNonNull(access);this.mapper=Objects.requireNonNull(mapper);this.clock=Objects.requireNonNull(clock);
    }
    public Map<String,Object> list(long actor,int limit,Long cursor) {
        if(actor<=0 || limit<1 || limit>50 || cursor!=null && cursor<=0) throw new IllegalArgumentException("Invalid supply query");
        MerchantAccessContext owner=currentOwner(actor);
        List<SmsCoupon> rows=mapper.options(actor,owner.getMerchantId(),cursor,limit+1,new Date(clock.millis()));
        if(rows==null || rows.size()>limit+1) throw new IllegalStateException("Invalid supply response");
        MerchantAccessContext refreshed=currentOwner(actor);
        if(!Objects.equals(owner.getMerchantId(),refreshed.getMerchantId()) || !Objects.equals(owner.getOwnerMemberId(),refreshed.getOwnerMemberId())
                || !Objects.equals(owner.getRoleCode(),refreshed.getRoleCode()) || !owner.getPermissions().equals(refreshed.getPermissions()))
            throw new IllegalStateException("Current merchant changed");
        long asOf=clock.millis(); List<Map<String,Object>> choices=new ArrayList<Map<String,Object>>();long previous=cursor==null?0:cursor;Long lastScanned=null;
        for(int index=0;index<Math.min(limit,rows.size());index++) {
            SmsCoupon coupon=rows.get(index);
            if(coupon==null || coupon.getId()==null || coupon.getId()<=previous || !Long.valueOf(actor).equals(coupon.getMemberId())
                    || !owner.getMerchantId().equals(coupon.getMerchantId()) || !Integer.valueOf(1).equals(coupon.getScopeType()))
                throw new IllegalStateException("Supply identity mismatch");
            previous=coupon.getId();lastScanned=coupon.getId();
            // Query time can pass while authority is checked. Never offer a now-expired definition.
            if(!Integer.valueOf(1).equals(coupon.getStatus()) || !Integer.valueOf(0).equals(coupon.getDelFlag())
                    || coupon.getStartTime()==null || coupon.getEndTime()==null || !coupon.getStartTime().before(coupon.getEndTime())
                    || coupon.getEndTime().getTime()<=asOf) continue;
            CouponTermsSnapshot terms=CouponTermsSnapshot.fromDefinition(coupon);
            if(terms.getName()==null || terms.getName().trim().isEmpty() || terms.getDescription()==null || terms.getDescription().trim().isEmpty()) continue;
            Long cap=coupon.getPublishCount(),received=coupon.getReceiveCount();long used=received==null?0:received;
            if(used<0 || cap!=null && cap>0 && used>=cap) continue;
            Map<String,Object> item=new LinkedHashMap<String,Object>();
            item.put("couponId",coupon.getId().toString());item.put("merchantId",owner.getMerchantId().toString());
            item.put("name",terms.getName());item.put("description",terms.getDescription());item.put("couponType",coupon.getCouponType());
            item.put("validFrom",terms.getStartTime());item.put("validUntil",terms.getEndTime());item.put("termsDigest",terms.getDigest());
            item.put("remainingCount",cap!=null && cap>0?cap-used:null);item.put("quantityLimited",cap!=null && cap>0);
            choices.add(Collections.unmodifiableMap(item));
        }
        Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("contractVersion",1);result.put("purpose","TOPIC_COMPLETION_COUPON");
        result.put("items",Collections.unmodifiableList(choices));result.put("nextCursor",rows.size()>limit && lastScanned!=null?lastScanned.toString():null);result.put("asOf",asOf);
        return Collections.unmodifiableMap(result);
    }
    private MerchantAccessContext currentOwner(long actor) {
        MerchantAccessContext value=access.require(actor,MerchantPermission.COUPON_MANAGE);
        if(value==null || !Long.valueOf(actor).equals(value.getPrincipalMemberId()) || !Long.valueOf(actor).equals(value.getOwnerMemberId())
                || value.getMerchantId()==null || value.getMerchantId()<=0 || !MerchantRoleCode.MERCHANT_OWNER.equals(value.getRoleCode()))
            throw new IllegalArgumentException("Existing topic contract requires own merchant coupon");
        return value;
    }
}

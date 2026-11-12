package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.GiftEntitlement;
import com.chengyinhub.business.domain.GiftPlayFact;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** 礼品领取来源。领取、过期、撤销都是一次状态迁移；成功后才回写原 registration id。 */
public interface GiftEntitlementMapper {

    int insert(GiftEntitlement gift);

    GiftEntitlement selectById(@Param("id") Long id);

    GiftEntitlement selectByOrderId(@Param("orderId") Long orderId);

    GiftEntitlement selectByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * 该所有者域内的礼品事实（待领取/已领取），一次查询。
     * 只读 cms_registration 做范围过滤，不加锁、不扫全表。
     */
    List<GiftPlayFact> selectPlayFactsByOwner(@Param("memberId") Long memberId,
                                              @Param("ownerType") long ownerType,
                                              @Param("ownerId") Long ownerId);

    /** 回写同一行绑定（单行语义）。 */
    int bindSameRegistration(@Param("id") Long id,
                             @Param("registrationId") Long registrationId,
                             @Param("now") Date now);

    List<GiftEntitlement> selectByBuyer(@Param("buyerMemberId") Long buyerMemberId,
                                        @Param("limit") int limit);

    List<GiftEntitlement> selectByClaimedMember(@Param("memberId") Long memberId,
                                               @Param("limit") int limit);

    int claimCas(@Param("id") Long id,
                 @Param("claimedMemberId") Long claimedMemberId,
                 @Param("requestId") String requestId,
                 @Param("now") Date now);

    int expire(@Param("id") Long id, @Param("updateTime") Date updateTime);

    int revoke(@Param("id") Long id,
               @Param("reason") String reason,
               @Param("updateTime") Date updateTime);
}

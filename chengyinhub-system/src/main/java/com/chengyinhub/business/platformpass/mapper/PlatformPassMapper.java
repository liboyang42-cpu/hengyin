package com.chengyinhub.business.platformpass.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/** 通行证（平台会员）四张表的读写。所有写都带状态/归属前置条件，返回影响行数供调用方判定。 */
public interface PlatformPassMapper {

    List<Map<String, Object>> selectOnSalePlans();

    Map<String, Object> selectPlanByCode(@Param("planCode") String planCode);

    int insertOrder(Map<String, Object> row);

    Map<String, Object> selectOrderByNo(@Param("orderNo") String orderNo);

    Map<String, Object> selectOrderByNoForUpdate(@Param("orderNo") String orderNo);

    Map<String, Object> selectOrderByRefundNoForUpdate(@Param("refundNo") String refundNo);

    Map<String, Object> selectOrderByMemberRequest(@Param("memberId") long memberId,
                                                   @Param("requestId") String requestId);

    List<Map<String, Object>> selectOrdersByMember(@Param("memberId") long memberId, @Param("limit") int limit);

    int markJsapiPaid(@Param("id") long id, @Param("factId") long factId, @Param("factHash") String factHash,
                      @Param("transactionId") String transactionId, @Param("paidTime") Date paidTime);

    int markXpayPaid(@Param("id") long id, @Param("wxOrderId") String wxOrderId, @Param("env") int env,
                     @Param("paidTime") Date paidTime);

    int markCancelled(@Param("id") long id, @Param("memberId") long memberId, @Param("cancelTime") Date cancelTime);

    int markRefundRequested(@Param("id") long id, @Param("refundNo") String refundNo,
                            @Param("reason") String reason, @Param("operator") String operator,
                            @Param("requestedTime") Date requestedTime);

    int markRefundRolledBack(@Param("id") long id, @Param("refundNo") String refundNo);

    int markRefunded(@Param("id") long id, @Param("factId") Long factId, @Param("factHash") String factHash,
                     @Param("refundedTime") Date refundedTime);

    /** 本会员最后一段仍有效（未撤销且未到期）的 valid_to；用于续买顺延。加锁串行同会员的并发开通。 */
    Date selectLatestValidToForUpdate(@Param("memberId") long memberId, @Param("now") Date now);

    int insertMembership(Map<String, Object> row);

    Map<String, Object> selectMembershipByOrder(@Param("orderId") long orderId);

    /** now 落在 [valid_from, valid_to) 内的有效段。 */
    Map<String, Object> selectActiveMembership(@Param("memberId") long memberId, @Param("now") Date now);

    /** 本会员所有未撤销段里最晚的 valid_to（可能是未来段）。 */
    Date selectCoverageEnd(@Param("memberId") long memberId, @Param("now") Date now);

    int revokeMembership(@Param("orderId") long orderId);

    /** 撤销中间一段后，把它之后的段整体前移，保证有效期连续且不白送。 */
    int shiftLaterMemberships(@Param("memberId") long memberId, @Param("after") Date after,
                              @Param("seconds") long seconds);

    int insertClaim(Map<String, Object> row);

    int countClaimsByMembership(@Param("membershipId") long membershipId);

    /** 官方（CONTENT 槽 = PLATFORM 且生效）、开放自玩、售票中/进行中、未删除的主题。 */
    int isOfficialSelfPlayTopic(@Param("topicId") long topicId);

    List<Map<String, Object>> selectLibrary(@Param("productType") Integer productType, @Param("limit") int limit);

    int countLibrary();
}

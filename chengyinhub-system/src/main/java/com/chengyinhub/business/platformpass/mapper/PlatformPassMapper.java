package com.chengyinhub.business.platformpass.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                     @Param("paidTime") Date paidTime, @Param("deliveryStatus") int deliveryStatus);

    int freezeXpayIntent(@Param("id") long id, @Param("productId") String productId,
                         @Param("offerId") String offerId, @Param("env") int env);

    int markDeliveryConfirmed(@Param("id") long id);

    int markDeliveryRetry(@Param("id") long id, @Param("nextTime") Date nextTime);

    List<Map<String, Object>> selectOrdersPendingDelivery(@Param("now") Date now, @Param("limit") int limit);

    Map<String, Object> selectOrderByIdForUpdate(@Param("id") long id);

    /** T10:同会员串行锁。先 insert ignore 保证锁行存在，再 for update。锁序固定：订单行 → 会员锁 → 段。 */
    int insertMemberLock(@Param("memberId") long memberId);

    Long lockMember(@Param("memberId") long memberId);

    int markRefundedFromChannel(@Param("id") long id, @Param("factId") Long factId, @Param("factHash") String factHash,
                                @Param("refundedTime") Date refundedTime, @Param("amount") long amount,
                                @Param("source") String source);

    int recordPartialChannelRefund(@Param("id") long id, @Param("amount") long amount, @Param("facts") String facts);

    int requestReview(@Param("id") long id, @Param("reason") String reason, @Param("facts") String facts);

    int decideReview(@Param("id") long id, @Param("decision") int decision, @Param("handler") String handler,
                     @Param("note") String note, @Param("time") Date time);

    int markReviewReceipt(@Param("id") long id, @Param("receipt") String receipt);

    List<Map<String, Object>> selectOrdersForAdmin(@Param("status") Integer status,
                                                   @Param("reviewStatus") Integer reviewStatus,
                                                   @Param("limit") int limit);

    Map<String, Object> selectMembershipById(@Param("id") long id);

    Map<String, Object> selectMembershipByIdForUpdate(@Param("id") long id);

    List<Map<String, Object>> selectActiveSegments(@Param("memberId") long memberId);

    List<Map<String, Object>> selectActiveClaims(@Param("memberId") long memberId);

    int markClaimsStopped(@Param("membershipId") long membershipId, @Param("now") Date now);

    int clampClaimTicket(@Param("registrationId") long registrationId, @Param("end") Date end);

    int extendClaimTicket(@Param("registrationId") long registrationId, @Param("lo") Date lo,
                          @Param("hi") Date hi, @Param("newEnd") Date newEnd);

    /** 覆盖与可领取同一口径用：已发布主题的自玩/价格/生命周期列。 */
    List<Map<String, Object>> selectCoverageTopics(@Param("topicIds") List<Long> topicIds);

    int markCancelled(@Param("id") long id, @Param("memberId") long memberId, @Param("cancelTime") Date cancelTime);

    int markRefundRequested(@Param("id") long id, @Param("refundNo") String refundNo,
                            @Param("reason") String reason, @Param("operator") String operator,
                            @Param("requestedTime") Date requestedTime);

    int markRefundRolledBack(@Param("id") long id, @Param("refundNo") String refundNo);

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

    /** 已发布主题，不按官方/玩家/俱乐部来源、交付形态或售卖生命周期缩小覆盖范围。 */
    int isPublishedTopic(@Param("topicId") long topicId);

    List<Long> selectPublishedTopicIds(@Param("topicIds") List<Long> topicIds);

    List<Map<String, Object>> selectLibrary(@Param("productType") Integer productType, @Param("limit") int limit);

    int countLibrary(@Param("topicIds") Set<Long> topicIds);
}

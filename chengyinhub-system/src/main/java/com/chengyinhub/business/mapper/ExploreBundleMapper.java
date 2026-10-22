package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ExploreBundleExitCase;
import com.chengyinhub.business.domain.ExploreBundleGrant;
import com.chengyinhub.business.domain.ExploreBundleOrder;
import com.chengyinhub.business.domain.ExploreBundleReservation;
import com.chengyinhub.business.domain.ExploreBundleUnit;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * F3 自由探索套票 Phase 1 账本(DOC2 D-11)。
 *
 * <p>余额真源是 explore_bundle_unit 的逐行状态,按 (registration, holder, chapter, grant) 归属;
 * 本 mapper 刻意没有任何读 merchant_chapter_offer 共享名额的语句。explore_bundle_order 只插不改。
 */
public interface ExploreBundleMapper {

    // ---------------------------------------------------------------- 订单快照(只插不改)

    int insertOrder(ExploreBundleOrder order);

    ExploreBundleOrder selectOrderByRegistration(@Param("registrationId") Long registrationId);

    // ---------------------------------------------------------------- 发放 / 单位

    int insertGrant(ExploreBundleGrant grant);

    List<ExploreBundleGrant> selectGrantsByRegistration(@Param("registrationId") Long registrationId);

    int insertUnit(ExploreBundleUnit unit);

    List<ExploreBundleUnit> selectUnitsByRegistration(@Param("registrationId") Long registrationId);

    /** 当前读:同票同章的全部单位(核销选单位用),与持有人一并过滤。 */
    List<ExploreBundleUnit> selectUnitsForUpdate(@Param("registrationId") Long registrationId,
                                                 @Param("holderMemberId") Long holderMemberId,
                                                 @Param("chapterId") Long chapterId);

    ExploreBundleUnit selectUnitByIdForUpdate(@Param("id") Long id);

    ExploreBundleUnit selectUnitByRedemptionForUpdate(@Param("redemptionId") Long redemptionId);

    /** 版本 + 起始状态双条件 CAS;REDEEMED 必须带 redemptionId,其余状态必须清空(与 ck_bundle_unit_redeemed 同口径)。 */
    int casUnitStatus(@Param("id") Long id, @Param("version") Integer version,
                      @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
                      @Param("redemptionId") Long redemptionId, @Param("reservationId") Long reservationId,
                      @Param("reason") String reason);

    /** 整单退款:尚未履约的单位全部置 REFUNDED(已核销/已罚没/待退款的不动)。 */
    int refundOpenUnits(@Param("registrationId") Long registrationId);

    // ---------------------------------------------------------------- 预约

    int insertReservation(ExploreBundleReservation reservation);

    ExploreBundleReservation selectReservationById(@Param("id") Long id);

    ExploreBundleReservation selectReservationByIdForUpdate(@Param("id") Long id);

    ExploreBundleReservation selectReservationByIdempotencyKey(@Param("holderMemberId") Long holderMemberId,
                                                               @Param("idempotencyKey") String idempotencyKey);

    List<ExploreBundleReservation> selectReservationsByRegistration(@Param("registrationId") Long registrationId);

    List<ExploreBundleReservation> selectMerchantReservations(@Param("merchantId") Long merchantId,
                                                              @Param("from") Date from, @Param("to") Date to);

    int casReservationStatus(@Param("id") Long id, @Param("version") Integer version,
                             @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus);

    int cancelBookedByRegistration(@Param("registrationId") Long registrationId);

    // ---------------------------------------------------------------- 月度豁免 / 商家退出

    /** 唯一键 (member_id, waiver_month) 即闸:返回 0 = 本月已用过。 */
    int insertWaiverIgnore(@Param("memberId") Long memberId, @Param("waiverMonth") String waiverMonth,
                           @Param("reservationId") Long reservationId);

    int insertExitCase(ExploreBundleExitCase exitCase);

    ExploreBundleExitCase selectExitCaseByIdempotencyKey(@Param("holderMemberId") Long holderMemberId,
                                                         @Param("idempotencyKey") String idempotencyKey);

    List<ExploreBundleExitCase> selectExitCasesByRegistration(@Param("registrationId") Long registrationId);
}

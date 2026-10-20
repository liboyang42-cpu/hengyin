package com.chengyinhub.business.reward;

import org.apache.ibatis.annotations.Param;
import static com.chengyinhub.business.reward.NonCashRewardRows.*;

public interface NonCashRewardMapper {
    Long lockProduct(@Param("productId") long productId, @Param("merchantId") long merchantId);
    int deductStock(@Param("productId") long productId, @Param("skuId") long skuId, @Param("quantity") long quantity);
    PledgeRow lockPledge(String pledgeId);
    int insertPledge(PledgeRow pledge);
    int move(@Param("pledgeId") String pledgeId, @Param("available") long available,
             @Param("reserved") long reserved, @Param("awarded") long awarded,
             @Param("consumed") long consumed, @Param("retired") long retired);
    ReservationRow reservation(String reservationId);
    int insertReservation(ReservationRow reservation);
    int finishReservation(@Param("reservationId") String reservationId, @Param("state") String state);
    AwardRow award(String awardId);
    int insertAward(AwardRow award);
    int finishAward(@Param("awardId") String awardId, @Param("state") String state);
    String consumption(String sourceReceiptId);
    int insertConsumption(@Param("sourceReceiptId") String sourceReceiptId, @Param("awardId") String awardId, @Param("staffActorId") long staffActorId);
    String audit(String auditId);
    int insertAudit(@Param("auditId") String auditId, @Param("pledgeId") String pledgeId,
                    @Param("awardId") String awardId, @Param("kind") String kind,
                    @Param("sourceId") String sourceId, @Param("occurredAt") long occurredAt);
}

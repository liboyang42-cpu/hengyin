package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.AiQuotaReservationRecord;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface AiQuotaReservationMapper {
    int insertPrepared(AiQuotaReservationRecord row);
    AiQuotaReservationRecord selectByReceipt(@Param("receipt") String receipt);
    AiQuotaReservationRecord selectByReceiptForUpdate(@Param("receipt") String receipt);
    int requestAsrNotSentRefund(@Param("receipt") String receipt,@Param("fence") Long fence);
    int recordReserved(@Param("receipt") String receipt, @Param("generation") String generation, @Param("used") Long used);
    int advanceStage(@Param("receipt") String receipt, @Param("stage") String stage);
    int recordOutcome(@Param("receipt") String receipt, @Param("state") String state, @Param("reason") String reason);
    List<AiQuotaReservationRecord> selectDue(@Param("limit") int limit);
    int claim(@Param("id") Long id, @Param("fence") Long fence, @Param("owner") String owner);
    AiQuotaReservationRecord selectById(@Param("id") Long id);
    int complete(@Param("id") Long id, @Param("owner") String owner, @Param("fence") Long fence,
                 @Param("state") String state, @Param("error") String error);
    int retry(@Param("id") Long id, @Param("owner") String owner, @Param("fence") Long fence,
              @Param("error") String error);
}

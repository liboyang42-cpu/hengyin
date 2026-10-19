package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CartSettlementReceipt;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface CartSettlementReceiptMapper {
    int insertRequest(CartSettlementReceipt receipt);
    CartSettlementReceipt selectForUpdate(@Param("memberId") Long memberId, @Param("requestId") String requestId);
    CartSettlementReceipt selectOwned(@Param("memberId") Long memberId, @Param("requestId") String requestId);
    int markSucceeded(@Param("id") Long id, @Param("intentHash") String intentHash,
                      @Param("firstOrderId") Long firstOrderId, @Param("totalPoints") Long totalPoints);
    int insertOrder(@Param("receiptId") Long receiptId, @Param("orderId") Long orderId,
                    @Param("sortOrder") int sortOrder);
    List<Long> selectOrderIds(@Param("receiptId") Long receiptId);
}

package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocationLine;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MarketingRevenueAllocationLineMapper {

    int batchInsert(@Param("lines") List<MarketingRevenueAllocationLine> lines);

    List<MarketingRevenueAllocationLine> selectByAllocationId(@Param("allocationId") Long allocationId);

    /**
     * 某退款事件在本分配下的全部分录。反向分录按 event_ref 归组，
     * 用于「同一退款事件重放 → 先命中幂等事实」而不是先谈累计封顶。
     */
    List<MarketingRevenueAllocationLine> selectByEvent(@Param("allocationId") Long allocationId,
                                                       @Param("eventRef") String eventRef);

    /** 某原分录已冲销的合计（分，正值）。封顶按各收款方分别不超过其原分录。 */
    long sumReversedFenByLineId(@Param("reversalOfLineId") Long reversalOfLineId);

    List<MarketingRevenueAllocationLine> selectByRecipient(@Param("recipientMemberId") Long recipientMemberId);
}

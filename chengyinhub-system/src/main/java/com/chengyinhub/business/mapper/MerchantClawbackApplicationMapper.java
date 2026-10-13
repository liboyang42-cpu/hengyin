package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.MerchantClawbackApplication;

/**
 * M块:商家违规倒扣申请 Mapper。
 *
 * <p>三条 update 均为条件置位(CAS),与 {@code MerchantPayableBatchMapper} 的
 * confirm/markPaid 同型 —— 返回 0 不是「没事发生」,是「你以为的前置状态不成立」。
 */
public interface MerchantClawbackApplicationMapper {

    public int insertApplication(MerchantClawbackApplication a);

    public MerchantClawbackApplication selectById(@Param("id") Long id);

    public List<MerchantClawbackApplication> selectByMerchantMemberId(@Param("merchantMemberId") Long merchantMemberId);

    public List<MerchantClawbackApplication> selectList(MerchantClawbackApplication query);

    /**
     * 复核通过并置为已执行:PENDING_REVIEW → EXECUTED。
     *
     * @return 0 = 该申请已不是待复核态(已执行或已驳回)
     */
    public int markExecutedIfPending(@Param("id") Long id, @Param("confirmedBy") String confirmedBy,
            @Param("confirmedAt") java.util.Date confirmedAt, @Param("executedAt") java.util.Date executedAt);

    /**
     * 驳回:PENDING_REVIEW → REJECTED。
     *
     * @return 0 = 该申请已不是待复核态
     */
    public int markRejectedIfPending(@Param("id") Long id, @Param("confirmedBy") String confirmedBy,
            @Param("confirmedAt") java.util.Date confirmedAt, @Param("rejectReason") String rejectReason);
}

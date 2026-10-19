package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.OmsOrderPaymentCheck;

/** F0535 订单支付事实回执(只追加)。 */
public interface OmsOrderPaymentCheckMapper
{
    int insertPaymentCheck(OmsOrderPaymentCheck row);

    OmsOrderPaymentCheck selectLatestByOrderId(@Param("orderId") Long orderId);
}

package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.SmsCouponRuleVersion;

/** F0572 优惠券规则版本(只追加,无 update/delete)。 */
public interface SmsCouponRuleVersionMapper
{
    int insertRuleVersion(SmsCouponRuleVersion row);

    int countByCouponAndVersion(@Param("couponId") Long couponId, @Param("ruleVersion") Integer ruleVersion);

    List<SmsCouponRuleVersion> selectByCouponId(@Param("couponId") Long couponId);

    SmsCouponRuleVersion selectLatestInvalidation(@Param("couponId") Long couponId);
}

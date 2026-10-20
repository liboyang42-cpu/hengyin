package com.chengyinhub.business.mapper;
import com.chengyinhub.business.domain.SmsCoupon;
import java.util.List;
import java.util.Date;
import org.apache.ibatis.annotations.Param;
/** Pure selection, unlike mypublishlist: never updates expired coupon state. */
public interface TopicCouponRewardSupplyMapper {
    List<SmsCoupon> options(@Param("owner") long owner, @Param("merchant") long merchant,
            @Param("cursor") Long cursor, @Param("limit") int limit, @Param("now") Date now);
}

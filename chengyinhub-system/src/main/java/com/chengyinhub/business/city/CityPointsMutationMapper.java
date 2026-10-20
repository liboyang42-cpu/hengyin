package com.chengyinhub.business.city;

import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.city.CityPersistenceRows.*;

/** Only existing individual points. The returned locking read is the balance fact. */
public interface CityPointsMutationMapper {
    PointRow lockCurrentPoints(@Param("id") long id);
    int applyNet(@Param("id") long id, @Param("before") long before, @Param("net") long net);
    int insertDetail(DetailRow row);
}

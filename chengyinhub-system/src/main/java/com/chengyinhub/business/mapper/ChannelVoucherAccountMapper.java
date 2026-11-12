package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ChannelVoucherAccount;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** 渠道发码主体。状态迁移走 CAS，不提供物理删除。 */
public interface ChannelVoucherAccountMapper {

    int insert(ChannelVoucherAccount account);

    ChannelVoucherAccount selectById(@Param("id") Long id);

    ChannelVoucherAccount selectByIdForUpdate(@Param("id") Long id);

    ChannelVoucherAccount selectByKey(@Param("channelKey") String channelKey);

    ChannelVoucherAccount selectByKeyForUpdate(@Param("channelKey") String channelKey);

    List<ChannelVoucherAccount> selectList(@Param("status") Integer status, @Param("limit") int limit);

    int updateStatus(@Param("id") Long id,
                     @Param("status") Integer status,
                     @Param("updateTime") Date updateTime);
}

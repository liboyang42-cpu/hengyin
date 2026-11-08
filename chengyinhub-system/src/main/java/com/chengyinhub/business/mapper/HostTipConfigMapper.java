package com.chengyinhub.business.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.HostTipConfig;

/**
 * 主持小费配置 Mapper。
 *
 * @author chengyinhub
 */
public interface HostTipConfigMapper
{
    HostTipConfig selectById(Long id);

    /** 按 host + 作用域查唯一配置({@code uk_tip_config_host_scope})。 */
    HostTipConfig selectByHostAndClub(@Param("hostMemberId") Long hostMemberId,
                                      @Param("clubId") Long clubId);

    List<HostTipConfig> selectByHost(@Param("hostMemberId") Long hostMemberId);

    int insertConfig(HostTipConfig config);

    int updateConfig(HostTipConfig config);

    /** CAS 启停:只有当前 enabled 等于 expected 才改,避免并发启停互相覆盖。 */
    int updateEnabledIfCurrent(@Param("id") Long id,
                               @Param("expectedEnabled") Integer expectedEnabled,
                               @Param("newEnabled") Integer newEnabled,
                               @Param("configVersion") Integer configVersion);
}

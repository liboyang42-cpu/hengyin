package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.IpQuest;
import com.chengyinhub.business.domain.IpWorld;
import com.chengyinhub.business.domain.dto.IpWorldQueryDTO;
import com.chengyinhub.business.domain.vo.IpWorldHomeVO;

import java.util.List;

/**
 * P3 IP世界服务。
 */
public interface IIpWorldService {
    IpWorldHomeVO getHome(IpWorldQueryDTO query);

    List<IpWorld> listPublishedWorlds(String cityCode);

    List<IpQuest> listPublishedQuests(Long worldId);

    List<IpWorld> selectAdminWorldList(IpWorld query);

    int saveWorld(IpWorld world);
}

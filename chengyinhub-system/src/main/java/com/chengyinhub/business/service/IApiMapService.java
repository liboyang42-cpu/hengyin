package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.NearbyNodeVO;

import java.util.List;

/**
 * 地图 Service —— 周边节点(只读)。
 *
 */
public interface IApiMapService {

    /**
     * 周边活动节点(按距离升序)。
     */
    List<NearbyNodeVO> nearbyNodes(double lng, double lat, double radius, int limit,
                                   Integer status, Integer auditStatus);
}

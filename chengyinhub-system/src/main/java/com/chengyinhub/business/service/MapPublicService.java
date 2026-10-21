package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.map.MapPublicQuery;
import com.chengyinhub.business.domain.map.MapPublicResult;

/** 已登录玩家的公开地图只读聚合。 */
public interface MapPublicService {

    MapPublicResult query(long viewerId, MapPublicQuery query);
}

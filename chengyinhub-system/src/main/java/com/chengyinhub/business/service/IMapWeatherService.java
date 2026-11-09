package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.MapWeatherVO;

/**
 * 地图实时天气氛围。任何失败都返回 clear,不抛给调用方。
 */
public interface IMapWeatherService {

    /** 调用方须先校验经纬度范围。 */
    MapWeatherVO weatherAt(double latitude, double longitude);
}

package com.chengyinhub.business.service;

import java.util.List;
import java.util.Map;

/**
 * 服务端地理编码，避免把第三方地图密钥下发到小程序。
 */
public interface IMapGeocodeService {

    String resolveCity(double longitude, double latitude);

    /**
     * 正向地理编码:地址 → 建议坐标(商家填地址时不用自己去找经纬度)。
     *
     * 供应商对匹配不上的地址不会报错,而是回退到区/市中心并照常返回 status=0 ——
     * 直接采信就会把据点悄悄插到错误位置。所以低可信度一律当失败处理:
     * 拿不到就返回 null 让调用方显式失败,绝不返回 (0,0) 或默认城市中心。
     *
     * @param region 城市名,用于把「中山路」这类重名地址限定在一个城市内;可为空
     * @return {经度, 纬度};无法可靠定位时返回 null
     */
    double[] suggestCoords(String address, String region);

    /**
     * 关键词搜地点(漫游地图顶部搜索框):按当前位置就近排序。
     * 每条只给 name / address / lat / lng / distance(米),不透传供应商的其它字段。
     * 供应商不可用或没有结果时返回空列表,调用方按「这附近没有搜到」处理。
     */
    List<Map<String, Object>> searchPlaces(String keyword, double longitude, double latitude);
}

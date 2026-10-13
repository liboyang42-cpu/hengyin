package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.MmsMerchant;

/**
 * 商家搜索/发现(E5)入参:在 MmsMerchant 既有筛选(名/品类/探索标签/城市角色)之上,
 * 追加"搜索者当前定位",用于按距离升序排序。
 *
 * originLat/originLng = 搜索者(前端)当前坐标,可选;两者都传才启用距离排序。
 * 注意:与 MmsMerchant.locationLat/locationLng(商家自身坐标)语义不同,不复用。
 * distance = 服务端算出的直线距离(米),仅距离排序时回填,供前端展示。
 */
public class MerchantSearchQuery extends MmsMerchant {

    private static final long serialVersionUID = 1L;

    /** 搜索者当前纬度(可选) */
    private Double originLat;

    /** 搜索者当前经度(可选) */
    private Double originLng;

    /** 到该商家的直线距离(米),距离排序时回填 */
    private Double distance;

    public Double getOriginLat() {
        return originLat;
    }

    public void setOriginLat(Double originLat) {
        this.originLat = originLat;
    }

    public Double getOriginLng() {
        return originLng;
    }

    public void setOriginLng(Double originLng) {
        this.originLng = originLng;
    }

    public Double getDistance() {
        return distance;
    }

    public void setDistance(Double distance) {
        this.distance = distance;
    }
}

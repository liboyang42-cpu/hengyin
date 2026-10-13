package com.chengyinhub.business.domain.vo;

import java.io.Serializable;

/**
 * 周边节点(地图用)VO。
 * 数据来源:cms_registration_merchant 已有的 longitude/latitude 列(无 schema 变更)。
 *
 */
public class NearbyNodeVO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 商家活动节点 id(cms_registration_merchant.id) */
    private Long id;

    /** 所属主题 id */
    private Long topicId;

    /** 节点 id */
    private Long nodeId;

    /** 地点名称 */
    private String addressName;

    /** 经度 */
    private String longitude;

    /** 纬度 */
    private String latitude;

    /** 详细地址 */
    private String address;

    /** 封面图 */
    private String picUrl;

    /** 距查询点的直线距离(米),由后端计算 */
    private Double distance;

    /**
     * 门店 AI 形象头像(npc_profile scope_type=2)。没配 / 未审 / 已停用时为 null,
     * 前端据此回落通用点位图标。
     */
    private String npcAvatar;

    /**
     * 所属主题的玩法(cms_topic.product_type):1=城市定向 2=自由探索。没挂主题时为 null。
     * 地图上主题点位的描边色按它分 —— 定向绿 / 探索蓝(2026-09-09 裁决)。
     * 之前没这个字段,前端只好一律当定向画,两种玩法在图上分不出来。
     */
    private Integer productType;

    /** 地点名的全拼,如「徐汇滨江」→ xuhuibinjiang。供地图顶部输入地址按字母搜。 */
    private String namePinyin;

    /** 地点名的拼音首字母,如「徐汇滨江」→ xhbj。敲四个字母就只剩这一条。 */
    private String nameInitials;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTopicId() {
        return topicId;
    }

    public void setTopicId(Long topicId) {
        this.topicId = topicId;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public String getAddressName() {
        return addressName;
    }

    public void setAddressName(String addressName) {
        this.addressName = addressName;
    }

    public Integer getProductType() {
        return productType;
    }

    public void setProductType(Integer productType) {
        this.productType = productType;
    }

    public String getNamePinyin() {
        return namePinyin;
    }

    public void setNamePinyin(String namePinyin) {
        this.namePinyin = namePinyin;
    }

    public String getNameInitials() {
        return nameInitials;
    }

    public void setNameInitials(String nameInitials) {
        this.nameInitials = nameInitials;
    }

    public String getLongitude() {
        return longitude;
    }

    public void setLongitude(String longitude) {
        this.longitude = longitude;
    }

    public String getLatitude() {
        return latitude;
    }

    public void setLatitude(String latitude) {
        this.latitude = latitude;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPicUrl() {
        return picUrl;
    }

    public void setPicUrl(String picUrl) {
        this.picUrl = picUrl;
    }

    public Double getDistance() {
        return distance;
    }

    public void setDistance(Double distance) {
        this.distance = distance;
    }

    public String getNpcAvatar() {
        return npcAvatar;
    }

    public void setNpcAvatar(String npcAvatar) {
        this.npcAvatar = npcAvatar;
    }
}

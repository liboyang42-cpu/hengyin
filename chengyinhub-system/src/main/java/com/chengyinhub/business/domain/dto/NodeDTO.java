package com.chengyinhub.business.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
/**
 * 主题发布-节点请求参数
 */
@Schema(description = "主题发布-节点请求参数")
public class NodeDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    /** 编辑已有主题时的旧节点 ID；重建后仅用于路线 nodeIdMap，不直接插入新行。 */
    private Long id;
    /** 首次创建主题时供 routeGraphJson 引用的稳定客户端键；不落 cms_topic_node。 */
    private String clientNodeKey;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    @Schema(description = "节点名称", example = "第一节点")
    private String name;
    @Schema(description = "节点描述", example = "example")
    private String description;
    @Schema(description = "地址", example = "上海人民广场")
    private String address;
    @Schema(description = "地址经度", example = "116.391275")
    private String longitude;
    @Schema(description = "地址纬度", example = "39.992357")
    private String latitude;
    @Schema(description = "图片", example = "")
    private String imgUrl;
    @Schema(description = "节点时间（分钟）", example = "15")
    private Integer nodeTime;
    @Schema(description = "模版ID", example = "12")
    private Long templateId;
    @Schema(description = "地点营业时间", example = "")
    private String businessTime;
    @Schema(description = "排序序号，用于确定节点顺序", example = "1")
    private Integer sortID;
    @Schema(description = "自由定向-卡堆钩子一句话", example = "她最后一次出现在这里")
    private String hookText;
    @Schema(description = "自由定向-地点大卡NPC长钩子", example = "")
    private String cardHookLong;
    @Schema(description = "自由定向-故事碎片正文（完成后入手记）", example = "")
    private String fragmentText;

    public String getClientNodeKey() { return clientNodeKey; }
    public void setClientNodeKey(String clientNodeKey) { this.clientNodeKey = clientNodeKey; }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
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

    public String getImgUrl() {
        return imgUrl;
    }

    public void setImgUrl(String imgUrl) {
        this.imgUrl = imgUrl;
    }

    public Integer getNodeTime() {
        return nodeTime;
    }

    public void setNodeTime(Integer nodeTime) {
        this.nodeTime = nodeTime;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public String getBusinessTime() {
        return businessTime;
    }

    public void setBusinessTime(String businessTime) {
        this.businessTime = businessTime;
    }

    public Integer getSortID() {
        return sortID;
    }

    public void setSortID(Integer sortID) {
        this.sortID = sortID;
    }

    public String getHookText() {
        return hookText;
    }

    public void setHookText(String hookText) {
        this.hookText = hookText;
    }

    public String getCardHookLong() {
        return cardHookLong;
    }

    public void setCardHookLong(String cardHookLong) {
        this.cardHookLong = cardHookLong;
    }

    public String getFragmentText() {
        return fragmentText;
    }

    public void setFragmentText(String fragmentText) {
        this.fragmentText = fragmentText;
    }
}

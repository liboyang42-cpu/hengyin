package com.chengyinhub.business.domain;

import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 建议版路线站点预设对象 preset_route_node
 *
 * @author chengyin
 */
public class CmsPresetRouteNode extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 站点ID */
    private Long id;

    /** 所属路线ID */
    @Excel(name = "所属路线ID")
    private Long routeId;

    /** 站点名称 */
    @Excel(name = "站点名称")
    private String name;

    /** 站点地址 */
    private String address;

    /** 经度 */
    private String longitude;

    /** 纬度 */
    private String latitude;

    /** 站点配图URL */
    private String imgUrl;

    /** 建议停留时长(分钟) */
    private Long nodeTime;

    /** 站点顺序(小在前) */
    private Integer sort;

    /** 绑定玩法模板ID(cms_template_library,可空) */
    private Long playTemplateId;

    /** 删除标志 0存在 2删除 */
    private Integer delFlag;

    public void setId(Long id) { this.id = id; }
    public Long getId() { return id; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }
    public Long getRouteId() { return routeId; }
    public void setName(String name) { this.name = name; }
    public String getName() { return name; }
    public void setAddress(String address) { this.address = address; }
    public String getAddress() { return address; }
    public void setLongitude(String longitude) { this.longitude = longitude; }
    public String getLongitude() { return longitude; }
    public void setLatitude(String latitude) { this.latitude = latitude; }
    public String getLatitude() { return latitude; }
    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }
    public String getImgUrl() { return imgUrl; }
    public void setNodeTime(Long nodeTime) { this.nodeTime = nodeTime; }
    public Long getNodeTime() { return nodeTime; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Integer getSort() { return sort; }
    public void setPlayTemplateId(Long playTemplateId) { this.playTemplateId = playTemplateId; }
    public Long getPlayTemplateId() { return playTemplateId; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public Integer getDelFlag() { return delFlag; }
}

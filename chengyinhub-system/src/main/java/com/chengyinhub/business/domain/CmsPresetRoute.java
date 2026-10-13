package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.List;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 建议版城市路线预设对象 preset_route
 *
 * @author chengyin
 */
public class CmsPresetRoute extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 路线ID */
    private Long id;

    /** 城市 */
    @Excel(name = "城市")
    private String city;

    /** 路线名称 */
    @Excel(name = "路线名称")
    private String name;

    /** 路线静态预览图URL */
    private String previewImg;

    /** 沿路坐标(JSON) */
    private String routeGeometry;

    /** 总距离(km) */
    private BigDecimal totalDistance;

    /** 总时长(分钟) */
    private Long totalTime;

    /** 默认分类ID(逗号分隔) */
    private String defaultCategoryIds;

    /** 排序(小在前) */
    private Integer sort;

    /** 上下架 1上架 0下架 */
    @Excel(name = "状态", readConverterExp = "1=上架,0=下架")
    private String status;

    /** 删除标志 0存在 2删除 */
    private Integer delFlag;

    /** 路线站点列表(查详情时回填,非表字段) */
    private List<CmsPresetRouteNode> nodes;

    public void setId(Long id) { this.id = id; }
    public Long getId() { return id; }
    public void setCity(String city) { this.city = city; }
    public String getCity() { return city; }
    public void setName(String name) { this.name = name; }
    public String getName() { return name; }
    public void setPreviewImg(String previewImg) { this.previewImg = previewImg; }
    public String getPreviewImg() { return previewImg; }
    public void setRouteGeometry(String routeGeometry) { this.routeGeometry = routeGeometry; }
    public String getRouteGeometry() { return routeGeometry; }
    public void setTotalDistance(BigDecimal totalDistance) { this.totalDistance = totalDistance; }
    public BigDecimal getTotalDistance() { return totalDistance; }
    public void setTotalTime(Long totalTime) { this.totalTime = totalTime; }
    public Long getTotalTime() { return totalTime; }
    public void setDefaultCategoryIds(String defaultCategoryIds) { this.defaultCategoryIds = defaultCategoryIds; }
    public String getDefaultCategoryIds() { return defaultCategoryIds; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Integer getSort() { return sort; }
    public void setStatus(String status) { this.status = status; }
    public String getStatus() { return status; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public Integer getDelFlag() { return delFlag; }
    public void setNodes(List<CmsPresetRouteNode> nodes) { this.nodes = nodes; }
    public List<CmsPresetRouteNode> getNodes() { return nodes; }
}

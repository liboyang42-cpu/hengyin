package com.chengyinhub.business.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 主题模板章节 VO —— 白名单字段，不含 routeGeometry 原值（精确坐标）。
 * routeShape 是归一化到 0~1 的相对点列，无底图无绝对经纬度。
 *
 * @author chengyinhub
 * @date 2026-07-19
 */
public class TopicTemplateChapterVO {

    @Schema(description = "章节ID；商家申请承接时作为唯一目标")
    private Long id;

    @Schema(description = "章节名称")
    private String name;

    @Schema(description = "章节描述")
    private String description;

    @Schema(description = "章节品类")
    private String category;

    @Schema(description = "是否必选章：1是 0否")
    private Integer required;

    @Schema(description = "排序")
    private Long sortId;

    @Schema(description = "节点数")
    private Integer nodeCount;

    @Schema(description = "节点列表（白名单字段）")
    private List<TopicTemplateNodeVO> nodes;

    @Schema(description = "路线剪影：归一化 0~1 相对点列 [{x,y}]，无底图无坐标")
    private List<RouteShapePoint> routeShape;

    @Schema(description = "商家招募状态：登录商家或本主题原发布者下发；recruitEnabled=0 的章节为 null")
    private TopicTemplateRecruitVO recruitStatus;

    @Schema(description = "圈层候选编号，如 A1/O1/K1/H1/N1；非圈层模板为 null")
    private String circleCandidateCode;

    @Schema(description = "该候选在本圈层中的供给角色")
    private String circleRole;

    @Schema(description = "该候选适用的供给准入规则编码")
    private String circlePolicy;

    @Schema(description = "城市实例是否选中该候选：1是 0否；模板原型固定为1")
    private Integer circleEnabled;

    // ====== 07-23 P0 字段字典·章节级(本轮收窄范围) ======
    // 来源 cms_topic_chapter_template_meta；未录入的章节整段为 null。

    @Schema(description = "本章节需要的商家角色与职责;未录入时不下发")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String roleNeed;

    @Schema(description = "排他规则(强度/区域/期限/原因)。⚠️模板层声明,不是运行时招商闸")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private TopicTemplateExclusivityVO exclusivity;

    /**
     * 运行投入。可见范围是「合作方」—— 与 {@link #recruitStatus} 同一道闸下发，
     * 匿名玩家看不到。这些是商家成本口径，不是玩家该看的内容。
     */
    @Schema(description = "运行投入(工时/人员/物料/场地/容量/时段/预约/取消/资质)。仅登录商家或原发布者可见")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private TopicTemplateEffortVO effort;

    // ====== getters/setters ======

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Integer getRequired() { return required; }
    public void setRequired(Integer required) { this.required = required; }
    public Long getSortId() { return sortId; }
    public void setSortId(Long sortId) { this.sortId = sortId; }
    public Integer getNodeCount() { return nodeCount; }
    public void setNodeCount(Integer nodeCount) { this.nodeCount = nodeCount; }
    public List<TopicTemplateNodeVO> getNodes() { return nodes; }
    public void setNodes(List<TopicTemplateNodeVO> nodes) { this.nodes = nodes; }
    public List<RouteShapePoint> getRouteShape() { return routeShape; }
    public void setRouteShape(List<RouteShapePoint> routeShape) { this.routeShape = routeShape; }
    public TopicTemplateRecruitVO getRecruitStatus() { return recruitStatus; }
    public void setRecruitStatus(TopicTemplateRecruitVO recruitStatus) { this.recruitStatus = recruitStatus; }
    public String getCircleCandidateCode() { return circleCandidateCode; }
    public void setCircleCandidateCode(String circleCandidateCode) { this.circleCandidateCode = circleCandidateCode; }
    public String getCircleRole() { return circleRole; }
    public void setCircleRole(String circleRole) { this.circleRole = circleRole; }
    public String getCirclePolicy() { return circlePolicy; }
    public void setCirclePolicy(String circlePolicy) { this.circlePolicy = circlePolicy; }
    public Integer getCircleEnabled() { return circleEnabled; }
    public void setCircleEnabled(Integer circleEnabled) { this.circleEnabled = circleEnabled; }
    public String getRoleNeed() { return roleNeed; }
    public void setRoleNeed(String roleNeed) { this.roleNeed = roleNeed; }
    public TopicTemplateExclusivityVO getExclusivity() { return exclusivity; }
    public void setExclusivity(TopicTemplateExclusivityVO exclusivity) { this.exclusivity = exclusivity; }
    public TopicTemplateEffortVO getEffort() { return effort; }
    public void setEffort(TopicTemplateEffortVO effort) { this.effort = effort; }
}

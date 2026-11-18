package com.chengyinhub.business.domain;

/** 自由探索地点的到访配置(cms_topic_node.visit_check / visit_radius_m),只读投影。 */
public class FreeExploreVisitConfig {
    private Long nodeId;
    private Integer visitCheck;
    private Integer visitRadiusM;

    public FreeExploreVisitConfig() {
    }

    public FreeExploreVisitConfig(Long nodeId, Integer visitCheck, Integer visitRadiusM) {
        this.nodeId = nodeId;
        this.visitCheck = visitCheck;
        this.visitRadiusM = visitRadiusM;
    }

    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Integer getVisitCheck() { return visitCheck; }
    public void setVisitCheck(Integer visitCheck) { this.visitCheck = visitCheck; }
    public Integer getVisitRadiusM() { return visitRadiusM; }
    public void setVisitRadiusM(Integer visitRadiusM) { this.visitRadiusM = visitRadiusM; }
}

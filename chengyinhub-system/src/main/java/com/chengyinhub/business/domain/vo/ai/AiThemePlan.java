package com.chengyinhub.business.domain.vo.ai;

import java.util.List;

/**
 * AI 主题方案(M1 组题 / M2 草稿 共用)。
 */
public class AiThemePlan
{
    private String title;
    private String subtitle;
    private String storyline;
    private List<String> tags;
    private Integer estDurationMin;
    private String fitReason;
    private List<String> risks;
    private List<AiPlanNode> nodes;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getStoryline() { return storyline; }
    public void setStoryline(String storyline) { this.storyline = storyline; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public Integer getEstDurationMin() { return estDurationMin; }
    public void setEstDurationMin(Integer estDurationMin) { this.estDurationMin = estDurationMin; }
    public String getFitReason() { return fitReason; }
    public void setFitReason(String fitReason) { this.fitReason = fitReason; }
    public List<String> getRisks() { return risks; }
    public void setRisks(List<String> risks) { this.risks = risks; }
    public List<AiPlanNode> getNodes() { return nodes; }
    public void setNodes(List<AiPlanNode> nodes) { this.nodes = nodes; }
}

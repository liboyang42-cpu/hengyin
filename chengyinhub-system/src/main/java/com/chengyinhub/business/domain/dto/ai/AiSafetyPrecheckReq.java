package com.chengyinhub.business.domain.dto.ai;

import java.util.List;

/**
 * M2 发布前安全预检请求。
 */
public class AiSafetyPrecheckReq
{
    private String title;
    private String subtitle;
    private String description;
    private List<PrecheckNode> nodes;

    public static class PrecheckNode
    {
        private String name;
        private String task;
        private String hint1;
        private String hint2;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getTask() { return task; }
        public void setTask(String task) { this.task = task; }
        public String getHint1() { return hint1; }
        public void setHint1(String hint1) { this.hint1 = hint1; }
        public String getHint2() { return hint2; }
        public void setHint2(String hint2) { this.hint2 = hint2; }
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<PrecheckNode> getNodes() { return nodes; }
    public void setNodes(List<PrecheckNode> nodes) { this.nodes = nodes; }
}

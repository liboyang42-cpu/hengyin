package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.List;

public class ServiceStatusVO {
    private String overallStatus;
    private String updatedAt;
    private String source;
    private List<Component> components = new ArrayList<>();
    private List<Incident> incidents = new ArrayList<>();
    public String getOverallStatus() { return overallStatus; }
    public void setOverallStatus(String overallStatus) { this.overallStatus = overallStatus; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public List<Component> getComponents() { return components; }
    public void setComponents(List<Component> components) { this.components = components; }
    public List<Incident> getIncidents() { return incidents; }
    public void setIncidents(List<Incident> incidents) { this.incidents = incidents; }

    public static class Component {
        private String code;
        private String name;
        private String status;
        private String updatedAt;
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    }

    public static class Incident {
        private String id;
        private String title;
        private String status;
        private String startedAt;
        private String resolvedAt;
        private String latestUpdate;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getStartedAt() { return startedAt; }
        public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
        public String getResolvedAt() { return resolvedAt; }
        public void setResolvedAt(String resolvedAt) { this.resolvedAt = resolvedAt; }
        public String getLatestUpdate() { return latestUpdate; }
        public void setLatestUpdate(String latestUpdate) { this.latestUpdate = latestUpdate; }
    }
}

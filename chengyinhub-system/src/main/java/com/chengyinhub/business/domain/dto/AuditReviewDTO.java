package com.chengyinhub.business.domain.dto;

/**
 * P3 审核处理请求。
 */
public class AuditReviewDTO {
    private Long taskId;
    private Integer expectedVersion;
    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
    private Integer status;
    private String reasonCode;
    private String reasonText;
    /** 可选：固定清单原因编码（见 AuditReasonCodes），清单外编码 400。 */
    private java.util.List<String> reasonCodes;
    private String requestId;
    /** 仅由已鉴权 controller 覆盖写入，不信任请求体。 */
    private String actorRoleCode;
    /** O-05 #4 可选:主题/玩法驳回时的逐项问题(开关 chengyin.audit.item-problems.enabled 关闭时忽略)。 */
    private java.util.List<ProblemItem> problems;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public java.util.List<String> getReasonCodes() { return reasonCodes; }
    public void setReasonCodes(java.util.List<String> reasonCodes) { this.reasonCodes = reasonCodes; }
    public String getReasonText() { return reasonText; }
    public void setReasonText(String reasonText) { this.reasonText = reasonText; }
    /** NF-01 新契约名；保留 reasonText 兼容既有管理端。 */
    public void setReason(String reason) { this.reasonText = reason; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getActorRoleCode() { return actorRoleCode; }
    public void setActorRoleCode(String actorRoleCode) { this.actorRoleCode = actorRoleCode; }
    public java.util.List<ProblemItem> getProblems() { return problems; }
    public void setProblems(java.util.List<ProblemItem> problems) { this.problems = problems; }

    /** 一条逐项问题:itemType=TOPIC/CHAPTER/NODE/TEMPLATE,itemId 为空表示针对整个审核对象。 */
    public static class ProblemItem {
        private String itemType;
        private Long itemId;
        private String reasonCode;
        private String reasonText;

        public String getItemType() { return itemType; }
        public void setItemType(String itemType) { this.itemType = itemType; }
        public Long getItemId() { return itemId; }
        public void setItemId(Long itemId) { this.itemId = itemId; }
        public String getReasonCode() { return reasonCode; }
        public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
        public String getReasonText() { return reasonText; }
        public void setReasonText(String reasonText) { this.reasonText = reasonText; }
    }
}

package com.chengyinhub.business.domain.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 主题工作台 P0 统一待办只读快照。
 *
 * <p>它只引用领域对象，并不承载报名、供给、邀约或合作的领域状态。所有允许动作都必须由
 * 对应领域服务再次裁决；当前没有对象级授权真源的动作必须保持禁用。</p>
 */
public class TopicOperationsSnapshotDTO {

    private boolean available = true;
    private String unavailableReasonCode;
    private TopicRefDTO topic = new TopicRefDTO();
    private List<OperationItemDTO> items = Collections.emptyList();
    private SummaryDTO summary = new SummaryDTO();

    /**
     * 无对象级主题范围时返回不含主题和来源数据的安全快照，避免将预期的 fail-closed 当成全局错误。
     */
    public static TopicOperationsSnapshotDTO unavailable(String reasonCode) {
        TopicOperationsSnapshotDTO result = new TopicOperationsSnapshotDTO();
        result.setAvailable(false);
        result.setUnavailableReasonCode(reasonCode);
        return result;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public String getUnavailableReasonCode() {
        return unavailableReasonCode;
    }

    public void setUnavailableReasonCode(String unavailableReasonCode) {
        this.unavailableReasonCode = unavailableReasonCode;
    }

    public TopicRefDTO getTopic() {
        return topic;
    }

    public void setTopic(TopicRefDTO topic) {
        this.topic = topic;
    }

    public List<OperationItemDTO> getItems() {
        return items;
    }

    public void setItems(List<OperationItemDTO> items) {
        this.items = items == null ? Collections.<OperationItemDTO>emptyList() : items;
    }

    public SummaryDTO getSummary() {
        return summary;
    }

    public void setSummary(SummaryDTO summary) {
        this.summary = summary;
    }

    /** 主题锚点与内容归属状态；不返回 CmsTopic 实体，避免扩散无关字段。 */
    public static class TopicRefDTO {
        private Long id;
        private String contentOwnershipState;
        private String contentOwnerType;
        private Long contentOwnerId;
        private String readOnlyReasonCode;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getContentOwnershipState() {
            return contentOwnershipState;
        }

        public void setContentOwnershipState(String contentOwnershipState) {
            this.contentOwnershipState = contentOwnershipState;
        }

        public String getContentOwnerType() {
            return contentOwnerType;
        }

        public void setContentOwnerType(String contentOwnerType) {
            this.contentOwnerType = contentOwnerType;
        }

        public Long getContentOwnerId() {
            return contentOwnerId;
        }

        public void setContentOwnerId(Long contentOwnerId) {
            this.contentOwnerId = contentOwnerId;
        }

        public String getReadOnlyReasonCode() {
            return readOnlyReasonCode;
        }

        public void setReadOnlyReasonCode(String readOnlyReasonCode) {
            this.readOnlyReasonCode = readOnlyReasonCode;
        }
    }

    /** 稳定指向一个领域来源；客户端不得以该引用构造通用写命令。 */
    public static class SourceRefDTO {
        private String type;
        private Long id;
        private Long topicId;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

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
    }

    public static class OperationItemDTO {
        private String itemKey;
        private SourceRefDTO sourceRef;
        private String sourceState;
        private String displayState;
        private String actionability;
        private Date latestAt;
        private List<AllowedActionDTO> allowedActions = Collections.emptyList();

        public String getItemKey() {
            return itemKey;
        }

        public void setItemKey(String itemKey) {
            this.itemKey = itemKey;
        }

        public SourceRefDTO getSourceRef() {
            return sourceRef;
        }

        public void setSourceRef(SourceRefDTO sourceRef) {
            this.sourceRef = sourceRef;
        }

        public String getSourceState() {
            return sourceState;
        }

        public void setSourceState(String sourceState) {
            this.sourceState = sourceState;
        }

        public String getDisplayState() {
            return displayState;
        }

        public void setDisplayState(String displayState) {
            this.displayState = displayState;
        }

        public String getActionability() {
            return actionability;
        }

        public void setActionability(String actionability) {
            this.actionability = actionability;
        }

        public Date getLatestAt() {
            return latestAt;
        }

        public void setLatestAt(Date latestAt) {
            this.latestAt = latestAt;
        }

        public List<AllowedActionDTO> getAllowedActions() {
            return allowedActions;
        }

        public void setAllowedActions(List<AllowedActionDTO> allowedActions) {
            this.allowedActions = allowedActions == null ? Collections.<AllowedActionDTO>emptyList() : allowedActions;
        }
    }

    /** 只表达动作可用性和所需输入；永不返回可绕过后端鉴权的裸 URL。 */
    public static class AllowedActionDTO {
        private String code;
        private boolean enabled;
        private String disabledReasonCode;
        private List<String> requiredInput = Collections.emptyList();

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDisabledReasonCode() {
            return disabledReasonCode;
        }

        public void setDisabledReasonCode(String disabledReasonCode) {
            this.disabledReasonCode = disabledReasonCode;
        }

        public List<String> getRequiredInput() {
            return requiredInput;
        }

        public void setRequiredInput(List<String> requiredInput) {
            this.requiredInput = requiredInput == null ? Collections.<String>emptyList() : new ArrayList<>(requiredInput);
        }
    }

    public static class SummaryDTO {
        private int actionableCount;
        private int waitingExternalCount;
        private int blockedCount;
        private int readOnlyCount;

        public int getActionableCount() {
            return actionableCount;
        }

        public void setActionableCount(int actionableCount) {
            this.actionableCount = actionableCount;
        }

        public int getWaitingExternalCount() {
            return waitingExternalCount;
        }

        public void setWaitingExternalCount(int waitingExternalCount) {
            this.waitingExternalCount = waitingExternalCount;
        }

        public int getBlockedCount() {
            return blockedCount;
        }

        public void setBlockedCount(int blockedCount) {
            this.blockedCount = blockedCount;
        }

        public int getReadOnlyCount() {
            return readOnlyCount;
        }

        public void setReadOnlyCount(int readOnlyCount) {
            this.readOnlyCount = readOnlyCount;
        }
    }
}

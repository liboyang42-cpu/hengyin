package com.chengyinhub.business.domain.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 作者在目标城市为某个地点槽选点。
 *
 * <p>ID 在 API 边界用十进制字符串，进入服务层前由控制器转成 {@link Long}；
 * 服务端只写 {@code bindingRevision/confirmedAt/confirmedBy/verificationStatus}，
 * 前端传的任何“已核验”标记都不采信。
 */
public class TopicLocationBindingCommand {

    private Long topicId;
    private String slotKey;
    private Long nodeId;
    private List<Long> approvedFallbackNodeIds = new ArrayList<Long>();
    private String openingHours;
    private String observationNote;
    private List<String> referenceMediaIds = new ArrayList<String>();
    /** 乐观锁：与当前 bindingRevision 不一致则拒绝；null = 不校验。 */
    private Integer expectedRevision;

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getSlotKey() { return slotKey; }
    public void setSlotKey(String slotKey) { this.slotKey = slotKey; }

    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }

    public List<Long> getApprovedFallbackNodeIds() { return approvedFallbackNodeIds; }
    public void setApprovedFallbackNodeIds(List<Long> approvedFallbackNodeIds) {
        this.approvedFallbackNodeIds = approvedFallbackNodeIds == null
                ? new ArrayList<Long>() : approvedFallbackNodeIds;
    }

    public String getOpeningHours() { return openingHours; }
    public void setOpeningHours(String openingHours) { this.openingHours = openingHours; }

    public String getObservationNote() { return observationNote; }
    public void setObservationNote(String observationNote) { this.observationNote = observationNote; }

    public List<String> getReferenceMediaIds() { return referenceMediaIds; }
    public void setReferenceMediaIds(List<String> referenceMediaIds) {
        this.referenceMediaIds = referenceMediaIds == null
                ? new ArrayList<String>() : referenceMediaIds;
    }

    public Integer getExpectedRevision() { return expectedRevision; }
    public void setExpectedRevision(Integer expectedRevision) { this.expectedRevision = expectedRevision; }
}

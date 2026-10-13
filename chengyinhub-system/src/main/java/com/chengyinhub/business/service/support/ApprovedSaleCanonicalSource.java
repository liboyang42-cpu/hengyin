package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ApprovedSaleCanonicalSource {
    private final AuditTaskMapper taskMapper;
    private final ObjectMapper json = new ObjectMapper();

    public ApprovedSaleCanonicalSource(AuditTaskMapper taskMapper) { this.taskMapper = taskMapper; }

    /** productType 决定 canonical15 按哪一档校验,见 {@link TopicSaleCanonicalV1#validateComplete(Integer)}。 */
    public ApprovedSaleCanonical read(Long topicId, Integer productType) {
        AuditTask query = new AuditTask();
        query.setBizType(AuditTask.BIZ_TOPIC_SALE_REVIEW);
        query.setBizId(topicId);
        List<AuditTask> tasks = taskMapper.selectAuditTaskList(query);
        AuditTask task = tasks == null || tasks.isEmpty() ? null : tasks.get(0);
        if (task == null || !AuditTask.STATUS_APPROVED.equals(task.getStatus())) {
            throw new IllegalStateException("开售审核未通过，缺不可变 canonical15 来源");
        }
        try {
            JsonNode root = json.readTree(task.getSnapshotJson());
            String schema = root.path("canonicalSchemaVersion").asText(null);
            if (!TopicSaleCanonicalV1.SCHEMA_VERSION.equals(schema)) {
                throw new IllegalStateException("SALE_SNAPSHOT_UNKNOWN_SCHEMA_VERSION:" + schema);
            }
            JsonNode versionNode = root.get("canonicalParameterVersion");
            if (versionNode == null || !versionNode.canConvertToLong() || versionNode.longValue() <= 0) {
                throw new IllegalStateException("开售审核快照缺 canonicalParameterVersion");
            }
            TopicSaleCanonicalV1 canonical = json.treeToValue(root.get("canonical15"), TopicSaleCanonicalV1.class);
            canonical.validateComplete(productType);
            return new ApprovedSaleCanonical(task.getId(), schema, versionNode.longValue(), canonical);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("开售审核 canonical15 不可解析", e);
        }
    }
}

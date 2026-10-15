package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/** 审核只绑定作者提交的内容与关系；不纳入计数、昵称、时间和媒体审核状态。 */
public final class CommunityPostAuditSnapshot {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] CONTENT = ("communityId postType title body audience commentPolicy "
            + "replyApprovalEnabled slowModeSeconds locationPrecision cityCode poiName approximateGeohash "
            + "disclosureType safetyLabelsJson guidelineVersionId").split(" ");
    private static final String[] MEDIA = ("id media_type object_key derived_object_key mime_type byte_size "
            + "width height duration_ms alt_text").split(" ");
    private static final String[] REFERENCES = "reference_type reference_id snapshot_json privacy_snapshot".split(" ");

    private CommunityPostAuditSnapshot() { }

    public static String capture(CommunityPost post, int reviewedPostVersion, CommunityPostMapper mapper) {
        ObjectNode snapshot = JSON.createObjectNode();
        snapshot.put("schemaVersion", 1);
        snapshot.put("bizType", AuditTask.BIZ_COMMUNITY_POST);
        snapshot.put("bizId", post.getId());
        snapshot.put("authorId", post.getAuthorId());
        snapshot.put("reviewedPostVersion", reviewedPostVersion);
        JsonNode raw = JSON.valueToTree(post);
        ObjectNode content = snapshot.putObject("post");
        for (String field : CONTENT) {
            JsonNode value = raw.has(field) ? raw.get(field) : JSON.nullNode();
            if ("safetyLabelsJson".equals(field) && value.isTextual()) {
                try { value = JSON.readTree(value.textValue()); }
                catch (Exception invalid) { throw stale(); }
            }
            content.set(field, value);
        }
        // 查询结果已按真实媒体顺序、话题、被提及用户、引用顺序排序。
        snapshot.set("media", rows(mapper.selectMediaForSafety(post.getId()), MEDIA));
        snapshot.set("topicCodes", JSON.valueToTree(mapper.selectTopicCodes(post.getId())));
        snapshot.set("mentionedMemberIds", JSON.valueToTree(mapper.selectMentionedMemberIds(post.getId())));
        snapshot.set("references", rows(mapper.selectReferences(post.getId()), REFERENCES));
        return snapshot.toString();
    }

    public static int reviewedVersion(String snapshot, Long postId, Long authorId) {
        try {
            JsonNode root = JSON.readTree(snapshot);
            JsonNode version = root.path("reviewedPostVersion");
            if (!root.path("schemaVersion").isIntegralNumber() || root.path("schemaVersion").asInt(-1) != 1
                    || !AuditTask.BIZ_COMMUNITY_POST.equals(root.path("bizType").asText())
                    || postId == null || postId <= 0L || !root.path("bizId").isIntegralNumber()
                    || !root.path("bizId").canConvertToLong() || root.path("bizId").longValue() != postId
                    || authorId == null || authorId <= 0L || !root.path("authorId").isIntegralNumber()
                    || !root.path("authorId").canConvertToLong() || root.path("authorId").longValue() != authorId
                    || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 0
                    || !root.path("post").isObject() || !root.path("media").isArray()
                    || !root.path("topicCodes").isArray() || !root.path("mentionedMemberIds").isArray()
                    || !root.path("references").isArray()) throw stale();
            return version.intValue();
        } catch (Exception invalid) {
            throw stale();
        }
    }

    public static boolean matches(String submitted, String current) {
        try { return JSON.readTree(current).equals(JSON.readTree(submitted)); }
        catch (Exception invalid) { return false; }
    }

    private static ArrayNode rows(List<Map<String, Object>> rows, String[] fields) {
        ArrayNode result = JSON.createArrayNode();
        if (rows == null) return result;
        for (Map<String, Object> row : rows) {
            ObjectNode value = result.addObject();
            for (String field : fields) {
                Object item = null;
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if (field.equalsIgnoreCase(entry.getKey())) { item = entry.getValue(); break; }
                }
                value.set(field, JSON.valueToTree(item));
            }
        }
        return result;
    }

    private static ServiceException stale() {
        return new ServiceException("审核内容缺少有效版本，请由作者重新保存后提交审核", 409);
    }
}

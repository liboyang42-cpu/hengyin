package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.ApprovedTopicReleaseMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * App 已批准主题发行 v1（/api/approved-topic-release/v1/*）。
 *
 * <p>审核侧：review/sources|prepare 读当前可送审捕获（城市版 pro 主题，只含可公开字段，答案只给 answerPresent）；
 * review/submit 走既有 BIZ_TOPIC 组合审核链路送审（开放单 refresh 版本 +1，否则新单版本 0），同事务冻结捕获；
 * review/status 读回原回执；review/current 读任务当前状态并比对当前内容是否仍等于送审捕获。
 *
 * <p>发行侧：prepare 只在任务已通过、任务快照仍是本次送审包、当前内容仍等于送审捕获时返回已批准摘要 + manifestHash；
 * publish 按 (topic, requestId) 幂等分配不可变发行（发行头 CAS，同一已批准快照只分配一次）；status 读回同一回执。
 * 发行记录不改变公开可见性或玩家资格（App 契约：receipt ≠ 当前公开/可玩）。
 *
 * <p>商家来源选择与主题封面绑定在本后端没有事实源：sources 如实返回空集，带 sourceSelections 的请求 409；
 * 捕获不含 selectedCover。错误码即 HTTP 状态（控制器照设）：401/403/404/409/422/400。
 */
@Service
public class ApprovedTopicReleases {
    public static final String REVIEW_CONTRACT = "questify.topic-release.review-preparation.v1";
    public static final String OBSERVATION_CONTRACT = "questify.topic-release.review-observation.v1";
    public static final String SOURCES_CONTRACT = "questify.topic-release.merchant-source-choices.v1";
    public static final String RELEASE_CONTRACT = "questify.topic-release.text-city.v1";
    public static final int SELECTOR_BODY_BYTES = 4096, SOURCE_BODY_BYTES = 16 * 1024;
    static final int MAX_CHAPTERS = 200, MAX_BLOCKS = 200;

    /** 走既有 BIZ_TOPIC 组合审核送审（与 submitTopicBundle 同一份冻结包），返回审核任务 id；在调用方事务内执行。 */
    public interface ReviewSubmitter { long submit(long topicId, long memberId); }

    private static final ObjectMapper STRICT = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final ObjectMapper CANONICAL = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Set<String> SELECTOR = Set.of("topicId", "observedAuditTaskId", "observedAuditTaskVersion",
            "sourceConfigVersion", "snapshotHash", "requestId");
    private static final Set<String> PUBLISH = Set.of("topicId", "auditTaskId", "auditTaskVersion", "auditSnapshotHash",
            "expectedHeadRevision", "expectedManifestHash", "requestId");

    private final ApprovedTopicReleaseMapper mapper;
    private final ReviewSubmitter submitter;

    public ApprovedTopicReleases(ApprovedTopicReleaseMapper mapper, ReviewSubmitter submitter) {
        this.mapper = mapper; this.submitter = submitter;
    }

    // ---------------------------------------------------------------- review

    public Map<String, Object> reviewSources(long member, String raw) {
        Map<String, JsonNode> b = body(raw, SELECTOR_BODY_BYTES, Set.of("topicId", "observedAuditTaskId"), Set.of());
        long topicId = positive(b, "topicId"), taskId = positive(b, "observedAuditTaskId");
        Map<String, Object> topic = ownedCityTopic(member, topicId, false);
        Map<String, Object> task = topicTask(topicId, taskId, false);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("contract", SOURCES_CONTRACT); d.put("templateIdNamespace", "CMS_MEMBER_TEMPLATE");
        d.put("currentness", "CONFIRMED_HISTORICAL_INPUTS"); d.put("ownerMemberId", member);
        d.put("topicId", topicId); d.put("observedAuditTaskId", taskId);
        d.put("sourceConfigVersion", num(topic.get("configVersion"))); d.put("observedAuditTaskVersion", num(task.get("version")));
        d.put("approvalProof", false); d.put("publicationAuthority", false);
        // 本后端没有 MERCHANT_AI_TEMPLATE_SOURCE_V1 / MERCHANT_STORE_FACTS_CONFIRMATION_V1 事实源。
        d.put("sources", List.of());
        return d;
    }

    public Map<String, Object> reviewPrepare(long member, String raw) {
        Map<String, JsonNode> b = body(raw, SOURCE_BODY_BYTES, Set.of("topicId", "observedAuditTaskId"), Set.of("sourceSelections"));
        long topicId = positive(b, "topicId"), taskId = positive(b, "observedAuditTaskId");
        rejectSourceSelections(b);
        Map<String, Object> topic = ownedCityTopic(member, topicId, false);
        Map<String, Object> task = topicTask(topicId, taskId, false);
        return captureData(capture(topic), taskId, (int) num(task.get("version")));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reviewSubmit(long member, String raw) {
        Map<String, JsonNode> b = body(raw, SOURCE_BODY_BYTES, SELECTOR, Set.of("sourceSelections"));
        Selector s = selector(b);
        rejectSourceSelections(b);
        Map<String, Object> prior = mapper.submissionByRequest(member, s.requestId);
        if (prior != null) return reviewReceipt(sameSelector(prior, s));

        Map<String, Object> topic = ownedCityTopic(member, s.topicId, true);
        Map<String, Object> observed = topicTask(s.topicId, s.taskId, true);
        if (num(observed.get("version")) != s.taskVersion) throw fail(409, "审核任务已变化，请重新读取");
        Capture before = capture(topic);
        if (!before.hash.equals(s.snapshotHash) || before.configVersion != s.configVersion)
            throw fail(409, "主题内容已变化，请重新读取");
        Long open = mapper.openTopicTask(s.topicId);
        boolean observedOpen = open(observed);
        if (observedOpen ? !Long.valueOf(s.taskId).equals(open) : open != null)
            throw fail(409, "主题已有另一张待审任务");

        long taskId;
        try { taskId = submitter.submit(s.topicId, member); }
        catch (ServiceException e) { throw fail(Objects.equals(e.getCode(), 403) ? 403 : 409, e.getMessage()); }
        catch (IllegalStateException | IllegalArgumentException e) { throw fail(409, e.getMessage()); }
        Map<String, Object> after = mapper.task(taskId, true);
        if (after == null) throw fail(409, "审核任务读取失败");
        int version = (int) num(after.get("version")), status = (int) num(after.get("status"));
        boolean sameTask = taskId == s.taskId;
        if (sameTask ? version != s.taskVersion + 1 || (status != 0 && status != 3) : version != 0 || status != 0)
            throw fail(409, "审核任务已被并发修改");
        Capture submitted = capture(mapper.topic(s.topicId, true));
        if (!submitted.hash.equals(before.hash)) throw fail(409, "主题内容在送审时变化");

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("topicId", s.topicId); row.put("memberId", member); row.put("requestId", s.requestId);
        row.put("observedTaskId", s.taskId); row.put("observedTaskVersion", s.taskVersion);
        row.put("sourceConfigVersion", s.configVersion); row.put("snapshotHash", s.snapshotHash);
        row.put("auditTaskId", taskId); row.put("submittedTaskVersion", version); row.put("submittedTaskStatus", status);
        row.put("bundleSha256", sha((String) after.get("snapshotJson")));
        row.put("captureJson", canonical(captureData(submitted, s.taskId, s.taskVersion)));
        try { mapper.insertSubmission(row); }
        catch (DuplicateKeyException e) { throw fail(409, "同一请求编号正在处理，请稍后读取结果"); }
        return reviewReceipt(row);
    }

    public Map<String, Object> reviewStatus(long member, String raw) {
        Selector s = selector(body(raw, SELECTOR_BODY_BYTES, SELECTOR, Set.of()));
        Map<String, Object> prior = mapper.submissionByRequest(member, s.requestId);
        if (prior == null) throw fail(404, "没有该送审请求");
        return reviewReceipt(sameSelector(prior, s));
    }

    public Map<String, Object> reviewCurrent(long member, String raw) {
        Selector s = selector(body(raw, SELECTOR_BODY_BYTES, SELECTOR, Set.of()));
        Map<String, Object> prior = mapper.submissionByRequest(member, s.requestId);
        if (prior == null) throw fail(404, "没有该送审请求");
        sameSelector(prior, s);
        long taskId = num(prior.get("auditTaskId"));
        Map<String, Object> task = mapper.task(taskId, false);
        if (task == null) throw fail(404, "审核任务不存在");
        Capture now = capture(ownedCityTopic(member, s.topicId, false));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("contract", OBSERVATION_CONTRACT); d.put("topicId", s.topicId); d.put("auditTaskId", taskId);
        d.put("requestId", s.requestId); d.put("submittedTaskVersion", num(prior.get("submittedTaskVersion")));
        d.put("observedTaskVersion", num(task.get("version"))); d.put("observedTaskStatus", num(task.get("status")));
        d.put("submittedSnapshotHash", s.snapshotHash); d.put("observedSnapshotHash", now.hash);
        d.put("matchesSubmittedCapture", now.hash.equals(s.snapshotHash));
        d.put("approvalProof", false); d.put("releaseAllocated", false);
        return d;
    }

    // ---------------------------------------------------------------- release

    public Map<String, Object> prepare(long member, String raw) {
        Map<String, JsonNode> b = body(raw, SELECTOR_BODY_BYTES, Set.of("topicId", "auditTaskId"), Set.of());
        return approved(member, positive(b, "topicId"), positive(b, "auditTaskId"), false).data;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> publish(long member, String raw) {
        Publish c = publishCommand(body(raw, SELECTOR_BODY_BYTES, PUBLISH, Set.of()));
        Map<String, Object> prior = mapper.releaseByRequest(c.topicId, c.requestId);
        if (prior != null) return releaseReceipt(samePublish(prior, member, c, 409));
        Approved a = approved(member, c.topicId, c.taskId, true);
        if (a.taskVersion != c.taskVersion || !a.auditSnapshotHash.equals(c.auditSnapshotHash)
                || !a.manifestHash.equals(c.manifestHash) || a.headRevision != c.headRevision)
            throw fail(409, "已批准摘要已变化，请重新读取");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("topicId", c.topicId); row.put("memberId", member); row.put("requestId", c.requestId);
        row.put("revisionNo", a.headRevision + 1); row.put("sourceConfigVersion", a.configVersion);
        row.put("auditTaskId", c.taskId); row.put("auditRecordId", a.auditRecordId); row.put("auditTaskVersion", a.taskVersion);
        row.put("manifestHash", a.manifestHash); row.put("auditSnapshotHash", a.auditSnapshotHash);
        row.put("reviewSubmissionId", a.submissionId); row.put("manifestJson", canonical(a.manifest));
        try { mapper.insertRelease(row); }
        catch (DuplicateKeyException e) { throw fail(409, "该已批准快照已分配发行或发行头已推进"); }
        row.put("id", num(row.get("id"))); row.put("schemaVersion", 1);
        return releaseReceipt(row);
    }

    public Map<String, Object> status(long member, String raw) {
        Publish c = publishCommand(body(raw, SELECTOR_BODY_BYTES, PUBLISH, Set.of()));
        Map<String, Object> prior = mapper.releaseByRequest(c.topicId, c.requestId);
        if (prior == null || num(prior.get("memberId")) != member) throw fail(404, "没有该发行请求");
        return releaseReceipt(samePublish(prior, member, c, 409));
    }

    private Approved approved(long member, long topicId, long taskId, boolean lock) {
        Map<String, Object> topic = ownedCityTopic(member, topicId, lock);
        Map<String, Object> task = topicTask(topicId, taskId, lock);
        if (num(task.get("status")) != 1) throw fail(409, "审核任务当前未通过");
        Map<String, Object> sub = mapper.latestSubmissionForTask(taskId);
        if (sub == null) throw fail(409, "该审核任务没有 App 送审捕获");
        if (!sha((String) task.get("snapshotJson")).equals(sub.get("bundleSha256")))
            throw fail(409, "审核任务快照已不是本次送审内容");
        Capture now = capture(topic);
        if (!now.hash.equals(sub.get("snapshotHash")) || now.configVersion != num(sub.get("sourceConfigVersion")))
            throw fail(409, "主题内容已在审核后变化");
        Long record = mapper.latestApproveRecord(taskId);
        if (record == null) throw fail(409, "没有审核通过记录");

        Approved a = new Approved();
        a.taskVersion = (int) num(task.get("version")); a.auditSnapshotHash = now.hash; a.configVersion = now.configVersion;
        a.auditRecordId = record; a.submissionId = num(sub.get("id")); a.headRevision = mapper.headRevision(topicId);
        List<Object> chapters = releaseChapters(now.chapters);
        a.manifest = new LinkedHashMap<>();
        a.manifest.put("contract", RELEASE_CONTRACT); a.manifest.put("topicId", topicId); a.manifest.put("auditTaskId", taskId);
        a.manifest.put("auditSnapshotHash", now.hash); a.manifest.put("sourceConfigVersion", now.configVersion);
        a.manifest.put("name", topic.get("name")); a.manifest.put("description", topic.get("description"));
        a.manifest.put("categoryIds", topic.get("categoryIds")); a.manifest.put("chapters", chapters);
        a.manifestHash = sha(canonical(a.manifest));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("contract", RELEASE_CONTRACT); d.put("topicId", topicId); d.put("auditTaskId", taskId);
        d.put("auditTaskVersion", (long) a.taskVersion); d.put("auditSnapshotHash", now.hash); d.put("manifestHash", a.manifestHash);
        d.put("headRevision", a.headRevision); d.put("sourceConfigVersion", now.configVersion);
        d.put("name", topic.get("name")); d.put("description", topic.get("description")); d.put("categoryIds", topic.get("categoryIds"));
        d.put("currentlyApproved", true); d.put("releaseAllocated", false); d.put("secretValuesExcluded", true);
        d.put("chapters", chapters);
        a.data = d;
        return a;
    }

    /** 发行版每章至少一个节点、每个节点必须有答案；节点图片不进入发行（App 只接受空 imgUrl）。 */
    static List<Object> releaseChapters(List<Map<String, Object>> captured) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> chapter : captured) {
            boolean hasNode = false;
            List<Object> blocks = new ArrayList<>();
            for (Object o : (List<?>) chapter.get("blocks")) {
                @SuppressWarnings("unchecked") Map<String, Object> block = (Map<String, Object>) o;
                Map<String, Object> copy = new LinkedHashMap<>(block);
                if ("node".equals(block.get("type"))) {
                    @SuppressWarnings("unchecked") Map<String, Object> node = new LinkedHashMap<>((Map<String, Object>) block.get("node"));
                    if (!Boolean.TRUE.equals(node.get("answerPresent"))) throw fail(422, "有节点未设置答案，不能发行");
                    node.remove("imageReference");
                    copy.put("node", node);
                    hasNode = true;
                }
                blocks.add(copy);
            }
            if (!hasNode) throw fail(422, "有章节没有任何节点，不能发行");
            Map<String, Object> c = new LinkedHashMap<>(chapter);
            c.put("blocks", blocks);
            out.add(c);
        }
        return out;
    }

    private Map<String, Object> releaseReceipt(Map<String, Object> row) {
        long taskId = num(row.get("auditTaskId"));
        Map<String, Object> task = mapper.task(taskId, false);
        boolean current = task != null && num(task.get("status")) == 1 && num(task.get("version")) == num(row.get("auditTaskVersion"));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("releaseId", num(row.get("id"))); d.put("topicId", num(row.get("topicId")));
        d.put("sourceConfigVersion", num(row.get("sourceConfigVersion"))); d.put("auditTaskId", taskId);
        d.put("auditRecordId", num(row.get("auditRecordId"))); d.put("auditTaskVersion", num(row.get("auditTaskVersion")));
        d.put("schemaVersion", num(row.get("schemaVersion"))); d.put("manifestHash", row.get("manifestHash"));
        d.put("auditSnapshotHash", row.get("auditSnapshotHash")); d.put("currentlyApproved", current);
        return d;
    }

    private static Map<String, Object> reviewReceipt(Map<String, Object> row) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("topicId", num(row.get("topicId"))); d.put("auditTaskId", num(row.get("auditTaskId")));
        d.put("sourceConfigVersion", num(row.get("sourceConfigVersion")));
        d.put("submittedTaskVersion", num(row.get("submittedTaskVersion"))); d.put("submittedTaskStatus", num(row.get("submittedTaskStatus")));
        d.put("snapshotHash", row.get("snapshotHash")); d.put("requestId", row.get("requestId"));
        d.put("approvalProof", false); d.put("releaseAllocated", false);
        return d;
    }

    // ---------------------------------------------------------------- capture

    static final class Capture {
        long topicId, configVersion; String hash; Map<String, Object> summary; List<Map<String, Object>> chapters;
    }

    private Capture capture(Map<String, Object> topic) {
        long topicId = num(topic.get("id"));
        List<Map<String, Object>> nodes = mapper.nodes(topicId);
        Set<Long> templateIds = new TreeSet<>();
        for (Map<String, Object> n : nodes) if (n.get("templateId") != null) templateIds.add(num(n.get("templateId")));
        List<Map<String, Object>> templates = templateIds.isEmpty() ? List.of() : mapper.templates(templateIds);
        return capture(topic, mapper.chapters(topicId), nodes, templates);
    }

    /** 纯函数：同一份库内事实永远得到同一捕获与同一 snapshotHash。 */
    static Capture capture(Map<String, Object> topic, List<Map<String, Object>> chapterRows,
                           List<Map<String, Object>> nodeRows, List<Map<String, Object>> templateRows) {
        Map<Long, Map<String, Object>> templates = new HashMap<>();
        for (Map<String, Object> t : templateRows) templates.put(num(t.get("id")), t);
        Map<Long, List<Map<String, Object>>> nodesByChapter = new HashMap<>();
        Map<Long, Map<String, Object>> nodeById = new HashMap<>();
        for (Map<String, Object> n : nodeRows) {
            nodesByChapter.computeIfAbsent(n.get("chapterId") == null ? 0L : num(n.get("chapterId")), k -> new ArrayList<>()).add(n);
            nodeById.put(num(n.get("id")), n);
        }
        if (chapterRows.isEmpty() || chapterRows.size() > MAX_CHAPTERS) throw fail(422, "主题章节数不在 1..200 内");
        List<Map<String, Object>> chapters = new ArrayList<>();
        for (Map<String, Object> row : chapterRows) {
            long chapterId = num(row.get("id"));
            List<Map<String, Object>> own = nodesByChapter.getOrDefault(chapterId, List.of());
            List<Map<String, Object>> blocks = new ArrayList<>();
            String stored = (String) row.get("blocks");
            if (stored == null || stored.isBlank()) {
                for (Map<String, Object> n : own) blocks.add(nodeBlock(null, n, templates));
            } else {
                JsonNode array;
                try { array = STRICT.readTree(stored); } catch (JsonProcessingException e) { throw fail(422, "章节块无法读取"); }
                if (!array.isArray()) throw fail(422, "章节块无法读取");
                for (JsonNode b : array) {
                    String type = b.path("type").asText(null);
                    String key = b.hasNonNull("key") ? b.get("key").asText() : null;
                    if ("text".equals(type)) {
                        Map<String, Object> text = new LinkedHashMap<>();
                        text.put("type", "text"); text.put("key", key);
                        text.put("content", b.hasNonNull("content") ? b.get("content").asText() : null);
                        text.put("node", null);
                        blocks.add(text);
                    } else if ("node".equals(type) && b.path("nodeId").canConvertToLong()) {
                        Map<String, Object> n = nodeById.get(b.get("nodeId").asLong());
                        if (n == null || n.get("chapterId") == null || num(n.get("chapterId")) != chapterId)
                            throw fail(422, "章节块引用了不属于本章的节点");
                        blocks.add(nodeBlock(key, n, templates));
                    } else {
                        throw fail(422, "章节含文本/节点以外的块，不属于文本城市发行");
                    }
                }
            }
            if (blocks.isEmpty() || blocks.size() > MAX_BLOCKS) throw fail(422, "章节块数不在 1..200 内");
            Map<String, Object> chapter = new LinkedHashMap<>();
            chapter.put("id", chapterId); chapter.put("name", row.get("name")); chapter.put("description", row.get("description"));
            chapter.put("blocks", blocks);
            chapters.add(chapter);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", topic.get("name")); summary.put("description", topic.get("description"));
        summary.put("categoryIds", topic.get("categoryIds")); summary.put("coverReference", topic.get("imgUrl"));
        summary.put("productType", 1L); summary.put("publishMode", "pro");
        summary.put("secretValuesExcluded", true); summary.put("publicationEligibilityChecked", false);
        summary.put("chapters", chapters);
        Capture c = new Capture();
        c.topicId = num(topic.get("id")); c.configVersion = num(topic.get("configVersion"));
        c.summary = summary; c.chapters = chapters;
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("contract", REVIEW_CONTRACT); identity.put("topicId", c.topicId);
        identity.put("sourceConfigVersion", c.configVersion); identity.put("summary", summary);
        c.hash = sha(canonical(identity));
        return c;
    }

    private static Map<String, Object> nodeBlock(String key, Map<String, Object> n, Map<Long, Map<String, Object>> templates) {
        Long templateId = n.get("templateId") == null ? null : num(n.get("templateId"));
        Map<String, Object> t = templateId == null ? null : templates.get(templateId);
        if (templateId == null || templateId <= 0 || t == null) throw fail(422, "节点未绑定有效玩法模板");
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", num(n.get("id"))); node.put("templateId", templateId);
        node.put("nodeTime", n.get("nodeTime") == null ? 0L : Math.max(0L, num(n.get("nodeTime"))));
        node.put("templateCategoryId", t.get("categoryId") == null || num(t.get("categoryId")) < 0 ? null : num(t.get("categoryId")));
        node.put("name", n.get("name")); node.put("description", n.get("description")); node.put("address", n.get("address"));
        node.put("longitude", n.get("longitude")); node.put("latitude", n.get("latitude")); node.put("imageReference", n.get("imgUrl"));
        node.put("templateTitle", t.get("title")); node.put("templateCategoryIds", t.get("activityCategoryIds"));
        node.put("templateContentHash", "sha256:" + templateContentHash(t));
        node.put("questionText", t.get("questionName")); node.put("ruleInstructions", t.get("ruleInstructions"));
        node.put("answerPresent", t.get("answerPresent") != null && num(t.get("answerPresent")) != 0);
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "node"); block.put("key", key); block.put("content", null); block.put("node", node);
        return block;
    }

    /** 模板公开内容摘要：mapper 只选出可公开列（不含答案、揭晓、商家指引），全部按字符串规范化后排序键 sha256。 */
    static String templateContentHash(Map<String, Object> template) {
        Map<String, Object> fields = new TreeMap<>();
        for (Map.Entry<String, Object> e : template.entrySet()) {
            if ("answerPresent".equals(e.getKey())) continue;
            fields.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }
        return sha(canonical(fields));
    }

    private static Map<String, Object> captureData(Capture c, long observedTaskId, int observedTaskVersion) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("contract", REVIEW_CONTRACT); d.put("topicId", c.topicId); d.put("observedAuditTaskId", observedTaskId);
        d.put("observedAuditTaskVersion", (long) observedTaskVersion); d.put("sourceConfigVersion", c.configVersion);
        d.put("snapshotHash", c.hash); d.put("approvalProof", false); d.put("releaseAllocated", false); d.put("summary", c.summary);
        return d;
    }

    // ---------------------------------------------------------------- guards

    private Map<String, Object> ownedCityTopic(long member, long topicId, boolean lock) {
        Map<String, Object> topic = mapper.topic(topicId, lock);
        if (topic == null || (topic.get("delFlag") != null && num(topic.get("delFlag")) == 2)) throw fail(404, "主题不存在");
        if (topic.get("memberId") == null || num(topic.get("memberId")) != member) throw fail(403, "无权操作该主题");
        if (topic.get("productType") == null || num(topic.get("productType")) != 1 || !"pro".equals(topic.get("publishMode")))
            throw fail(422, "只有城市版专业模式主题走已批准发行");
        return topic;
    }

    private Map<String, Object> topicTask(long topicId, long taskId, boolean lock) {
        Map<String, Object> task = mapper.task(taskId, lock);
        if (task == null || !"TOPIC".equals(task.get("bizType")) || num(task.get("bizId")) != topicId
                || "report".equals(task.get("sourceType"))) throw fail(404, "该主题没有这张内容审核任务");
        return task;
    }

    private static boolean open(Map<String, Object> task) { long s = num(task.get("status")); return s == 0 || s == 3; }

    private static void rejectSourceSelections(Map<String, JsonNode> b) {
        if (b.containsKey("sourceSelections")) throw fail(409, "商家来源确认不存在或已变化");
    }

    // ---------------------------------------------------------------- wire

    record Selector(long topicId, long taskId, int taskVersion, long configVersion, String snapshotHash, String requestId) {}
    record Publish(long topicId, long taskId, int taskVersion, String auditSnapshotHash, long headRevision, String manifestHash, String requestId) {}

    private static Selector selector(Map<String, JsonNode> b) {
        return new Selector(positive(b, "topicId"), positive(b, "observedAuditTaskId"), int32(b, "observedAuditTaskVersion"),
                nonNegative(b, "sourceConfigVersion"), hash(b, "snapshotHash"), requestId(b));
    }

    private static Publish publishCommand(Map<String, JsonNode> b) {
        return new Publish(positive(b, "topicId"), positive(b, "auditTaskId"), int32(b, "auditTaskVersion"), hash(b, "auditSnapshotHash"),
                nonNegative(b, "expectedHeadRevision"), hash(b, "expectedManifestHash"), requestId(b));
    }

    private static Map<String, Object> sameSelector(Map<String, Object> row, Selector s) {
        if (num(row.get("topicId")) != s.topicId || num(row.get("observedTaskId")) != s.taskId
                || num(row.get("observedTaskVersion")) != s.taskVersion || num(row.get("sourceConfigVersion")) != s.configVersion
                || !s.snapshotHash.equals(row.get("snapshotHash"))) throw fail(409, "同一请求编号对应不同的送审内容");
        return row;
    }

    private static Map<String, Object> samePublish(Map<String, Object> row, long member, Publish c, int code) {
        if (num(row.get("memberId")) != member || num(row.get("auditTaskId")) != c.taskId
                || num(row.get("auditTaskVersion")) != c.taskVersion || !c.auditSnapshotHash.equals(row.get("auditSnapshotHash"))
                || !c.manifestHash.equals(row.get("manifestHash")) || num(row.get("revisionNo")) != c.headRevision + 1)
            throw fail(code, "同一请求编号对应不同的发行命令");
        return row;
    }

    /** 严格请求体：大小上限、合法 JSON、对象、无重复键、键集合精确。 */
    static Map<String, JsonNode> body(String raw, int limit, Set<String> required, Set<String> optional) {
        if (raw == null || raw.isBlank()) throw fail(400, "请求体为空");
        if (raw.getBytes(StandardCharsets.UTF_8).length > limit) throw fail(400, "请求体过大");
        JsonNode root;
        try { root = STRICT.readTree(raw); } catch (JsonProcessingException e) { throw fail(400, "请求体不是合法 JSON"); }
        if (root == null || !root.isObject()) throw fail(400, "请求体必须是对象");
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        root.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));
        if (!fields.keySet().containsAll(required)) throw fail(400, "缺少字段");
        for (String k : fields.keySet()) if (!required.contains(k) && !optional.contains(k)) throw fail(400, "未知字段 " + k);
        return fields;
    }

    private static long positive(Map<String, JsonNode> b, String k) {
        long v = nonNegative(b, k); if (v <= 0) throw fail(400, k + " 必须为正整数"); return v;
    }
    private static long nonNegative(Map<String, JsonNode> b, String k) {
        JsonNode n = b.get(k);
        if (n == null || !n.isIntegralNumber() || !n.canConvertToLong() || n.asLong() < 0) throw fail(400, k + " 必须为非负整数");
        return n.asLong();
    }
    private static int int32(Map<String, JsonNode> b, String k) {
        long v = nonNegative(b, k); if (v > Integer.MAX_VALUE) throw fail(400, k + " 超出范围"); return (int) v;
    }
    private static String hash(Map<String, JsonNode> b, String k) {
        JsonNode n = b.get(k);
        if (n == null || !n.isTextual() || !n.asText().matches("[0-9a-f]{64}")) throw fail(400, k + " 必须为 64 位小写十六进制");
        return n.asText();
    }
    private static String requestId(Map<String, JsonNode> b) {
        JsonNode n = b.get("requestId");
        if (n == null || !n.isTextual() || !n.asText().matches("[A-Za-z0-9-]{1,128}")) throw fail(400, "requestId 无效");
        return n.asText();
    }

    static long num(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof Boolean) return (Boolean) v ? 1 : 0;
        if (v instanceof String) return Long.parseLong((String) v);
        throw new IllegalStateException("缺少数值");
    }

    static String canonical(Object value) {
        try { return CANONICAL.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("规范化失败", e); }
    }

    static String sha(String text) {
        return AppOriginalRequestLedger.sha256Hex((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    static ServiceException fail(int code, String message) { return new ServiceException(message, code); }

    private static final class Approved {
        int taskVersion; long configVersion, headRevision, auditRecordId, submissionId;
        String auditSnapshotHash, manifestHash; Map<String, Object> manifest, data;
    }
}

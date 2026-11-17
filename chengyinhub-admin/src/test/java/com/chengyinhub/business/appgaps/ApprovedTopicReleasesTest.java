package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.ApprovedTopicReleaseMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 已批准主题发行 v1 单测（真库部分见 ApprovedTopicReleasesMySqlIT）：严格请求体、送审捕获是纯函数且不含答案、
 * 送审按 requestId 幂等且回执不变量不成立不落捕获、发行只在已通过且内容未变时分配、发行版剔除节点图片并要求每章有带答案的节点。
 */
class ApprovedTopicReleasesTest {
    static final long TOPIC = 11L, MEMBER = 7L, CHAPTER = 21L, NODE = 31L, TEMPLATE = 41L, TASK = 51L;
    static final String BLOCKS = "[{\"type\":\"text\",\"key\":\"t1\",\"content\":\"开场\"},{\"type\":\"node\",\"key\":\"n1\",\"nodeId\":31}]";

    ApprovedTopicReleaseMapper mapper;
    ApprovedTopicReleases.ReviewSubmitter submitter;
    ApprovedTopicReleases service;
    Map<String, Object> topic;

    static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static Map<String, Object> task(int status, int version, String snapshotJson) {
        return row("id", TASK, "bizType", "TOPIC", "bizId", TOPIC, "sourceType", "topic_bundle_v2",
                "status", status, "version", version, "snapshotJson", snapshotJson);
    }

    static List<Map<String, Object>> chapters(String blocks) {
        return List.of(row("id", CHAPTER, "name", "第一章", "description", null, "blocks", blocks));
    }

    static List<Map<String, Object>> nodes() {
        return List.of(row("id", NODE, "chapterId", CHAPTER, "templateId", TEMPLATE, "nodeTime", 15, "name", "钟楼",
                "description", null, "address", null, "longitude", "121.1", "latitude", "31.2", "imgUrl", "https://x/n.jpg"));
    }

    static List<Map<String, Object>> templates(int answerPresent) {
        return List.of(row("id", TEMPLATE, "categoryId", 7L, "title", "钟声谜题", "activityCategoryIds", "1",
                "questionName", "几点?", "ruleInstructions", "听钟", "answerPresent", answerPresent));
    }

    static int code(Executable call) {
        ServiceException e = assertThrows(ServiceException.class, call);
        return e.getCode();
    }

    @BeforeEach void setUp() {
        mapper = mock(ApprovedTopicReleaseMapper.class);
        submitter = mock(ApprovedTopicReleases.ReviewSubmitter.class);
        service = new ApprovedTopicReleases(mapper, submitter);
        topic = row("id", TOPIC, "memberId", MEMBER, "productType", 1, "publishMode", "pro", "delFlag", 0,
                "name", "IT 城南夜游", "description", "夜里走一圈", "categoryIds", "1,2", "imgUrl", "https://x/c.jpg", "configVersion", 3L);
        when(mapper.topic(eq(TOPIC), anyBoolean())).thenAnswer(a -> topic);
        when(mapper.chapters(TOPIC)).thenReturn(chapters(BLOCKS));
        when(mapper.nodes(TOPIC)).thenReturn(nodes());
        when(mapper.templates(any())).thenReturn(templates(1));
        // Mockito 对 Map 返回值默认给空 Map；"没有先前请求" 在 mapper 契约里是 null。
        when(mapper.submissionByRequest(anyLong(), anyString())).thenReturn(null);
        when(mapper.releaseByRequest(anyLong(), anyString())).thenReturn(null);
        when(mapper.latestSubmissionForTask(anyLong())).thenReturn(null);
        when(mapper.task(anyLong(), anyBoolean())).thenReturn(null);
    }

    // ---------------------------------------------------------------- strict body

    @Test void bodyIsStrictAboutSizeShapeDuplicatesAndKeySet() {
        Set<String> req = Set.of("topicId", "observedAuditTaskId");
        assertEquals(2, ApprovedTopicReleases.body("{\"topicId\":1,\"observedAuditTaskId\":2}", 4096, req, Set.of()).size());
        assertEquals(400, code(() -> ApprovedTopicReleases.body(" ", 4096, req, Set.of())), "空体");
        assertEquals(400, code(() -> ApprovedTopicReleases.body("{\"topicId\":1,\"observedAuditTaskId\":2}", 10, req, Set.of())), "超限");
        assertEquals(400, code(() -> ApprovedTopicReleases.body("[1]", 4096, req, Set.of())), "非对象");
        assertEquals(400, code(() -> ApprovedTopicReleases.body("{\"topicId\":1", 4096, req, Set.of())), "非法 JSON");
        assertEquals(400, code(() -> ApprovedTopicReleases.body("{\"topicId\":1,\"topicId\":1,\"observedAuditTaskId\":2}", 4096, req, Set.of())), "重复键");
        assertEquals(400, code(() -> ApprovedTopicReleases.body("{\"topicId\":1}", 4096, req, Set.of())), "缺字段");
        assertEquals(400, code(() -> ApprovedTopicReleases.body("{\"topicId\":1,\"observedAuditTaskId\":2,\"x\":0}", 4096, req, Set.of())), "未知字段");
        assertEquals(400, code(() -> service.reviewSources(MEMBER, "{\"topicId\":0,\"observedAuditTaskId\":2}")), "id 必须为正");
        assertEquals(400, code(() -> service.reviewSources(MEMBER, "{\"topicId\":\"11\",\"observedAuditTaskId\":2}")), "id 必须为整数");
    }

    // ---------------------------------------------------------------- capture

    @Test void captureIsPureExcludesAnswersAndHashFollowsContent() {
        ApprovedTopicReleases.Capture a = ApprovedTopicReleases.capture(topic, chapters(BLOCKS), nodes(), templates(1));
        ApprovedTopicReleases.Capture b = ApprovedTopicReleases.capture(topic, chapters(BLOCKS), nodes(), templates(1));
        assertEquals(a.hash, b.hash, "同一事实同一 snapshotHash");
        assertTrue(a.hash.matches("[0-9a-f]{64}"));
        assertEquals(2, ((List<?>) a.chapters.get(0).get("blocks")).size());
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) ((Map<String, Object>) ((List<?>) a.chapters.get(0).get("blocks")).get(1)).get("node");
        assertEquals(Boolean.TRUE, node.get("answerPresent"), "答案只给 answerPresent");
        assertFalse(node.containsKey("questionAnswer"));
        assertFalse(a.summary.containsKey("selectedCover"), "捕获不含 selectedCover");
        assertEquals(Boolean.TRUE, a.summary.get("secretValuesExcluded"));

        topic.put("configVersion", 4L);
        assertNotEquals(a.hash, ApprovedTopicReleases.capture(topic, chapters(BLOCKS), nodes(), templates(1)).hash, "配置版本变化即换哈希");
        topic.put("configVersion", 3L);
        assertNotEquals(a.hash, ApprovedTopicReleases.capture(topic, chapters(BLOCKS), nodes(), templates(0)).hash, "答案有无变化即换哈希");

        // 章节没存块时按节点顺序生成节点块
        assertEquals(1, ((List<?>) ApprovedTopicReleases.capture(topic, chapters(null), nodes(), templates(1)).chapters.get(0).get("blocks")).size());
    }

    @Test void templateContentHashIgnoresAnswerFlagButTracksPublicFields() {
        Map<String, Object> t = new LinkedHashMap<>(templates(1).get(0));
        String h = ApprovedTopicReleases.templateContentHash(t);
        t.put("answerPresent", 0);
        assertEquals(h, ApprovedTopicReleases.templateContentHash(t));
        t.put("title", "新标题");
        assertNotEquals(h, ApprovedTopicReleases.templateContentHash(t));
    }

    @Test void captureRejectsBlocksOutsideTextCity() {
        assertEquals(422, code(() -> ApprovedTopicReleases.capture(topic, List.of(), nodes(), templates(1))), "零章节");
        assertEquals(422, code(() -> ApprovedTopicReleases.capture(topic,
                chapters("[{\"type\":\"image\",\"key\":\"i\"}]"), nodes(), templates(1))), "文本/节点以外的块");
        assertEquals(422, code(() -> ApprovedTopicReleases.capture(topic,
                chapters("[{\"type\":\"node\",\"key\":\"n\",\"nodeId\":999}]"), nodes(), templates(1))), "引用别章节点");
        assertEquals(422, code(() -> ApprovedTopicReleases.capture(topic, chapters("{}"), nodes(), templates(1))), "块不是数组");
        assertEquals(422, code(() -> ApprovedTopicReleases.capture(topic, chapters(BLOCKS), nodes(), List.of())), "节点无有效模板");
    }

    @Test void releaseChaptersDropNodeImagesAndRequireAnsweredNodes() {
        ApprovedTopicReleases.Capture c = ApprovedTopicReleases.capture(topic, chapters(BLOCKS), nodes(), templates(1));
        List<Object> out = ApprovedTopicReleases.releaseChapters(c.chapters);
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) out.get(0)).get("blocks")).get(1)).get("node");
        assertFalse(node.containsKey("imageReference"), "节点图片不进发行");
        @SuppressWarnings("unchecked")
        Map<String, Object> captured = (Map<String, Object>) ((Map<?, ?>) ((List<?>) c.chapters.get(0).get("blocks")).get(1)).get("node");
        assertEquals("https://x/n.jpg", captured.get("imageReference"), "不改动送审捕获本身");

        ApprovedTopicReleases.Capture noAnswer = ApprovedTopicReleases.capture(topic, chapters(BLOCKS), nodes(), templates(0));
        assertEquals(422, code(() -> ApprovedTopicReleases.releaseChapters(noAnswer.chapters)), "节点必须有答案");
        ApprovedTopicReleases.Capture textOnly = ApprovedTopicReleases.capture(topic,
                chapters("[{\"type\":\"text\",\"key\":\"t1\",\"content\":\"开场\"}]"), nodes(), templates(1));
        assertEquals(422, code(() -> ApprovedTopicReleases.releaseChapters(textOnly.chapters)), "每章至少一个节点");
    }

    // ---------------------------------------------------------------- review

    @Test void reviewSourcesIsHonestlyEmptyAndSourceSelectionsConflict() {
        when(mapper.task(eq(TASK), anyBoolean())).thenReturn(task(0, 2, "{}"));
        Map<String, Object> d = service.reviewSources(MEMBER, "{\"topicId\":11,\"observedAuditTaskId\":51}");
        assertEquals(List.of(), d.get("sources"));
        assertEquals(3L, d.get("sourceConfigVersion"));
        assertEquals(2L, d.get("observedAuditTaskVersion"));
        assertEquals(409, code(() -> service.reviewPrepare(MEMBER,
                "{\"topicId\":11,\"observedAuditTaskId\":51,\"sourceSelections\":[]}")));
    }

    @Test void ownershipAndProductGuards() {
        when(mapper.task(eq(TASK), anyBoolean())).thenReturn(task(0, 2, "{}"));
        String body = "{\"topicId\":11,\"observedAuditTaskId\":51}";
        assertEquals(403, code(() -> service.reviewPrepare(8L, body)), "别人的主题");
        topic.put("publishMode", "simple");
        assertEquals(422, code(() -> service.reviewPrepare(MEMBER, body)), "只收城市版专业模式");
        topic.put("publishMode", "pro");
        topic.put("delFlag", 2);
        assertEquals(404, code(() -> service.reviewPrepare(MEMBER, body)), "已删主题");
        topic.put("delFlag", 0);
        when(mapper.task(eq(TASK), anyBoolean())).thenReturn(row("bizType", "TOPIC", "bizId", 99L, "sourceType", "x", "status", 0, "version", 0));
        assertEquals(404, code(() -> service.reviewPrepare(MEMBER, body)), "任务不属于该主题");
    }

    String prepareHash() {
        when(mapper.task(eq(TASK), anyBoolean())).thenReturn(task(0, 2, "{\"v\":1}"));
        Map<String, Object> d = service.reviewPrepare(MEMBER, "{\"topicId\":11,\"observedAuditTaskId\":51}");
        assertEquals(Boolean.FALSE, d.get("approvalProof"));
        return (String) d.get("snapshotHash");
    }

    static String selector(String hash, int version, String requestId) {
        return "{\"topicId\":11,\"observedAuditTaskId\":51,\"observedAuditTaskVersion\":" + version
                + ",\"sourceConfigVersion\":3,\"snapshotHash\":\"" + hash + "\",\"requestId\":\"" + requestId + "\"}";
    }

    @Test void reviewSubmitRefreshesOpenTaskAndFreezesCapture() {
        String hash = prepareHash();
        when(mapper.task(TASK, true)).thenReturn(task(0, 2, "{\"v\":1}"), task(0, 3, "{\"v\":2}"));
        when(mapper.openTopicTask(TOPIC)).thenReturn(TASK);
        when(submitter.submit(TOPIC, MEMBER)).thenReturn(TASK);

        Map<String, Object> receipt = service.reviewSubmit(MEMBER, selector(hash, 2, "req-1"));
        assertEquals(TASK, receipt.get("auditTaskId"));
        assertEquals(3L, receipt.get("submittedTaskVersion"));
        assertEquals(hash, receipt.get("snapshotHash"));
        assertEquals(Boolean.FALSE, receipt.get("approvalProof"));

        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(mapper).insertSubmission(row.capture());
        assertEquals(ApprovedTopicReleases.sha("{\"v\":2}"), row.getValue().get("bundleSha256"), "冻结送审后的任务快照");
        assertTrue(((String) row.getValue().get("captureJson")).contains(hash));
    }

    @Test void reviewSubmitReplaysSameRequestWithoutResubmitting() {
        String hash = prepareHash();
        Map<String, Object> prior = row("topicId", TOPIC, "observedTaskId", TASK, "observedTaskVersion", 2, "sourceConfigVersion", 3L,
                "snapshotHash", hash, "auditTaskId", TASK, "submittedTaskVersion", 3, "submittedTaskStatus", 0, "requestId", "req-1");
        when(mapper.submissionByRequest(MEMBER, "req-1")).thenReturn(prior);

        assertEquals(3L, service.reviewSubmit(MEMBER, selector(hash, 2, "req-1")).get("submittedTaskVersion"));
        assertEquals(3L, service.reviewStatus(MEMBER, selector(hash, 2, "req-1")).get("submittedTaskVersion"));
        assertEquals(409, code(() -> service.reviewSubmit(MEMBER, selector(hash, 1, "req-1"))), "同 requestId 不同内容");
        verify(submitter, never()).submit(anyLong(), anyLong());
        verify(mapper, never()).insertSubmission(any());
        assertEquals(404, code(() -> service.reviewStatus(MEMBER, selector(hash, 2, "req-2"))));
    }

    @Test void reviewSubmitRejectsStaleObservationsBeforeSubmitting() {
        String hash = prepareHash();
        when(mapper.openTopicTask(TOPIC)).thenReturn(TASK);
        assertEquals(409, code(() -> service.reviewSubmit(MEMBER, selector(hash, 1, "req-1"))), "任务版本已变");
        assertEquals(409, code(() -> service.reviewSubmit(MEMBER, selector("0".repeat(64), 2, "req-1"))), "内容已变");
        when(mapper.openTopicTask(TOPIC)).thenReturn(99L);
        assertEquals(409, code(() -> service.reviewSubmit(MEMBER, selector(hash, 2, "req-1"))), "另一张待审任务");
        verify(submitter, never()).submit(anyLong(), anyLong());
    }

    @Test void reviewSubmitDoesNotRecordCaptureWhenReceiptInvariantFails() {
        String hash = prepareHash();
        when(mapper.task(TASK, true)).thenReturn(task(0, 2, "{\"v\":1}"), task(0, 5, "{\"v\":2}"));
        when(mapper.openTopicTask(TOPIC)).thenReturn(TASK);
        when(submitter.submit(TOPIC, MEMBER)).thenReturn(TASK);
        assertEquals(409, code(() -> service.reviewSubmit(MEMBER, selector(hash, 2, "req-1"))), "版本不是 +1");
        verify(mapper, never()).insertSubmission(any());

        when(mapper.task(TASK, true)).thenReturn(task(0, 2, "{\"v\":1}"));
        when(submitter.submit(TOPIC, MEMBER)).thenThrow(new ServiceException("无权", 403));
        assertEquals(403, code(() -> service.reviewSubmit(MEMBER, selector(hash, 2, "req-1"))), "送审口 403 原样给出");
        verify(mapper, never()).insertSubmission(any());
    }

    // ---------------------------------------------------------------- release

    String approvedSetup(int status) {
        String hash = ApprovedTopicReleases.capture(topic, chapters(BLOCKS), nodes(), templates(1)).hash;
        when(mapper.task(eq(TASK), anyBoolean())).thenReturn(task(status, 3, "{\"v\":2}"));
        when(mapper.latestSubmissionForTask(TASK)).thenReturn(row("id", 61L, "bundleSha256", ApprovedTopicReleases.sha("{\"v\":2}"),
                "snapshotHash", hash, "sourceConfigVersion", 3L));
        when(mapper.latestApproveRecord(TASK)).thenReturn(71L);
        when(mapper.headRevision(TOPIC)).thenReturn(0L);
        return hash;
    }

    static String publish(String hash, String manifest, long head, String requestId) {
        return "{\"topicId\":11,\"auditTaskId\":51,\"auditTaskVersion\":3,\"auditSnapshotHash\":\"" + hash
                + "\",\"expectedHeadRevision\":" + head + ",\"expectedManifestHash\":\"" + manifest + "\",\"requestId\":\"" + requestId + "\"}";
    }

    @Test void prepareAndPublishAllocateOneReleaseOnTheApprovedSnapshot() {
        String hash = approvedSetup(1);
        Map<String, Object> d = service.prepare(MEMBER, "{\"topicId\":11,\"auditTaskId\":51}");
        assertEquals(Boolean.TRUE, d.get("currentlyApproved"));
        assertEquals(Boolean.FALSE, d.get("releaseAllocated"));
        assertEquals(hash, d.get("auditSnapshotHash"));
        String manifest = (String) d.get("manifestHash");
        assertTrue(manifest.matches("[0-9a-f]{64}"));

        when(mapper.insertRelease(any())).thenAnswer(a -> { a.<Map<String, Object>>getArgument(0).put("id", 81L); return 1; });
        Map<String, Object> receipt = service.publish(MEMBER, publish(hash, manifest, 0, "pub-1"));
        assertEquals(81L, receipt.get("releaseId"));
        assertEquals(71L, receipt.get("auditRecordId"));
        assertEquals(Boolean.TRUE, receipt.get("currentlyApproved"));
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(mapper).insertRelease(row.capture());
        assertEquals(1L, row.getValue().get("revisionNo"), "发行头 +1");
        assertEquals(61L, row.getValue().get("reviewSubmissionId"));

        assertEquals(409, code(() -> service.publish(MEMBER, publish(hash, manifest, 1, "pub-2"))), "发行头已推进");
        assertEquals(409, code(() -> service.publish(MEMBER, publish(hash, "0".repeat(64), 0, "pub-3"))), "摘要不符");
    }

    @Test void publishReplayReturnsSameReceiptAndStatusIsOwnerOnly() {
        String hash = approvedSetup(1);
        String manifest = "a".repeat(64);
        Map<String, Object> prior = row("id", 81L, "topicId", TOPIC, "memberId", MEMBER, "auditTaskId", TASK, "auditTaskVersion", 3,
                "auditSnapshotHash", hash, "manifestHash", manifest, "revisionNo", 1L, "sourceConfigVersion", 3L,
                "auditRecordId", 71L, "schemaVersion", 1);
        when(mapper.releaseByRequest(TOPIC, "pub-1")).thenReturn(prior);
        assertEquals(81L, service.publish(MEMBER, publish(hash, manifest, 0, "pub-1")).get("releaseId"));
        assertEquals(81L, service.status(MEMBER, publish(hash, manifest, 0, "pub-1")).get("releaseId"));
        assertEquals(404, code(() -> service.status(8L, publish(hash, manifest, 0, "pub-1"))), "别人读不到");
        assertEquals(409, code(() -> service.publish(MEMBER, publish(hash, "b".repeat(64), 0, "pub-1"))), "同 requestId 不同命令");
        verify(mapper, never()).insertRelease(any());
    }

    @Test void prepareRefusesUnapprovedOrChangedContent() {
        approvedSetup(0);
        String body = "{\"topicId\":11,\"auditTaskId\":51}";
        assertEquals(409, code(() -> service.prepare(MEMBER, body)), "任务未通过");

        approvedSetup(1);
        topic.put("name", "审后改名");
        assertEquals(409, code(() -> service.prepare(MEMBER, body)), "审核后内容变化");

        topic.put("name", "IT 城南夜游");
        when(mapper.task(eq(TASK), anyBoolean())).thenReturn(task(1, 3, "{\"v\":9}"));
        assertEquals(409, code(() -> service.prepare(MEMBER, body)), "任务快照已不是本次送审包");

        approvedSetup(1);
        when(mapper.latestApproveRecord(TASK)).thenReturn(null);
        assertEquals(409, code(() -> service.prepare(MEMBER, body)), "没有通过记录");
        verify(mapper, never()).insertRelease(any());
    }
}

package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.business.util.TopicContentSafetyHold;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O5b-4:主题/模板状态位真源的一致性探针(只读,不改任何写路径)。
 *
 * <p>配套设计文档 docs/delivery/doc2-o5b-audit-truth.md。三类断言:</p>
 * <ol>
 *   <li><b>写入口清单棘轮</b>:扫描全部 MyBatis XML,凡写 cms_topic / cms_member_template /
 *       cms_template_library 状态位的 UPDATE 语句必须与文档登记的清单一致。新增写入口 = 新的分叉风险,
 *       测试失败,先更新设计文档的真源矩阵再改这里。</li>
 *   <li><b>成对写入不变式</b>:cms_member_template 的 status 与 check_status 只能成对写(D-6)。</li>
 *   <li><b>审核结论 → 内容行</b>:主题过审/驳回写出的状态组合必须落在真源矩阵允许的组合里,
 *       且过审同时解除两处内容安全留置编码(D-1)。</li>
 * </ol>
 * 已知分叉(D-1 reopenAuditDecision 不清 content_safety_hold 等)在这里钉住现状;
 * 收敛实施(待 owner 拍板)修掉它时,本测试会失败并提醒同步更新文档。
 */
class AuditStatusTruthConsistencyTest {

    private static final Pattern UPDATE = Pattern.compile("<update\\s+id=\"(\\w+)\"[^>]*>(.*?)</update>", Pattern.DOTALL);
    private static final Pattern COLUMNS = Pattern.compile(
            "(?<![\\w.])(?:\\w+\\.)?(status|check_status|user_status|publish_status|draft_status"
                    + "|content_safety_hold|review_flag|beta_flag)\\s*=");
    private static final List<String> TABLES = Arrays.asList("cms_topic", "cms_member_template", "cms_template_library");

    /** 设计文档 §2「写入口清单」:table#statement → 写到的状态列。改这里必须同步改文档。 */
    private static final Map<String, String> REGISTERED_WRITERS = new TreeMap<>();

    static {
        REGISTERED_WRITERS.put("cms_member_template#approveBundledDraft", "check_status,draft_status,publish_status,status");
        REGISTERED_WRITERS.put("cms_member_template#applyAuditDecision", "check_status,publish_status,status");
        REGISTERED_WRITERS.put("cms_member_template#takeDownReported", "check_status,publish_status,status");
        REGISTERED_WRITERS.put("cms_member_template#updateCmsMemberTemplate", "check_status,draft_status,publish_status,status");
        REGISTERED_WRITERS.put("cms_member_template#reopenAuditDecision", "check_status,publish_status,status");
        REGISTERED_WRITERS.put("cms_member_template#restoreReportTakedown", "check_status,publish_status,status");
        REGISTERED_WRITERS.put("cms_template_library#applyAuditDecision", "publish_status,status");
        REGISTERED_WRITERS.put("cms_template_library#takeDownReported", "publish_status,status");
        REGISTERED_WRITERS.put("cms_template_library#takeDownReportedByMemberTemplate", "publish_status,status");
        REGISTERED_WRITERS.put("cms_template_library#updateCmsTemplateLibrary", "publish_status,status");
        REGISTERED_WRITERS.put("cms_template_library#reopenAuditDecision", "publish_status,status");
        REGISTERED_WRITERS.put("cms_template_library#restoreReportTakedown", "publish_status,status");
        REGISTERED_WRITERS.put("cms_topic#updateCmsTopic", "beta_flag,check_status,status,user_status");
        REGISTERED_WRITERS.put("cms_topic#casUserStatus", "user_status");
        REGISTERED_WRITERS.put("cms_topic#updateReviewFlag", "review_flag");
        REGISTERED_WRITERS.put("cms_topic#graduateBetaIfBeta", "beta_flag");
        REGISTERED_WRITERS.put("cms_topic#reopenAuditDecision", "check_status,status,user_status");
        REGISTERED_WRITERS.put("cms_topic#markContentSafetyHold", "content_safety_hold");
        REGISTERED_WRITERS.put("cms_topic#clearContentSafetyHold", "content_safety_hold");
    }

    @Test
    void statusWriterInventoryMatchesTheDesignDoc() throws IOException {
        Map<String, String> actual = scanStatusWriters();
        assertEquals(REGISTERED_WRITERS, actual,
                "主题/模板状态位的写入口变了:先在 docs/delivery/doc2-o5b-audit-truth.md §2 登记并说明对真源的影响,"
                        + "再同步 REGISTERED_WRITERS");
    }

    /** D-6:cms_member_template 的 status 与 check_status 是同一事实的两份拷贝,只能成对写。 */
    @Test
    void memberTemplateStatusAndCheckStatusAreAlwaysWrittenTogether() throws IOException {
        List<String> split = new ArrayList<>();
        for (Map.Entry<String, String> writer : scanStatusWriters().entrySet()) {
            if (!writer.getKey().startsWith("cms_member_template#")) continue;
            List<String> cols = Arrays.asList(writer.getValue().split(","));
            if (cols.contains("status") != cols.contains("check_status")) split.add(writer.getKey());
        }
        assertTrue(split.isEmpty(), "cms_member_template status/check_status 被拆开写: " + split);
    }

    /** D-1 已知分叉钉住:撤销/申诉重开只回写 check_status,不动 content_safety_hold(两处留置编码可能分叉)。 */
    @Test
    void knownDivergenceReopenDoesNotTouchContentSafetyHoldIsPinned() throws IOException {
        String reopen = scanStatusWriters().get("cms_topic#reopenAuditDecision");
        assertFalse(reopen.contains("content_safety_hold"),
                "D-1 已被修复:请更新 doc2-o5b-audit-truth.md 的分叉清单并删掉本条钉子");
    }

    @Test
    void topicApprovalLandsInAnAllowedCombinationAndClearsBothHoldEncodings() {
        Fixture f = new Fixture();
        AuditReviewDTO approve = new AuditReviewDTO();
        approve.setStatus(AuditTask.STATUS_APPROVED);

        f.service.review(9L, 1L, approve);

        CmsTopic written = f.writtenTopic();
        assertAllowedTopicCombination(AuditTask.STATUS_APPROVED, written);
        assertFalse(TopicContentSafetyHold.isHeld(written));
        verify(f.topics).clearContentSafetyHold(77L);
        verify(f.topics).updateReviewFlag(77L, 0);
    }

    @Test
    void topicRejectionLandsInAnAllowedCombination() {
        Fixture f = new Fixture();
        AuditReviewDTO reject = new AuditReviewDTO();
        reject.setStatus(AuditTask.STATUS_REJECTED);
        reject.setReasonText("内容不合规");

        f.service.review(9L, 1L, reject);

        CmsTopic written = f.writtenTopic();
        assertAllowedTopicCombination(AuditTask.STATUS_REJECTED, written);
        assertFalse(TopicContentSafetyHold.isHeld(written), "人工驳回(status=2)不得再被读成机审留置");
    }

    /** 真源矩阵 §3:内容审核槽的结论 → cms_topic (status, check_status, user_status) 允许的组合。 */
    static void assertAllowedTopicCombination(Integer decision, CmsTopic topic) {
        if (AuditTask.STATUS_APPROVED.equals(decision)) {
            assertEquals(Integer.valueOf(1), topic.getStatus());
            assertEquals(Integer.valueOf(1), topic.getCheckStatus());
            assertTrue(Integer.valueOf(0).equals(topic.getUserStatus()) || Integer.valueOf(1).equals(topic.getUserStatus()),
                    "过审后 user_status 只能是 0(配额/供给未上架)或 1");
        } else if (AuditTask.STATUS_REJECTED.equals(decision)) {
            assertEquals(Integer.valueOf(2), topic.getStatus());
            assertEquals(Integer.valueOf(2), topic.getCheckStatus());
            assertEquals(Integer.valueOf(0), topic.getUserStatus());
        } else {
            throw new AssertionError("未登记的结论: " + decision);
        }
    }

    static Map<String, String> scanStatusWriters() throws IOException {
        Path root = Paths.get("..", "chengyinhub-system", "src", "main", "resources", "mapper");
        Map<String, String> writers = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(p -> p.toString().endsWith(".xml")).sorted().collect(Collectors.toList());
        }
        for (Path file : files) {
            String xml = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Matcher update = UPDATE.matcher(xml);
            while (update.find()) {
                String body = update.group(2);
                for (String table : TABLES) {
                    Matcher target = Pattern.compile("\\bupdate\\s+" + table + "\\b", Pattern.CASE_INSENSITIVE).matcher(body);
                    if (!target.find()) continue;
                    String rest = body.substring(target.end());
                    Matcher end = Pattern.compile("</set>|\\bwhere\\b|<where>", Pattern.CASE_INSENSITIVE).matcher(rest);
                    String setClause = end.find() ? rest.substring(0, end.start()) : rest;
                    TreeSet<String> cols = new TreeSet<>();
                    Matcher col = COLUMNS.matcher(setClause);
                    while (col.find()) cols.add(col.group(1));
                    if (!cols.isEmpty()) writers.put(table + "#" + update.group(1), String.join(",", cols));
                }
            }
        }
        return writers;
    }

    /** 与 AuditTopicApproveBetaFlagTest 同一套最小桩:真 AuditTaskServiceImpl,mapper 全 mock。 */
    @SuppressWarnings("unchecked")
    private static final class Fixture {
        final CmsTopicMapper topics = mock(CmsTopicMapper.class);
        final AuditTaskServiceImpl service = new AuditTaskServiceImpl();

        Fixture() {
            AuditTaskMapper taskMapper = mock(AuditTaskMapper.class);
            AuditRecordMapper recordMapper = mock(AuditRecordMapper.class);
            IQuotaService quotaService = mock(IQuotaService.class);
            ObjectProvider<ICmsTopicService> topicServiceProvider = mock(ObjectProvider.class);
            com.chengyinhub.business.mapper.BusinessAuditEventMapper auditMapper =
                    mock(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class);
            ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
            ReflectionTestUtils.setField(service, "auditRecordMapper", recordMapper);
            ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);
            ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
            com.chengyinhub.business.service.support.AppMediaPublicationGuard mediaGuard =
                    com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(5L);
            com.chengyinhub.business.mapper.CmsTopicChapterMapper chapters =
                    mock(com.chengyinhub.business.mapper.CmsTopicChapterMapper.class);
            com.chengyinhub.business.mapper.CmsTopicNodeMapper nodes =
                    mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class);
            when(chapters.selectCmsTopicChapterList(any())).thenReturn(java.util.Collections.emptyList());
            when(nodes.selectCmsTopicNodeList(any())).thenReturn(java.util.Collections.emptyList());
            ReflectionTestUtils.setField(mediaGuard, "topics", topics);
            ReflectionTestUtils.setField(mediaGuard, "chapters", chapters);
            ReflectionTestUtils.setField(mediaGuard, "nodes", nodes);
            ReflectionTestUtils.setField(service, "mediaPublicationGuard", mediaGuard);
            ReflectionTestUtils.setField(service, "quotaService", quotaService);
            ReflectionTestUtils.setField(service, "cmsTopicServiceProvider", topicServiceProvider);
            when(topicServiceProvider.getObject()).thenReturn(mock(ICmsTopicService.class));

            AuditTask task = new AuditTask();
            task.setId(9L);
            task.setBizType(AuditTask.BIZ_TOPIC);
            task.setBizId(77L);
            task.setStatus(AuditTask.STATUS_PENDING);
            task.setVersion(0);
            when(taskMapper.selectAuditTaskById(9L)).thenReturn(task);
            when(taskMapper.updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any()))
                    .thenReturn(1);
            when(recordMapper.insertAuditRecord(any())).thenReturn(1);
            when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);

            CmsTopic exist = new CmsTopic();
            exist.setId(77L);
            exist.setMemberId(5L);
            when(topics.selectCmsTopicById(77L)).thenReturn(exist);
            when(topics.selectCmsTopicByIdForUpdate(77L)).thenReturn(exist);
            when(topics.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
            when(quotaService.lockAndCheckThemeOnline(5L, 77L)).thenReturn(null);
        }

        CmsTopic writtenTopic() {
            ArgumentCaptor<CmsTopic> captor = ArgumentCaptor.forClass(CmsTopic.class);
            verify(topics).updateCmsTopic(captor.capture());
            return captor.getValue();
        }
    }
}

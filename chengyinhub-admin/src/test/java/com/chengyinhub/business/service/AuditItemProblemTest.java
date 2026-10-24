package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.AuditProblem;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditProblemMapper;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.business.service.support.AuditProblemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O-05 #4:主题/玩法驳回可附逐项问题,落 audit_problem,作者在 /api/audit/timeline 对应轮次只读查看。
 * 开关 chengyin.audit.item-problems.enabled 默认关:关时请求里的 problems 被忽略,不读不写。
 */
class AuditItemProblemTest {
    private AuditTaskMapper taskMapper;
    private AuditRecordMapper recordMapper;
    private CmsTopicMapper topicMapper;
    private AuditProblemMapper problemMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicNodeMapper nodeMapper;
    private AuditTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(AuditTaskMapper.class);
        recordMapper = mock(AuditRecordMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        problemMapper = mock(AuditProblemMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "auditRecordMapper", recordMapper);
        com.chengyinhub.business.mapper.BusinessAuditEventMapper auditMapper =
                mock(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "auditAppealMapper",
                mock(com.chengyinhub.business.mapper.AuditAppealMapper.class));
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ICmsTopicService> topicServiceProvider = mock(ObjectProvider.class);
        when(topicServiceProvider.getObject()).thenReturn(topicService);
        ReflectionTestUtils.setField(service, "cmsTopicServiceProvider", topicServiceProvider);
        @SuppressWarnings("unchecked")
        ObjectProvider<IImService> imProvider = mock(ObjectProvider.class);
        when(imProvider.getObject()).thenReturn(mock(IImService.class));
        ReflectionTestUtils.setField(service, "imServiceProvider", imProvider);
        ReflectionTestUtils.setField(service, "auditProblemService",
                new AuditProblemService(problemMapper, chapterMapper, nodeMapper));

        AuditTask task = new AuditTask();
        task.setId(9L);
        task.setBizType(AuditTask.BIZ_TOPIC);
        task.setBizId(77L);
        task.setSubmitterId(5L);
        task.setSourceType("post_review_flag");
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setVersion(0);
        when(taskMapper.selectAuditTaskById(9L)).thenReturn(task);
        when(taskMapper.updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any())).thenReturn(1);
        when(recordMapper.insertAuditRecord(any())).thenAnswer(inv -> {
            ((com.chengyinhub.business.domain.AuditRecord) inv.getArgument(0)).setId(501L);
            return 1;
        });
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        when(problemMapper.insertAuditProblem(any())).thenReturn(1);

        CmsTopic topic = new CmsTopic();
        topic.setId(77L);
        topic.setMemberId(5L);
        topic.setStatus(1);
        topic.setDelFlag(0);
        when(topicMapper.selectCmsTopicById(77L)).thenReturn(topic);
        when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);

        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(31L);
        chapter.setTopicId(77L);
        when(chapterMapper.selectCmsTopicChapterById(31L)).thenReturn(chapter);
        CmsTopicChapter foreignChapter = new CmsTopicChapter();
        foreignChapter.setId(32L);
        foreignChapter.setTopicId(88L);
        when(chapterMapper.selectCmsTopicChapterById(32L)).thenReturn(foreignChapter);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(41L);
        node.setTopicId(77L);
        node.setTemplateId(61L);
        when(nodeMapper.selectCmsTopicNodeById(41L)).thenReturn(node);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
    }

    private void enable() {
        ReflectionTestUtils.setField(service, "itemProblemsEnabled", true);
    }

    private AuditReviewDTO.ProblemItem item(String type, Long id, String code, String text) {
        AuditReviewDTO.ProblemItem item = new AuditReviewDTO.ProblemItem();
        item.setItemType(type);
        item.setItemId(id);
        item.setReasonCode(code);
        item.setReasonText(text);
        return item;
    }

    private AuditReviewDTO reject(AuditReviewDTO.ProblemItem... items) {
        AuditReviewDTO decision = new AuditReviewDTO();
        decision.setStatus(AuditTask.STATUS_REJECTED);
        decision.setReasonText("内容不合规");
        decision.setProblems(new ArrayList<>(Arrays.asList(items)));
        return decision;
    }

    @Test
    void 开关关闭_problems被忽略() {
        service.review(9L, 1L, reject(item("CHAPTER", 32L, null, "跨主题条目也不校验")));

        verify(problemMapper, never()).insertAuditProblem(any());
        verify(chapterMapper, never()).selectCmsTopicChapterById(anyLong());
    }

    @Test
    void 开关打开_逐项落库_归属内容作者_挂在驳回记录上() {
        enable();
        service.review(9L, 1L, reject(
                item("topic", null, "TEXT_RISK", " 标题含违规词 "),
                item("CHAPTER", 31L, null, "第二章图片模糊"),
                item("NODE", 41L, "LOCATION_ISSUE", "节点坐标偏离"),
                item("TEMPLATE", 61L, "FALSE_INFO", "玩法说明与实际不符")));

        ArgumentCaptor<AuditProblem> rows = ArgumentCaptor.forClass(AuditProblem.class);
        verify(problemMapper, times(4)).insertAuditProblem(rows.capture());
        List<AuditProblem> saved = rows.getAllValues();
        AuditProblem first = saved.get(0);
        assertEquals("TOPIC", first.getItemType());
        assertNull(first.getItemId());
        assertEquals("标题含违规词", first.getReasonText());
        assertEquals(Long.valueOf(9L), first.getTaskId());
        assertEquals(Long.valueOf(501L), first.getRecordId());
        assertEquals(Long.valueOf(5L), first.getOwnerMemberId());
        assertEquals("FALSE_INFORMATION", saved.get(3).getReasonCode(), "别名按固定清单归一");
    }

    @Test
    void 开关打开_跨主题条目整单400_不改任何状态() {
        enable();
        assertThrows(IllegalArgumentException.class,
                () -> service.review(9L, 1L, reject(item("CHAPTER", 32L, null, "不属于本主题"))));
        verify(taskMapper, never()).updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any());
        verify(problemMapper, never()).insertAuditProblem(any());
    }

    @Test
    void 开关打开_非本主题引用的玩法_未知编码_空说明均400() {
        enable();
        assertThrows(IllegalArgumentException.class,
                () -> service.review(9L, 1L, reject(item("TEMPLATE", 62L, null, "未被本主题引用"))));
        assertThrows(IllegalArgumentException.class,
                () -> service.review(9L, 1L, reject(item("TOPIC", null, "NOPE", "未知编码"))));
        assertThrows(IllegalArgumentException.class,
                () -> service.review(9L, 1L, reject(item("NODE", 41L, null, "  "))));
        assertThrows(IllegalArgumentException.class,
                () -> service.review(9L, 1L, reject(item("NODE", null, null, "缺条目 id"))));
        verify(taskMapper, never()).updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any());
    }

    @Test
    void 开关打开_通过时附逐项问题400() {
        enable();
        AuditReviewDTO decision = reject(item("TOPIC", null, null, "x"));
        decision.setStatus(AuditTask.STATUS_APPROVED);
        assertThrows(IllegalArgumentException.class, () -> service.review(9L, 1L, decision));
    }

    @Test
    void 玩法审核只允许针对玩法本身() {
        AuditProblemService problems = new AuditProblemService(problemMapper, chapterMapper, nodeMapper);
        AuditTask template = new AuditTask();
        template.setId(3L);
        template.setBizType(AuditTask.BIZ_TEMPLATE);
        template.setBizId(61L);

        assertEquals(1, problems.prepare(template,
                Collections.singletonList(item("TEMPLATE", 61L, null, "玩法说明缺失"))).size());
        assertThrows(IllegalArgumentException.class, () -> problems.prepare(template,
                Collections.singletonList(item("TEMPLATE", 60L, null, "别的玩法"))));
        assertThrows(IllegalArgumentException.class, () -> problems.prepare(template,
                Collections.singletonList(item("CHAPTER", 31L, null, "章节不属于玩法审核"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 作者时间线按轮次带出逐项问题_只按作者本人读() {
        enable();
        AuditTask task = taskMapper.selectAuditTaskById(9L);
        task.setStatus(AuditTask.STATUS_REJECTED);
        when(taskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(task));
        AuditProblem row = new AuditProblem();
        row.setItemType("CHAPTER");
        row.setItemId(31L);
        row.setReasonText("第二章图片模糊");
        List<AuditProblem> stored = Collections.singletonList(row);
        when(problemMapper.selectByTaskForOwner(9L, 5L)).thenReturn(stored);

        Map<String, Object> timeline = service.contentTimeline(AuditTask.BIZ_TOPIC, 77L, 5L);

        List<Map<String, Object>> rounds = (List<Map<String, Object>>) timeline.get("rounds");
        assertSame(stored, rounds.get(0).get("problems"));
        verify(problemMapper).selectByTaskForOwner(9L, 5L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 开关关闭_时间线不带problems字段() {
        AuditTask task = taskMapper.selectAuditTaskById(9L);
        when(taskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(task));

        Map<String, Object> timeline = service.contentTimeline(AuditTask.BIZ_TOPIC, 77L, 5L);

        List<Map<String, Object>> rounds = (List<Map<String, Object>>) timeline.get("rounds");
        assertFalse(rounds.get(0).containsKey("problems"));
        verify(problemMapper, never()).selectByTaskForOwner(anyLong(), anyLong());
    }
}

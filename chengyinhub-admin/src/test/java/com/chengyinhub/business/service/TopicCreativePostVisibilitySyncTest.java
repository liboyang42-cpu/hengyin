package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.business.service.impl.UmsCreativeSquareServiceImpl;
import com.chengyinhub.business.mapper.UmsCreativeSquareMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [6-09] 主题可见性 → 广场同步帖联动。
 *
 * <p>主题被驳回、删除或下架后,自动同步到广场的帖子必须跟着不公开;主题恢复公开时按原规则恢复。
 * 这里钉死三件事:
 * <ul>
 *   <li>所有改主题可见性的入口(上下架 CAS / 删除 / 审核驳回与过审 / 发布草稿 / 移交 / 后台删除 /
 *       内容安全自动下架)都会触发联动,不许有入口漏掉;</li>
 *   <li>联动 SQL 的边界:只动 data_type=2、del_flag=0 的同步帖;恢复时不复活被审核驳回(check_status=2)
 *       或作者已删的帖子;</li>
 *   <li>读取端兜底:公开列表按主题公开态过滤同步帖(存量行与漏掉的入口也漏不出去)。</li>
 * </ul>
 */
class TopicCreativePostVisibilitySyncTest {

    private static final Long TOPIC_ID = 77L;
    private static final Long OWNER_ID = 100L;
    private static final Long TASK_ID = 9L;

    private CmsTopicMapper cmsTopicMapper;
    private CmsRegistrationMapper cmsRegistrationMapper;
    private IUmsCreativeSquareService squareService;
    private CmsTopicServiceImpl topicService;

    @BeforeEach
    void setUp() {
        cmsTopicMapper = mock(CmsTopicMapper.class);
        cmsRegistrationMapper = mock(CmsRegistrationMapper.class);
        squareService = mock(IUmsCreativeSquareService.class);

        topicService = new CmsTopicServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(topicService, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(topicService, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(topicService, "cmsTopicMapper", cmsTopicMapper);
        // Online restores now run the real physical-content budget gate; this fixture has no content.
        var chapters = mock(com.chengyinhub.business.mapper.CmsTopicChapterMapper.class);
        var nodes = mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class);
        ReflectionTestUtils.setField(topicService, "cmsTopicChapterMapper", chapters);
        ReflectionTestUtils.setField(topicService, "cmsTopicNodeMapper", nodes);
        when(chapters.countContentByTopicForUpdate(TOPIC_ID)).thenReturn(0L);
        when(nodes.countContentByTopicForUpdate(TOPIC_ID)).thenReturn(0L);
        ReflectionTestUtils.setField(topicService, "cmsRegistrationMapper", cmsRegistrationMapper);
        ReflectionTestUtils.setField(topicService, "cmsActivityMapper", mock(CmsActivityMapper.class));
        ReflectionTestUtils.setField(topicService, "umsCreativeSquareService", squareService);
    }

    private CmsTopic ownedTopic() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(OWNER_ID);
        topic.setDelFlag(0);
        return topic;
    }

    @Test
    void casOffline_hidesSyncedPost_casOnline_restoresIt() {
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(ownedTopic());
        when(cmsTopicMapper.casUserStatus(eq(TOPIC_ID), anyInt(), anyInt(), any())).thenReturn(1);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(ownedTopic());

        assertEquals(1, com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(topicService).casUserStatus(TOPIC_ID, 1, 0));
        verify(squareService).syncTopicPostVisibility(TOPIC_ID, false);

        assertEquals(1, com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(topicService).casUserStatus(TOPIC_ID, 0, 1));
        verify(squareService).syncTopicPostVisibility(TOPIC_ID, true);
    }

    @Test
    void casLosingRace_changesNothing() {
        when(cmsTopicMapper.casUserStatus(eq(TOPIC_ID), anyInt(), anyInt(), any())).thenReturn(0);

        assertEquals(0, com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(topicService).casUserStatus(TOPIC_ID, 1, 0));

        verify(squareService, never()).syncTopicPostVisibility(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void ownerDelete_hidesSyncedPost() {
        when(cmsTopicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(ownedTopic());
        when(cmsTopicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        when(cmsRegistrationMapper.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.<CmsRegistration>emptyList());

        assertTrue(topicService.deleteTopicByOwner(TOPIC_ID, OWNER_ID));

        verify(squareService).syncTopicPostVisibility(TOPIC_ID, false);
    }

    @Test
    void auditReject_hidesSyncedPost_approveWithQuota_restoresByOriginalRule() {
        ICmsTopicService providerTopicService = mock(ICmsTopicService.class);
        AuditTaskServiceImpl audit = auditService(null, providerTopicService);

        audit.review(TASK_ID, 1L, decision(AuditTask.STATUS_REJECTED));
        verify(providerTopicService).syncCreativeSquarePostVisibility(TOPIC_ID, false);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> audit.review(TASK_ID,1L,decision(AuditTask.STATUS_APPROVED)));
        verify(providerTopicService,never()).syncCreativeSquarePostVisibility(TOPIC_ID,true);

        // The rejected task is closed. Author resubmission produces a new pending task.
        audit.review(TASK_ID + 1, 1L, decision(AuditTask.STATUS_APPROVED));
        verify(providerTopicService).syncCreativeSquarePostVisibility(TOPIC_ID, true);
    }

    @Test
    void auditApproveOverQuota_keepsSyncedPostHidden() {
        ICmsTopicService providerTopicService = mock(ICmsTopicService.class);
        AuditTaskServiceImpl audit = auditService("配额满", providerTopicService);

        audit.review(TASK_ID, 1L, decision(AuditTask.STATUS_APPROVED));

        verify(providerTopicService).syncCreativeSquarePostVisibility(TOPIC_ID, false);
    }

    /** 审核通过但配额满 = 结论生效但不自动上架,同步帖不能跟着放出来。 */
    private AuditTaskServiceImpl auditService(String quotaError, ICmsTopicService providerTopicService) {
        AuditTaskMapper auditTaskMapper = mock(AuditTaskMapper.class);
        AuditRecordMapper auditRecordMapper = mock(AuditRecordMapper.class);
        IQuotaService quotaService = mock(IQuotaService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ICmsTopicService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(providerTopicService);

        AuditTaskServiceImpl svc = new AuditTaskServiceImpl();
        com.chengyinhub.business.mapper.BusinessAuditEventMapper businessAuditEventMapper =
                mock(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class);
        when(businessAuditEventMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        ReflectionTestUtils.setField(svc, "auditTaskMapper", auditTaskMapper);
        ReflectionTestUtils.setField(svc, "auditRecordMapper", auditRecordMapper);
        ReflectionTestUtils.setField(svc, "businessAuditEventMapper", businessAuditEventMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(svc, "quotaService", quotaService);
        ReflectionTestUtils.setField(svc, "cmsTopicServiceProvider", provider);

        java.util.Map<Long, AuditTask> stored = new java.util.HashMap<>();
        for (long id : new long[]{TASK_ID, TASK_ID + 1}) {
            AuditTask task = new AuditTask(); task.setId(id);
            task.setBizType(AuditTask.BIZ_TOPIC); task.setBizId(TOPIC_ID);
            task.setStatus(AuditTask.STATUS_PENDING); task.setVersion(0);
            stored.put(id, task);
        }
        when(auditTaskMapper.selectAuditTaskById(anyLong())).thenAnswer(call -> {
            AuditTask row = stored.get(call.getArgument(0));
            if (row == null) return null;
            AuditTask snapshot = new AuditTask();
            org.springframework.beans.BeanUtils.copyProperties(row, snapshot);
            return snapshot;
        });
        when(auditTaskMapper.updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any()))
                .thenAnswer(call -> {
                    AuditTask row = stored.get(call.getArgument(0));
                    if (row == null || !row.getStatus().equals(call.getArgument(1))
                            || !row.getVersion().equals(call.getArgument(4))) return 0;
                    row.setStatus(call.getArgument(2)); row.setVersion(row.getVersion() + 1);
                    return 1;
                });
        when(auditRecordMapper.insertAuditRecord(any())).thenReturn(1);
        when(cmsTopicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(ownedTopic());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(ownedTopic());
        when(cmsTopicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        com.chengyinhub.business.service.support.AppMediaPublicationGuard media =
                com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(OWNER_ID);
        ReflectionTestUtils.setField(media,"topics",cmsTopicMapper);
        ReflectionTestUtils.setField(media,"chapters",mock(com.chengyinhub.business.mapper.CmsTopicChapterMapper.class));
        ReflectionTestUtils.setField(media,"nodes",mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class));
        ReflectionTestUtils.setField(media,"templates",mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(svc,"mediaPublicationGuard",media);
        when(quotaService.lockAndCheckThemeOnline(OWNER_ID, TOPIC_ID)).thenReturn(quotaError);
        return svc;
    }

    private AuditReviewDTO decision(Integer status) {
        AuditReviewDTO dto = new AuditReviewDTO();
        dto.setStatus(status);
        dto.setExpectedVersion(0);
        dto.setReasonText("审核依据");
        return dto;
    }

    @Test
    void serviceRoutesVisibleFlagToTheRightStatement() {
        UmsCreativeSquareMapper mapper = mock(UmsCreativeSquareMapper.class);
        UmsCreativeSquareServiceImpl service = new UmsCreativeSquareServiceImpl();
        ReflectionTestUtils.setField(service, "umsCreativeSquareMapper", mapper);
        when(mapper.hideTopicSyncPosts(TOPIC_ID)).thenReturn(2);
        when(mapper.restoreTopicSyncPosts(TOPIC_ID)).thenReturn(1);

        assertEquals(2, service.syncTopicPostVisibility(TOPIC_ID, false));
        verify(mapper).hideTopicSyncPosts(TOPIC_ID);
        verify(mapper, never()).restoreTopicSyncPosts(anyLong());

        assertEquals(1, service.syncTopicPostVisibility(TOPIC_ID, true));
        verify(mapper).restoreTopicSyncPosts(TOPIC_ID);

        assertEquals(0, service.syncTopicPostVisibility(null, true));
    }

    @Test
    void mapperSqlKeepsTheHiddenBoundary() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get(
                "../chengyinhub-system/src/main/resources/mapper/business/UmsCreativeSquareMapper.xml")),
                StandardCharsets.UTF_8);
        String hide = xml.substring(xml.indexOf("id=\"hideTopicSyncPosts\""),
                xml.indexOf("</update>", xml.indexOf("id=\"hideTopicSyncPosts\"")));
        String restore = xml.substring(xml.indexOf("id=\"restoreTopicSyncPosts\""),
                xml.indexOf("</update>", xml.indexOf("id=\"restoreTopicSyncPosts\"")));

        // 只动主题同步帖、未删、仍公开的行;覆盖为 3(随主题隐藏,与平台下架的 2 分开)。
        // 真库语义见 it/TopicSyncPostRestoreMyBatisTest。
        assertTrue(hide.contains("data_type = 2") && hide.contains("del_flag = 0")
                && hide.contains("status = 1") && hide.contains("status = 3"), hide);
        // 恢复只放回被联动隐藏的(3),且不复活审核驳回(check_status = 2)/待审(status=0)的行
        assertTrue(restore.contains("status = 3") && restore.contains("status = 1")
                && restore.contains("check_status is null or check_status") && restore.contains("del_flag = 0"),
                restore);
    }

    @Test
    void publicFeedFiltersSyncedPostsOfNonPublicTopics() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get(
                "../chengyinhub-system/src/main/resources/mapper/business/ViewCreativeSquareMapper.xml")),
                StandardCharsets.UTF_8);
        int listStart = xml.indexOf("id=\"selectViewCreativeSquareList\"");
        String list = xml.substring(listStart, xml.indexOf("</select>", listStart));

        assertTrue(list.contains("data_type != 2"), "公开列表必须有主题同步帖过滤");
        assertTrue(list.contains("cms_topic") && list.contains("t.status = 1")
                && list.contains("t.user_status = 1") && list.contains("t.del_flag = 0"),
                "过滤判据必须是主题公开口径(del_flag/status/user_status 三项)");
    }
}

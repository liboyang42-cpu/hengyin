package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.business.service.impl.WxContentSecurityServiceImpl;
import com.chengyinhub.business.util.TopicContentSafetyHold;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiTopicController;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUDIT-G2-1:内容安全图片回调下架的主题,作者不能自助翻回上架;要记下下架原因,
 * 作者换图后重新上架必须过人工复核。
 */
class TopicContentSafetyHoldTest {

    private static final Long TOPIC_ID = 880L;
    private static final Long OWNER_ID = 19L;

    private static CmsTopic topic(Integer status, Integer userStatus, Integer checkStatus) {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC_ID);
        t.setMemberId(OWNER_ID);
        t.setStatus(status);
        t.setUserStatus(userStatus);
        t.setCheckStatus(checkStatus);
        t.setPublishedConfigVersion(3L);
        return t;
    }

    /** 回调:命中违规要记下原因(check_status=2 + reason),不只是 user_status=0。 */
    @Nested
    class ImageCallback {
        private final ICmsTopicService topics = mock(ICmsTopicService.class);
        private final WxContentSecurityServiceImpl service = new WxContentSecurityServiceImpl();

        private boolean riskyCallback() {
            ReflectionTestUtils.setField(service, "cmsTopicService", topics);
            return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service, "processMappedMediaCheckResult",
                    IWxContentSecurityService.BIZ_TYPE_TOPIC + ":" + TOPIC_ID, "trace-1", true));
        }

        private CmsTopic patch() {
            ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
            verify(topics).updateCmsTopic(cap.capture());
            return cap.getValue();
        }

        @Test
        void onlineTopicIsTakenDownWithRecordedReason() {
            when(topics.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(1, 1, 0));
            when(topics.updateCmsTopic(any())).thenReturn(1);

            assertTrue(riskyCallback());

            CmsTopic p = patch();
            assertEquals(0, p.getUserStatus());
            assertEquals(2, p.getCheckStatus(), "下架要记成处置事实,作者不能凭 update_user_status 翻回去");
            assertEquals(TopicContentSafetyHold.REASON, p.getReason());
            assertEquals(null, p.getStatus(), "status=2 是人工驳回口径,机审下架不得冒用");
        }

        /** 作者先自己下架、图片后被判违规:也要落留置,否则之后照样能自助上架。 */
        @Test
        void alreadyOfflineTopicStillGetsTheHold() {
            when(topics.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(1, 0, 1));
            when(topics.updateCmsTopic(any())).thenReturn(1);

            assertTrue(riskyCallback());

            assertEquals(2, patch().getCheckStatus());
        }

        @Test
        void alreadyHeldTopicIsIdempotent() {
            when(topics.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(1, 0, 2));

            assertTrue(riskyCallback());

            verify(topics, never()).updateCmsTopic(any());
        }

        /** 人工已驳回(status=2)的主题不改写驳回原因。 */
        @Test
        void humanRejectedOfflineTopicKeepsItsReason() {
            when(topics.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(2, 0, 2));

            assertTrue(riskyCallback());

            verify(topics, never()).updateCmsTopic(any());
        }
    }

    /** 作者端翻转上架:留置中一律拒,改为提交人工复核。 */
    @Nested
    class AuthorRelist {
        private final ApiTopicController controller = spy(new ApiTopicController());
        private final ICmsTopicService topics = mock(ICmsTopicService.class);
        private final IQuotaService quota = mock(IQuotaService.class);

        private AjaxResult toggle(CmsTopic stored) {
            doReturn(OWNER_ID).when(controller).getAppUserId();
            ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
            ReflectionTestUtils.setField(controller, "quotaService", quota);
            ReflectionTestUtils.setField(controller, "clubAccessService",
                    mock(com.chengyinhub.business.access.club.IClubAccessService.class));
            doAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(2)).get())
                    .when(quota).runWithThemeOnlineGuard(anyLong(), any(), any());
            when(topics.casUserStatus(anyLong(), any(), any())).thenReturn(1);
            when(topics.selectCmsTopicById(TOPIC_ID)).thenReturn(stored);
            return controller.topicUpdateUserStatus(String.valueOf(TOPIC_ID), "0", null);
        }

        @Test
        void heldTopicCannotBePutBackOnlineByAuthor() {
            when(topics.requestContentSafetyRelistReview(TOPIC_ID, OWNER_ID)).thenReturn(501L);

            AjaxResult r = toggle(topic(1, 0, 2));

            assertEquals(TopicContentSafetyHold.RELIST_REFUSED, r.get(AjaxResult.MSG_TAG));
            assertFalse(r.isSuccess());
            verify(topics, never()).casUserStatus(anyLong(), any(), eq(1));
            verify(topics).requestContentSafetyRelistReview(TOPIC_ID, OWNER_ID);
        }

        @Test
        void ordinaryOfflineTopicStillToggles() {
            AjaxResult r = toggle(topic(1, 0, 1));

            assertEquals("上架成功", r.get(AjaxResult.MSG_TAG));
            verify(topics, never()).requestContentSafetyRelistReview(anyLong(), anyLong());
        }
    }

    /** CAS 上架 SQL 自己也挡留置:关掉「读到态 → 回调落留置 → 写上架」的窗口。 */
    @Test
    void casOnlineSqlRefusesHeldRows() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain.vo");
        String resource = "mapper/business/CmsTopicMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("id", TOPIC_ID);
        params.put("expected", 0);
        params.put("target", 1);
        params.put("updateTime", new java.util.Date());
        String sql = configuration.getMappedStatement("com.chengyinhub.business.mapper.CmsTopicMapper.casUserStatus")
                .getBoundSql(params).getSql().replaceAll("\\s+", " ");
        assertTrue(sql.contains("coalesce(check_status, 0) <> 2"), sql);
        params.put("target", 0);
        String down = configuration.getMappedStatement("com.chengyinhub.business.mapper.CmsTopicMapper.casUserStatus")
                .getBoundSql(params).getSql();
        assertFalse(down.contains("check_status"), "下架不受留置限制:" + down);
    }

    /** 审核台:复核单过审 = 人工清掉留置,恢复在架;复核单不锁作者编辑,可原地升级成组合审核。 */
    @Nested
    class Review {
        private final AuditTaskMapper taskMapper = mock(AuditTaskMapper.class);
        private final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        private final ICmsTopicService topicService = mock(ICmsTopicService.class);
        private final AuditTaskServiceImpl svc = new AuditTaskServiceImpl();
        private final AuditTask task = new AuditTask();

        private void wire(CmsTopic stored) {
            AuditRecordMapper records = mock(AuditRecordMapper.class);
            com.chengyinhub.business.mapper.BusinessAuditEventMapper events =
                    mock(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class);
            ReflectionTestUtils.setField(svc, "auditTaskMapper", taskMapper);
            ReflectionTestUtils.setField(svc, "auditRecordMapper", records);
            ReflectionTestUtils.setField(svc, "businessAuditEventMapper", events);
            ReflectionTestUtils.setField(svc, "cmsTopicMapper", topicMapper);
            com.chengyinhub.business.service.support.AppMediaPublicationGuard guard =
                    com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(OWNER_ID);
            com.chengyinhub.business.mapper.CmsTopicChapterMapper chapters =
                    mock(com.chengyinhub.business.mapper.CmsTopicChapterMapper.class);
            com.chengyinhub.business.mapper.CmsTopicNodeMapper nodes =
                    mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class);
            when(chapters.selectCmsTopicChapterList(any())).thenReturn(java.util.Collections.emptyList());
            when(nodes.selectCmsTopicNodeList(any())).thenReturn(java.util.Collections.emptyList());
            ReflectionTestUtils.setField(guard, "topics", topicMapper);
            ReflectionTestUtils.setField(guard, "chapters", chapters);
            ReflectionTestUtils.setField(guard, "nodes", nodes);
            ReflectionTestUtils.setField(svc, "mediaPublicationGuard", guard);
            ReflectionTestUtils.setField(svc, "quotaService", mock(IQuotaService.class));
            @SuppressWarnings("unchecked")
            ObjectProvider<ICmsTopicService> provider = mock(ObjectProvider.class);
            when(provider.getObject()).thenReturn(topicService);
            ReflectionTestUtils.setField(svc, "cmsTopicServiceProvider", provider);
            @SuppressWarnings("unchecked")
            ObjectProvider<IImService> im = mock(ObjectProvider.class);
            when(im.getObject()).thenReturn(mock(IImService.class));
            ReflectionTestUtils.setField(svc, "imServiceProvider", im);
            task.setId(9L);
            task.setBizType(AuditTask.BIZ_TOPIC);
            task.setBizId(TOPIC_ID);
            task.setSubmitterId(OWNER_ID);
            task.setSourceType(TopicContentSafetyHold.SOURCE_RELIST);
            task.setStatus(AuditTask.STATUS_PENDING);
            task.setVersion(0);
            when(taskMapper.selectAuditTaskById(9L)).thenReturn(task);
            when(taskMapper.updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any())).thenReturn(1);
            when(records.insertAuditRecord(any())).thenReturn(1);
            when(events.insertBusinessAuditEvent(any())).thenReturn(1);
            when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(stored);
            when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(stored);
            when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        }

        private CmsTopic decide(Integer status) {
            AuditReviewDTO dto = new AuditReviewDTO();
            dto.setStatus(status);
            dto.setReasonText("复核结论");
            svc.review(9L, 1L, dto);
            ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
            verify(topicMapper).updateCmsTopic(cap.capture());
            return cap.getValue();
        }

        @Test
        void approvingHeldTopicClearsHoldAndRelists() {
            wire(topic(1, 0, 2));
            CmsTopic p = decide(AuditTask.STATUS_APPROVED);
            assertEquals(1, p.getCheckStatus());
            assertEquals(1, p.getUserStatus(), "人工复核通过 = 留置解除,恢复在架");
            assertEquals("", p.getReason());
        }

        @Test
        void rejectingHeldTopicKeepsItDown() {
            wire(topic(1, 0, 2));
            CmsTopic p = decide(AuditTask.STATUS_REJECTED);
            assertEquals(2, p.getStatus());
            assertEquals(0, p.getUserStatus());
        }

        @Test
        void relistTaskDoesNotLockEditingAndUpgradesToBundle() {
            wire(topic(1, 0, 2));
            when(taskMapper.selectOpenAuditTaskForUpdate(AuditTask.BIZ_TOPIC, TOPIC_ID)).thenReturn(task);
            when(taskMapper.refreshOpenAuditSnapshot(anyLong(), any(), any(), any(), anyLong(), any())).thenReturn(1);

            assertFalse(svc.lockOpenTopicBundleTask(TOPIC_ID), "复核单不是组合审核,不得锁住作者换图");
            assertEquals(9L, svc.submitOrRefreshTopicBundle(TOPIC_ID, OWNER_ID, "{\"schemaVersion\":2}"));
            verify(taskMapper).refreshOpenAuditSnapshot(eq(9L), eq(AuditTask.BIZ_TOPIC), eq("topic_bundle_v2"),
                    any(), eq(OWNER_ID), any());
        }
    }
}

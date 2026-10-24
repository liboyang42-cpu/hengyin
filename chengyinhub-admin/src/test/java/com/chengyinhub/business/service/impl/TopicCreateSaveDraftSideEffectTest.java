package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 草稿不公开；正式发布先上架并保留后台审核任务。 */
class TopicCreateSaveDraftSideEffectTest {

    private CmsTopicServiceImpl svc;
    private CmsTopicMapper topicMapper;
    private CmsActivityMapper activityMapper;
    private IAuditTaskService auditTaskService;

    @BeforeEach
    void setUp() {
        svc = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(svc, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        // createTopic 途经的协作者很多且与本测试无关:全量置 mock,再对断言目标取回引用。
        for (Field f : CmsTopicServiceImpl.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            try {
                if (f.get(svc) == null) {
                    ReflectionTestUtils.setField(svc, f.getName(), Mockito.mock(f.getType()));
                }
            } catch (Exception ignore) {
            }
        }
        topicMapper = (CmsTopicMapper) ReflectionTestUtils.getField(svc, "cmsTopicMapper");
        activityMapper = (CmsActivityMapper) ReflectionTestUtils.getField(svc, "cmsActivityMapper");
        // CORE OWN-C03-15：自办活动插入后须生成有效身份，否则 fail-closed；夹具按真实插入回填 id。
        org.mockito.Mockito.lenient().when(activityMapper.insertCmsActivity(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            ((com.chengyinhub.business.domain.CmsActivity) inv.getArgument(0)).setId(880001L);
            return 1;
        });
        auditTaskService = (IAuditTaskService) ReflectionTestUtils.getField(svc, "auditTaskService");
        doAnswer(inv -> {
            CmsTopic topic = inv.getArgument(0);
            topic.setId(700L);
            when(topicMapper.selectCmsTopicById(700L)).thenReturn(topic);
            doAnswer(update -> {
                CmsTopic patch = update.getArgument(0);
                if (patch.getStatus() != null) topic.setStatus(patch.getStatus());
                if (patch.getUserStatus() != null) topic.setUserStatus(patch.getUserStatus());
                return 1;
            }).when(topicMapper).updateCmsTopic(any());
            return 1;
        }).when(topicMapper).insertCmsTopic(any());
        when(topicMapper.publishRouteSnapshot(any(), any(), any())).thenReturn(1);
        ReflectionTestUtils.setField(svc, "topicStateKeyRefService",
                new com.chengyinhub.business.service.support.TopicStateKeyRefService(
                    Mockito.mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class),
                    Mockito.mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class),
                    Mockito.mock(com.chengyinhub.business.mapper.CmsTopicChapterMapper.class)));
    }

    private TopicCreateDTO dto(Integer saveDraft) {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setName("测试城市定向");
        dto.setProductType(ProductType.ORIENTEERING);
        dto.setPublishMode("pro");
        dto.setChapters(new ArrayList<>());
        dto.setSaveDraft(saveDraft);
        return dto;
    }

    @Test
    void draftCreateSkipsSelfHostShellAndAudit() {
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto(1), 42L, "tester");

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).insertCmsTopic(cap.capture());
        assertEquals(Integer.valueOf(0), cap.getValue().getStatus(), "草稿:待审态位,但不提审");
        verify(topicMapper).updateTopicDraftState(700L, 1);
        verify(activityMapper, never()).insertCmsActivity(any(CmsActivity.class));
        verify(auditTaskService, never()).submit(anyString(), anyLong(), anyLong(), anyString(), anyString());
    }

    /** 专业版发布即建活动，同时创建后台审核任务。 */
    @Test
    void publishedProCreateSubmitsPostReviewAndBuildsPublicShell() {
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto(null), 42L, "tester");

        verify(activityMapper).insertCmsActivity(any(CmsActivity.class));
        verify(auditTaskService).submit(anyString(), anyLong(), anyLong(), anyString(), anyString());
        verify(topicMapper, never()).updateTopicDraftState(anyLong(), any());
    }

    /** 正面对照:简易版免审直发(status=1),发布时照旧建公开活动壳、不提审。 */
    @Test
    void publishedSimpleCreateStillBuildsSelfHostShell() {
        TopicCreateDTO simple = dto(null);
        simple.setPublishMode("simple");
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(simple, 42L, "tester");

        verify(activityMapper).insertCmsActivity(any(CmsActivity.class));
        verify(auditTaskService, never()).submit(anyString(), anyLong(), anyLong(), anyString(), anyString());
        verify(topicMapper, never()).updateTopicDraftState(anyLong(), any());
        // AUDIT-G2-4:免审直发要落 check_status=1(机审通过/免审),与专业版后审中的 0 区分开。
        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).insertCmsTopic(cap.capture());
        assertEquals(Integer.valueOf(1), cap.getValue().getCheckStatus(), "简易版直发 → check_status=1");
    }

    /** AUDIT-G2-4 对照:专业版发布后审中仍是 0,草稿不写 1。 */
    @Test
    void proAndDraftCreateDoNotClaimMachinePassed() {
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto(1), 42L, "tester");
        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).insertCmsTopic(cap.capture());
        assertTrue(!Integer.valueOf(1).equals(cap.getValue().getCheckStatus()), "草稿不得写 check_status=1");
    }

    /**
     * release-0917 同步 master(#1078)× rule-funds,总控裁定 C:pro 建题即待审、不建壳,欠款逾期闸不能只留在建壳处 ——
     * 提交时显式拦,主理人在提交那一刻看到原因(与合并前「建题即拦」一致)。负控:删掉 createTopicInternal 里那行
     * settlementDebtLedger.requireNoOverdueDebt(memberId) → 本用例红。
     */
    @Test
    void publishedProCreate_withOverdueDebt_isRejectedAtSubmission() {
        SettlementDebtLedger ledger = (SettlementDebtLedger) ReflectionTestUtils.getField(svc, "settlementDebtLedger");
        doThrow(new ServiceException("SETTLEMENT_DEBT_OVERDUE:你有逾期未补足的欠款,暂不能发布新活动,请联系平台客服处理"))
                .when(ledger).requireNoOverdueDebt(42L);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        ServiceException e = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto(null), 42L, "tester"));

        assertTrue(e.getMessage().startsWith("SETTLEMENT_DEBT_OVERDUE"), e.getMessage());
        verify(activityMapper, never()).insertCmsActivity(any(CmsActivity.class));
    }
}

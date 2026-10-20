package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.support.TopicSupplyReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验收 SC-FREE-FROZEN-GUARD / SC-EXIT-PARTIAL-CASE(F-L3-03)。
 *
 * 锁价或开始后,主办方审核不能改写已生效点位的结论(否则付费玩家路线上的站点会被隐藏);
 * 驳回只作用于待审版本;拒绝时契约、价格与节点都不写。下线走商家退出流程。
 */
class ChapterMerchantNodeAuditFreezeAcceptanceTest {

    private static final Long MERCHANT = 88L;
    private static final Long CHAPTER = 9L;
    private static final Long TOPIC = 100L;
    private static final Long PUBLISHER = 5L;
    private static final Long NODE = 1L;

    private ICmsTopicNodeService nodeSvc;
    private MerchantChapterOfferMapper offerMapper;
    private MerchantChapterApplicationMapper applicationMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicMapper topicMapper;
    private ChapterMerchantNodeServiceImpl service;

    @BeforeEach
    void setUp() {
        nodeSvc = mock(ICmsTopicNodeService.class);
        ICmsTopicChapterService chapterSvc = mock(ICmsTopicChapterService.class);
        ICmsTopicService topicSvc = mock(ICmsTopicService.class);
        ICmsMemberTemplateService tplSvc = mock(ICmsMemberTemplateService.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        applicationMapper = mock(MerchantChapterApplicationMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        service = new ChapterMerchantNodeServiceImpl(nodeSvc, chapterSvc, topicSvc, tplSvc, offerMapper, applicationMapper);
        ReflectionTestUtils.setField(service, "supplyReviewService", mock(TopicSupplyReviewService.class));
        ReflectionTestUtils.setField(service, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "topicMapper", topicMapper);
        when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CHAPTER)))
                .thenReturn(Collections.singletonList(CHAPTER));
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER);
        chapter.setTopicId(TOPIC);
        when(chapterSvc.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter);

        MerchantChapterOffer offer = new MerchantChapterOffer();
        offer.setChapterId(CHAPTER);
        offer.setMerchantId(MERCHANT);
        offer.setStatus(1);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.singletonList(offer));

        MerchantChapterApplication application = new MerchantChapterApplication();
        application.setChapterId(CHAPTER);
        application.setMerchantId(MERCHANT);
        application.setStatus(MerchantChapterApplication.STATUS_APPROVED);
        application.setSource(MerchantChapterApplication.SOURCE_APPLY);
        when(applicationMapper.selectByChapterAndMerchantForUpdate(CHAPTER, MERCHANT)).thenReturn(application);
    }

    private void givenTopic(TopicLifecycleState state, Date start) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(PUBLISHER);
        topic.setLifecycle(state.getCode());
        topic.setStartDate(start);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
    }

    private CmsTopicNode givenNode(int auditStatus) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(NODE);
        n.setTopicId(TOPIC);
        n.setChapterId(CHAPTER);
        n.setMerchantMemberId(MERCHANT);
        n.setName("幸会咖啡");
        n.setAddress("星海街 8 号");
        n.setLongitude("120.7");
        n.setLatitude("31.3");
        n.setNodeAuditStatus(auditStatus);
        when(nodeSvc.selectCmsTopicNodeById(NODE)).thenReturn(n);
        return n;
    }

    private static Date daysFromNow(int days) {
        return new Date(System.currentTimeMillis() + days * 86_400_000L);
    }

    /** 拒绝后:节点、承接(契约/价格)、申请与主题都没有任何写调用。 */
    private void assertNothingWritten() {
        verify(nodeSvc, never()).updateCmsTopicNode(any());
        for (Object mapper : new Object[] {offerMapper, applicationMapper, topicMapper, chapterMapper}) {
            for (Invocation call : mockingDetails(mapper).getInvocations()) {
                String name = call.getMethod().getName();
                assertTrue(name.startsWith("select") || name.startsWith("lock"),
                        "拒绝审核时不得写 " + mapper.getClass().getSimpleName() + "." + name);
            }
        }
    }

    @Test
    void approvedNodeKeepsItsResultAfterPriceLockSaleOrStart() {
        TopicLifecycleState[] frozen = {TopicLifecycleState.PRICING, TopicLifecycleState.SELLING, TopicLifecycleState.RUNNING};
        for (TopicLifecycleState state : frozen) {
            for (boolean approve : new boolean[] {false, true}) {
                CmsTopicNode node = givenNode(ChapterMerchantNodeServiceImpl.AUDIT_APPROVED);
                givenTopic(state, daysFromNow(-1));
                IllegalStateException e = assertThrows(IllegalStateException.class,
                        () -> service.audit(PUBLISHER, NODE, approve, "主办方想下架"));
                assertEquals("只能审核待审版本，已生效点位请走退出或处置流程", e.getMessage(), state + "/" + approve);
                assertEquals(Integer.valueOf(ChapterMerchantNodeServiceImpl.AUDIT_APPROVED), node.getNodeAuditStatus());
                assertNull(node.getNodeAuditReason());
                assertNull(node.getNodeAuditMemberId());
            }
        }
        assertNothingWritten();
    }

    @Test
    void pendingVersionCannotBeDecidedOnceTermsFrozen() {
        for (TopicLifecycleState state : new TopicLifecycleState[] {TopicLifecycleState.PRICING, TopicLifecycleState.SELLING}) {
            givenNode(ChapterMerchantNodeServiceImpl.AUDIT_PENDING);
            givenTopic(state, daysFromNow(5));
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> service.audit(PUBLISHER, NODE, false, "不合适"));
            assertEquals("主题已锁价或开始，不能改变点位审核结论", e.getMessage(), state.name());
        }
        assertNothingWritten();
    }

    /** 生命周期本身未冻结,只靠开始时间判定「已开始」的分支。 */
    @Test
    void pendingVersionCannotBeDecidedAfterStartDateEvenWithoutFrozenLifecycle() {
        assertFalse(TopicLifecycleState.commercialTermsFrozen(TopicLifecycleState.DRAFT.getCode()));
        givenNode(ChapterMerchantNodeServiceImpl.AUDIT_PENDING);
        givenTopic(TopicLifecycleState.DRAFT, daysFromNow(-1));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.audit(PUBLISHER, NODE, false, "不合适"));
        assertEquals("主题已锁价或开始，不能改变点位审核结论", e.getMessage());
        assertNothingWritten();
    }

    /** 正控制:招募期内,驳回仍作用于待审版本。 */
    @Test
    void pendingVersionCanStillBeRejectedWhileRecruiting() {
        givenNode(ChapterMerchantNodeServiceImpl.AUDIT_PENDING);
        givenTopic(TopicLifecycleState.RECRUITING, daysFromNow(5));
        CmsTopicNode out = service.audit(PUBLISHER, NODE, false, " 地址不对 ");
        assertEquals(Integer.valueOf(ChapterMerchantNodeServiceImpl.AUDIT_REJECTED), out.getNodeAuditStatus());
        assertEquals("地址不对", out.getNodeAuditReason());
        verify(nodeSvc).updateCmsTopicNode(out);
    }
}

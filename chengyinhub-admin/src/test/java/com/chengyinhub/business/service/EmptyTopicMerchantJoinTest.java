package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.ChapterMerchantNodeServiceImpl;
import com.chengyinhub.business.service.impl.CmsTopicChapterServiceImpl;
import com.chengyinhub.business.service.impl.EmptyTopicMerchantJoinService;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.business.service.support.TopicContentBudgetTestWiring;
import com.chengyinhub.business.service.support.TopicSupplyReviewService;
import com.chengyinhub.business.support.EmptyTopicChapterPolicy;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.TermsMode;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-11-03 业主(ROUND4 l5-onboard):自由探索主题一章都没有时,商家只带玩法模板也能报名,
 * 服务端建出这一章;有章节但都没开招商时不能借这条路绕过主办方。
 */
class EmptyTopicMerchantJoinTest {

    static final Long ME = 88L;
    static final Long PUBLISHER = 5L;
    static final Long TOPIC = 100L;
    static final Integer CATEGORY = 30;

    static CmsTopic recruitingFreeExplore() {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setMemberId(PUBLISHER);
        t.setProductType(ProductType.FREE_EXPLORE);
        t.setStatus(1);
        t.setUserStatus(1);
        t.setDelFlag(0);
        t.setLifecycle(TopicLifecycleState.RECRUITING.getCode());
        t.setRecruitDeadline(new Date(System.currentTimeMillis() + 3 * 86400000L));
        t.setStartDate(new Date(System.currentTimeMillis() + 5 * 86400000L));
        return t;
    }

    @Nested
    class Policy {
        @Test
        void openRecruitingThemeIsJoinable() {
            assertNull(EmptyTopicChapterPolicy.blocker(recruitingFreeExplore(), ME, CATEGORY, new Date()));
        }

        @Test
        void refusesOwnThemeOtherProductTypesClosedWindowAndMissingCategory() {
            CmsTopic own = recruitingFreeExplore();
            assertTrue(EmptyTopicChapterPolicy.blocker(own, PUBLISHER, CATEGORY, new Date()).contains("自己"));
            CmsTopic other = recruitingFreeExplore();
            other.setProductType(1);
            assertTrue(EmptyTopicChapterPolicy.blocker(other, ME, CATEGORY, new Date()).contains("自由探索"));
            CmsTopic closed = recruitingFreeExplore();
            closed.setRecruitDeadline(new Date(System.currentTimeMillis() - 1000L));
            assertTrue(EmptyTopicChapterPolicy.blocker(closed, ME, CATEGORY, new Date()).contains("招商"));
            CmsTopic offline = recruitingFreeExplore();
            offline.setUserStatus(0);
            assertTrue(EmptyTopicChapterPolicy.blocker(offline, ME, CATEGORY, new Date()).contains("上架"));
            CmsTopic selling = recruitingFreeExplore();
            selling.setLifecycle(TopicLifecycleState.SELLING.getCode());
            assertTrue(EmptyTopicChapterPolicy.blocker(selling, ME, CATEGORY, new Date()) != null);
            assertTrue(EmptyTopicChapterPolicy.blocker(recruitingFreeExplore(), ME, null, new Date()).contains("行业类型"));
        }
    }

    @Nested
    class ChapterCreation {
        CmsTopicChapterMapper chapterMapper;
        CmsTopicMapper topicMapper;
        CmsTopicChapterServiceImpl service;

        @BeforeEach
        void setUp() {
            chapterMapper = mock(CmsTopicChapterMapper.class);
            topicMapper = mock(CmsTopicMapper.class);
            service = new CmsTopicChapterServiceImpl();
            ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
            ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
            TopicContentBudgetTestWiring.chapter(service);
            ReflectionTestUtils.setField(service, "productRoleService", mock(IProductRoleService.class));
            ReflectionTestUtils.setField(service, "commercialTopicPolicy", mock(CommercialTopicPolicy.class));
            ISysCategoryService categories = mock(ISysCategoryService.class);
            SysCategory category = new SysCategory();
            category.setId(CATEGORY.longValue());
            category.setType(1L);
            category.setCategoryName("书店");
            category.setStatus(1);
            category.setParentId(0L);
            category.setDelFlag(0);
            when(categories.selectSysCategoryById(CATEGORY.longValue())).thenReturn(category);
            ReflectionTestUtils.setField(service, "sysCategoryService", categories);
            when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(recruitingFreeExplore());
            when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(recruitingFreeExplore());
            when(chapterMapper.insertCmsTopicChapter(any())).thenAnswer(call -> {
                ((CmsTopicChapter) call.getArgument(0)).setId(501L);
                return 1;
            });
        }

        @Test
        void zeroChaptersCreatesOneOpenPerkChapterInTheMerchantsCategory() {
            when(chapterMapper.countContentByTopicForUpdate(TOPIC)).thenReturn(0L, 1L);
            CmsTopicChapter created = service.openChapterForEmptyTopic(TOPIC, ME, CATEGORY, "  街角书店  ");
            assertEquals(501L, created.getId());
            ArgumentCaptor<CmsTopicChapter> cap = ArgumentCaptor.forClass(CmsTopicChapter.class);
            verify(chapterMapper).insertCmsTopicChapter(cap.capture());
            CmsTopicChapter row = cap.getValue();
            assertEquals(TOPIC, row.getTopicId());
            assertEquals("街角书店", row.getName());
            assertEquals(CATEGORY, row.getCategoryId());
            assertEquals("书店", row.getCategory());
            assertEquals(Integer.valueOf(1), row.getRecruitEnabled());
            assertEquals(TermsMode.PERK, row.getTermsMode());
            assertEquals(Integer.valueOf(1), row.getMaxMerchant());
            assertEquals(Integer.valueOf(1), row.getRequired());
        }

        @Test
        void anyExistingChapterRefusesWithoutInsert() {
            when(chapterMapper.countContentByTopicForUpdate(TOPIC)).thenReturn(1L);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> service.openChapterForEmptyTopic(TOPIC, ME, CATEGORY, "街角书店"));
            assertTrue(e.getMessage().contains("已有章节"));
            verify(chapterMapper, never()).insertCmsTopicChapter(any());
        }

        @Test
        void ownThemeIsRefusedBeforeCounting() {
            assertThrows(IllegalStateException.class,
                    () -> service.openChapterForEmptyTopic(TOPIC, PUBLISHER, CATEGORY, "x"));
            verify(chapterMapper, never()).insertCmsTopicChapter(any());
        }
    }

    @Nested
    class NodeSubmit {
        ICmsTopicNodeService nodeSvc;
        ICmsTopicChapterService chapterSvc;
        ICmsMemberTemplateService tplSvc;
        MerchantChapterApplicationMapper applicationMapper;
        IMerchantChapterApplicationService applicationSvc;
        TopicSupplyReviewService supplyReview;
        ChapterMerchantNodeServiceImpl nodeService;
        EmptyTopicMerchantJoinService service;

        @BeforeEach
        void setUp() {
            nodeSvc = mock(ICmsTopicNodeService.class);
            chapterSvc = mock(ICmsTopicChapterService.class);
            tplSvc = mock(ICmsMemberTemplateService.class);
            applicationMapper = mock(MerchantChapterApplicationMapper.class);
            applicationSvc = mock(IMerchantChapterApplicationService.class);
            supplyReview = mock(TopicSupplyReviewService.class);
            CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
            CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
            MmsMerchantMapper merchantMapper = mock(MmsMerchantMapper.class);
            // 真实点位服务 + 真实编排:建章 → 原申请 → 原点位提交
            nodeService = new ChapterMerchantNodeServiceImpl(nodeSvc, chapterSvc, mock(ICmsTopicService.class), tplSvc,
                    mock(MerchantChapterOfferMapper.class), applicationMapper);
            ReflectionTestUtils.setField(nodeService, "supplyReviewService", supplyReview);
            ReflectionTestUtils.setField(nodeService, "chapterMapper", chapterMapper);
            ReflectionTestUtils.setField(nodeService, "topicMapper", topicMapper);
            service = new EmptyTopicMerchantJoinService(nodeService, chapterSvc, applicationSvc, supplyReview, merchantMapper);

            MmsMerchant merchant = new MmsMerchant();
            merchant.setMemberId(ME);
            merchant.setCategoryId(CATEGORY);
            when(merchantMapper.selectMmsMerchantByMemberId(ME)).thenReturn(merchant);
            CmsMemberTemplate tpl = new CmsMemberTemplate();
            tpl.setId(700L);
            tpl.setMemberId(ME);
            tpl.setValidationMethod(1);
            when(tplSvc.selectCmsMemberTemplateById(700L)).thenReturn(tpl);

            CmsTopicChapter created = new CmsTopicChapter();
            created.setId(501L);
            created.setTopicId(TOPIC);
            created.setRecruitEnabled(1);
            created.setTermsMode(TermsMode.PERK);
            when(chapterSvc.openChapterForEmptyTopic(TOPIC, ME, CATEGORY, "街角书店")).thenReturn(created);
            when(chapterSvc.selectCmsTopicChapterById(501L)).thenReturn(created);
            when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(501L)))
                    .thenReturn(Collections.singletonList(501L));
            when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(recruitingFreeExplore());
            MerchantChapterApplication pending = new MerchantChapterApplication();
            pending.setChapterId(501L);
            pending.setMerchantId(ME);
            pending.setStatus(MerchantChapterApplication.STATUS_PENDING);
            pending.setSource(MerchantChapterApplication.SOURCE_APPLY);
            when(applicationMapper.selectByChapterAndMerchantForUpdate(501L, ME)).thenReturn(pending);
            when(nodeSvc.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        }

        CmsTopicNode draft(Long templateId) {
            CmsTopicNode n = new CmsTopicNode();
            n.setName("街角书店");
            n.setTemplateId(templateId);
            n.setAddress("星海街 8 号");
            n.setLongitude("120.7");
            n.setLatitude("31.3");
            return n;
        }

        @Test
        void createsChapterThenAppliesThenSavesPendingNodeInThatChapter() {
            CmsTopicNode saved = service.submit(ME, TOPIC, "想承接", draft(700L));
            InOrder order = inOrder(supplyReview, chapterSvc, applicationSvc, nodeSvc);
            order.verify(supplyReview).lockTopic(TOPIC);
            order.verify(chapterSvc).openChapterForEmptyTopic(TOPIC, ME, CATEGORY, "街角书店");
            order.verify(applicationSvc).apply(501L, ME, "想承接");
            order.verify(nodeSvc).insertCmsTopicNode(any());
            assertEquals(501L, saved.getChapterId());
            assertEquals(TOPIC, saved.getTopicId());
            assertEquals(ME, saved.getMerchantMemberId());
            // 商家自己申请的 ⇒ 点位随申请待主办方审核,不免审
            assertEquals(Integer.valueOf(ChapterMerchantNodeServiceImpl.AUDIT_PENDING), saved.getNodeAuditStatus());
        }

        @Test
        void someoneElsesTemplateIsRefusedBeforeAnyChapterIsCreated() {
            CmsMemberTemplate foreign = new CmsMemberTemplate();
            foreign.setId(701L);
            foreign.setMemberId(99L);
            when(tplSvc.selectCmsMemberTemplateById(701L)).thenReturn(foreign);
            assertThrows(IllegalStateException.class, () -> service.submit(ME, TOPIC, "", draft(701L)));
            verify(chapterSvc, never()).openChapterForEmptyTopic(anyLong(), anyLong(), any(), anyString());
            verify(applicationSvc, never()).apply(anyLong(), anyLong(), any());
        }
    }
}

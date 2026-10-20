package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopPerk;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.MerchantCircleSupplyProfileMapper;
import com.chengyinhub.business.service.impl.ChapterMerchantNodeServiceImpl;
import com.chengyinhub.business.service.impl.MerchantOfferServiceImpl;
import com.chengyinhub.common.constant.TermsMode;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysCategoryService;
import com.chengyinhub.web.controller.api.ApiCoopController;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自由探索验收:SC-FREE-SUPPLY / SC-FREE-NODE-EDIT / SC-FREE-TERMS。
 *
 * <p>只补既有用例没覆盖的那一截(见各 Nested 说明),setup 与相邻用例同口径。
 * mock 证明不了的(读路径 SQL 回读、唯一键)归真库,不在本类。
 */
class FreeExploreSupplyNodeTermsAcceptanceTest {

    // ===================================================================== SC-FREE-SUPPLY
    /**
     * 既有 MerchantOfferServiceImplTest 只在客户端「没传」时断言了 topicId/merchantId/status/quotaUsed;
     * 本组补:客户端**伪造**这些字段时服务端仍覆盖;冻结的玩法边界取章节而非客户端;
     * quotaTotal PERK 保留、非容量档归 0;控制器 → 服务端到端时 merchantId 为解析后的商家主体。
     */
    @Nested
    class Supply {
        private static final Long CHAPTER = 33L;
        private static final Long TOPIC = 10L;
        private static final Long OWNER = 500L;
        private static final Long OPERATOR = 7002L;
        private static final Long FORGED = 999L;
        private static final Long PERK = 77L;
        private static final String CHAPTER_METHODS = "1,2";
        private static final Integer CHAPTER_MAX_XP = 5;

        private final MerchantChapterOfferMapper offerMapper = mock(MerchantChapterOfferMapper.class);
        private final CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        private final CmsTopicMapper cmsTopicMapper = mock(CmsTopicMapper.class);
        private final CoopPerkMapper coopPerkMapper = mock(CoopPerkMapper.class);
        private final IProductRoleService productRoleService = mock(IProductRoleService.class);
        private final ICoopInviteService coopInviteService = mock(ICoopInviteService.class);
        private final ICmsRegistrationMerchantService registrationMerchantService =
                mock(ICmsRegistrationMerchantService.class);
        private final IMerchantChapterApplicationService applicationService =
                mock(IMerchantChapterApplicationService.class);
        private final ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        private final MerchantOfferServiceImpl svc = new MerchantOfferServiceImpl();
        private CmsTopic topic;

        Supply() {
            when(applicationService.approvedReplacementOfferId(anyLong(), anyLong())).thenReturn(null);
            when(scopeAuthorizer.canViewTopic(anyLong())).thenReturn(true);
            when(scopeAuthorizer.hasGlobalTopicScope()).thenReturn(true);
            when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenAnswer(i -> topic);
            // 默认:调用方是本主题中标商家(主题级授权闸放行),与既有用例同口径
            when(registrationMerchantService.selectCmsRegistrationMerchantList(any(CmsRegistrationMerchant.class)))
                    .thenReturn(Collections.singletonList(new CmsRegistrationMerchant()));
            when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());

            ReflectionTestUtils.setField(svc, "supplyReviewService",
                    mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class));
            ReflectionTestUtils.setField(svc, "businessNotice",
                    mock(com.chengyinhub.business.service.support.CommittedBusinessNotice.class));
            ReflectionTestUtils.setField(svc, "offerMapper", offerMapper);
            ReflectionTestUtils.setField(svc, "circleSupplyMapper", mock(MerchantCircleSupplyProfileMapper.class));
            ReflectionTestUtils.setField(svc, "chapterMapper", chapterMapper);
            ReflectionTestUtils.setField(svc, "cmsTopicMapper", cmsTopicMapper);
            ReflectionTestUtils.setField(svc, "coopPerkMapper", coopPerkMapper);
            ReflectionTestUtils.setField(svc, "productRoleService", productRoleService);
            ReflectionTestUtils.setField(svc, "coopInviteService", coopInviteService);
            ReflectionTestUtils.setField(svc, "registrationMerchantService", registrationMerchantService);
            ReflectionTestUtils.setField(svc, "merchantChapterApplicationService", applicationService);
            ReflectionTestUtils.setField(svc, "sysCategoryService", mock(ISysCategoryService.class));
            ReflectionTestUtils.setField(svc, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        }

        private CmsTopicChapter chapter(String mode) {
            CmsTopicChapter c = new CmsTopicChapter();
            c.setId(CHAPTER);
            c.setTopicId(TOPIC);
            c.setTermsMode(mode);
            c.setMaxMerchant(4);
            c.setRecruitEnabled(0);
            c.setCategoryId(18);
            c.setAllowedValidationMethods(CHAPTER_METHODS);
            c.setMaxNodeXp(CHAPTER_MAX_XP);
            when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(c);
            when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CHAPTER)))
                    .thenReturn(Collections.singletonList(CHAPTER));
            topic = new CmsTopic();
            topic.setId(TOPIC);
            topic.setProductType(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
            topic.setLifecycle(TopicLifecycleState.RECRUITING.getCode());
            topic.setRecruitDeadline(new Date(System.currentTimeMillis() + 60_000));
            when(cmsTopicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
            return c;
        }

        private void givenOwnPerk() {
            CoopPerk p = new CoopPerk();
            p.setId(PERK);
            p.setRetailValue(new BigDecimal("88.00"));
            p.setQuota(50);
            when(coopPerkMapper.selectById(PERK)).thenReturn(p);
            when(coopInviteService.resolvePerkOwner(PERK)).thenReturn(OWNER);
        }

        /** 客户端把所有服务端字段都伪造了一遍。 */
        private MerchantChapterOffer forged(MerchantChapterOffer o) {
            o.setId(4242L);
            o.setChapterId(CHAPTER);
            o.setTopicId(FORGED);
            o.setMerchantId(FORGED);
            o.setQuotaUsed(40);
            o.setStatus(0);
            o.setAllowedValidationMethods("1,2,3,4,5");
            o.setMaxNodeXp(99);
            return o;
        }

        private MerchantChapterOffer captureInserted() {
            ArgumentCaptor<MerchantChapterOffer> cap = ArgumentCaptor.forClass(MerchantChapterOffer.class);
            verify(offerMapper).insertMerchantChapterOffer(cap.capture());
            return cap.getValue();
        }

        private void assertServerOwnedFields(MerchantChapterOffer saved) {
            assertEquals(TOPIC, saved.getTopicId(), "topicId 必须由章节反查,不信客户端");
            assertEquals(OWNER, saved.getMerchantId(), "merchantId 必须是解析后的商家主体,不信客户端");
            assertEquals(Integer.valueOf(1), saved.getStatus(), "非补位供给落库即生效,客户端 status 无效");
            assertEquals(Integer.valueOf(0), saved.getQuotaUsed(), "quotaUsed 恒从 0 起,客户端不能预占");
            assertEquals(CHAPTER_METHODS, saved.getAllowedValidationMethods(),
                    "核验方式边界必须冻结章节那份,客户端不能自行放宽");
            assertEquals(CHAPTER_MAX_XP, saved.getMaxNodeXp(), "maxNodeXp 必须冻结章节那份");
        }

        @Test
        void perkEnroll_overwritesForgedClientFields_freezesChapterTerms_keepsQuotaTotal() {
            chapter(TermsMode.PERK);
            givenOwnPerk();
            MerchantChapterOffer o = forged(new MerchantChapterOffer());
            o.setTermsMode(TermsMode.PERK);
            o.setPerkId(PERK);
            o.setQuotaTotal(50);

            svc.enroll(o, OWNER);

            MerchantChapterOffer saved = captureInserted();
            assertServerOwnedFields(saved);
            assertEquals(Integer.valueOf(50), saved.getQuotaTotal(), "PERK 档 quotaTotal 是接待上限,必须保留");
        }

        @Test
        void trafficEnroll_zeroesClientQuotaTotal_andOverwritesForgedFields() {
            chapter(TermsMode.TRAFFIC);
            MerchantChapterOffer o = forged(new MerchantChapterOffer());
            o.setTermsMode(TermsMode.TRAFFIC);
            o.setQuotaTotal(80);

            svc.enroll(o, OWNER);

            MerchantChapterOffer saved = captureInserted();
            assertServerOwnedFields(saved);
            assertEquals(Integer.valueOf(0), saved.getQuotaTotal(), "非容量档 quotaTotal 必须归 0,客户端值无效");
        }

        @Test
        void revshareEnroll_zeroesClientQuotaTotal_andOverwritesForgedFields() {
            chapter(TermsMode.REVSHARE).setMaxPerHeadFee(new BigDecimal("20.00"));
            when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);
            MerchantChapterOffer o = forged(new MerchantChapterOffer());
            o.setTermsMode(TermsMode.REVSHARE);
            o.setPerHeadFee(new BigDecimal("15.00"));
            o.setQuotaTotal(80);

            svc.enroll(o, OWNER);

            MerchantChapterOffer saved = captureInserted();
            assertServerOwnedFields(saved);
            assertEquals(Integer.valueOf(0), saved.getQuotaTotal(), "非容量档 quotaTotal 必须归 0,客户端值无效");
        }

        /** 端到端(控制器 → 真服务):员工身份入驻,落库 merchantId 是商家主体,不是员工也不是客户端值。 */
        @Test
        void controllerToService_operatorEnroll_persistsResolvedOwnerNotPrincipalOrForgedId() {
            chapter(TermsMode.TRAFFIC);
            IMerchantAccessService access = mock(IMerchantAccessService.class);
            when(access.require(OPERATOR, MerchantPermission.COOP_MANAGE)).thenReturn(
                    new MerchantAccessContext(OPERATOR, 11L, OWNER, MerchantRoleCode.MERCHANT_MANAGER));
            ApiCoopController controller = new ApiCoopController() {
                @Override
                public Long getAppUserId() {
                    return OPERATOR;
                }
            };
            ReflectionTestUtils.setField(controller, "merchantAccessService", access);
            ReflectionTestUtils.setField(controller, "merchantOfferService", svc);
            MerchantChapterOffer body = forged(new MerchantChapterOffer());
            body.setTermsMode(TermsMode.TRAFFIC);
            body.setQuotaTotal(80);

            AjaxResult r = controller.offerEnroll(body);

            assertEquals(200, r.get(AjaxResult.CODE_TAG), String.valueOf(r.get(AjaxResult.MSG_TAG)));
            MerchantChapterOffer saved = captureInserted();
            assertServerOwnedFields(saved);
            assertEquals(Integer.valueOf(0), saved.getQuotaTotal());
            assertNull(saved.getId(), "客户端伪造的主键必须被控制器清掉");
        }
    }

    // ===================================================================== SC-FREE-NODE-EDIT
    /**
     * 既有 resubmitAfterApproval_backToPending 只用 any() 桩返回一个节点,证明不了查找范围;
     * 本组用一张按 probe 过滤的「伪表」,邻居节点(他章 / 他商家 / 他主题)排在前面,
     * 查找条件少任一维都会命中邻居。锁价拒改由既有 lockedStagesCannotUpdateAnApprovedNodeEvenBeforePlannedStart 覆盖。
     */
    @Nested
    class NodeEdit {
        private static final Long ME = 88L;
        private static final Long OTHER = 99L;
        private static final Long CHAPTER = 9L;
        private static final Long OTHER_CHAPTER = 10L;
        private static final Long TOPIC = 100L;
        private static final Long OTHER_TOPIC = 101L;
        private static final Long TPL = 700L;

        private final ICmsTopicNodeService nodeSvc = mock(ICmsTopicNodeService.class);
        private final ICmsTopicChapterService chapterSvc = mock(ICmsTopicChapterService.class);
        private final ICmsMemberTemplateService tplSvc = mock(ICmsMemberTemplateService.class);
        private final MerchantChapterOfferMapper offerMapper = mock(MerchantChapterOfferMapper.class);
        private final MerchantChapterApplicationMapper applicationMapper = mock(MerchantChapterApplicationMapper.class);
        private final CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        private final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        private final ChapterMerchantNodeServiceImpl service = new ChapterMerchantNodeServiceImpl(
                nodeSvc, chapterSvc, mock(ICmsTopicService.class), tplSvc, offerMapper, applicationMapper);
        private final List<CmsTopicNode> table = new ArrayList<>();

        NodeEdit() {
            ReflectionTestUtils.setField(service, "supplyReviewService",
                    mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class));
            ReflectionTestUtils.setField(service, "chapterMapper", chapterMapper);
            ReflectionTestUtils.setField(service, "topicMapper", topicMapper);
            when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CHAPTER)))
                    .thenReturn(Collections.singletonList(CHAPTER));
            CmsTopicChapter chapter = new CmsTopicChapter();
            chapter.setId(CHAPTER);
            chapter.setTopicId(TOPIC);
            when(chapterSvc.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter);
            CmsTopic topic = new CmsTopic();
            topic.setId(TOPIC);
            topic.setMemberId(5L);
            topic.setLifecycle(TopicLifecycleState.RECRUITING.getCode());
            topic.setStartDate(new Date(System.currentTimeMillis() + 5 * 86400000L));
            when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
            CmsMemberTemplate tpl = new CmsMemberTemplate();
            tpl.setId(TPL);
            tpl.setMemberId(ME);
            tpl.setValidationMethod(1);
            when(tplSvc.selectCmsMemberTemplateById(TPL)).thenReturn(tpl);
            MerchantChapterOffer live = new MerchantChapterOffer();
            live.setChapterId(CHAPTER);
            live.setMerchantId(ME);
            live.setStatus(1);
            when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.singletonList(live));
            MerchantChapterApplication app = new MerchantChapterApplication();
            app.setChapterId(CHAPTER);
            app.setMerchantId(ME);
            app.setStatus(MerchantChapterApplication.STATUS_APPROVED);
            app.setSource(MerchantChapterApplication.SOURCE_APPLY);
            when(applicationMapper.selectByChapterAndMerchantForUpdate(CHAPTER, ME)).thenReturn(app);

            // 伪表:邻居排在我的节点前面 —— 查找条件少哪一维,就会先命中哪个邻居
            table.add(node(14L, OTHER_TOPIC, CHAPTER, ME, "他主题同章号节点"));
            table.add(node(12L, TOPIC, OTHER_CHAPTER, ME, "我在他章的节点"));
            table.add(node(13L, TOPIC, CHAPTER, OTHER, "同章别家节点"));
            table.add(node(11L, TOPIC, CHAPTER, ME, "我的旧点位"));
            when(nodeSvc.selectCmsTopicNodeList(any())).thenAnswer(inv -> {
                CmsTopicNode probe = inv.getArgument(0);
                List<CmsTopicNode> hit = new ArrayList<>();
                for (CmsTopicNode n : table) {
                    if (probe.getTopicId() != null && !probe.getTopicId().equals(n.getTopicId())) continue;
                    if (probe.getChapterId() != null && !probe.getChapterId().equals(n.getChapterId())) continue;
                    if (probe.getMerchantMemberId() != null
                            && !probe.getMerchantMemberId().equals(n.getMerchantMemberId())) continue;
                    hit.add(n);
                }
                return hit;
            });
        }

        private CmsTopicNode node(Long id, Long topicId, Long chapterId, Long owner, String name) {
            CmsTopicNode n = new CmsTopicNode();
            n.setId(id);
            n.setTopicId(topicId);
            n.setChapterId(chapterId);
            n.setMerchantMemberId(owner);
            n.setName(name);
            n.setNodeAuditStatus(ChapterMerchantNodeServiceImpl.AUDIT_APPROVED);
            return n;
        }

        @Test
        void editExistingNode_updatesOnlyMyNodeInThisChapter_neverInserts_neighboursUntouched() {
            CmsTopicNode draft = new CmsTopicNode();
            draft.setId(13L);               // 客户端伪造邻居 id
            draft.setTopicId(OTHER_TOPIC);  // 伪造归属
            draft.setChapterId(OTHER_CHAPTER);
            draft.setMerchantMemberId(OTHER);
            draft.setName("新点位名");
            draft.setTemplateId(TPL);
            draft.setAddress("星海街 9 号");
            draft.setLongitude("120.7");
            draft.setLatitude("31.3");

            CmsTopicNode saved = service.submit(ME, CHAPTER, draft);

            ArgumentCaptor<CmsTopicNode> cap = ArgumentCaptor.forClass(CmsTopicNode.class);
            verify(nodeSvc, times(1)).updateCmsTopicNode(cap.capture());
            verify(nodeSvc, never()).insertCmsTopicNode(any());
            CmsTopicNode updated = cap.getValue();
            assertEquals(Long.valueOf(11L), updated.getId(), "只能更新 (topic,chapter,我) 那一行");
            assertEquals(Long.valueOf(11L), saved.getId());
            assertEquals(TOPIC, updated.getTopicId());
            assertEquals(CHAPTER, updated.getChapterId());
            assertEquals(ME, updated.getMerchantMemberId());
            assertEquals("新点位名", updated.getName());
            assertEquals("他主题同章号节点", table.get(0).getName());
            assertEquals("我在他章的节点", table.get(1).getName());
            assertEquals("同章别家节点", table.get(2).getName());
            for (int i = 0; i < 3; i++) {
                assertEquals(ChapterMerchantNodeServiceImpl.AUDIT_APPROVED, table.get(i).getNodeAuditStatus(),
                        "邻居节点审核态不得被波及");
            }

            ArgumentCaptor<CmsTopicNode> probe = ArgumentCaptor.forClass(CmsTopicNode.class);
            verify(nodeSvc).selectCmsTopicNodeList(probe.capture());
            assertEquals(TOPIC, probe.getValue().getTopicId());
            assertEquals(CHAPTER, probe.getValue().getChapterId());
            assertEquals(ME, probe.getValue().getMerchantMemberId());
        }
    }

    // ===================================================================== SC-FREE-TERMS
    /**
     * 既有 contact 用例只覆盖 legacy type2;本组补:第三方 / 未接受邀约一律拒且不建会话,
     * 双方各自从 /list 的 sent / received 看到的同一条邀约条款字段一致。
     */
    @Nested
    class Terms {
        private static final Long HOST = 300L;
        private static final Long OWNER = 500L;
        private static final Long STRANGER = 777L;
        /** 合作商家 OWNER 名下有 COOP_MANAGE 的员工。 */
        private static final Long OWNER_OPERATOR = 501L;
        /** 另一家商家(主体 600)的员工:对这条邀约是第三方。 */
        private static final Long FOREIGN_OPERATOR = 601L;
        private static final Long INVITE = 61L;
        private static final Long TOPIC = 200L;
        private final Date deadline = new Date(1_900_000_000_000L);

        private final ICoopInviteService coopInviteService = mock(ICoopInviteService.class);
        private final ICmsTopicService cmsTopicService = mock(ICmsTopicService.class);
        private final IClubService clubService = mock(IClubService.class);
        private final IMmsMerchantService mmsMerchantService = mock(IMmsMerchantService.class);
        private final IImService imService = mock(IImService.class);
        private final IMerchantAccessService access = mock(IMerchantAccessService.class);
        private Long principal;
        private final ApiCoopController c = new ApiCoopController() {
            @Override
            public Long getAppUserId() {
                return principal;
            }
        };

        Terms() {
            ReflectionTestUtils.setField(c, "coopInviteService", coopInviteService);
            ReflectionTestUtils.setField(c, "cmsTopicService", cmsTopicService);
            ReflectionTestUtils.setField(c, "clubService", clubService);
            ReflectionTestUtils.setField(c, "mmsMerchantService", mmsMerchantService);
            ReflectionTestUtils.setField(c, "imService", imService);
            ReflectionTestUtils.setField(c, "merchantAccessService", access);
            ReflectionTestUtils.setField(c, "umsMemberService", mock(IUmsMemberService.class));
            when(clubService.selectClubsByMemberId(any())).thenReturn(Collections.emptyList());
            when(access.require(OWNER, MerchantPermission.COOP_MANAGE)).thenReturn(
                    new MerchantAccessContext(OWNER, 9001L, OWNER, MerchantRoleCode.MERCHANT_OWNER));
            when(access.require(HOST, MerchantPermission.COOP_MANAGE))
                    .thenThrow(new ServiceException("非商家", 403));
            when(access.require(STRANGER, MerchantPermission.COOP_MANAGE))
                    .thenThrow(new ServiceException("非商家", 403));
            when(access.require(OWNER_OPERATOR, MerchantPermission.COOP_MANAGE)).thenReturn(
                    new MerchantAccessContext(OWNER_OPERATOR, 9001L, OWNER, MerchantRoleCode.MERCHANT_MANAGER));
            when(access.require(FOREIGN_OPERATOR, MerchantPermission.COOP_MANAGE)).thenReturn(
                    new MerchantAccessContext(FOREIGN_OPERATOR, 9002L, 600L, MerchantRoleCode.MERCHANT_MANAGER));
            CmsTopic topic = new CmsTopic();
            topic.setId(TOPIC);
            topic.setMemberId(HOST);
            topic.setLifecycle(TopicLifecycleState.RECRUITING.getCode());
            when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
            MmsMerchant m = new MmsMerchant();
            m.setName("幸会咖啡");
            m.setPhone("13800000000");
            when(mmsMerchantService.selectMmsMerchantByMemberId(OWNER)).thenReturn(m);
            when(imService.startSingle(any(), any())).thenReturn(9L);
        }

        /** 每次查询都返回一份新拷贝,模拟两次独立 DB 读。 */
        private CoopInvite row(Integer status) {
            CoopInvite inv = new CoopInvite();
            inv.setId(INVITE);
            inv.setInviteType(0);
            inv.setFromType("talent");
            inv.setFromId(HOST);
            inv.setToType("merchant");
            inv.setToId(OWNER);
            inv.setTopicId(TOPIC);
            inv.setStatus(status);
            inv.setShareMode(1);
            inv.setShareRate(new BigDecimal("0.20"));
            inv.setFixedFee(new BigDecimal("30.00"));
            inv.setDepositAmount(new BigDecimal("100.00"));
            inv.setConfigDeadline(deadline);
            inv.setMessage("周末夜跑合作");
            return inv;
        }

        private AjaxResult contactAs(Long who, Integer status) {
            principal = who;
            when(coopInviteService.selectCoopInviteById(INVITE)).thenReturn(row(status));
            CoopInvite req = new CoopInvite();
            req.setId(INVITE);
            return c.contact(req);
        }

        @Test
        void contact_thirdPartyOnAcceptedInvite_refusedWithoutConversationOrContact() {
            AjaxResult r = contactAs(STRANGER, 1);

            assertEquals(500, r.get(AjaxResult.CODE_TAG));
            assertEquals("无权联系", r.get(AjaxResult.MSG_TAG));
            assertNull(r.get(AjaxResult.DATA_TAG), "拒绝时不得带出会话 id 或任何对方信息");
            verify(imService, never()).startSingle(any(), any());
        }

        @Test
        void contact_nonAcceptedInvite_refusedForBothRealParties() {
            for (Integer status : Arrays.asList(null, 0, 2, 3)) {
                for (Long party : Arrays.asList(HOST, OWNER)) {
                    AjaxResult r = contactAs(party, status);
                    assertEquals(500, r.get(AjaxResult.CODE_TAG), "status=" + status + " party=" + party);
                    assertEquals("仅已合作可联系", r.get(AjaxResult.MSG_TAG));
                    assertNull(r.get(AjaxResult.DATA_TAG));
                }
            }
            verify(imService, never()).startSingle(any(), any());
        }

        /** 正控:同一套桩下,真双方在已接受邀约上能互联 —— 证明上面的拒绝不是桩把一切都拒了。 */
        @Test
        void contact_acceptedInvite_eachRealPartyReachesTheOther() {
            AjaxResult host = contactAs(HOST, 1);
            AjaxResult owner = contactAs(OWNER, 1);

            assertEquals(200, host.get(AjaxResult.CODE_TAG));
            assertEquals(200, owner.get(AjaxResult.CODE_TAG));
            verify(imService).startSingle(HOST, OWNER);
            verify(imService).startSingle(OWNER, HOST);
        }

        /** 与 /list 同一口径:商家员工按商家主体认合同方,能联系对方;会话由员工本人发起。 */
        @Test
        void contact_authorisedOperatorOfMerchantParty_reachesTheOtherParty() {
            AjaxResult r = contactAs(OWNER_OPERATOR, 1);

            assertEquals(200, r.get(AjaxResult.CODE_TAG), String.valueOf(r.get(AjaxResult.MSG_TAG)));
            verify(imService).startSingle(OWNER_OPERATOR, HOST);
        }

        @Test
        void contact_operatorOfAnotherMerchant_refusedWithoutConversation() {
            AjaxResult r = contactAs(FOREIGN_OPERATOR, 1);

            assertEquals(500, r.get(AjaxResult.CODE_TAG));
            assertEquals("无权联系", r.get(AjaxResult.MSG_TAG));
            assertNull(r.get(AjaxResult.DATA_TAG));
            verify(imService, never()).startSingle(any(), any());
        }

        @Test
        @SuppressWarnings("unchecked")
        void list_sameInvite_sentSideAndReceivedSide_carryIdenticalTerms() {
            when(coopInviteService.selectCoopInviteListReadOnly(any())).thenAnswer(inv -> {
                CoopInvite q = inv.getArgument(0);
                boolean sentQ = HOST.equals(q.getFromId()) && q.getFromType() == null;
                boolean recvQ = "merchant".equals(q.getToType()) && OWNER.equals(q.getToId());
                return sentQ || recvQ ? Collections.singletonList(row(1)) : Collections.emptyList();
            });

            principal = HOST;
            Map<String, Object> hostData = (Map<String, Object>) c.list(null).get(AjaxResult.DATA_TAG);
            principal = OWNER;
            Map<String, Object> ownerData = (Map<String, Object>) c.list(null).get(AjaxResult.DATA_TAG);

            List<CoopInvite> sent = (List<CoopInvite>) hostData.get("sent");
            List<CoopInvite> received = (List<CoopInvite>) ownerData.get("received");
            assertEquals(1, sent.size());
            assertEquals(1, received.size());
            assertTrue(((List<?>) hostData.get("received")).isEmpty());
            assertTrue(((List<?>) ownerData.get("sent")).isEmpty());
            CoopInvite a = sent.get(0);
            CoopInvite b = received.get(0);
            assertEquals(INVITE, a.getId());
            assertEquals(a.getId(), b.getId());
            assertEquals(a.getStatus(), b.getStatus());
            assertEquals(a.getShareMode(), b.getShareMode());
            assertNotNull(a.getShareRate());
            assertEquals(0, a.getShareRate().compareTo(b.getShareRate()), "分成比例两侧必须一致");
            assertEquals(0, a.getFixedFee().compareTo(b.getFixedFee()), "固定费两侧必须一致");
            assertEquals(0, a.getDepositAmount().compareTo(b.getDepositAmount()), "保证金两侧必须一致");
            assertEquals(a.getConfigDeadline(), b.getConfigDeadline());
            assertEquals(a.getTopicId(), b.getTopicId());
            assertEquals(a.getTermsFrozen(), b.getTermsFrozen());
            assertEquals(Boolean.FALSE, a.getTermsFrozen());
            // 合作方:双方都看到对方名称;已接受 ⇒ 承接方也拿得到发布方联系方式(此处发布方无档案,仅昵称/空)
            assertEquals("幸会咖啡", a.getPartner().getName());
            assertEquals("13800000000", a.getPartner().getPhone());
        }
    }
}

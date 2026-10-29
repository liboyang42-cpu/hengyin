package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.mapper.CmsTopicLikeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsCommentMapper;
import com.chengyinhub.business.mapper.UmsMemberFollowMapper;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.TopicChapterVO;
import com.chengyinhub.business.domain.vo.TopicInfoVO;
import com.chengyinhub.business.domain.vo.TopicNodeVO;
import com.chengyinhub.business.domain.vo.PublicMerchantRegistrationVO;
import com.chengyinhub.business.domain.vo.PublicTemplateListItemVO;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationScanService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTemplateLibraryService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicLikeService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.ITopicRouteService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IUmsMemberFollowService;
import com.chengyinhub.business.service.IUmsCommentService;
import com.chengyinhub.business.service.IViewCollaboratorsService;
import com.chengyinhub.business.service.IViewCommentService;
import com.chengyinhub.business.service.IViewRegistrationService;
import com.chengyinhub.business.service.ITmsBrowseRecordService;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.mapper.CmsRegistrationStatisticsMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.system.service.ISysCategoryService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模板敏感字段(答案/提示)出参剥离闸:
 * 多处 app 端口整实体下发模板,questionAnswer/correctAnswer 直接泄题
 * (绕过 /api/play 的「答案不下发」防作弊层),hint1/hint2 泄露积分付费墙内容。
 * 口径:仅模板 owner(编辑回填)可见明文,其余出口一律剥离。
 */
class ApiTemplateSecretsGateTest {

    private static final Long VIEWER = 88L;
    private static final Long OTHER_OWNER = 99L;
    private static final Long TPL = 401L;
    private static final String LOCKED_CHAPTER_DESCRIPTION = "第二章的秘密剧情";

    private static CmsMemberTemplate memberTplWithSecrets(Long ownerId) {
        CmsMemberTemplate t = new CmsMemberTemplate();
        t.setId(TPL);
        t.setMemberId(ownerId);
        t.setDelFlag(0);
        t.setDraftStatus(1);
        t.setStatus(1L);
        t.setPublishStatus(1L);
        t.setValidationMethod(1);
        t.setQuestionAnswer("暗号是「北纬31」");
        t.setCorrectAnswer("A");
        t.setHint1("留意店内的特色陈设");
        t.setHint2("靠近收银台或入口处");
        t.setAnswerReveal("兜底答案：北纬31");
        return t;
    }

    @Test
    void canEditIsResponseOnlyAndCannotBeSuppliedByClient() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CmsMemberTemplate input = mapper.readValue("{\"canEdit\":true}", CmsMemberTemplate.class);
        assertNull(input.getCanEdit());
        input.setCanEdit(true);
        assertTrue(mapper.readTree(mapper.writeValueAsString(input)).get("canEdit").asBoolean());
    }

    // ---------- ApiTemplateController ----------

    private ICmsMemberTemplateService tplSvc;
    private ICmsTemplateLibraryService libSvc;
    private ApiTemplateController tplController;

    @BeforeEach
    void setUp() {
        tplSvc = mock(ICmsMemberTemplateService.class);
        libSvc = mock(ICmsTemplateLibraryService.class);
        tplController = spy(new ApiTemplateController());
        doReturn(VIEWER).when(tplController).getAppUserId();
        ReflectionTestUtils.setField(tplController, "memberTemplateService", tplSvc);
        ReflectionTestUtils.setField(tplController, "templateLibraryService", libSvc);
        TemplatePublishServiceImpl publishService = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(publishService, "memberTemplateService", tplSvc);
        ReflectionTestUtils.setField(tplController, "templatePublishService", publishService);
        ReflectionTestUtils.setField(tplController, "umsMemberService", mock(IUmsMemberService.class));
        // /list 的 startPage() 读 servlet 请求参数,给个空请求上下文
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void myinfo_returns_safe_projection_for_non_owner_published_template() {
        when(tplSvc.selectCmsMemberTemplateById(TPL)).thenReturn(memberTplWithSecrets(OTHER_OWNER));

        AjaxResult res = tplController.myInfo(String.valueOf(TPL), null);

        assertEquals(200, res.get("code"), res.toString());
        CmsMemberTemplate out = (CmsMemberTemplate) res.get("data");
        assertMemberTemplateSecretsStripped(out);
        assertEquals(Boolean.FALSE, out.getCanEdit());
    }

    @Test
    void myinfo_rejects_non_owner_draft_with_same_response_as_missing_template() {
        CmsMemberTemplate privateDraft = memberTplWithSecrets(OTHER_OWNER);
        privateDraft.setDraftStatus(0);
        privateDraft.setStatus(0L);
        privateDraft.setPublishStatus(0L);
        when(tplSvc.selectCmsMemberTemplateById(TPL)).thenReturn(privateDraft);

        AjaxResult forbidden = tplController.myInfo(String.valueOf(TPL), null);
        when(tplSvc.selectCmsMemberTemplateById(TPL)).thenReturn(null);
        AjaxResult missing = tplController.myInfo(String.valueOf(TPL), null);

        assertEquals(missing.get("code"), forbidden.get("code"));
        assertEquals(missing.get("msg"), forbidden.get("msg"));
        assertNull(forbidden.get("data"));
    }

    @Test
    void myinfo_rejects_non_owner_deleted_template_with_same_response_as_missing_template() {
        CmsMemberTemplate deletedTemplate = memberTplWithSecrets(OTHER_OWNER);
        deletedTemplate.setDelFlag(2);
        when(tplSvc.selectCmsMemberTemplateById(TPL)).thenReturn(deletedTemplate);

        AjaxResult forbidden = tplController.myInfo(String.valueOf(TPL), null);
        when(tplSvc.selectCmsMemberTemplateById(TPL)).thenReturn(null);
        AjaxResult missing = tplController.myInfo(String.valueOf(TPL), null);

        assertEquals(missing.get("code"), forbidden.get("code"));
        assertEquals(missing.get("msg"), forbidden.get("msg"));
        assertNull(forbidden.get("data"));
    }

    @Test
    void myinfo_keeps_secrets_for_owner_edit_backfill() {
        when(tplSvc.selectCmsMemberTemplateById(TPL)).thenReturn(memberTplWithSecrets(VIEWER));

        AjaxResult res = tplController.myInfo(String.valueOf(TPL), null);

        assertEquals(200, res.get("code"), res.toString());
        CmsMemberTemplate out = (CmsMemberTemplate) res.get("data");
        assertEquals(Boolean.TRUE, out.getCanEdit());
        assertEquals("暗号是「北纬31」", out.getQuestionAnswer());
        assertEquals("A", out.getCorrectAnswer());
        assertEquals("留意店内的特色陈设", out.getHint1());
    }

    @Test
    void library_info_stripped_for_non_owner() {
        CmsTemplateLibrary lib = new CmsTemplateLibrary();
        lib.setId(TPL);
        lib.setMemberId(OTHER_OWNER);
        lib.setDelFlag(0);
        lib.setStatus(1L);
        lib.setPublishStatus(1L);
        lib.setValidationMethod(1);
        lib.setQuestionAnswer("答案X");
        lib.setCorrectAnswer("B");
        lib.setHint1("h1");
        lib.setHint2("h2");
        lib.setAnswerReveal("解析X");
        when(libSvc.selectCmsTemplateLibraryById(TPL)).thenReturn(lib);

        AjaxResult res = tplController.producInfo(String.valueOf(TPL));

        assertEquals(200, res.get("code"));
        CmsTemplateLibrary out = (CmsTemplateLibrary) res.get("data");
        assertLibraryTemplateSecretsStripped(out);
    }

    @Test
    void public_library_info_rejects_every_non_public_state_like_missing_template() {
        CmsTemplateLibrary hidden = publicLibraryTemplate();
        hidden.setDelFlag(1);
        when(libSvc.selectCmsTemplateLibraryById(TPL)).thenReturn(hidden);
        AjaxResult disabled = tplController.producInfo(String.valueOf(TPL));

        hidden = publicLibraryTemplate();
        hidden.setStatus(null);
        when(libSvc.selectCmsTemplateLibraryById(TPL)).thenReturn(hidden);
        AjaxResult missingReviewState = tplController.producInfo(String.valueOf(TPL));

        hidden = publicLibraryTemplate();
        hidden.setPublishStatus(2L);
        when(libSvc.selectCmsTemplateLibraryById(TPL)).thenReturn(hidden);
        AjaxResult abnormalPublishState = tplController.producInfo(String.valueOf(TPL));

        when(libSvc.selectCmsTemplateLibraryById(TPL)).thenReturn(null);
        AjaxResult missing = tplController.producInfo(String.valueOf(TPL));

        for (AjaxResult rejected : new AjaxResult[] { disabled, missingReviewState, abnormalPublishState }) {
            assertEquals(missing.get("code"), rejected.get("code"));
            assertEquals(missing.get("msg"), rejected.get("msg"));
            assertNull(rejected.get("data"));
        }
    }

    @Test
    void library_info_projects_reward_fields_from_canonical_template_for_adoption() {
        CmsTemplateLibrary lib = new CmsTemplateLibrary();
        lib.setId(TPL);
        lib.setMemberId(OTHER_OWNER);
        lib.setMemberTemplateId(777L);
        lib.setDelFlag(0);
        lib.setStatus(1L);
        lib.setPublishStatus(1L);
        lib.setValidationMethod(1);
        CmsMemberTemplate canonical = memberTplWithSecrets(OTHER_OWNER);
        canonical.setId(777L);
        canonical.setMedalName("街角发现者");
        canonical.setMedalImg("https://cdn.example/medal.png");
        canonical.setMedalStyle("enamel");
        when(libSvc.selectCmsTemplateLibraryById(TPL)).thenReturn(lib);
        when(tplSvc.selectCmsMemberTemplateById(777L)).thenReturn(canonical);

        AjaxResult res = tplController.producInfo(String.valueOf(TPL));

        assertEquals(200, res.get("code"), res.toString());
        BeanWrapperImpl rewardProjection = new BeanWrapperImpl(res.get("data"));
        assertEquals("街角发现者", rewardProjection.getPropertyValue("medalName"));
        assertEquals("https://cdn.example/medal.png", rewardProjection.getPropertyValue("medalImg"));
        assertEquals("enamel", rewardProjection.getPropertyValue("medalStyle"));
    }

    @Test
    void library_info_does_not_project_unreviewed_canonical_reward() {
        CmsTemplateLibrary lib = new CmsTemplateLibrary();
        lib.setId(TPL);
        lib.setMemberId(OTHER_OWNER);
        lib.setMemberTemplateId(777L);
        lib.setDelFlag(0);
        lib.setStatus(1L);
        lib.setPublishStatus(1L);
        lib.setValidationMethod(1);
        CmsMemberTemplate pending = memberTplWithSecrets(OTHER_OWNER);
        pending.setId(777L);
        pending.setStatus(0L);
        pending.setPublishStatus(0L);
        pending.setMedalName("未审核勋章");
        pending.setMedalStyle("enamel");
        when(libSvc.selectCmsTemplateLibraryById(TPL)).thenReturn(lib);
        when(tplSvc.selectCmsMemberTemplateById(777L)).thenReturn(pending);

        AjaxResult res = tplController.producInfo(String.valueOf(TPL));

        assertEquals(200, res.get("code"), res.toString());
        BeanWrapperImpl rewardProjection = new BeanWrapperImpl(res.get("data"));
        assertNull(rewardProjection.getPropertyValue("medalName"));
        assertNull(rewardProjection.getPropertyValue("medalStyle"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void library_list_uses_exact_public_projection() {
        CmsTemplateLibrary lib = new CmsTemplateLibrary();
        lib.setId(TPL);
        lib.setMemberId(OTHER_OWNER);
        lib.setTitle("城市解谜");
        lib.setDescription("沿街寻找线索");
        lib.setImgUrl("https://cdn.example/template.png");
        lib.setPlayers("2-4人");
        lib.setDuration(90L);
        lib.setActivityCategoryids("4,7");
        lib.setQuestionAnswer("答案X");
        lib.setCorrectAnswer("B");
        lib.setHint1("h1");
        lib.setHint2("h2");
        lib.setAnswerReveal("解析X");
        when(libSvc.selectCmsTemplateLibraryList(any())).thenReturn(Collections.singletonList(lib));

        AjaxResult res = tplController.commonTemplateList(null, null, null, null);  // pack_type 不传 = 不过滤形态

        assertEquals(200, res.get("code"));
        TableDataInfo table = (TableDataInfo) res.get("data");
        PublicTemplateListItemVO out = ((List<PublicTemplateListItemVO>) table.getRows()).get(0);
        JsonNode json = new ObjectMapper().valueToTree(out);
        // 2026-09-05 7 → 9:模板市场加了形态筛选轴,列表投影多出 packType / useNum 两个
        //   **展示**字段。这道闸钉的是「字段数只能在一次显式审查里变」——
        //   下面 assertFalse 那三条(memberId/reason/questionAnswer)是它真正要挡的东西,
        //   本批一个都没放进来。改这个数字必须同时说清多出来的是哪两个、为什么是展示字段。
        assertEquals(9, json.size(), json.toString());
        assertEquals(0, json.path("packType").asInt(), "老数据 packType 为 null 时服务端归一成 0");
        assertEquals(0, json.path("useNum").asInt());
        assertEquals(TPL.longValue(), json.path("id").asLong());
        assertEquals("城市解谜", json.path("title").asText());
        assertEquals("沿街寻找线索", json.path("description").asText());
        assertEquals("https://cdn.example/template.png", json.path("imgUrl").asText());
        assertEquals("2-4人", json.path("players").asText());
        assertEquals(90L, json.path("duration").asLong());
        assertEquals("4,7", json.path("activityCategoryids").asText());
        assertFalse(json.has("memberId"));
        assertFalse(json.has("reason"));
        assertFalse(json.has("questionAnswer"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void public_library_list_is_anonymous_and_keeps_visibility_filters_and_secret_stripping() {
        ApiTemplateController anonymous = spy(new ApiTemplateController());
        doThrow(new RuntimeException("anonymous")).when(anonymous).getAppUserId();
        ReflectionTestUtils.setField(anonymous, "templateLibraryService", libSvc);

        CmsTemplateLibrary lib = new CmsTemplateLibrary();
        lib.setId(TPL);
        lib.setMemberId(OTHER_OWNER);
        lib.setQuestionAnswer("答案X");
        lib.setCorrectAnswer("B");
        lib.setHint1("h1");
        lib.setHint2("h2");
        lib.setAnswerReveal("解析X");
        when(libSvc.selectCmsTemplateLibraryList(any())).thenReturn(Collections.singletonList(lib));

        AjaxResult res = anonymous.commonTemplateList("城市", "4", null, null);  // pack_type 不传 = 不过滤形态

        assertEquals(200, res.get("code"), res.toString());
        ArgumentCaptor<CmsTemplateLibrary> query = ArgumentCaptor.forClass(CmsTemplateLibrary.class);
        verify(libSvc).selectCmsTemplateLibraryList(query.capture());
        assertEquals("城市", query.getValue().getTitle());
        assertEquals("4", query.getValue().getActivityCategoryids());
        assertEquals(0, query.getValue().getDelFlag());
        assertEquals(1L, query.getValue().getStatus());
        assertEquals(1L, query.getValue().getPublishStatus());
        TableDataInfo table = (TableDataInfo) res.get("data");
        PublicTemplateListItemVO out = ((List<PublicTemplateListItemVO>) table.getRows()).get(0);
        assertEquals(TPL, out.getId());
        verify(anonymous, never()).getAppUserId();
    }

    // ---------- ApiRegistrationController /info:玩家自己的报名详情附带的模板 ----------

    @Test
    void registration_info_attached_template_stripped_for_player() {
        ICmsRegistrationService regSvc = mock(ICmsRegistrationService.class);
        ICmsTopicService topicSvc = mock(ICmsTopicService.class);
        ICmsRegistrationScanService scanSvc = mock(ICmsRegistrationScanService.class);
        ICmsRegistrationMerchantService merchantSvc = mock(ICmsRegistrationMerchantService.class);
        ICmsMemberTemplateService memberTplSvc = mock(ICmsMemberTemplateService.class);
        IRefundService refundSvc = mock(IRefundService.class);

        ApiRegistrationController c = spy(new ApiRegistrationController());
        doReturn(VIEWER).when(c).getAppUserId();
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(c), "registrationService", regSvc);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(c), "topicService", topicSvc);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(c), "registrationScanService", scanSvc);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(c), "registrationMerchantService", merchantSvc);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(c), "memberTemplateService", memberTplSvc);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(c), "mmsMerchantService", mock(IMmsMerchantService.class));
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(c), "refundService", refundSvc);
        when(refundSvc.selectRefundList(any())).thenReturn(Collections.emptyList());

        CmsRegistration reg = new CmsRegistration();
        reg.setId(5001L);
        reg.setMemberId(VIEWER); // 本人报名单(玩家视角)
        reg.setOwnerType(1L);
        reg.setOwnerId(300L);
        when(regSvc.selectCmsRegistrationById(5001L)).thenReturn(reg);

        CmsTopic topic = new CmsTopic();
        topic.setId(300L);
        when(topicSvc.selectCmsTopicById(300L)).thenReturn(topic);

        CmsRegistrationScan scan = new CmsRegistrationScan();
        scan.setRegistrationType(1);
        scan.setMemberId(OTHER_OWNER);
        when(scanSvc.selectCmsRegistrationScanList(any())).thenReturn(Collections.singletonList(scan));

        CmsRegistrationMerchant rm = new CmsRegistrationMerchant();
        rm.setTemplateId(TPL);
        when(merchantSvc.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.singletonList(rm));
        when(memberTplSvc.selectCmsMemberTemplateById(TPL)).thenReturn(memberTplWithSecrets(OTHER_OWNER));

        AjaxResult res = RegistrationQueryTestFixtures.wire(c).getRegistrationDetail("5001");

        assertEquals(200, res.get("code"), res.toString());
        assertMemberTemplateSecretsStripped(scan.getMemberTemplate());
    }

    // ---------- ApiTopicController: 主题详情/商家列表均把节点模板整实体下发 ----------

    @Test
    void activity_info_usesPublicGate_and_stripsAllTemplateSecrets() {
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        CmsActivity activity = new CmsActivity();
        activity.setId(700L);
        activity.setMemberId(OTHER_OWNER);
        activity.setTemplateId(TPL);
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        when(activityService.selectCmsActivityDetailById(700L, VIEWER)).thenReturn(activity);

        ApiActivityController controller = spy(new ApiActivityController());
        // release-0917 集成:rule-merchant-0917 给 info 接口加了 @Autowired 营业状态依赖(#10),手工装配须补上;mock 默认 false=营业中。
        ReflectionTestUtils.setField(controller, "merchantBusinessStatusService", mock(com.chengyinhub.business.service.IMerchantBusinessStatusService.class));
        doReturn(VIEWER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(controller, "memberTemplateService", tplSvc);
        ReflectionTestUtils.setField(controller, "clubMemberService", mock(IClubMemberService.class));
        ReflectionTestUtils.setField(controller, "viewCollaboratorsService", mock(IViewCollaboratorsService.class));
        ReflectionTestUtils.setField(controller, "commentService", mock(IViewCommentService.class));
        ReflectionTestUtils.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
        ReflectionTestUtils.setField(controller, "viewRegistrationService", mock(IViewRegistrationService.class));
        ReflectionTestUtils.setField(controller, "sysCategoryService", mock(ISysCategoryService.class));
        ReflectionTestUtils.setField(controller, "tmsBrowseRecordService", mock(ITmsBrowseRecordService.class));
        ReflectionTestUtils.setField(controller, "umsCommentService", mock(IUmsCommentService.class));
        CmsRegistrationStatisticsMapper statisticsMapper = mock(CmsRegistrationStatisticsMapper.class);
        when(statisticsMapper.statisticsSalesAmoutByOwner(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "cmsRegistrationStatisticsMapper", statisticsMapper);
        ITmsBrowseRecordService browseService = (ITmsBrowseRecordService)
                ReflectionTestUtils.getField(controller, "tmsBrowseRecordService");
        when(browseService.countBrowseByTypeAndTarget(any())).thenReturn(Collections.emptyList());
        when(tplSvc.selectCmsMemberTemplateByIds(any())).thenReturn(
                Collections.singletonList(memberTplWithSecrets(OTHER_OWNER)));

        AjaxResult res = controller.activityInfo("700");

        assertEquals(200, res.get("code"), res.toString());
        com.chengyinhub.business.domain.vo.ActivityInfoVO out =
                (com.chengyinhub.business.domain.vo.ActivityInfoVO) res.get("data");
        CmsMemberTemplate template = out.getMemberTemplateList().get(0);
        assertNull(template.getQuestionAnswer());
        assertNull(template.getCorrectAnswer());
        assertNull(template.getHint1());
        assertNull(template.getHint2());
        assertNull(template.getAnswerReveal());
    }

    @Test
    void activity_info_doesNotFallbackToRawEntityWhenPublicGateHidesIt() {
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        CmsActivity hidden = new CmsActivity();
        hidden.setId(701L);
        hidden.setStatus(1L);
        hidden.setPublishStatus(0L);
        when(activityService.selectCmsActivityById(701L)).thenReturn(hidden);

        ApiActivityController controller = spy(new ApiActivityController());
        // release-0917 集成:rule-merchant-0917 给 info 接口加了 @Autowired 营业状态依赖(#10),手工装配须补上;mock 默认 false=营业中。
        ReflectionTestUtils.setField(controller, "merchantBusinessStatusService", mock(com.chengyinhub.business.service.IMerchantBusinessStatusService.class));
        doReturn(VIEWER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);

        AjaxResult res = controller.activityInfo("701");

        assertEquals(500, res.get("code"));
        assertEquals("活动不存在", res.get("msg"));
    }

    @Test
    void activity_info_reusesAuthenticatedOwnerSeamForPreview() {
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        CmsActivity hidden = new CmsActivity();
        hidden.setId(703L);
        hidden.setMemberId(VIEWER);
        hidden.setStatus(0L);
        hidden.setPublishStatus(0L);
        when(activityService.selectCmsActivityDetailById(703L, VIEWER)).thenReturn(hidden);

        ApiActivityController controller = activityControllerForMinimalDetail(activityService);
        AjaxResult res = controller.activityInfo("703");

        assertEquals(200, res.get("code"), res.toString());
    }

    @Test
    void topic_info_doesNotFallbackToRawEntityWhenPublicGateHidesIt() {
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        CmsTopic hidden = new CmsTopic();
        hidden.setId(702L);
        hidden.setStatus(1);
        hidden.setUserStatus(0);
        when(topicService.selectCmsTopicById(702L)).thenReturn(hidden);

        ApiTopicController controller = spy(new ApiTopicController());
        // release-0917 集成:rule-merchant-0917 给 info 接口加了 @Autowired 营业状态依赖(#10),手工装配须补上;mock 默认 false=营业中。
        ReflectionTestUtils.setField(controller, "merchantBusinessStatusService", mock(com.chengyinhub.business.service.IMerchantBusinessStatusService.class));
        doReturn(VIEWER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);

        AjaxResult res = controller.topicInfo("702");

        assertEquals(500, res.get("code"));
        assertEquals("主题活动不存在", res.get("msg"));
    }

    @Test
    void topic_info_reusesAuthenticatedOwnerSeamForPreview() {
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        CmsTopic hidden = new CmsTopic();
        hidden.setId(704L);
        hidden.setMemberId(VIEWER);
        hidden.setStatus(0);
        hidden.setUserStatus(0);
        when(topicService.selectCmsTopicDetailById(704L, VIEWER)).thenReturn(hidden);

        ApiTopicController controller = topicControllerWithEmptyDetailDependencies(topicService);
        AjaxResult res = controller.topicInfo("704");

        assertEquals(200, res.get("code"), res.toString());
    }

    @Test
    void topic_info_to_user_strips_node_and_registered_merchant_answers() {
        AjaxResult res = topicControllerWithSecretNode().topicInfo("300");

        assertEquals(200, res.get("code"), res.toString());
        assertTopicNodeSecretsStripped(firstTopicNode(res), true);
    }

    @Test
    void topic_info_to_user_returns_only_chapter_teaser_for_branch_graph() {
        AjaxResult res = topicControllerWithSecretNode(TopicRouteState.BRANCH_GRAPH).topicInfo("300");

        assertEquals(200, res.get("code"), res.toString());
        TopicInfoVO detail = (TopicInfoVO) res.get("data");
        assertEquals(8L, detail.getLocationCount(), "Full-route count survives restricted/empty chapter projection");
        assertEquals(1, detail.getChaptersList().size(), "公共详情仍保留章节级 teaser");
        TopicChapterVO chapter = detail.getChaptersList().get(0);
        assertTrue(chapter.getNodes() == null || chapter.getNodes().isEmpty(),
                "分支主题公共详情不得下发未绑定权威路线局的节点素材");
        assertTrue(chapter.getBlocks() == null || chapter.getBlocks().isEmpty(),
                "分支主题公共详情不得下发章节内容块");
        assertNull(chapter.getBlocksJson(), "分支主题公共详情不得保留章节内容块原文");
        assertNull(chapter.getRouteGeometry(), "分支主题公共详情不得用完整节点生成路线几何");
    }

    @Test
    void topic_info_to_user_keeps_nodes_and_geometry_for_linear_route() {
        AjaxResult res = topicControllerWithSecretNode(TopicRouteState.LINEAR).topicInfo("300");

        assertEquals(200, res.get("code"), res.toString());
        TopicInfoVO detail = (TopicInfoVO) res.get("data");
        TopicChapterVO chapter = detail.getChaptersList().get(0);
        assertEquals(302L, chapter.getNodes().get(0).getId());
        assertEquals("secret-chapter-block", chapter.getBlocks().get(0));
        assertEquals("secret-blocks-json", chapter.getBlocksJson());
        assertEquals("secret-route-geometry", chapter.getRouteGeometry());
    }

    /* 2026-09-05:teaser 是防玩家剧透的,不该打到承接方自己那一站。放行范围严格限定在
       「本人已中标的 nodeId」,别的节点、章节 blocks 与完整路线几何一律照旧不给。 */
    @Test
    void 分支图主题下承接方只看得到自己那一站其余仍teaser() {
        AjaxResult res = topicControllerWithSecretNode(TopicRouteState.BRANCH_GRAPH, false, false, 302L)
                .topicInfo("300");

        assertEquals(200, res.get("code"), res.toString());
        TopicInfoVO detail = (TopicInfoVO) res.get("data");
        assertEquals(8L, detail.getLocationCount(), "Full-route count survives restricted/empty chapter projection");
        TopicChapterVO chapter = detail.getChaptersList().get(0);
        assertEquals(1, chapter.getNodes().size(), "承接方应当看得到自己承接的那一站");
        assertEquals(302L, chapter.getNodes().get(0).getId());
        // 放行的只是「哪一站是我的」,模板答案照旧 strip —— 答案走承接页自己那条链路
        assertTopicNodeSecretsStripped(chapter.getNodes().get(0), true);
        assertTrue(chapter.getBlocks() == null || chapter.getBlocks().isEmpty(),
                "章节内容块仍然不给:那才是整条 HIDDEN 分支会漏出去的地方");
        assertNull(chapter.getBlocksJson());
        assertNull(chapter.getRouteGeometry(), "完整路线几何仍然不给");
    }

    @Test
    void 分支图主题下非承接方一个节点都拿不到() {
        AjaxResult res = topicControllerWithSecretNode(TopicRouteState.BRANCH_GRAPH, false, false, null)
                .topicInfo("300");

        TopicInfoVO detail = (TopicInfoVO) res.get("data");
        assertEquals(8L, detail.getLocationCount(), "Full-route count survives restricted/empty chapter projection");
        TopicChapterVO chapter = detail.getChaptersList().get(0);
        assertTrue(chapter.getNodes() == null || chapter.getNodes().isEmpty(),
                "白名单为空必须回落到 2026-09-05 之前的 teaser 行为");
    }

    @Test
    void 分支图主题下承接方也拿不到不属于自己的节点() {
        // 自己承接的是 999(不在本章),本章那一站 302 不是自己的 → 仍然一个都不给
        AjaxResult res = topicControllerWithSecretNode(TopicRouteState.BRANCH_GRAPH, false, false, 999L)
                .topicInfo("300");

        TopicInfoVO detail = (TopicInfoVO) res.get("data");
        assertEquals(8L, detail.getLocationCount(), "Full-route count survives restricted/empty chapter projection");
        TopicChapterVO chapter = detail.getChaptersList().get(0);
        assertTrue(chapter.getNodes() == null || chapter.getNodes().isEmpty(),
                "白名单里没有的节点被放行了 = 承接一站就能看全图");
    }

    /* 剧情解锁:hasActiveTopicStoryAccess 查的是玩家报名单,承接商家没有报名单。
       不放行的话多章主题会把承接方锁在第 1 章,连自己那一章的剧情都读不到 ——
       而他要照着本章剧情接待玩家,那是他的工作内容不是剧透。 */
    @Test
    void 承接商家没有玩家报名单也应解锁全部章节剧情() {
        AjaxResult res = topicControllerWithSecretNode(TopicRouteState.LINEAR, true, false, 302L)
                .topicInfo("300");

        TopicInfoVO detail = (TopicInfoVO) res.get("data");
        assertEquals(2, detail.getChaptersList().size(), "承接方仍被锁在第一章");
        assertFalse(detail.getStoryLocked());
        assertEquals(2, detail.getUnlockedChapterCount());
    }

    @Test
    void topic_info_to_user_hides_story_after_first_chapter_without_ticket() {
        AjaxResult res = topicControllerWithSecretNode(TopicRouteState.LINEAR, true, false).topicInfo("300");

        assertEquals(200, res.get("code"), res.toString());
        TopicInfoVO detail = (TopicInfoVO) res.get("data");
        assertEquals(8L, detail.getLocationCount(), "Full-route count survives restricted/empty chapter projection");
        assertEquals(1, detail.getChaptersList().size(), "未购票响应只能下发第一章");
        assertEquals(2, detail.getTotalChapterCount());
        assertEquals(1, detail.getUnlockedChapterCount());
        assertTrue(detail.getStoryLocked());
        assertFalse(detail.getChaptersList().stream()
                .anyMatch(chapter -> LOCKED_CHAPTER_DESCRIPTION.equals(chapter.getDescription())),
                "未购票响应不得包含第二章剧情正文");
    }

    @Test
    void topic_info_to_user_returns_all_story_chapters_with_active_ticket() {
        AjaxResult res = topicControllerWithSecretNode(TopicRouteState.LINEAR, true, true).topicInfo("300");

        assertEquals(200, res.get("code"), res.toString());
        TopicInfoVO detail = (TopicInfoVO) res.get("data");
        assertEquals(detail.getTotalChapterCount(), detail.getChaptersList().size());
        assertEquals(detail.getTotalChapterCount(), detail.getUnlockedChapterCount());
        assertFalse(detail.getStoryLocked());
        assertTrue(detail.getChaptersList().stream()
                .anyMatch(chapter -> LOCKED_CHAPTER_DESCRIPTION.equals(chapter.getDescription())),
                "有效票响应必须恢复第二章剧情正文");
    }

    @Test
    void topic_info_to_user_does_not_lock_single_chapter_topic() {
        AjaxResult res = topicControllerWithSecretNode(TopicRouteState.LINEAR, false, false).topicInfo("300");

        assertEquals(200, res.get("code"), res.toString());
        TopicInfoVO detail = (TopicInfoVO) res.get("data");
        assertEquals(1, detail.getTotalChapterCount());
        assertEquals(detail.getTotalChapterCount(), detail.getUnlockedChapterCount());
        assertEquals(detail.getTotalChapterCount(), detail.getChaptersList().size());
        assertFalse(detail.getStoryLocked());
    }

    @Test
    void topic_list_to_merchant_strips_node_and_registered_merchant_answers() {
        AjaxResult res = topicControllerWithSecretNode().topicListToMerchant();

        assertEquals(200, res.get("code"));
        assertTopicNodeSecretsStripped(firstTopicNode(res), true);
    }

    @Test
    void topic_info_to_merchant_strips_node_template_answers() {
        AjaxResult res = topicControllerWithSecretNode().topicInfoToMerchant("300");

        assertEquals(200, res.get("code"));
        assertEquals(8L, ((TopicInfoVO) res.get("data")).getLocationCount());
        assertEquals(Integer.valueOf(0), ((TopicInfoVO) res.get("data")).getDelFlag());
        assertTopicNodeSecretsStripped(firstTopicNode(res), false);
    }

    @Test
    void topic_info_to_merchant_preservesActualDeletionFlagWithoutInventingLiveState() {
        for (Integer flag : new Integer[] {null, 1, 2}) {
            ApiTopicController controller = topicControllerWithSecretNode();
            ICmsTopicService topicService = (ICmsTopicService)
                    ReflectionTestUtils.getField(controller, "cmsTopicService");
            CmsTopic actual = topicService.selectMerchantVisibleCmsTopicById(300L);
            actual.setDelFlag(flag);

            AjaxResult res = controller.topicInfoToMerchant("300");

            assertEquals(200, res.get("code"));
            assertEquals(flag, ((TopicInfoVO) res.get("data")).getDelFlag(),
                    "读取投影不能把实际未知或删除标记改成可编辑的0");
        }
    }

    @Test
    void topic_info_to_merchant_allowsManagedProjectionWhenPublicMerchantProjectionIsHidden() {
        ApiTopicController controller = topicControllerWithSecretNode();
        ICmsTopicService topicService = (ICmsTopicService)
                ReflectionTestUtils.getField(controller, "cmsTopicService");
        IUmsMemberService memberService = (IUmsMemberService)
                ReflectionTestUtils.getField(controller, "umsMemberService");
        UmsMember rbacMerchant = new UmsMember();
        rbacMerchant.setId(VIEWER);
        rbacMerchant.setStatus(1L);
        rbacMerchant.setUserType(1L);
        when(memberService.selectUmsMemberById(VIEWER)).thenReturn(rbacMerchant);
        CmsTopic managed = topicService.selectMerchantVisibleCmsTopicById(300L);
        when(topicService.selectMerchantVisibleCmsTopicById(300L)).thenReturn(null);
        when(topicService.selectMerchantManagedCmsTopicById(300L, VIEWER)).thenReturn(managed);

        AjaxResult res = controller.topicInfoToMerchant("300");

        assertEquals(200, res.get("code"), "已承接商家仍须进入下架主题的履约详情");
        verify(topicService).selectMerchantManagedCmsTopicById(300L, VIEWER);
    }

    @Test
    void topic_info_to_merchant_rejectsNonMerchantThroughMerchantAccessTruth() {
        ApiTopicController controller = topicControllerWithSecretNode();
        IMerchantAccessService merchantAccessService = (IMerchantAccessService)
                ReflectionTestUtils.getField(controller, "merchantAccessService");
        ICmsTopicService topicService = (ICmsTopicService)
                ReflectionTestUtils.getField(controller, "cmsTopicService");
        when(merchantAccessService.resolve(VIEWER))
                .thenThrow(new com.chengyinhub.common.exception.ServiceException(
                        "商家身份无效或权限不足", com.chengyinhub.common.constant.HttpStatus.FORBIDDEN));

        AjaxResult res = controller.topicInfoToMerchant("300");

        assertEquals(com.chengyinhub.common.constant.HttpStatus.FORBIDDEN, res.get("code"));
        verify(topicService, never()).selectMerchantVisibleCmsTopicById(anyLong());
    }

    // M-12:营销岗能在合作中心看到招商主题(MARKETING_READ),「申请承接」要 COOP_MANAGE;
    // 详情原先一律要 PROJECT_MANAGE ⇒ 列表可见、详情被拒。招商池浏览与申请承接同权。
    @Test
    void topic_info_to_merchant_letsCoopRoleBrowsePoolButNotManagedFallback() {
        ApiTopicController controller = topicControllerWithSecretNode();
        IMerchantAccessService merchantAccessService = (IMerchantAccessService)
                ReflectionTestUtils.getField(controller, "merchantAccessService");
        ICmsTopicService topicService = (ICmsTopicService)
                ReflectionTestUtils.getField(controller, "cmsTopicService");
        when(merchantAccessService.resolve(VIEWER)).thenReturn(new MerchantAccessContext(
                VIEWER, 77L, 901L, MerchantRoleCode.MERCHANT_MARKETING));

        assertEquals(200, controller.topicInfoToMerchant("300").get("code"), "营销岗须能打开招商池里的主题详情");

        when(topicService.selectMerchantVisibleCmsTopicById(300L)).thenReturn(null);
        AjaxResult hidden = controller.topicInfoToMerchant("300");
        assertEquals("主题活动不存在", hidden.get("msg"));
        verify(topicService, never()).selectMerchantManagedCmsTopicById(anyLong(), any());
    }

    @Test
    void topic_info_to_merchant_rejectsRoleWithoutCoopOrProjectPermission() {
        ApiTopicController controller = topicControllerWithSecretNode();
        IMerchantAccessService merchantAccessService = (IMerchantAccessService)
                ReflectionTestUtils.getField(controller, "merchantAccessService");
        ICmsTopicService topicService = (ICmsTopicService)
                ReflectionTestUtils.getField(controller, "cmsTopicService");
        when(merchantAccessService.resolve(VIEWER)).thenReturn(new MerchantAccessContext(
                VIEWER, 77L, 901L, MerchantRoleCode.MERCHANT_CHECKIN));

        AjaxResult res = controller.topicInfoToMerchant("300");

        assertEquals(com.chengyinhub.common.constant.HttpStatus.FORBIDDEN, res.get("code"));
        verify(topicService, never()).selectMerchantVisibleCmsTopicById(anyLong());
    }

    @Test
    void topic_info_to_merchant_keepsDisabledMemberGateAheadOfMerchantAccess() {
        ApiTopicController controller = topicControllerWithSecretNode();
        IUmsMemberService memberService = (IUmsMemberService)
                ReflectionTestUtils.getField(controller, "umsMemberService");
        IMerchantAccessService merchantAccessService = (IMerchantAccessService)
                ReflectionTestUtils.getField(controller, "merchantAccessService");
        UmsMember disabled = new UmsMember();
        disabled.setId(VIEWER);
        disabled.setStatus(0L);
        disabled.setUserType(1L);
        when(memberService.selectUmsMemberById(VIEWER)).thenReturn(disabled);

        AjaxResult res = controller.topicInfoToMerchant("300");

        assertEquals("会员已禁用，请联系管理员", res.get("msg"));
        verify(merchantAccessService, never()).resolve(anyLong());
    }

    private ApiActivityController activityControllerForMinimalDetail(ICmsActivityService activityService) {
        ApiActivityController controller = spy(new ApiActivityController());
        // release-0917 集成:rule-merchant-0917 给 info 接口加了 @Autowired 营业状态依赖(#10),手工装配须补上;mock 默认 false=营业中。
        ReflectionTestUtils.setField(controller, "merchantBusinessStatusService", mock(com.chengyinhub.business.service.IMerchantBusinessStatusService.class));
        doReturn(VIEWER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(controller, "memberTemplateService", tplSvc);
        ReflectionTestUtils.setField(controller, "clubMemberService", mock(IClubMemberService.class));
        ReflectionTestUtils.setField(controller, "viewCollaboratorsService", mock(IViewCollaboratorsService.class));
        ReflectionTestUtils.setField(controller, "commentService", mock(IViewCommentService.class));
        ReflectionTestUtils.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
        ReflectionTestUtils.setField(controller, "viewRegistrationService", mock(IViewRegistrationService.class));
        ReflectionTestUtils.setField(controller, "sysCategoryService", mock(ISysCategoryService.class));
        ReflectionTestUtils.setField(controller, "tmsBrowseRecordService", mock(ITmsBrowseRecordService.class));
        ReflectionTestUtils.setField(controller, "umsCommentService", mock(IUmsCommentService.class));
        CmsRegistrationStatisticsMapper statisticsMapper = mock(CmsRegistrationStatisticsMapper.class);
        when(statisticsMapper.statisticsSalesAmoutByOwner(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "cmsRegistrationStatisticsMapper", statisticsMapper);
        when(tplSvc.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.emptyList());
        return controller;
    }

    private ApiTopicController topicControllerWithEmptyDetailDependencies(ICmsTopicService topicService) {
        ApiTopicController controller = topicControllerWithSecretNode();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        return controller;
    }

    private ApiTopicController topicControllerWithSecretNode() {
        return topicControllerWithSecretNode(TopicRouteState.LINEAR);
    }

    private ApiTopicController topicControllerWithSecretNode(String routeMode) {
        return topicControllerWithSecretNode(routeMode, false, false);
    }

    private ApiTopicController topicControllerWithSecretNode(String routeMode, boolean includeLockedChapter,
                                                             boolean hasStoryAccess) {
        return topicControllerWithSecretNode(routeMode, includeLockedChapter, hasStoryAccess, null);
    }

    /**
     * @param ownFulfilledNodeId 当前 VIEWER 已中标承接的 nodeId;null = VIEWER 不是本主题的承接方。
     */
    private ApiTopicController topicControllerWithSecretNode(String routeMode, boolean includeLockedChapter,
                                                             boolean hasStoryAccess, Long ownFulfilledNodeId) {
        ICmsTopicService topicSvc = mock(ICmsTopicService.class);
        ICmsTopicChapterService chapterSvc = mock(ICmsTopicChapterService.class);
        ICmsTopicNodeService nodeSvc = mock(ICmsTopicNodeService.class);
        ICmsMemberTemplateService memberTplSvc = mock(ICmsMemberTemplateService.class);
        ICmsRegistrationMerchantService merchantRegSvc = mock(ICmsRegistrationMerchantService.class);
        IUmsMemberService memberSvc = mock(IUmsMemberService.class);

        ApiTopicController c = spy(new ApiTopicController());
        // release-0917 集成:rule-merchant-0917 给 info 接口加了 @Autowired 营业状态依赖(#10),手工装配须补上;mock 默认 false=营业中。
        ReflectionTestUtils.setField(c, "merchantBusinessStatusService", mock(com.chengyinhub.business.service.IMerchantBusinessStatusService.class));
        doReturn(VIEWER).when(c).getAppUserId();
        ReflectionTestUtils.setField(c, "cmsTopicService", topicSvc);
        ReflectionTestUtils.setField(c, "cmsTopicChapterService", chapterSvc);
        ReflectionTestUtils.setField(c, "cmsTopicNodeService", nodeSvc);
        ReflectionTestUtils.setField(c, "memberTemplateService", memberTplSvc);
        ReflectionTestUtils.setField(c, "cmsRegistrationMerchantService", merchantRegSvc);
        ReflectionTestUtils.setField(c, "umsMemberService", memberSvc);
        IMerchantAccessService merchantAccessService = mock(IMerchantAccessService.class);
        MerchantAccessContext merchantAccess = new MerchantAccessContext(
                VIEWER, 77L, VIEWER, MerchantRoleCode.MERCHANT_OWNER);
        when(merchantAccessService.require(VIEWER, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(merchantAccess);
        when(merchantAccessService.resolve(VIEWER)).thenReturn(merchantAccess);
        ReflectionTestUtils.setField(c, "merchantAccessService", merchantAccessService);
        ReflectionTestUtils.setField(c, "cmsTopicLikeService", mock(ICmsTopicLikeService.class));
        ReflectionTestUtils.setField(c, "umsMemberFollowService", mock(IUmsMemberFollowService.class));
        ReflectionTestUtils.setField(c, "umsCommentService", mock(IUmsCommentService.class));
        ReflectionTestUtils.setField(c, "omsTicketService", mock(IOmsTicketService.class));
        ITopicRouteService routeService = mock(ITopicRouteService.class);
        when(routeService.buildGeometryJson(any())).thenReturn("secret-route-geometry");
        ReflectionTestUtils.setField(c, "topicRouteService", routeService);
        ReflectionTestUtils.setField(c, "mmsMerchantService", mock(IMmsMerchantService.class));
        ReflectionTestUtils.setField(c, "viewCollaboratorsService", mock(IViewCollaboratorsService.class));
        ReflectionTestUtils.setField(c, "commentService", mock(IViewCommentService.class));
        ReflectionTestUtils.setField(c, "viewRegistrationService", mock(IViewRegistrationService.class));
        ReflectionTestUtils.setField(c, "cmsActivityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(c, "playerNodeProgressService", mock(IPlayerNodeProgressService.class));
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        when(registrationMapper.hasActiveTopicStoryAccess(300L, VIEWER)).thenReturn(hasStoryAccess);
        ReflectionTestUtils.setField(c, "cmsRegistrationMapper", registrationMapper);
        // 章节招商统计:本类不测名额,给个空 mock(返回空 Map ⇒ 聚合得 null ⇒ 不下发角标)。
        ReflectionTestUtils.setField(c, "merchantChapterApplicationService",
                mock(com.chengyinhub.business.service.IMerchantChapterApplicationService.class));
        // 「来源」投影:本类不测它,给个空 mock 即可(project 返回 null/原表都不影响剥离断言)。
        ReflectionTestUtils.setField(c, "topicContentPublisherProjectionService",
                mock(com.chengyinhub.business.service.ITopicContentPublisherProjectionService.class));
        // 批量装饰 mapper:本类仅 topicListToMerchant 触发,用空清单避免 NPE。
        CmsTopicLikeMapper tlm = mock(CmsTopicLikeMapper.class);
        when(tlm.selectCmsTopicLikesByMemberAndDataIds(any(), any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(c, "cmsTopicLikeMapper", tlm);
        UmsMemberFollowMapper mfm = mock(UmsMemberFollowMapper.class);
        when(mfm.selectUmsMemberFollowsByMemberAndFollowMemberIds(any(), any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(c, "umsMemberFollowMapper", mfm);
        OmsTicketMapper otm = mock(OmsTicketMapper.class);
        when(otm.selectMinTicketPrices(any(), any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(c, "omsTicketMapper", otm);
        UmsCommentMapper ucm = mock(UmsCommentMapper.class);
        when(ucm.selectRatingSummaryByOwners(any(), any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(c, "umsCommentMapper", ucm);

        CmsTopic topic = new CmsTopic();
        topic.setId(300L);
        topic.setLocationCount(8L); // Refreshed full-route scalar; viewer projection remains separate.
        topic.setMemberId(OTHER_OWNER);
        topic.setDelFlag(0);
        topic.setStatus(1);
        topic.setMerchantStatus(1);
        topic.setProductType(1);
        topic.setRouteMode(TopicRouteState.BRANCH_GRAPH.equals(routeMode)
                ? TopicRouteState.LINEAR : TopicRouteState.BRANCH_GRAPH);
        topic.setPublishedRouteGraphJson(TopicRouteState.BRANCH_GRAPH.equals(routeMode)
                ? "{\"schemaVersion\":1}" : null);
        when(topicSvc.selectCmsTopicById(300L)).thenReturn(topic);
        when(topicSvc.selectCmsTopicDetailById(300L, VIEWER)).thenReturn(topic);
        when(topicSvc.selectMerchantVisibleCmsTopicById(300L)).thenReturn(topic);
        when(topicSvc.selectCmsTopicList(any())).thenReturn(Collections.singletonList(topic));

        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(301L);
        chapter.setDescription("第一章公开剧情");
        chapter.setBlocks(Collections.<Object>singletonList("secret-chapter-block"));
        chapter.setBlocksJson("secret-blocks-json");
        if (includeLockedChapter) {
            CmsTopicChapter lockedChapter = new CmsTopicChapter();
            lockedChapter.setId(303L);
            lockedChapter.setDescription(LOCKED_CHAPTER_DESCRIPTION);
            lockedChapter.setBlocks(Collections.<Object>singletonList("locked-secret-block"));
            lockedChapter.setBlocksJson("locked-secret-blocks-json");
            when(chapterSvc.selectCmsTopicChapterList(any())).thenReturn(Arrays.asList(chapter, lockedChapter));
        } else {
            when(chapterSvc.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
        }

        CmsTopicNode node = new CmsTopicNode();
        node.setId(302L);
        node.setTemplateId(TPL);
        when(nodeSvc.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        // 每次查库模拟独立实体，避免商家附带模板的 strip 意外修改节点模板，制造假绿。
        when(memberTplSvc.selectCmsMemberTemplateById(TPL)).thenAnswer(invocation -> memberTplWithSecrets(OTHER_OWNER));

        CmsRegistrationMerchant merchantRegistration = new CmsRegistrationMerchant();
        merchantRegistration.setMemberId(OTHER_OWNER);
        merchantRegistration.setTemplateId(TPL);
        merchantRegistration.setQuestionAnswer("商家暗号答案");
        merchantRegistration.setCorrectAnswer("C");
        merchantRegistration.setQuestionA("选项A");
        merchantRegistration.setQuestionB("选项B");
        merchantRegistration.setQuestionC("选项C");
        merchantRegistration.setQuestionD("选项D");
        // 这个 mock 服务被 topicInfo 里**两条**不同的查询共用,必须按探针分流:
        //   ① 节点上「承接了这个模板的商家」——用 templateId/nodeId 查,要返回上面那条;
        //   ② selectOwnFulfillments「当前用户自己的中标承接单」——用 memberId + auditStatus=1 查。
        // 装置里的 VIEWER 不是承接方,② 必须返回空。不分流的话 ② 会拿到 ① 的数据,
        // 当前用户被当成承接方 → story 全解锁,「未购票只给第一章」那条断言就成了假绿。
        when(merchantRegSvc.selectCmsRegistrationMerchantList(any())).thenAnswer(invocation -> {
            CmsRegistrationMerchant probe = invocation.getArgument(0);
            if (probe != null && Long.valueOf(1L).equals(probe.getAuditStatus())) {
                if (ownFulfilledNodeId == null) return Collections.emptyList();
                CmsRegistrationMerchant own = new CmsRegistrationMerchant();
                own.setMemberId(VIEWER);
                own.setNodeId(ownFulfilledNodeId);
                return Collections.singletonList(own);
            }
            return Collections.singletonList(merchantRegistration);
        });

        UmsMember merchant = new UmsMember();
        merchant.setId(VIEWER);
        merchant.setStatus(1L);
        merchant.setUserType(1L); // 旧兼容字段不能否决真实商家角色
        merchant.setRole("merchant");
        when(memberSvc.selectUmsMemberById(anyLong())).thenReturn(merchant);
        return c;
    }

    @SuppressWarnings("unchecked")
    private TopicNodeVO firstTopicNode(AjaxResult res) {
        Object data = res.get("data");
        List<TopicChapterVO> chapters;
        if (data instanceof TopicInfoVO) {
            chapters = ((TopicInfoVO) data).getChaptersList();
        } else {
            TableDataInfo table = (TableDataInfo) data;
            Object row = table.getRows().get(0);
            chapters = row instanceof TopicInfoVO
                    ? ((TopicInfoVO) row).getChaptersList()
                    : ((CmsTopic) row).getChaptersList();
        }
        return chapters.get(0).getNodes().get(0);
    }

    private void assertTopicNodeSecretsStripped(TopicNodeVO node, boolean withMerchantRegistration) {
        assertMemberTemplateSecretsStripped(node.getCmsMemberTemplate());
        if (withMerchantRegistration) {
            PublicMerchantRegistrationVO merchantRegistration = node.getRegistrationMerchantList().get(0);
            assertEquals(OTHER_OWNER, merchantRegistration.getMemberId());
            assertNotNull(merchantRegistration.getMmsMerchant());
        }
    }

    private void assertMemberTemplateSecretsStripped(CmsMemberTemplate template) {
        assertNull(template.getQuestionAnswer());
        assertNull(template.getCorrectAnswer());
        assertNull(template.getHint1());
        assertNull(template.getHint2());
        assertNull(template.getAnswerReveal());
    }

    private CmsTemplateLibrary publicLibraryTemplate() {
        CmsTemplateLibrary template = new CmsTemplateLibrary();
        template.setId(TPL);
        template.setMemberId(OTHER_OWNER);
        template.setDelFlag(0);
        template.setStatus(1L);
        template.setPublishStatus(1L);
        template.setValidationMethod(1);
        return template;
    }

    private void assertLibraryTemplateSecretsStripped(CmsTemplateLibrary template) {
        assertNull(template.getQuestionAnswer());
        assertNull(template.getCorrectAnswer());
        assertNull(template.getHint1());
        assertNull(template.getHint2());
        assertNull(template.getAnswerReveal());
    }
}

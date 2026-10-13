package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.ViewRegistrationMerchant;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationScanService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IMerchantRegistrationDetailReadService;
import com.chengyinhub.business.service.IViewRegistrationMerchantService;
import com.chengyinhub.business.service.impl.MerchantRegistrationDetailReadServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * E3 哨兵报名详情 NPE 防护。
 *
 * 病:node_id ∈ {null, 0} 是「没有节点」的哨兵(mode=2 主题无有效节点时的报名会落它,口径见
 * ApiCoopController.hasNodeSlot)。getRegistrationDetail 拿它直接 selectCmsTopicNodeById → 返 null
 * → getTemplateId() 必 NPE → 被方法尾部的 catch 吞成 code=500 / msg=null(NPE.getMessage() 为 null)
 * ⇒ 商家点详情白屏,且拿不到任何可读错因。
 *
 * ⚠️ 负控已实测:把守卫改回 `cmsTopicNodeService.selectCmsTopicNodeById(registration.getNodeId())`
 *    直接解引用 ⇒ sentinelZero_returnsDetailWithoutNode / nullNodeId_returnsDetailWithoutNode 双红
 *    (msg=null,code=500)。
 */
class ApiRegistrationMerchantSentinelDetailTest {

    private static final Long MEMBER = 88L;
    private static final Long OTHER_MEMBER = 89L;
    private static final Long REG = 900L;
    private static final Long TOPIC = 300L;
    private static final Long NODE = 401L;
    private static final Long TEMPLATE = 501L;
    private static final Long AUDIT_PENDING = 0L;
    private static final Long AUDIT_ASSIGNED = 1L;
    private static final Long AUDIT_REJECTED = 2L;
    private static final int HTTP_OK = 200;
    private static final String DESCRIPTION = "找到吧台的暗号";
    private static final String PLAYERS = "2-6人";
    private static final Long DURATION = 15L;
    private static final String DIFFICULTY = "简单";
    private static final String USAGE_LOCATION = "店内";
    private static final String REQUIRED_MATERIALS = "线索卡 20 张";
    private static final String RULE_INSTRUCTIONS = "一次只发一张";
    private static final String STORY_TEXT = "老板留下了一句话";
    private static final String STORY_IMG = "story.png";
    private static final String AUDIO_URL = "story.mp3";
    private static final Integer VALIDATION_METHOD = 1;
    private static final String PHOTO_REQUIRE_DESC = "拍下暗号卡";
    private static final String QUESTION_ANSWER = "暗号是霓虹";
    private static final String CORRECT_ANSWER = "A";
    private static final String HINT_1 = "看灯";
    private static final String HINT_2 = "看明灭";
    private static final String ANSWER_REVEAL = "答案解析";

    private IViewRegistrationMerchantService viewSvc;
    private ICmsTopicNodeService nodeSvc;
    private ICmsMemberTemplateService templateSvc;
    private ICmsRegistrationMerchantService merchantSvc;
    private ApiRegistrationMerchantController controller;

    @BeforeEach
    void setUp() {
        viewSvc = mock(IViewRegistrationMerchantService.class);
        nodeSvc = mock(ICmsTopicNodeService.class);
        templateSvc = mock(ICmsMemberTemplateService.class);
        ICmsRegistrationService regSvc = mock(ICmsRegistrationService.class);
        merchantSvc = mock(ICmsRegistrationMerchantService.class);
        ICmsTopicChapterService chapterSvc = mock(ICmsTopicChapterService.class);
        ICmsRegistrationScanService scanSvc = mock(ICmsRegistrationScanService.class);
        IMerchantAccessService merchantAccessService = mock(IMerchantAccessService.class);

        when(regSvc.selectCmsRegistrationList(any())).thenReturn(Collections.<CmsRegistration>emptyList());
        when(merchantAccessService.require(MEMBER, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(MEMBER, 1L, MEMBER,
                        MerchantRoleCode.MERCHANT_OWNER));

        controller = spy(new ApiRegistrationMerchantController());
        doReturn(MEMBER).when(controller).getAppUserId();
        IMerchantRegistrationDetailReadService detailReadService = new MerchantRegistrationDetailReadServiceImpl(
                viewSvc, merchantSvc, nodeSvc, templateSvc, chapterSvc, regSvc, scanSvc);
        ReflectionTestUtils.setField(controller, "merchantRegistrationDetailReadService", detailReadService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
    }

    private void givenRegistrationWithNode(Long nodeId) {
        ViewRegistrationMerchant v = new ViewRegistrationMerchant();
        v.setId(REG);
        v.setMemberId(MEMBER);
        v.setTopicId(TOPIC);
        v.setNodeId(nodeId);
        when(viewSvc.selectViewRegistrationMerchantById(REG)).thenReturn(v);
    }

    @SuppressWarnings("unchecked")
    private ViewRegistrationMerchant dataOf(AjaxResult r) {
        return (ViewRegistrationMerchant) r.get("data");
    }

    /** 哨兵 0:必须正常返回详情,节点相关字段留空 —— 不是 500。 */
    @Test
    @Timeout(5)
    void sentinelZero_returnsDetailWithoutNode() {
        givenRegistrationWithNode(0L);

        AjaxResult r = controller.getRegistrationDetail(String.valueOf(REG));

        assertEquals(200, r.get("code"), "哨兵报名详情不得 500;实际 msg=" + r.get("msg"));
        assertNull(dataOf(r).getNodeName(), "没有节点就不该有节点名");
        assertNull(dataOf(r).getTemplateName(), "没有节点就不该有模板名");
    }

    /** 哨兵 null:同上(#63 归一后新数据落 NULL,存量仍可能是 0,两种都得扛)。 */
    @Test
    @Timeout(5)
    void nullNodeId_returnsDetailWithoutNode() {
        givenRegistrationWithNode(null);

        AjaxResult r = controller.getRegistrationDetail(String.valueOf(REG));

        assertEquals(200, r.get("code"), "哨兵报名详情不得 500;实际 msg=" + r.get("msg"));
        assertNull(dataOf(r).getNodeName());
    }

    /** 真实节点:字段照常回填,守卫不得把正常路径改坏。 */
    @Test
    @Timeout(5)
    void realNode_populatesNodeAndTemplate() {
        givenRegistrationWithNode(NODE);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setName("第三个点");
        node.setTemplateId(TEMPLATE);
        when(nodeSvc.selectCmsTopicNodeById(NODE)).thenReturn(node);

        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setId(TEMPLATE);
        tpl.setTitle("咖啡馆模板");
        when(templateSvc.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(tpl);

        AjaxResult r = controller.getRegistrationDetail(String.valueOf(REG));

        assertEquals(200, r.get("code"));
        assertNotNull(dataOf(r));
        assertEquals("第三个点", dataOf(r).getNodeName());
        assertEquals("咖啡馆模板", dataOf(r).getTemplateName());
        assertEquals(TEMPLATE, dataOf(r).getTemplateId());
    }

    /**
     * 承接详情下发完整玩法;核销执行商家还必须看到答案和提示。
     */
    @Test
    @Timeout(5)
    void templateDetail_carriesPlayFieldsAndSecretsForAssignedMerchant() {
        givenTemplateDetail(AUDIT_ASSIGNED);

        AjaxResult r = controller.getRegistrationDetail(String.valueOf(REG));

        assertEquals(HTTP_OK, r.get("code"));
        CmsMemberTemplate out = dataOf(r).getTemplateDetail();
        assertNotNull(out, "商家要看到玩法详情,不能只给一个模板名");
        assertEquals(DESCRIPTION, out.getDescription());
        assertEquals(PLAYERS, out.getPlayers());
        assertEquals(DURATION, out.getDuration());
        assertEquals(DIFFICULTY, out.getDifficulty());
        assertEquals(USAGE_LOCATION, out.getUsageLocation());
        assertEquals(REQUIRED_MATERIALS, out.getRequiredMaterials(), "准备物品是商家开场前唯一的清单");
        assertEquals(RULE_INSTRUCTIONS, out.getRuleInstructions());
        assertEquals(STORY_TEXT, out.getStoryText());
        assertEquals(STORY_IMG, out.getStoryImg());
        assertEquals(AUDIO_URL, out.getAudioUrl());
        assertEquals(VALIDATION_METHOD, out.getValidationMethod());
        assertEquals(PHOTO_REQUIRE_DESC, out.getPhotoRequireDesc());
        assertSecretsPresent(out);
    }

    @Test
    @Timeout(5)
    void pendingMerchantCanSeePlayFieldsButNotSecrets() {
        assertUnassignedMerchantCanSeePlayFieldsButNotSecrets(AUDIT_PENDING);
    }

    @Test
    @Timeout(5)
    void rejectedMerchantCanSeePlayFieldsButNotSecrets() {
        assertUnassignedMerchantCanSeePlayFieldsButNotSecrets(AUDIT_REJECTED);
    }

    private void assertUnassignedMerchantCanSeePlayFieldsButNotSecrets(Long auditStatus) {
        givenTemplateDetail(auditStatus);

        CmsMemberTemplate out = dataOf(controller.getRegistrationDetail(String.valueOf(REG)))
                .getTemplateDetail();

        assertEquals(DESCRIPTION, out.getDescription());
        assertNull(out.getQuestionAnswer(), "auditStatus=" + auditStatus);
        assertNull(out.getCorrectAnswer(), "auditStatus=" + auditStatus);
        assertNull(out.getHint1(), "auditStatus=" + auditStatus);
        assertNull(out.getHint2(), "auditStatus=" + auditStatus);
        assertNull(out.getAnswerReveal(), "auditStatus=" + auditStatus);
    }

    private void givenTemplateDetail(Long auditStatus) {
        givenRegistrationWithNode(NODE);
        CmsRegistrationMerchant merchant = new CmsRegistrationMerchant();
        merchant.setAuditStatus(auditStatus);
        when(merchantSvc.selectCmsRegistrationMerchantById(REG)).thenReturn(merchant);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setName("第三个点");
        node.setTemplateId(TEMPLATE);
        when(nodeSvc.selectCmsTopicNodeById(NODE)).thenReturn(node);

        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setId(TEMPLATE);
        tpl.setTitle("咖啡馆模板");
        tpl.setDescription(DESCRIPTION);
        tpl.setPlayers(PLAYERS);
        tpl.setDuration(DURATION);
        tpl.setDifficulty(DIFFICULTY);
        tpl.setUsageLocation(USAGE_LOCATION);
        tpl.setRequiredMaterials(REQUIRED_MATERIALS);
        tpl.setRuleInstructions(RULE_INSTRUCTIONS);
        tpl.setStoryText(STORY_TEXT);
        tpl.setStoryImg(STORY_IMG);
        tpl.setAudioUrl(AUDIO_URL);
        tpl.setValidationMethod(VALIDATION_METHOD);
        tpl.setPhotoRequireDesc(PHOTO_REQUIRE_DESC);
        tpl.setQuestionAnswer(QUESTION_ANSWER);
        tpl.setCorrectAnswer(CORRECT_ANSWER);
        tpl.setHint1(HINT_1);
        tpl.setHint2(HINT_2);
        tpl.setAnswerReveal(ANSWER_REVEAL);
        when(templateSvc.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(tpl);
    }

    private void assertSecretsPresent(CmsMemberTemplate out) {
        assertEquals(QUESTION_ANSWER, out.getQuestionAnswer());
        assertEquals(CORRECT_ANSWER, out.getCorrectAnswer());
        assertEquals(HINT_1, out.getHint1());
        assertEquals(HINT_2, out.getHint2());
        assertEquals(ANSWER_REVEAL, out.getAnswerReveal());
    }

    /** 第二颗雷:节点在但模板被删 ⇒ 仍不得 NPE。 */
    @Test
    @Timeout(5)
    void nodeWithMissingTemplate_returnsDetailWithoutTemplateName() {
        givenRegistrationWithNode(NODE);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setName("第三个点");
        node.setTemplateId(TEMPLATE);
        when(nodeSvc.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(templateSvc.selectCmsMemberTemplateById(anyLong())).thenReturn(null);   // 模板已删

        AjaxResult r = controller.getRegistrationDetail(String.valueOf(REG));

        assertEquals(200, r.get("code"), "模板缺失不得 500;实际 msg=" + r.get("msg"));
        assertEquals("第三个点", dataOf(r).getNodeName(), "节点字段仍应回填");
        assertNull(dataOf(r).getTemplateName());
    }

    /** 报名不存在仍走既有分支,守卫没把它挤掉。 */
    @Test
    @Timeout(5)
    void missingRegistration_returnsReadableError() {
        when(viewSvc.selectViewRegistrationMerchantById(anyLong())).thenReturn(null);

        AjaxResult r = controller.getRegistrationDetail(String.valueOf(REG));

        assertEquals("报名记录不存在", r.get("msg"));
    }

    /** 旧报名详情含地址与答案，只能由该报名商家本人读取。 */
    @Test
    void anotherMerchantCannotReadRegistrationDetail() {
        ViewRegistrationMerchant v = new ViewRegistrationMerchant();
        v.setId(REG);
        v.setMemberId(OTHER_MEMBER);
        v.setTopicId(TOPIC);
        when(viewSvc.selectViewRegistrationMerchantById(REG)).thenReturn(v);

        AjaxResult r = controller.getRegistrationDetail(String.valueOf(REG));

        assertEquals(500, r.get("code"));
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(r.get("msg")).contains("无权"));
        verifyNoInteractions(nodeSvc, templateSvc);
    }
}

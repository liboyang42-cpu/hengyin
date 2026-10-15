package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.RegistrationMerchantDTO;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 旧报名表只服务经典定向：自由探索不得绕过章节申请，节点必须属于主题。
 *
 * 病:旧 `/create` 接口只按主题级字段落库。自由探索商家能带 mode=2 绕过 chapter-application，
 * 经典定向也能直传其他主题的 node/template。这样章节的品类、审核与容量闸都形同虚设。
 *
 * ⚠️ 本用例走【真实 createRegistration 路径】断言没有写入旧报名表，而不是只测私有 helper。
 */
class ApiRegistrationMerchantNodeIdTest {

    private static final Long MEMBER = 88L;
    private static final Long TOPIC = 200L;
    private static final Long NODE = 400L;
    private static final Long OTHER_NODE = 401L;

    private ICmsRegistrationMerchantService regSvc;
    private ICmsTopicNodeService nodeSvc;
    private IMerchantAccessService accessSvc;
    private CmsTopic topic;
    private UmsMember merchant;

    private ApiRegistrationMerchantController controller() {
        regSvc = mock(ICmsRegistrationMerchantService.class);
        IUmsMemberService memberSvc = mock(IUmsMemberService.class);
        ICmsTopicService topicSvc = mock(ICmsTopicService.class);
        nodeSvc = mock(ICmsTopicNodeService.class);
        accessSvc = mock(IMerchantAccessService.class);
        when(accessSvc.require(MEMBER, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(
                        MEMBER, 11L, MEMBER, MerchantRoleCode.MERCHANT_OWNER));

        merchant = new UmsMember();
        merchant.setRole("merchant");
        merchant.setUserType(1L); // 兼容字段不是权限真源
        when(memberSvc.selectUmsMemberById(MEMBER)).thenReturn(merchant);

        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setStatus(1);                             // 已过审
        topic.setMerchantStatus(1);                     // 已上架
        topic.setProductType(ProductType.ORIENTEERING);
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(topic);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        node.setTemplateId(700L);
        when(nodeSvc.selectCmsTopicNodeById(NODE)).thenReturn(node);

        // 查重:无既有报名
        when(regSvc.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.emptyList());

        ApiRegistrationMerchantController c = spy(new ApiRegistrationMerchantController());
        doReturn(MEMBER).when(c).getAppUserId();
        ReflectionTestUtils.setField(c, "cmsRegistrationMerchantService", regSvc);
        ReflectionTestUtils.setField(c, "umsMemberService", memberSvc);
        ReflectionTestUtils.setField(c, "cmsTopicService", topicSvc);
        ReflectionTestUtils.setField(c, "cmsTopicNodeService", nodeSvc);
        ReflectionTestUtils.setField(c, "merchantAccessService", accessSvc);
        return c;
    }

    @Test
    void compatibilityMerchantTypeCannotRegrantOwnerWithPlayerRole() {
        ApiRegistrationMerchantController c = controller();
        merchant.setRole("player"); merchant.setUserType(2L);
        AjaxResult result = c.createRegistration(param(NODE, ProductType.ORIENTEERING));
        assertEquals("商家才能报名", result.get("msg"));
        verify(regSvc, never()).insertCmsRegistrationMerchant(any());
    }

    @Test
    void inactiveMerchantCannotCreateRegistration() {
        ApiRegistrationMerchantController c = controller();
        when(accessSvc.require(MEMBER, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));

        AjaxResult result = c.createRegistration(param(NODE, ProductType.ORIENTEERING));

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(regSvc, never()).insertCmsRegistrationMerchant(any());
    }

    private RegistrationMerchantDTO param(Long nodeId, Integer mode) {
        RegistrationMerchantDTO p = new RegistrationMerchantDTO();
        p.setTopicId(TOPIC);
        p.setMode(mode);
        p.setNodeId(nodeId);
        return p;
    }

    private void givenExistingRegistrationAt(Long existingNodeId) {
        CmsRegistrationMerchant existing = new CmsRegistrationMerchant();
        existing.setMemberId(MEMBER);
        existing.setTopicId(TOPIC);
        existing.setNodeId(existingNodeId);
        when(regSvc.selectCmsRegistrationMerchantList(any())).thenAnswer(invocation -> {
            CmsRegistrationMerchant query = invocation.getArgument(0);
            return query.getNodeId() == null || existingNodeId.equals(query.getNodeId())
                    ? Collections.singletonList(existing)
                    : Collections.emptyList();
        });
    }

    /** 落库实体的 nodeId。 */
    private Long insertedNodeId() {
        ArgumentCaptor<CmsRegistrationMerchant> captor = ArgumentCaptor.forClass(CmsRegistrationMerchant.class);
        verify(regSvc).insertCmsRegistrationMerchant(captor.capture());
        return captor.getValue().getNodeId();
    }

    /** 自由探索的商家不能再写旧报名表，必须改走 chapter-application。 */
    @Test
    void freeExploreLegacyRegistrationRejected() {
        ApiRegistrationMerchantController c = controller();
        topic.setProductType(ProductType.FREE_EXPLORE);

        AjaxResult result = c.createRegistration(param(NODE, ProductType.FREE_EXPLORE));

        assertEquals(500, result.get("code"));
        verify(regSvc, never()).insertCmsRegistrationMerchant(any());
    }

    /** mode=1 城市定向:真实节点 ID 原样落库,不得被归一动过。 */
    @Test
    void realNodeIdPassedThrough() {
        ApiRegistrationMerchantController c = controller();
        c.createRegistration(param(NODE, 1));
        assertEquals(NODE, insertedNodeId(), "真实节点 ID 必须原样落库");
    }

    /** 同一商家可报名同一主题的不同节点，是否都通过由主办方审核决定。 */
    @Test
    void merchantCanRegisterDifferentNodesInSameTopic() {
        ApiRegistrationMerchantController c = controller();
        givenExistingRegistrationAt(OTHER_NODE);

        AjaxResult result = c.createRegistration(param(NODE, ProductType.ORIENTEERING));

        assertEquals(200, result.get("code"), result.toString());
        verify(regSvc).insertCmsRegistrationMerchant(any());
    }

    /** 同一商家重复报名同一主题的同一节点仍必须被拒。 */
    @Test
    void merchantCannotRegisterSameNodeTwice() {
        ApiRegistrationMerchantController c = controller();
        givenExistingRegistrationAt(NODE);

        AjaxResult result = c.createRegistration(param(NODE, ProductType.ORIENTEERING));

        assertEquals(500, result.get("code"));
        verify(regSvc, never()).insertCmsRegistrationMerchant(any());
    }

    /** 旧入口只服务经典定向，不能用 mode=2 伪造无节点的主题级报名。 */
    @Test
    void legacyRegistrationRejectsWrongMode() {
        ApiRegistrationMerchantController c = controller();
        AjaxResult result = c.createRegistration(param(NODE, ProductType.FREE_EXPLORE));

        assertEquals(500, result.get("code"));
        verify(regSvc, never()).insertCmsRegistrationMerchant(any());
    }

    /** 经典定向仍必须给出属于该主题的节点；不能把空节点落成哨兵。 */
    @Test
    void legacyRegistrationRejectsMissingNode() {
        ApiRegistrationMerchantController c = controller();
        AjaxResult result = c.createRegistration(param(null, ProductType.ORIENTEERING));

        assertEquals(500, result.get("code"));
        verify(regSvc, never()).insertCmsRegistrationMerchant(any());
    }

    /** 报名字段是自然日，结束日当天不能在 00:00 后被提前拒绝。 */
    @Test
    void signupEndDateIncludesItsCalendarDay() {
        ApiRegistrationMerchantController c = controller();
        java.util.Calendar today = java.util.Calendar.getInstance();
        today.set(java.util.Calendar.HOUR_OF_DAY, 0);
        today.set(java.util.Calendar.MINUTE, 0);
        today.set(java.util.Calendar.SECOND, 0);
        today.set(java.util.Calendar.MILLISECOND, 0);
        topic.setMerchantSignUpEndDate(today.getTime());

        AjaxResult result = c.createRegistration(param(NODE, ProductType.ORIENTEERING));

        assertEquals(200, result.get("code"), result.toString());
        verify(regSvc).insertCmsRegistrationMerchant(any());
    }

    /** 报名字段是自然日，开始日当天不能被非零时间误判为“未开始”。 */
    @Test
    void signupStartDateIncludesItsCalendarDay() {
        ApiRegistrationMerchantController c = controller();
        java.util.Calendar today = java.util.Calendar.getInstance();
        today.set(java.util.Calendar.HOUR_OF_DAY, 23);
        today.set(java.util.Calendar.MINUTE, 59);
        today.set(java.util.Calendar.SECOND, 59);
        today.set(java.util.Calendar.MILLISECOND, 999);
        topic.setMerchantSignUpStartDate(today.getTime());

        AjaxResult result = c.createRegistration(param(NODE, ProductType.ORIENTEERING));

        assertEquals(200, result.get("code"), result.toString());
        verify(regSvc).insertCmsRegistrationMerchant(any());
    }

    /**
     * ★不能报名自己发布的主题。
     *
     * 另外三条承接路径(俱乐部合作池 / 商家章节申请 / 发邀约)都有这道闸,
     * 只有这条报名路径漏了 —— 不是"有意允许自接",是漏。
     * 实害:审的人就是被审的人(候选池确认由主题 owner 操作),自审必过,
     * 等于绕过审核给自己中标 + 占掉一个站点名额。
     *
     * 必须断言文案指明是这道闸 —— 紧挨着还有上架/过审/窗口期几道同型返回。
     */
    @Test
    void cannotRegisterOwnTopic() {
        ApiRegistrationMerchantController c = controller();
        topic.setMemberId(MEMBER);   // 主题就是我发的

        AjaxResult result = c.createRegistration(param(NODE, ProductType.ORIENTEERING));

        assertEquals(500, result.get("code"));
        assertEquals("不能报名自己发布的主题", result.get("msg"),
                "该红在【自接闸】上,实际:" + result.get("msg"));
        verify(regSvc, org.mockito.Mockito.never()).insertCmsRegistrationMerchant(any());
    }

    /** 负控的另一半:别人的主题必须放行,否则上面那条用「见谁都拒」也能满足。 */
    @Test
    void canRegisterOthersTopic() {
        ApiRegistrationMerchantController c = controller();
        topic.setMemberId(999L);     // 别人发的

        AjaxResult result = c.createRegistration(param(NODE, ProductType.ORIENTEERING));

        assertEquals(200, result.get("code"), result.toString());
        verify(regSvc).insertCmsRegistrationMerchant(any());
    }

    /** 存量脏数据:member_id 为空判不出归属,不能拿它当「是我」而拦掉。 */
    @Test
    void nullOwner_notTreatedAsSelf() {
        ApiRegistrationMerchantController c = controller();
        topic.setMemberId(null);

        AjaxResult result = c.createRegistration(param(NODE, ProductType.ORIENTEERING));

        assertEquals(200, result.get("code"), result.toString());
    }
}

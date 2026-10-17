package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.TopicInfoVO;
import com.chengyinhub.business.mapper.CmsTopicLikeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsCommentMapper;
import com.chengyinhub.business.mapper.UmsMemberFollowMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicLikeService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.ITopicContentPublisherProjectionService;
import com.chengyinhub.business.service.ITopicRouteService;
import com.chengyinhub.business.service.IUmsCommentService;
import com.chengyinhub.business.service.IUmsMemberFollowService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IViewCollaboratorsService;
import com.chengyinhub.business.service.IViewCommentService;
import com.chengyinhub.business.service.IViewRegistrationService;
import com.chengyinhub.common.constant.RoleEntityType;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * `/api/topic/list-to-merchant` 的两条 wire 级行为约束。
 *
 * <p>这个端点是商家招募广场(小程序 pages/merchant/marketing/index.js:128)的唯一数据源。
 * 两件事必须同时成立,而且都只能在**序列化后的 JSON**上验证:
 * <ol>
 *   <li><b>「来源」要到得了 wire</b>。服务层把 CmsTopic 填对了不算数 ——
 *       这个端点最后会把每行按字段名快照成 {@link TopicInfoVO},
 *       VO 上没有的字段会被<b>静默丢弃</b>,服务层单测照样全绿。</li>
 *   <li><b>内部字段不许出现在 JSON 里</b>。抽成率、保底率公式参数、审核驳回理由、
 *       审核状态属于平台内部治理数据,商家端一个都不能看到。</li>
 * </ol>
 *
 * <p>⚠️ 断言 JSON 文本而不是断言 VO 的 getter,是因为只有前者能同时抓住
 * 「字段没进 VO」和「字段进了 VO 但没被投影填上」两种失败。
 */
class ApiTopicListToMerchantPublisherTest {

    private static final Long MERCHANT_VIEWER = 900L;

    // startPage() 走 PageHelper,要从 RequestContextHolder 读分页参数;不绑请求会 NPE。
    @BeforeEach
    void bindRequest() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void unbindRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void publisherReachesTheWireAndInternalFieldsDoNot() throws Exception {
        CmsTopic topic = topicCarryingBothPublisherAndInternalFields();
        ApiTopicController controller =
                controllerReturning(topic, RoleEntityType.CLUB, "夜航俱乐部");

        AjaxResult res = controller.topicListToMerchant();
        assertEquals(200, res.get("code"), res.toString());

        TableDataInfo table = (TableDataInfo) res.get("data");
        List<?> rows = table.getRows();
        assertEquals(1, rows.size());
        // 端点必须产出白名单 VO,不是裸实体 —— 裸实体会把 CmsTopic 上的一切都带出去。
        assertTrue(rows.get(0) instanceof TopicInfoVO,
                "list-to-merchant 必须下发 TopicInfoVO 白名单,不能是裸 CmsTopic;实际: "
                        + rows.get(0).getClass().getName());

        String json = new ObjectMapper().writeValueAsString(rows.get(0));

        // ① 来源必须到得了 wire
        assertTrue(json.contains("\"contentPublisherType\":\"CLUB\""),
                "「来源」类型没到 wire —— 招募广场卡片会恒显无来源。实际: " + json);
        assertTrue(json.contains("\"contentPublisherName\":\"夜航俱乐部\""),
                "「来源」显示名没到 wire。实际: " + json);

        // ② 内部治理字段一个都不许出现
        for (String forbidden : new String[]{
                "feePlatformRate", "feeGuaranteeBaseRate", "feeGuaranteeStepRate",
                "feeGuaranteeMaxMerchant", "reason", "checkStatus", "isSettlement"}) {
            assertFalse(json.contains("\"" + forbidden + "\""),
                    "内部字段 " + forbidden + " 泄给了商家端。实际: " + json);
        }
        // 值层面再兜一道:即使字段改了名,内容也不该出现在 wire 上。
        assertFalse(json.contains("0.15"), "抽成率数值出现在商家端 JSON 里。实际: " + json);
        assertFalse(json.contains("资质不全，驳回"), "审核驳回理由泄给了商家端。实际: " + json);
    }

    /** 无生效 CONTENT 槽时两个字段都不下发 —— 前端必须渲染中性态,不得默认成官方。 */
    @Test
    void topicWithoutContentSlotEmitsNoPublisherFields() throws Exception {
        CmsTopic historical = new CmsTopic();
        historical.setId(2L);
        historical.setName("历史待归属主题");
        // 刻意不设 contentPublisher*,模拟没有生效 CONTENT 槽的存量主题
        ApiTopicController controller = controllerReturning(historical);

        AjaxResult res = controller.topicListToMerchant();
        TableDataInfo table = (TableDataInfo) res.get("data");
        TopicInfoVO row = (TopicInfoVO) table.getRows().get(0);

        assertNull(row.getContentPublisherType(), "无 CONTENT 槽不能猜成任何主体");
        assertNull(row.getContentPublisherName(), "无 CONTENT 槽时来源名必须留空,不得兜底成官方");
    }

    // ====== fixtures ======

    private CmsTopic topicCarryingBothPublisherAndInternalFields() {
        CmsTopic topic = new CmsTopic();
        topic.setId(1L);
        topic.setName("赛博夜巡");
        topic.setDelFlag(0);
        topic.setStatus(1);
        topic.setMerchantStatus(1);
        // ⚠️ 刻意**不**在这里设 contentPublisher* —— 它们只能由投影服务填。
        // 预先设在实体上会让「controller 忘了调投影」这种失败依然通过(实测过,确实假绿)。
        // 内部治理字段 —— 实体上确实有值,正因如此才要证明它们出不去
        topic.setFeePlatformRate(new BigDecimal("0.15"));
        topic.setFeeGuaranteeBaseRate(new BigDecimal("0.05"));
        topic.setReason("资质不全，驳回");
        topic.setCheckStatus(2);
        topic.setIsSettlement(1);
        return topic;
    }

    private ApiTopicController controllerReturning(CmsTopic topic) {
        return controllerReturning(topic, null, null);
    }

    /**
     * 装配一个只跑通 list-to-merchant 主路径的 controller。
     *
     * <p>⚠️ <b>「来源」字段只能由这里 mock 的 project() 写入</b>,fixture 绝不预设。
     * 这样「controller 压根没调 project()」就会让字段永远为 null、测试变红。
     * 反过来,若 fixture 预先把字段设在 CmsTopic 上,拆掉 controller 的投影调用测试照样绿
     * —— 这是实测确认过的假绿形态,别改回去。
     *
     * @param publisherType 传 null 表示模拟「该主题无生效 CONTENT 槽」,投影不写任何字段
     */
    private ApiTopicController controllerReturning(CmsTopic topic,
                                                   String publisherType,
                                                   String publisherName) {
        ICmsTopicService topicSvc = mock(ICmsTopicService.class);
        when(topicSvc.selectCmsTopicList(any())).thenReturn(Collections.singletonList(topic));

        IUmsMemberService memberSvc = mock(IUmsMemberService.class);
        UmsMember merchant = new UmsMember();
        merchant.setId(MERCHANT_VIEWER);
        merchant.setStatus(1L);
        merchant.setUserType(1L); // 旧兼容字段不能否决真实商家角色
        merchant.setRole("merchant");
        when(memberSvc.selectUmsMemberById(anyLong())).thenReturn(merchant);

        // 复刻真实投影的行为:就地把「来源」写进传入的 CmsTopic 列表,并原样返回同一个 List
        // (真实实现也必须返回同一实例,否则 PageHelper 的 total 会丢)。
        ITopicContentPublisherProjectionService publisherProjection =
                mock(ITopicContentPublisherProjectionService.class);
        when(publisherProjection.project(any())).thenAnswer(inv -> {
            List<CmsTopic> passed = inv.getArgument(0);
            if (publisherType != null && passed != null) {
                for (CmsTopic t : passed) {
                    t.setContentPublisherType(publisherType);
                    t.setContentPublisherName(publisherName);
                }
            }
            return passed;
        });

        ApiTopicController c = spy(new ApiTopicController());
        doReturn(MERCHANT_VIEWER).when(c).getAppUserId();
        ReflectionTestUtils.setField(c, "cmsTopicService", topicSvc);
        ReflectionTestUtils.setField(c, "umsMemberService", memberSvc);
        ReflectionTestUtils.setField(c, "topicContentPublisherProjectionService", publisherProjection);
        ReflectionTestUtils.setField(c, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        ReflectionTestUtils.setField(c, "cmsTopicNodeService", mock(ICmsTopicNodeService.class));
        ReflectionTestUtils.setField(c, "memberTemplateService", mock(ICmsMemberTemplateService.class));
        ReflectionTestUtils.setField(c, "cmsRegistrationMerchantService", mock(ICmsRegistrationMerchantService.class));
        ReflectionTestUtils.setField(c, "cmsTopicLikeService", mock(ICmsTopicLikeService.class));
        ReflectionTestUtils.setField(c, "umsMemberFollowService", mock(IUmsMemberFollowService.class));
        ReflectionTestUtils.setField(c, "umsCommentService", mock(IUmsCommentService.class));
        ReflectionTestUtils.setField(c, "omsTicketService", mock(IOmsTicketService.class));
        ReflectionTestUtils.setField(c, "topicRouteService", mock(ITopicRouteService.class));
        ReflectionTestUtils.setField(c, "mmsMerchantService", mock(IMmsMerchantService.class));
        ReflectionTestUtils.setField(c, "viewCollaboratorsService", mock(IViewCollaboratorsService.class));
        ReflectionTestUtils.setField(c, "commentService", mock(IViewCommentService.class));
        ReflectionTestUtils.setField(c, "viewRegistrationService", mock(IViewRegistrationService.class));
        ReflectionTestUtils.setField(c, "cmsActivityService", mock(ICmsActivityService.class));
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
        // 章节招商统计:本类不测名额,给个空 mock(返回空 Map ⇒ 聚合得 null ⇒ 不下发角标)。
        ReflectionTestUtils.setField(c, "merchantChapterApplicationService",
                mock(com.chengyinhub.business.service.IMerchantChapterApplicationService.class));
        return c;
    }
}

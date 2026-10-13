package com.chengyinhub.business.deadbuttons;

import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.controller.api.ApiTopicController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * D2:本来就有明确业务文案的失败,不许被压成不可诊断的「主题发布失败,请稍后重试」。
 *
 * <p>病:{@code ApiTopicController.createTopic} 的 {@code catch (Exception e)} 把四种完全不同的
 * 原因压成同一句 —— 其中「只有俱乐部主理人可以开放商家招商」「仅城市定向主题可开启邀请俱乐部带队」
 * 「上架额度已满」在服务端本来就是有明确文案的业务闸,用户自己能改。压成通用句 = 真人在后台点一次
 * 失败一次,只能找人翻服务器日志。
 *
 * <p>⚠️ 通用兜底<b>不删</b>:技术异常(Jackson 解析失败 / NPE / SQL)仍然只给稳定文案,
 * 由最后一条测试钉住 —— 否则这条修法就成了把内部细节泄给用户。
 */
class TopicCreateBusinessErrorPassthroughTest {

    private ApiTopicController controller;
    private ICmsTopicService topicService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiTopicController());
        topicService = mock(ICmsTopicService.class);
        IQuotaService quotaService = mock(IQuotaService.class);
        IWxContentSecurityService contentSecurity = mock(IWxContentSecurityService.class);
        doReturn(19L).when(controller).getAppUserId();
        doReturn("tester").when(controller).getAppUserName();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", contentSecurity);
        ReflectionTestUtils.setField(controller, "quotaService", quotaService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", mock(IMerchantAccessService.class));
        // RUN-52 发布者实名:本用例测的是「业务闸文案能不能原样透到用户面前」,实名闸在这里放行
        // (mock 的 checkRegistered 返回 null = 已登记)。
        ReflectionTestUtils.setField(controller, "memberIdentityService",
                mock(com.chengyinhub.business.service.impl.MemberIdentityService.class));
        when(quotaService.checkCreate(anyLong(), anyString())).thenReturn(null);
        when(contentSecurity.checkText(any(), any())).thenReturn(null);
    }

    /** 自由探索:绕开经典定向那段俱乐部门禁,直达 service 调用,让抛出的异常走到 catch。 */
    private TopicCreateDTO freeExploreDto() {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setName("探店日");
        return dto;
    }

    private String msgOf(AjaxResult r) {
        return String.valueOf(r.get(AjaxResult.MSG_TAG));
    }

    @Test
    void clubLeaderGate_reachesTheUserVerbatim() {
        when(topicService.createTopic(any(), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("只有俱乐部主理人可以开放商家招商;如需招商请先注册主理人并绑定俱乐部"));

        String msg = msgOf(controller.createTopic(freeExploreDto()));

        assertTrue(msg.contains("俱乐部主理人"), "用户自己能改的闸必须原样透出,实际:" + msg);
    }

    @Test
    void cityOrienteeringOnlyGate_reachesTheUserVerbatim() {
        when(topicService.createTopic(any(), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("仅城市定向主题可开启邀请俱乐部带队"));

        String msg = msgOf(controller.createTopic(freeExploreDto()));

        assertTrue(msg.contains("仅城市定向主题"), "用户自己能改的闸必须原样透出,实际:" + msg);
    }

    /** 额度闸抛的是 ServiceException;代码注释自称「由外层 catch 统一转 error 文案」,实际转成了通用句。 */
    @Test
    void quotaServiceException_reachesTheUserVerbatim() {
        when(topicService.createTopic(any(), anyLong(), anyString()))
                .thenThrow(new ServiceException("在架主题数已达上限,请先下架一个再发布"));

        String msg = msgOf(controller.createTopic(freeExploreDto()));

        assertTrue(msg.contains("在架主题数已达上限"), "额度闸文案必须透出,实际:" + msg);
    }

    /** 参数级业务闸(IllegalArgumentException)同样是用户能改的。 */
    @Test
    void nodeLocationGate_reachesTheUserVerbatim() {
        when(topicService.createTopic(any(), anyLong(), anyString()))
                .thenThrow(new IllegalArgumentException("第2个地点尚未在地图确认"));

        String msg = msgOf(controller.createTopic(freeExploreDto()));

        assertTrue(msg.contains("第2个地点"), "缺哪个点要说清楚,实际:" + msg);
    }

    // ---------- 兜底不许被拆掉 ----------

    @Test
    void unexpectedTechnicalFailure_stillGetsTheGenericMessage() {
        when(topicService.createTopic(any(), anyLong(), anyString()))
                .thenThrow(new NullPointerException("Cannot invoke \"String.trim()\" because \"in\" is null"));

        assertEquals("主题发布失败，请稍后重试", msgOf(controller.createTopic(freeExploreDto())),
                "技术异常仍必须走稳定兜底文案,不许把内部细节泄给用户");
    }

    @Test
    void businessExceptionCarryingTechnicalLeak_isStillMasked() {
        when(topicService.createTopic(any(), anyLong(), anyString()))
                .thenThrow(new IllegalStateException(
                        "Error querying database.  Cause: java.sql.SQLSyntaxErrorException: Unknown column 'x'"));

        String msg = msgOf(controller.createTopic(freeExploreDto()));

        assertTrue(msg.equals("主题发布失败，请稍后重试") || msg.equals("系统繁忙，请稍后重试"),
                "业务异常类型也可能裹着 SQL 片段,透出前必须过掩码,实际:" + msg);
    }
}

package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UI-20 返修:/api/registration/list 的主题订单必须拿到读取口径的产品类型。
 *
 * <p>复现:topic 10(product_type=NULL 的存量主题)下单后,游玩页 resolveMode 按票 mode 回落出
 * 「城市定向」,订单卡却因 cmsTopic.productType=null 整块标签消失 —— 同一主题两种读法。
 * 这里钉住「订单列表与游玩页共用同一条回落规则」。
 *
 * <p>⚠️ 负控:删掉 myRegistrationList 里的 readTopicProductType 调用 ⇒ legacyNull* 红。
 */
class ApiRegistrationListTopicModeTest {

    private static final Long TOPIC = 10L;

    private ICmsRegistrationService registrationService;
    private ICmsTopicService topicService;
    private IOmsTicketService ticketService;
    private ApiRegistrationController controller;

    @BeforeEach
    void setUp() {
        registrationService = mock(ICmsRegistrationService.class);
        topicService = mock(ICmsTopicService.class);
        ticketService = mock(IOmsTicketService.class);

        CmsRegistration reg = new CmsRegistration();
        reg.setId(1L);
        reg.setOwnerType(1L);
        reg.setOwnerId(TOPIC);
        reg.setRegistrationStatus(1);   // 待支付:跳过 attachRefundInfoForList 的退款判定,只测模式回落
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(reg)));

        controller = spy(new ApiRegistrationController());
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationService", registrationService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "topicService", topicService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "activityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "omsTicketService", ticketService);
        IRefundService refundService = mock(IRefundService.class);
        when(refundService.selectRefundList(any())).thenReturn(new ArrayList<>());
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "refundService", refundService);
        doReturn(100L).when(controller).getAppUserId();
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.<OmsTicket>emptyList());
    }

    private void givenTopic(Integer productType) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(productType);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
    }

    private CmsTopic listedTopic() {
        AjaxResult res = RegistrationQueryTestFixtures.wire(controller).myRegistrationList("3", "0", null);
        assertEquals(200, res.get(AjaxResult.CODE_TAG), "订单列表应正常返回");
        TableDataInfo data = (TableDataInfo) res.get(AjaxResult.DATA_TAG);
        List<?> rows = data.getRows();
        assertEquals(1, rows.size());
        return ((CmsRegistration) rows.get(0)).getCmsTopic();
    }

    private OmsTicket ticketWithMode(Integer mode) {
        OmsTicket ticket = new OmsTicket();
        ticket.setMode(mode);
        return ticket;
    }

    /** 截图复现:存量 NULL 主题 + 票 mode=1 ⇒ 订单卡必须能读到「城市定向」的 1。 */
    @Test
    void legacyNullProductType_fallsBackToTicketMode() {
        givenTopic(null);
        when(ticketService.selectOmsTicketList(any()))
                .thenReturn(Collections.singletonList(ticketWithMode(ProductType.ORIENTEERING)));

        assertEquals(ProductType.ORIENTEERING, listedTopic().getProductType(),
                "存量主题不回落到票 mode,订单卡标签整块消失(与游玩页矛盾)");
    }

    /** 真源在时以主题为准,且不该再查票(省一次 DB,也证明分支短路)。 */
    @Test
    void productTypePresent_winsOverTicketMode_andSkipsTicketQuery() {
        givenTopic(ProductType.FREE_EXPLORE);
        when(ticketService.selectOmsTicketList(any()))
                .thenReturn(Collections.singletonList(ticketWithMode(ProductType.ORIENTEERING)));

        assertEquals(ProductType.FREE_EXPLORE, listedTopic().getProductType(), "product_type 是真源");
        verify(ticketService, never()).selectOmsTicketList(any());
    }

    /** 票也没有 ⇒ 与游玩页同向回落经典定向,不得留 null 让标签消失。 */
    @Test
    void noTickets_defaultsToOrienteering() {
        givenTopic(null);

        assertEquals(ProductType.ORIENTEERING, listedTopic().getProductType());
    }
}

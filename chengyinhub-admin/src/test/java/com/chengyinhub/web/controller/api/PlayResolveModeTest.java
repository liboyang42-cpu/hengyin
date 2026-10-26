package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.common.constant.PlayMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-02:resolveMode 改读产品真源 + 过渡期双读。
 *
 * <p>旧实现取「第一张有 mode 的票」,而 oms_ticket 列表当时无 order by ⇒ **玩法模式由 InnoDB 扫描
 * 路径决定**:混票主题下 mode=2 的买家可能被线性闸拦住(线性闸在 4 处调用本方法)。
 *
 * <p>双读的必要性:迁移刻意不静默回填混票主题(施工图「判不清的人工确认,禁止静默回填」),
 * 那批 product_type 会是 NULL。回落旧推断 ⇒ 迁移落库前后行为都正确,存量清完可删 fallback。
 *
 * ⚠️ 负控已实测:把 resolveMode 里读 product_type 的分支删掉 ⇒ productTypeWinsOverTicketMode 红
 *    (退回「由票序决定」);把 fallback 删掉 ⇒ legacy* 系列红。
 */
class PlayResolveModeTest {

    private static final Long TOPIC = 300L;
    private static final Long ACTIVITY = 400L;

    private ICmsTopicService topicSvc;
    private IOmsTicketService ticketSvc;
    private ApiPlayProgressController controller;

    @BeforeEach
    void setUp() {
        topicSvc = mock(ICmsTopicService.class);
        ticketSvc = mock(IOmsTicketService.class);
        controller = new ApiPlayProgressController();
        PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicSvc);
        PlayNodeAccessTestFixtures.setField(controller, "omsTicketService", ticketSvc);
        when(ticketSvc.selectOmsTicketList(any())).thenReturn(Collections.<OmsTicket>emptyList());
    }

    private int resolveMode(Long activityId, Long topicId) {
        Integer r = com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "resolveMode", activityId, topicId);
        return r == null ? -1 : r;
    }

    private void givenTopicProductType(Integer productType) {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setProductType(productType);
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(t);
    }

    private OmsTicket ticketWithMode(Integer mode) {
        OmsTicket t = new OmsTicket();
        t.setMode(mode);
        return t;
    }

    /**
     * 核心:主题真源压过票 mode。票说 1、主题说 2 ⇒ 必须 2。
     * 这一条挂了 = 退回「玩法模式由 DB 返回行序决定」。
     */
    @Test
    void productTypeWinsOverTicketMode() {
        givenTopicProductType(2);
        when(ticketSvc.selectOmsTicketList(any()))
                .thenReturn(Collections.singletonList(ticketWithMode(1)));    // 票说经典

        assertEquals(2, resolveMode(ACTIVITY, TOPIC), "product_type 是真源,必须压过票 mode");
    }

    /** 真源命中时不该再去查票(省一次 DB,也证明分支真的短路了)。 */
    @Test
    void productTypePresent_doesNotQueryTickets() {
        givenTopicProductType(1);

        resolveMode(ACTIVITY, TOPIC);

        verify(ticketSvc, never()).selectOmsTicketList(any());
    }

    /** 存量未回填(product_type=NULL)⇒ 回落票 mode 推断,行为与 P0-02 之前一致。 */
    @Test
    void legacyNullProductType_fallsBackToTicketMode() {
        givenTopicProductType(null);
        when(ticketSvc.selectOmsTicketList(any()))
                .thenReturn(Arrays.asList(ticketWithMode(null), ticketWithMode(2)));

        assertEquals(2, resolveMode(ACTIVITY, TOPIC), "未回填时必须回落旧推断,否则存量主题全变经典定向");
    }

    /** 主题查不到 ⇒ 同样回落,不得 NPE。 */
    @Test
    void missingTopic_fallsBackToTicketMode() {
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(null);
        when(ticketSvc.selectOmsTicketList(any()))
                .thenReturn(Collections.singletonList(ticketWithMode(2)));

        assertEquals(2, resolveMode(ACTIVITY, TOPIC));
    }

    /** topicId 为空(只有活动)⇒ 回落活动票,不得 NPE。 */
    @Test
    void nullTopicId_fallsBackToActivityTicket() {
        when(ticketSvc.selectOmsTicketList(any()))
                .thenReturn(Collections.singletonList(ticketWithMode(2)));

        assertEquals(2, resolveMode(ACTIVITY, null));
    }

    /** 全都没有 ⇒ 经典定向兜底(PlayMode 契约已写明)。 */
    @Test
    void nothingKnown_defaultsToLinear() {
        givenTopicProductType(null);

        assertEquals(PlayMode.LINEAR_ORIENTEERING, resolveMode(ACTIVITY, TOPIC));
    }
}

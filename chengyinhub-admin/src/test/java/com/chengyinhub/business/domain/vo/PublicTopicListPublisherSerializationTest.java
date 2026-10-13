package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.common.constant.RoleEntityType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「来源」栏必须真的到得了客户端。
 *
 * <p><b>为什么单独有这个测试</b>:投影服务的单测只证明 CmsTopic 上的字段被填对了,
 * <b>证明不了它出得去</b>。/api/topic/list 与 /like_list 的返回并不是 CmsTopic 本身 ——
 * 控制器最后会把每行过一遍 {@link PublicTopicListVO#from(CmsTopic)} 白名单。
 * 白名单里没有的字段会被静默丢弃:服务层全绿、接口返回里却没有这两个字段,
 * 而且不报任何错。这正是「改了入口没改通整条链路」的典型形态,只有在序列化边界断言才抓得到。
 */
class PublicTopicListPublisherSerializationTest {

    @Test
    void publisherFieldsSurviveTheWhitelistAndReachTheWire() throws Exception {
        CmsTopic topic = new CmsTopic();
        topic.setId(1L);
        topic.setName("赛博夜巡");
        topic.setContentPublisherType(RoleEntityType.CLUB);
        topic.setContentPublisherName("夜航俱乐部");

        String json = new ObjectMapper().writeValueAsString(PublicTopicListVO.from(topic));

        assertTrue(json.contains("\"contentPublisherType\":\"CLUB\""),
                "白名单丢了 contentPublisherType,接口返回里没有「来源」类型。实际: " + json);
        assertTrue(json.contains("\"contentPublisherName\":\"夜航俱乐部\""),
                "白名单丢了 contentPublisherName,卡片上的来源名会是空的。实际: " + json);
    }

    /** 无生效 CONTENT 槽的主题必须原样留空 —— 白名单不得在这里补任何默认值。 */
    @Test
    void missingSlotStaysEmptyThroughTheWhitelist() {
        CmsTopic historical = new CmsTopic();
        historical.setId(2L);

        PublicTopicListVO vo = PublicTopicListVO.from(historical);

        assertNull(vo.getContentPublisherType(), "历史待归属主题不能被白名单兜底成任何主体");
        assertNull(vo.getContentPublisherName(), "无槽时来源名必须留空,不得回退成「官方」");
    }

    /**
     * 顺带守住 P0-5 的边界:公开列表白名单永远不许带出抽成率/保底率公式参数/驳回理由。
     * 本次为了「来源」栏往这个白名单加了字段,这条断言保证加的过程中没把口子开大。
     */
    @Test
    void whitelistStillExcludesFeeRatesAndAuditReason() {
        for (String forbidden : new String[]{
                "getFeePlatformRate", "getFeeGuaranteeBaseRate", "getFeeGuaranteeStepRate",
                "getFeeGuaranteeMaxMerchant", "getReason", "getCheckStatus"}) {
            assertFalse(hasMethod(PublicTopicListVO.class, forbidden),
                    "公开列表白名单不得暴露 " + forbidden + "(P0-5 口径)");
        }
    }

    private static boolean hasMethod(Class<?> type, String name) {
        for (java.lang.reflect.Method m : type.getMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.GameSession;
import com.chengyinhub.business.domain.GameStationRun;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.service.support.CoopOrderLineupSource;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 第十轮 f2 第 6 项(j2 F-01e):中标某站的商家没有生效合作单时,商家扫团码必被拒、玩家过站卡死。
 * 开售与开局两处都要先拦下并说出是哪一站。
 */
class StationMerchantContractGateTest {

    private static final long MERCHANT = 9002L;

    private static CmsRegistrationMerchant winner(long nodeId) {
        CmsRegistrationMerchant r = new CmsRegistrationMerchant();
        r.setMemberId(MERCHANT);
        r.setNodeId(nodeId);
        return r;
    }

    private TopicSaleFinalizationServiceImpl finalizer(List<CmsRegistrationMerchant> winners) {
        TopicSaleFinalizationServiceImpl service = new TopicSaleFinalizationServiceImpl(
                null, null, null, null, null, null, null, null, null);
        CmsRegistrationMerchantMapper registrations = mock(CmsRegistrationMerchantMapper.class);
        when(registrations.selectCmsRegistrationMerchantList(any())).thenReturn(winners);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        CmsTopicNode fountain = new CmsTopicNode();
        fountain.setName("人民广场音乐喷泉");
        when(nodes.selectCmsTopicNodeById(71L)).thenReturn(fountain);
        ReflectionTestUtils.setField(service, "registrationMerchantMapper", registrations);
        ReflectionTestUtils.setField(service, "topicNodeMapper", nodes);
        return service;
    }

    private static CmsTopic city() {
        CmsTopic topic = new CmsTopic();
        topic.setId(990015L);
        topic.setProductType(ProductType.ORIENTEERING);
        return topic;
    }

    @Test
    void saleRefused_whenStationWinnerHasNoCoopOrder_namingTheStation() {
        TopicSaleFinalizationServiceImpl service = finalizer(Collections.singletonList(winner(71L)));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.assertStationMerchantsContracted(city(), Collections.emptyList()));
        assertTrue(e.getMessage().contains("「人民广场音乐喷泉」的商家还没有生效的合作单，不能开售"), e.getMessage());
    }

    @Test
    void saleAllowed_whenWinnerHasCoopOrder_orNoStationHasAMerchant() {
        CoopOrderLineupSource.LineupEntry order = new CoopOrderLineupSource.LineupEntry(
                "MERCHANT", MERCHANT, "SUPPLIER", "TOPIC", 990015L, "COOP_ORDER", 1L, false);
        assertDoesNotThrow(() -> finalizer(Collections.singletonList(winner(71L)))
                .assertStationMerchantsContracted(city(), Collections.singletonList(order)));
        assertDoesNotThrow(() -> finalizer(Collections.emptyList())
                .assertStationMerchantsContracted(city(), Collections.emptyList()));
    }

    private GameSessionRuntimeServiceImpl runtime(List<CoopOrder> orders) {
        GameSessionRuntimeServiceImpl service = new GameSessionRuntimeServiceImpl();
        GameSessionRuntimeMapper runtimeMapper = mock(GameSessionRuntimeMapper.class);
        GameStationRun merchantStation = new GameStationRun();
        merchantStation.setNodeName("人民广场音乐喷泉");
        merchantStation.setMerchantMemberId(MERCHANT);
        merchantStation.setSourceType("REGISTRATION_WINNER");
        GameStationRun hostStation = new GameStationRun();
        hostStation.setNodeName("上海博物馆");
        hostStation.setSourceType("HOST_NODE");
        when(runtimeMapper.selectStations(any())).thenReturn(Arrays.asList(merchantStation, hostStation));
        CoopOrderMapper coop = mock(CoopOrderMapper.class);
        when(coop.selectEffectiveOrdersByTopicId(any())).thenReturn(orders);
        ReflectionTestUtils.setField(service, "runtimeMapper", runtimeMapper);
        ReflectionTestUtils.setField(service, "coopOrderMapper", coop);
        return service;
    }

    private static GameSession clubSession() {
        GameSession session = new GameSession();
        session.setId(1L);
        session.setTopicId(990015L);
        session.setClubId(990004L);
        return session;
    }

    @Test
    void startBlocked_whenStationMerchantHasNoCoopOrder() {
        List<String> blockers = runtime(Collections.emptyList()).merchantOrderBlockers(clubSession());
        assertEquals(Collections.singletonList("人民广场音乐喷泉（商家没有生效的合作单）"), blockers);
    }

    @Test
    void startNotBlocked_onceTheMerchantHasACoopOrder() {
        CoopOrder order = new CoopOrder();
        order.setToType("merchant");
        order.setToId(MERCHANT);
        assertTrue(runtime(Collections.singletonList(order)).merchantOrderBlockers(clubSession()).isEmpty());
    }
}

package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.service.support.ExploreSaleSnapshotGate;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExploreSaleSnapshotGateTest {
    @Test
    void missingSnapshotRefusesNewSalesEvenWhenSellingStartedBeforeCanonicalLaunch() {
        TopicSaleSnapshotMapper snapshots = mock(TopicSaleSnapshotMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        when(registrations.lockExploreTopicForUpdate(9L)).thenReturn(9L);

        CmsTopic legacy = topic(Date.from(Instant.parse("2026-08-14T15:59:59Z")));
        assertThrows(IllegalStateException.class, () -> ExploreSaleSnapshotGate.assertSnapshotFrozen(legacy, snapshots, registrations));

        CmsTopic current = topic(Date.from(Instant.parse("2026-08-14T16:00:00Z")));
        assertThrows(IllegalStateException.class,
                () -> ExploreSaleSnapshotGate.assertSnapshotFrozen(current, snapshots, registrations));
    }

    /**
     * C-22 集成(2026-11-03):l3 当初按「按场次限购」写了这条;round 3 业主改为取消全部「每人限 1 张」(含探店日),
     * 以 l2 的实现为准。同一场景改证:普通自由探索与四店探店日快照都不再按账号数票,只校验快照、只锁主题行。
     */
    @Test
    void ordinaryFreeExploreDoesNotCountAccountsButLegacyFourStoreStopsNewSales() throws Exception {
        TopicSaleSnapshotMapper snapshots = mock(TopicSaleSnapshotMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        CmsTopic topic = topic(Date.from(Instant.parse("2026-11-01T00:00:00Z")));
        when(registrations.lockExploreTopicForUpdate(9L)).thenReturn(9L);
        com.fasterxml.jackson.databind.node.ObjectNode value = TopicSaleCanonicalTestFixture.json();
        ((com.fasterxml.jackson.databind.node.ObjectNode) value.path("completionPolicy"))
                .put("evidenceRule", "ALL_REQUIRED_CHAPTERS");
        com.chengyinhub.business.domain.TopicSaleSnapshot ordinary = new com.chengyinhub.business.domain.TopicSaleSnapshot();
        ordinary.setSchemaVersion(com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1.SCHEMA_VERSION);
        ordinary.setCanonicalJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value));
        when(snapshots.selectSnapshotByTopicId(9L)).thenReturn(ordinary);

        assertDoesNotThrow(() -> ExploreSaleSnapshotGate.assertSnapshotFrozen(topic, snapshots, registrations));

        when(snapshots.selectSnapshotByTopicId(9L)).thenReturn(TopicSaleCanonicalTestFixture.snapshot(9L));
        assertThrows(IllegalStateException.class, () -> ExploreSaleSnapshotGate.assertSnapshotFrozen(topic, snapshots, registrations));
        org.mockito.Mockito.verify(registrations, org.mockito.Mockito.times(1)).lockExploreTopicForUpdate(9L);
        org.mockito.Mockito.verifyNoMoreInteractions(registrations);
    }

    @Test
    void registrationCallerStopsFourStoreBeforeAccountOrOrderWork() {
        TopicSaleSnapshotMapper snapshots = mock(TopicSaleSnapshotMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl service =
                new com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "topicSaleSnapshotMapper", snapshots);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
        when(snapshots.selectSnapshotByTopicId(9L)).thenReturn(TopicSaleCanonicalTestFixture.snapshot(9L));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,
                        "assertExploreAccountTicketAvailable", topic(new Date()), 7L));
        org.junit.jupiter.api.Assertions.assertEquals("四店固定补偿产品已停止新售票，已售合同继续履行", refused.getMessage());
        org.mockito.Mockito.verifyNoInteractions(registrations);
    }

    @Test
    void ordinaryRegistrationStillUsesTheExistingAccountTicketGuard() {
        TopicSaleSnapshotMapper snapshots = mock(TopicSaleSnapshotMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1 ordinary = TopicSaleCanonicalTestFixture.canonical();
        ordinary.getCompletionPolicy().put("evidenceRule", "ALL_REQUIRED_CHAPTERS");
        com.chengyinhub.business.domain.TopicSaleSnapshot snapshot = TopicSaleCanonicalTestFixture.snapshot(9L);
        snapshot.setCanonicalJson(com.chengyinhub.business.service.support.TopicSaleCanonicalCodec.write(ordinary, ProductType.FREE_EXPLORE));
        when(snapshots.selectSnapshotByTopicId(9L)).thenReturn(snapshot);
        when(registrations.lockExploreTopicForUpdate(9L)).thenReturn(9L);
        when(registrations.selectLiveExploreTicketIdsForUpdate(9L, 7L)).thenReturn(java.util.Collections.singletonList(100L));
        com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl service =
                new com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "topicSaleSnapshotMapper", snapshots);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,
                        "assertExploreAccountTicketAvailable", topic(new Date()), 7L));
        org.junit.jupiter.api.Assertions.assertEquals("SHOP_DAY_ACCOUNT_TICKET_LIMIT_REACHED", refused.getMessage());
        org.mockito.Mockito.verify(registrations).selectLiveExploreTicketIdsForUpdate(9L, 7L);
    }

    @Test
    void sessionClubRuleAndRegistrationCallerKeepTheOriginalProductBoundary() {
        CmsTopic free = topic(new Date()); free.setClubId(21L);
        assertDoesNotThrow(() -> ExploreSaleSnapshotGate.assertSessionClub(free, 21L));
        assertThrows(IllegalStateException.class, () -> ExploreSaleSnapshotGate.assertSessionClub(free, 22L));
        com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl service =
                new com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl();
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setClubId(22L);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,
                        "assertRegistrationSellable", activity, free, null, new Date()));
        org.junit.jupiter.api.Assertions.assertEquals("自由探索只开放内容所属俱乐部的场次，不能跨俱乐部售票", refused.getMessage());
        free.setProductType(ProductType.ORIENTEERING);
        assertDoesNotThrow(() -> ExploreSaleSnapshotGate.assertSessionClub(free, 22L));
    }

    private CmsTopic topic(Date sellingStartedAt) {
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setSellingStartedAt(sellingStartedAt);
        return topic;
    }
}

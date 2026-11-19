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

    // FE-G11:商家自由探索看「承接完整」,其它主题仍看俱乐部一致。
    private static com.chengyinhub.business.service.support.FreeExploreSlotProgress slots(int total, int open) {
        com.chengyinhub.business.service.support.FreeExploreSlotProgress progress =
                mock(com.chengyinhub.business.service.support.FreeExploreSlotProgress.class);
        when(progress.slots(9L)).thenReturn(new com.chengyinhub.business.service.support.FreeExploreSlotProgress.Slots(total, open));
        return progress;
    }

    @Test
    void merchantFreeExploreSellsOnlyWhenEverySlotIsUndertakenWhateverTheSessionClub() {
        CmsTopic merchantTopic = topic(new Date());
        assertDoesNotThrow(() -> ExploreSaleSnapshotGate.assertSessionClub(merchantTopic, 7002L, slots(3, 0)));
        assertDoesNotThrow(() -> ExploreSaleSnapshotGate.assertSessionClub(merchantTopic, null, slots(3, 0)));
        IllegalStateException open = assertThrows(IllegalStateException.class,
                () -> ExploreSaleSnapshotGate.assertSessionClub(merchantTopic, null, slots(3, 1)));
        org.junit.jupiter.api.Assertions.assertEquals("还有商家位置未承接，暂不能售票", open.getMessage());
        assertThrows(IllegalStateException.class,
                () -> ExploreSaleSnapshotGate.assertSessionClub(merchantTopic, 7002L, slots(3, 1)));
    }

    @Test
    void slotReadFailureIsNotSellable() {
        CmsTopic merchantTopic = topic(new Date());
        com.chengyinhub.business.service.support.FreeExploreSlotProgress broken =
                mock(com.chengyinhub.business.service.support.FreeExploreSlotProgress.class);
        when(broken.slots(9L)).thenThrow(new IllegalStateException("db down"));
        assertThrows(IllegalStateException.class,
                () -> ExploreSaleSnapshotGate.assertSessionClub(merchantTopic, 7002L, broken));
    }

    @Test
    void clubTopicsPureContentAndMissingProgressKeepTheClubCheck() {
        CmsTopic clubTopic = topic(new Date());
        clubTopic.setClubId(5L);
        // 俱乐部主题:承接完整也不放宽跨俱乐部
        assertThrows(IllegalStateException.class,
                () -> ExploreSaleSnapshotGate.assertSessionClub(clubTopic, 7002L, slots(3, 0)));
        assertDoesNotThrow(() -> ExploreSaleSnapshotGate.assertSessionClub(clubTopic, 5L, slots(3, 0)));
        CmsTopic merchantTopic = topic(new Date());
        // 纯内容探索(没有位置)与取不到承接进度:原俱乐部一致校验
        assertThrows(IllegalStateException.class,
                () -> ExploreSaleSnapshotGate.assertSessionClub(merchantTopic, 7002L, slots(0, 0)));
        assertThrows(IllegalStateException.class,
                () -> ExploreSaleSnapshotGate.assertSessionClub(merchantTopic, 7002L, null));
        assertDoesNotThrow(() -> ExploreSaleSnapshotGate.assertSessionClub(merchantTopic, null, null));
        // 非自由探索不受影响
        CmsTopic guided = topic(new Date());
        guided.setProductType(ProductType.ORIENTEERING);
        assertDoesNotThrow(() -> ExploreSaleSnapshotGate.assertSessionClub(guided, 7002L, slots(3, 1)));
    }

    private CmsTopic topic(Date sellingStartedAt) {
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setSellingStartedAt(sellingStartedAt);
        return topic;
    }
}

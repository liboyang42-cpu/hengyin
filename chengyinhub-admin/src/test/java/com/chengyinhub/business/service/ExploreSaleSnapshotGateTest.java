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
    void legacySellingBeforeCanonicalLaunchKeepsSellingButNewMissingSnapshotFailsClosed() {
        TopicSaleSnapshotMapper snapshots = mock(TopicSaleSnapshotMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        when(registrations.lockExploreTopicForUpdate(9L)).thenReturn(9L);

        CmsTopic legacy = topic(Date.from(Instant.parse("2026-08-14T15:59:59Z")));
        assertDoesNotThrow(() -> ExploreSaleSnapshotGate.assertSnapshotFrozen(legacy, snapshots, registrations));

        CmsTopic current = topic(Date.from(Instant.parse("2026-08-14T16:00:00Z")));
        assertThrows(IllegalStateException.class,
                () -> ExploreSaleSnapshotGate.assertSnapshotFrozen(current, snapshots, registrations));
    }

    /**
     * C-22 集成(2026-11-03):l3 当初按「按场次限购」写了这条;round 3 业主改为取消全部「每人限 1 张」(含探店日),
     * 以 l2 的实现为准。同一场景改证:普通自由探索与四店探店日快照都不再按账号数票,只校验快照、只锁主题行。
     */
    @Test
    void ordinaryFreeExploreAndShopDayNoLongerCountTicketsPerAccount() throws Exception {
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
        assertDoesNotThrow(() -> ExploreSaleSnapshotGate.assertSnapshotFrozen(topic, snapshots, registrations));
        org.mockito.Mockito.verify(registrations, org.mockito.Mockito.times(2)).lockExploreTopicForUpdate(9L);
        org.mockito.Mockito.verifyNoMoreInteractions(registrations);
    }

    private CmsTopic topic(Date sellingStartedAt) {
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setSellingStartedAt(sellingStartedAt);
        return topic;
    }
}

package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.service.support.ExploreAccountTicketGate;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExploreAccountTicketGateTest {
    @Test
    void legacySellingBeforeCanonicalLaunchKeepsSellingButNewMissingSnapshotFailsClosed() {
        TopicSaleSnapshotMapper snapshots = mock(TopicSaleSnapshotMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        when(registrations.lockExploreTopicForUpdate(9L)).thenReturn(9L);
        when(registrations.selectLiveExploreTicketIdsForUpdate(9L, 7L)).thenReturn(Collections.emptyList());

        CmsTopic legacy = topic(Date.from(Instant.parse("2026-08-14T15:59:59Z")));
        assertDoesNotThrow(() -> ExploreAccountTicketGate.assertAvailable(legacy, 7L, snapshots, registrations));

        CmsTopic current = topic(Date.from(Instant.parse("2026-08-14T16:00:00Z")));
        assertThrows(IllegalStateException.class,
                () -> ExploreAccountTicketGate.assertAvailable(current, 7L, snapshots, registrations));
    }

    /** C-22：普通自由探索按场次限购：同一场第二张拒，别的场次照卖；四店探店日仍一期一票。 */
    @Test
    void ordinaryFreeExploreLimitsPerSessionButShopDayStaysPerEdition() throws Exception {
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
        // 主题下已有另一场的票(按主题算会被拒),这一场还没有
        when(registrations.selectLiveExploreTicketIdsForUpdate(9L, 7L)).thenReturn(Collections.singletonList(501L));
        when(registrations.selectLiveExploreTicketIdsInActivityForUpdate(13L, 7L)).thenReturn(Collections.emptyList());
        when(registrations.selectLiveExploreTicketIdsInActivityForUpdate(12L, 7L))
                .thenReturn(Collections.singletonList(501L));

        assertDoesNotThrow(() -> ExploreAccountTicketGate.assertAvailable(topic, 7L, 13L, snapshots, registrations));
        IllegalStateException same = assertThrows(IllegalStateException.class,
                () -> ExploreAccountTicketGate.assertAvailable(topic, 7L, 12L, snapshots, registrations));
        org.junit.jupiter.api.Assertions.assertEquals("EXPLORE_SESSION_TICKET_LIMIT_REACHED", same.getMessage());
        // 无场次(自玩通行证)仍按主题
        assertThrows(IllegalStateException.class,
                () -> ExploreAccountTicketGate.assertAvailable(topic, 7L, null, snapshots, registrations));

        when(snapshots.selectSnapshotByTopicId(9L)).thenReturn(TopicSaleCanonicalTestFixture.snapshot(9L));
        IllegalStateException shopDay = assertThrows(IllegalStateException.class,
                () -> ExploreAccountTicketGate.assertAvailable(topic, 7L, 13L, snapshots, registrations));
        org.junit.jupiter.api.Assertions.assertEquals("SHOP_DAY_ACCOUNT_TICKET_LIMIT_REACHED", shopDay.getMessage());
    }

    private CmsTopic topic(Date sellingStartedAt) {
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setSellingStartedAt(sellingStartedAt);
        return topic;
    }
}

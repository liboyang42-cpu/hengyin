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

    private CmsTopic topic(Date sellingStartedAt) {
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setSellingStartedAt(sellingStartedAt);
        return topic;
    }
}

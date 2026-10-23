package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.mapper.CoopSettlementEvidenceMapper;
import com.chengyinhub.business.service.impl.TopicSettlementEvidenceService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Arrays;
import java.util.Date;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TopicSettlementEvidenceServiceTest {

    @Test
    void resolvesActiveActivityIdsBeforeRegistrationForUpdateQuery() {
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        CoopGroupVerifyMapper groupVerifyMapper = mock(CoopGroupVerifyMapper.class);
        CoopSettlementEvidenceMapper evidenceMapper = mock(CoopSettlementEvidenceMapper.class);
        TopicSettlementEvidenceService service = new TopicSettlementEvidenceService(
                activityMapper, registrationMapper, groupVerifyMapper, evidenceMapper);
        CoopSettlementSnapshot snapshot = new CoopSettlementSnapshot();
        snapshot.setTopicId(23L);
        snapshot.setCommercialContextType("ACTIVITY");
        snapshot.setCommercialContextId(201L);
        Date cutoff = new Date(1234L);
        when(activityMapper.selectActiveActivityIdsByTopicId(23L)).thenReturn(Arrays.asList(201L, 202L));

        service.lockCandidates(snapshot, cutoff);

        InOrder inOrder = org.mockito.Mockito.inOrder(activityMapper, evidenceMapper);
        inOrder.verify(activityMapper).selectActiveActivityIdsByTopicId(23L);
        inOrder.verify(evidenceMapper).selectTopicEvidenceCandidatesForUpdate(
                snapshot, Arrays.asList(201L, 202L), cutoff);
    }
}

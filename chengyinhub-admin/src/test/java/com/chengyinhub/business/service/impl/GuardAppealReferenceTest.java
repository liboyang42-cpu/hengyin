package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.PlayArriveGuardLog;
import com.chengyinhub.business.mapper.AuditAppealMapper;
import com.chengyinhub.business.mapper.PlayArriveGuardLogMapper;
import com.chengyinhub.business.service.GuardArrivalEvidenceReader;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Service boundary tests; the real receipt/runtime adapter needs separate integration coverage. */
class GuardAppealReferenceTest {
    private AuditTaskServiceImpl service;
    private PlayArriveGuardLogMapper logs;
    private GuardArrivalEvidenceReader reader;
    private AuditAppealMapper appeals;
    private PlayArriveGuardLog log;
    private ObjectNode receipt;

    @BeforeEach void prepare() {
        service = new AuditTaskServiceImpl(); logs = mock(PlayArriveGuardLogMapper.class);
        reader = mock(GuardArrivalEvidenceReader.class); appeals = mock(AuditAppealMapper.class);
        ReflectionTestUtils.setField(service, "guardAppealLogMapper", logs);
        ReflectionTestUtils.setField(service, "guardArrivalEvidenceReader", reader);
        ReflectionTestUtils.setField(service, "auditAppealMapper", appeals);
        log = new PlayArriveGuardLog(); log.setId(Long.MAX_VALUE); log.setMemberId(10L);
        log.setTopicId(77L); log.setActivityId(88L); log.setNodeId(99L);
        log.setVerdict("REJECT"); log.setReasonCode("ARRIVE_GUARD_SPEED");
        log.setDetail("untrusted legacy detail with a current-session suggestion");
        when(logs.selectByIdAndMemberId(Long.MAX_VALUE, 10L)).thenReturn(log);
        receipt = new ObjectMapper().createObjectNode(); receipt.put("guardLogId", Long.toString(Long.MAX_VALUE));
        receipt.put("nodeId", "99"); receipt.put("routeSessionId", "101"); receipt.put("appealable", true);
        receipt.put("appealBizType", "play_arrive_guard"); receipt.put("errorCode", "ARRIVE_GUARD_SPEED");
    }
    @Test void actorComesFromCallerAndForeignLogStopsBeforeReceiptRead() {
        assertThrows(ServiceException.class, () -> service.guardAppealReference(11L, Long.MAX_VALUE, "arrive-1"));
        verify(logs).selectByIdAndMemberId(Long.MAX_VALUE, 11L);
        verifyNoInteractions(reader, appeals);
    }
    @Test void noOriginalReceiptCannotBeReplacedByLogDetailOrCurrentRun() {
        when(reader.readOwned(10L, Long.MAX_VALUE, "other-run-command")).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.guardAppealReference(10L, Long.MAX_VALUE, "other-run-command"));
        verify(reader).readOwned(10L, Long.MAX_VALUE, "other-run-command");
        verifyNoInteractions(appeals);
        verify(reader, never()).lockOwned(any(), any(), any());
    }
    @Test void mismatchedReceiptLogAndNodeAreRejected() {
        for (String field : new String[]{"guardLogId", "nodeId"}) {
            ObjectNode mismatch = receipt.deepCopy(); mismatch.put(field, "42");
            when(reader.readOwned(10L, Long.MAX_VALUE, "arrive-1"))
                    .thenReturn(new GuardArrivalEvidenceReader.Origin(mismatch, true));
            assertThrows(ServiceException.class, () -> service.guardAppealReference(10L, Long.MAX_VALUE, "arrive-1"));
        }
        verifyNoInteractions(appeals);
    }
    @Test void endedOriginalRunIsReadableWithoutNewSubmissionOrRuntimeLock() {
        receipt.put("detail", "not for the applicant"); receipt.put("nodeLat", 31.0);
        when(reader.readOwned(10L, Long.MAX_VALUE, "arrive-1"))
                .thenReturn(new GuardArrivalEvidenceReader.Origin(receipt, false));
        Map<String, Object> result = service.guardAppealReference(10L, Long.MAX_VALUE, "arrive-1");
        assertEquals(false, result.get("originalRunActive")); assertEquals(false, result.get("canSubmit"));
        JsonNode origin = (JsonNode) result.get("guardOrigin");
        assertEquals(Long.toString(Long.MAX_VALUE), origin.path("guardLogId").asText());
        assertEquals("101", origin.path("routeSessionId").asText());
        assertFalse(origin.has("detail")); assertFalse(origin.has("nodeLat"));
        verify(reader, never()).lockOwned(any(), any(), any()); verifyNoInteractions(appeals);
    }
    @Test void invalidActorAndLogNeverQueryDatabase() {
        assertThrows(ServiceException.class, () -> service.guardAppealReference(null, Long.MAX_VALUE, "arrive-1"));
        assertThrows(ServiceException.class, () -> service.guardAppealReference(10L, 0L, "arrive-1"));
        verifyNoInteractions(logs, reader, appeals);
    }
}

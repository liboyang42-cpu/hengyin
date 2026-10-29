package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.NpcAsrIntent;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** The same explicit contract cases drive real Java VO serialization and the actual client state-machine test. */
class NpcAsrProjectionContractTest {
    @Test void originalRecoveryFieldsAndIndependentAsrMetadataSurviveRealJsonSerialization() throws Exception {
        Path path=Paths.get("docs/delivery/architecture-day/D/npc-asr-projection/contract-cases.json");
        if(!Files.exists(path))path=Paths.get("..").resolve(path);
        ObjectMapper json=new ObjectMapper();JsonNode cases=json.readTree(Files.readString(path));assertEquals(9,cases.size());
        String rid="e2596106-82d9-4a4f-9ff2-058414599f90";
        for(JsonNode expected:cases){
            NpcChatResp response;
            if(expected.get("state").isNull()) response=NpcAsrIntentService.unavailable(rid,expected.get("asrReason").asText());
            else {
                // Project each stored phase from an intent admitted through the real quota/ASR owner.
                // A legacy row without the original reservation deliberately has a different contract.
                NpcAsrTestFixture fixture=new NpcAsrTestFixture();
                NpcAsrIntent row=fixture.recognize();
                assertEquals(rid,row.getRequestId());assertNotNull(row.getQuotaReservation());
                org.mockito.Mockito.verify(fixture.quota).consumePlannedQuota(
                        row.getUserId(),"npc_shop_chat",row.getQuotaReservation());
                row.setState(expected.get("state").asText());
                row.setTranscript(expected.get("transcript").isNull()?null:expected.get("transcript").asText());
                row.setHandoffStarted(expected.get("handoffStarted").asBoolean());row.setBodyErased(expected.get("bodyErased").asBoolean());
                response=NpcAsrIntentService.outcome(row);
            }
            JsonNode actual=json.readTree(json.writeValueAsString(response));String name=expected.get("name").asText();
            for(String field:new String[]{"outcomeStatus","safetyDecision","errorCode","asrReason","asrResumable"})
                assertEquals(expected.get(field),actual.get(field),name+":"+field);
            assertEquals(expected.get("state"),actual.get("asrState"),name+":stored phase");
            assertEquals(rid,actual.get("requestId").asText());assertFalse(actual.get("retryable").asBoolean());
            assertFalse(json.writeValueAsString(response).contains("private original"));
            assertFalse(actual.has("transcript"));assertFalse(actual.has("claimToken"));assertFalse(actual.has("audioSha256"));
        }
    }

    @org.junit.jupiter.api.Test void quotaUnknownProjectionIsProcessingAndNeverClaimsACompletedDebit() {
        var response=NpcAsrIntentService.unavailable("original-rid","ASR_OUTCOME_UNKNOWN");
        var json=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(response);
        org.junit.jupiter.api.Assertions.assertEquals("PROCESSING",json.path("quotaStatus").asText());
        org.junit.jupiter.api.Assertions.assertEquals("REQUEST_OUTCOME_UNKNOWN",json.path("errorCode").asText());
        org.junit.jupiter.api.Assertions.assertTrue(json.path("safeText").asText().contains("额度状态处理中"));
        org.junit.jupiter.api.Assertions.assertFalse(response.isRetryable());
    }

}

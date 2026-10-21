package com.chengyinhub.business.home;

import com.chengyinhub.business.home.PrivateHomeContracts.Proof;
import com.chengyinhub.business.home.PrivateHomeContracts.Receipt;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** New protocol routes stay distinct from the retained GET/PUT/DELETE contract. */
class PrivateHomeReturnHttpTest extends PrivateHomeHttpContractTest {
    @Test void legacyVerifyAndRestorePathsStayAbsent() throws Exception {
        mvc.perform(post("/api/native/home/verify")).andExpect(status().isNotFound());
        mvc.perform(post("/api/native/home/restore")).andExpect(status().isNotFound());
    }
    @Test void unknownFieldAndOwnerAreRejected() throws Exception {
        mvc.perform(post("/api/native/home/return-verifications").contentType("application/json")
                .content("{\"requestId\":\"v\",\"expectedVersion\":1,\"scope\":\"run:1\",\"evidenceHandle\":\"e\",\"ownerId\":9,\"observation\":{\"permission\":\"GRANTED\",\"network\":\"KNOWN\"}}"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("msg").value("HOME_REQUEST_INVALID"));
        verifyNoInteractions(service);
    }
    @Test void proofAndReceiptBodiesDoNotLeakHome() throws Exception {
        Proof proof = new Proof(); proof.requestId = "v"; proof.proofId = "proof-1"; proof.scope = "run:1"; proof.status = "READY";
        proof.homeVersion = 1L; proof.issuedAt = 5L; proof.expiresAt = 9L; proof.grantId = "secret-grant"; proof.ownerId = 7L;
        when(service.verifyReturnHome(eq("v"), eq(1L), eq("run:1"), eq("evidence"), any())).thenReturn(proof);
        when(service.readMyProof("v")).thenReturn(proof);
        mvc.perform(post("/api/native/home/return-verifications").contentType("application/json").content(body()))
                .andExpect(jsonPath("data.decision").value("VERIFIED")).andExpect(jsonPath("data.proofId").value("proof-1"))
                .andExpect(jsonPath("data.latitude").doesNotExist()).andExpect(jsonPath("data.ownerId").doesNotExist())
                .andExpect(jsonPath("data.grantId").doesNotExist()).andExpect(jsonPath("data.label").doesNotExist());
        mvc.perform(get("/api/native/home/return-verifications/v")).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("data.grantId").doesNotExist()).andExpect(jsonPath("data.proofId").value("proof-1"));
        Receipt receipt = new Receipt(); receipt.requestId = "r"; receipt.receiptId = "receipt-1"; receipt.proofId = "proof-1";
        receipt.scope = "run:1"; receipt.decision = "RESTORED"; receipt.homeVersion = 1L; receipt.beforeValue = 2L; receipt.afterValue = 10L; receipt.amount = 8L; receipt.ownerId = 7L;
        when(service.restore("r", "proof-1", "run:1")).thenReturn(receipt);
        when(service.readMyReceipt("r")).thenReturn(receipt);
        mvc.perform(post("/api/native/home/resource-restores").contentType("application/json")
                .content("{\"requestId\":\"r\",\"proofId\":\"proof-1\",\"scope\":\"run:1\"}"))
                .andExpect(jsonPath("data.decision").value("RESTORED")).andExpect(jsonPath("data.amount").value(8))
                .andExpect(jsonPath("data.latitude").doesNotExist()).andExpect(jsonPath("data.longitude").doesNotExist())
                .andExpect(jsonPath("data.ownerId").doesNotExist()).andExpect(jsonPath("data.label").doesNotExist());
        mvc.perform(get("/api/native/home/resource-restores/r")).andExpect(jsonPath("data.receiptId").value("receipt-1"))
                .andExpect(jsonPath("data.latitude").doesNotExist());
    }
    @Test void absentReadbackIsNotASuccessfulRestore() throws Exception {
        when(service.readMyReceipt("missing")).thenThrow(new ServiceException("HOME_RESULT_ABSENT"));
        mvc.perform(get("/api/native/home/resource-restores/missing")).andExpect(jsonPath("msg").value("HOME_RESULT_ABSENT"))
                .andExpect(jsonPath("data.decision").doesNotExist());
    }
    private static String body() {
        return "{\"requestId\":\"v\",\"expectedVersion\":1,\"scope\":\"run:1\",\"evidenceHandle\":\"evidence\",\"observation\":{\"latitude\":30.123456,\"longitude\":120.123456,\"datum\":\"WGS84\",\"accuracyMeters\":10,\"observedAtEpochMillis\":1,\"dwellMillis\":1,\"permission\":\"GRANTED\",\"network\":\"KNOWN\"}}";
    }
}

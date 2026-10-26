package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Direct identity/dispatch SOURCE controls; service doubles do not prove MySQL or economic effects. */
class RestoreHttpCallbackReplayTest {
    @TempDir Path directory;

    private ObjectNode binding() {
        return RestoreHttpApplicationChild.JSON.createObjectNode().put("sourceSha","a".repeat(40))
                .put("jarSha256","b".repeat(64)).put("classManifestSha256","c".repeat(64))
                .put("scope","d".repeat(32)).put("schema","cy_e2e_auth_"+"d".repeat(32))
                .put("serverUuid","11111111-1111-1111-1111-111111111111").put("restoreMode","true");
    }
    private WechatVerifiedPaymentFact original() {
        return new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,
                "restore-http-mch","restore-http-app","RH_ORIGINAL","RH_ORIGINAL","RH_ORIGINAL",
                "RH_TX_ORIGINAL","RH_TX_ORIGINAL","JSAPI","CNY",100,100,
                Instant.parse("2026-01-01T00:00:00Z"),null,"SUCCESS");
    }
    private WechatVerifiedPaymentFactService facts(WechatVerifiedPaymentFact fact) {
        var service=mock(WechatVerifiedPaymentFactService.class);
        var reference=new WechatVerifiedPaymentFact.Reference(71,fact.factHash());
        when(service.recordVerified(fact)).thenReturn(reference);
        when(service.requireVerified(reference)).thenReturn(new WechatVerifiedPaymentFactService.Stored(reference,fact));
        return service;
    }
    private WechatVerifiedPaymentFact.Reference resolve(ObjectNode binding,WechatVerifiedPaymentFact fact,
            WechatVerifiedPaymentFactService service) throws Exception {
        return RestoreHttpApplicationChild.originalCallbackReference(directory,binding,8,fact,service,
                new RestoreHttpApplicationChild.CallbackAttempt(binding));
    }

    @Test void allThreeHeldBootBindingsReuseOneCommittedReferenceButDispatchEveryDelivery() throws Exception {
        var fact=original();var service=facts(fact);var binding=binding();
        var registrations=mock(ICmsRegistrationService.class);
        when(registrations.handleWechatPayCallback(any())).thenReturn("FAIL");
        for(String mode:List.of("true","invalid","")) {
            binding.put("restoreMode",mode);
            for(int replay=0;replay<2;replay++) {
                var reference=resolve(binding,fact,service);
                assertEquals(71,reference.id());assertEquals(fact.factHash(),reference.hash());
                assertEquals("FAIL",RestoreHttpApplicationChild.callOriginalRegistration(registrations,fact,reference));
            }
        }
        verify(service,times(1)).recordVerified(fact);
        verify(service,times(1)).findPaymentSuccess(fact.mchid(),fact.wxOutTradeNo(),fact.transactionId());
        verify(service,times(6)).requireVerified(new WechatVerifiedPaymentFact.Reference(71,fact.factHash()));
        ArgumentCaptor<Map<String,String>> payload=ArgumentCaptor.forClass(Map.class);
        verify(registrations,times(6)).handleWechatPayCallback(payload.capture());
        for(var value:payload.getAllValues()) {
            assertEquals("71",value.get("verified_fact_id"));assertEquals(fact.factHash(),value.get("verified_fact_hash"));
            assertEquals("100",value.get("total"));assertEquals("SUCCESS",value.get("trade_state"));
            assertEquals(fact.businessOutTradeNo(),value.get("out_trade_no"));
            assertEquals(fact.wxOutTradeNo(),value.get("wx_out_trade_no"));
            assertEquals(fact.transactionId(),value.get("transaction_id"));
        }
        assertEquals(1,Files.readAllLines(directory.resolve("provider-facts.jsonl")).size());
        JsonNode receipt=RestoreHttpApplicationChild.readCallbackPrivate(directory.resolve("callback-reference.json"));
        assertEquals(8,receipt.size());assertEquals(71,receipt.path("factId").asLong());
        assertFalse(receipt.has("restoreMode"));assertFalse(receipt.has("bootId"));
    }

    @Test void committedFactWithoutPrivateReceiptIsRecoveredByOriginalIdentityWithoutASecondInsert() throws Exception {
        var fact=original();var service=facts(fact);var reference=new WechatVerifiedPaymentFact.Reference(71,fact.factHash());
        when(service.findPaymentSuccess(fact.mchid(),fact.wxOutTradeNo(),fact.transactionId()))
                .thenReturn(new WechatVerifiedPaymentFactService.Stored(reference,fact));
        assertEquals(reference,resolve(binding(),fact,service));
        verify(service,never()).recordVerified(any());verify(service).requireVerified(reference);
        assertTrue(Files.isRegularFile(directory.resolve("callback-reference.json")));
    }

    @Test void savedReferenceMissingFromStoreThrowsAndNeverCreatesAnotherFactOrDispatches() throws Exception {
        var fact=original();var service=facts(fact);resolve(binding(),fact,service);
        byte[] before=Files.readAllBytes(directory.resolve("callback-reference.json"));
        var missing=new IllegalStateException("private-missing-reference-canary");
        when(service.requireVerified(new WechatVerifiedPaymentFact.Reference(71,fact.factHash()))).thenThrow(missing);
        var failure=assertThrows(IllegalStateException.class,() -> resolve(binding(),fact,service));
        assertSame(missing,failure);verify(service,times(1)).recordVerified(fact);
        verify(service,times(1)).findPaymentSuccess(fact.mchid(),fact.wxOutTradeNo(),fact.transactionId());
        assertArrayEquals(before,Files.readAllBytes(directory.resolve("callback-reference.json")));
    }

    @Test void foreignReceiptBindingOrMalformedPrivateIdentityIsNotReplanned() throws Exception {
        var fact=original();var service=facts(fact);var originalBinding=binding();resolve(originalBinding,fact,service);
        for(String field:List.of("sourceSha","jarSha256","schema","serverUuid")) {
            var changed=originalBinding.deepCopy();changed.put(field,"foreign");
            var failure=assertThrows(RestoreHttpApplicationChild.CallbackRejected.class,() -> resolve(changed,fact,service));
            assertEquals(field.equals("sourceSha") ? RestoreHttpApplicationChild.CallbackReason.PROVIDER_IDENTITY
                    : RestoreHttpApplicationChild.CallbackReason.RECEIPT_INVALID,failure.reason);
        }
        Files.writeString(directory.resolve("callback-reference.json"),"{}");
        assertThrows(RestoreHttpApplicationChild.CallbackRejected.class,() -> resolve(originalBinding,fact,service));
        verify(service,times(1)).recordVerified(fact);
    }

    @Test void conflictingCommittedFactIsRejectedBeforeReceiptOrRegistrationDispatch() {
        var fact=original();var service=facts(fact);
        var wrong=new WechatVerifiedPaymentFact.Reference(72,"e".repeat(64));
        when(service.findPaymentSuccess(fact.mchid(),fact.wxOutTradeNo(),fact.transactionId()))
                .thenReturn(new WechatVerifiedPaymentFactService.Stored(wrong,fact));
        var failure=assertThrows(RestoreHttpApplicationChild.CallbackRejected.class,() -> resolve(binding(),fact,service));
        assertEquals(RestoreHttpApplicationChild.CallbackReason.REFERENCE_CONFLICT,failure.reason);
        verify(service,never()).recordVerified(any());verify(service,never()).requireVerified(any());
        assertFalse(Files.exists(directory.resolve("callback-reference.json")));
    }

    @Test void originalRegistrationFailureEscapesUnchangedAndIsNotReplacedWithCachedFail() {
        var fact=original();var reference=new WechatVerifiedPaymentFact.Reference(71,fact.factHash());
        var registrations=mock(ICmsRegistrationService.class);
        var original=new IllegalStateException("private-callback-error-canary");
        when(registrations.handleWechatPayCallback(any())).thenThrow(original);
        assertSame(original,assertThrows(IllegalStateException.class,
                () -> RestoreHttpApplicationChild.callOriginalRegistration(registrations,fact,reference)));
        verify(registrations,times(1)).handleWechatPayCallback(any());
    }

    @Test void activeOuterTransactionIsRejectedBeforeFactLookupOrReceiptWrite() {
        var fact=original();var service=facts(fact);
        boolean previous=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            var failure=assertThrows(RestoreHttpApplicationChild.CallbackRejected.class,() -> resolve(binding(),fact,service));
            assertEquals(RestoreHttpApplicationChild.CallbackReason.OUTER_TRANSACTION,failure.reason);
            verifyNoInteractions(service);
            assertFalse(Files.exists(directory.resolve("callback-reference.json")));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(previous);
        }
    }

    @Test void stageAndTypedCauseProjectionNeverSerializesMessagesSqlPathsOrArbitraryFrames() throws Exception {
        var attempt=new RestoreHttpApplicationChild.CallbackAttempt(binding());
        attempt.stage=RestoreHttpApplicationChild.CallbackStage.REGISTRATION_CALLBACK;
        var sql=new SQLIntegrityConstraintViolationException("private-SQL-canary","23000",1062);
        sql.setStackTrace(new StackTraceElement[]{new StackTraceElement(
                "com.chengyinhub.business.service.impl.WxPaymentCallbackInboxServiceImpl",
                "registerVerified","private-source-path",123)});
        var failure=new DataIntegrityViolationException("private-body-canary",sql);
        var value=RestoreHttpApplicationChild.callbackDocument(attempt,"THREW",failure);
        assertEquals("REGISTRATION_CALLBACK",value.path("stage").asText());
        assertEquals("UNCLASSIFIED_FAILURE",value.path("reasonCode").asText());
        assertEquals("DATA_ACCESS",value.path("causeTypes").get(0).asText());
        assertEquals("23000",value.path("sqlState").asText());assertEquals(1062,value.path("sqlErrorCode").asInt());
        assertEquals(123,value.path("origin").path("line").asInt());
        assertFalse(value.toString().contains("private"));
        assertTrue(RestoreHttpApplicationChild.JSON.writeValueAsBytes(value).length<=4096);
    }
}

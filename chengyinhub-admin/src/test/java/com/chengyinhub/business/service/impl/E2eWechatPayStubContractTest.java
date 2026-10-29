package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.*;
import com.chengyinhub.common.config.WxPayConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class E2eWechatPayStubContractTest {
    @TempDir static Path scratch;
    private static Class<?> stubClass;
    private final VerifiedPaymentProofFixtures proof=new VerifiedPaymentProofFixtures();

    @BeforeAll
    static void compileActualFixture() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("scripts/e2e/E2eWechatPayStub.java.stub"))) {
            root = root.getParent();
        }
        assertNotNull(root, "repository fixture must be present");
        Path source = scratch.resolve("E2eWechatPayStub.java");
        Files.copy(root.resolve("scripts/e2e/E2eWechatPayStub.java.stub"), source);
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "run on the VPS JDK, not a JRE");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", classpath, "-d", scratch.toString(), source.toString()));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {scratch.toUri().toURL()},
                E2eWechatPayStubContractTest.class.getClassLoader())) {
            stubClass = loader.loadClass("com.chengyinhub.e2e.E2eWechatPayStub");
        }
    }

    @Test
    void unsupportedAttemptCloseAndLockNeverAuthorizeLocalSettlement() throws Exception {
        IWechatPayService stub = (IWechatPayService) stubClass.getDeclaredConstructor().newInstance();
        assertFalse(stub.closePaymentAttempt("REG1", "WXP-original"));
        assertNull(stub.queryRefundFacts((com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference)null,"RF1"));
        assertFalse(stub.lockPaymentAttemptForUpdate("REG1", "WXP-original"));
        assertFalse(stub.lockClosablePaymentAttemptForUpdate("REG1", "WXP-original"));
        assertEquals("true", stub.transferToBalance((com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent) null).get("ambiguous"));
        assertEquals("UNKNOWN", stub.queryTransferResult((com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent) null).get("result"));
    }

    @Test
    void dispatchesEachBusinessToItsRealServiceAndKeepsUnknownMembershipClosed() throws Exception {
        IWechatPayService stub = (IWechatPayService) stubClass.getDeclaredConstructor().newInstance();
        ICmsRegistrationService registration = mock(ICmsRegistrationService.class);
        IClubMembershipService membership = mock(IClubMembershipService.class);
        IMerchantCommerceService commerce = mock(IMerchantCommerceService.class);
        ICoopInviteService invite = mock(ICoopInviteService.class);
        ObjectProvider<IClubMembershipService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(membership);
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.getMerchantId()).thenReturn("fixture-merchant");
        ReflectionTestUtils.setField(stub, "registrationService", registration);
        ReflectionTestUtils.setField(stub, "clubMembershipServiceProvider", provider);
        ReflectionTestUtils.setField(stub, "merchantCommerceService", commerce);
        ReflectionTestUtils.setField(stub, "coopInviteService", invite);
        ReflectionTestUtils.setField(stub, "wxPayConfig", config);
        ReflectionTestUtils.setField(stub,"verifiedPaymentFacts",proof.reader);

        when(membership.supportsOutTradeNo("CLM1")).thenReturn(true);
        when(membership.handlePaidInvoice("CLM1", true, 100, "tx",proof.issue("CLM1",100,"tx"))).thenReturn(true);
        assertTrue(stub.handlePayCallback(body("CLM1", "CNY", "fixture-merchant"), Collections.emptyMap()).contains("SUCCESS"));
        verify(membership).handlePaidInvoice("CLM1", true, 100, "tx",proof.issue("CLM1",100,"tx"));
        assertTrue(stub.handlePayCallback(body("CLM-unknown", "CNY", "fixture-merchant"), Collections.emptyMap()).contains("FAIL"));
        verifyNoInteractions(registration);

        when(commerce.supportsOutTradeNo("COMM1")).thenReturn(true);
        when(commerce.handlePaidOrder("COMM1", true, 100, "tx",proof.issue("COMM1",100,"tx"))).thenReturn(true);
        assertTrue(stub.handlePayCallback(body("COMM1", "CNY", "fixture-merchant"), Collections.emptyMap()).contains("SUCCESS"));
        verify(commerce).handlePaidOrder("COMM1", true, 100, "tx",proof.issue("COMM1",100,"tx"));

        assertTrue(stub.handlePayCallback(body("DEP1", "USD", "fixture-merchant"), Collections.emptyMap()).contains("FAIL"));
        assertTrue(stub.handlePayCallback(body("DEP1", "CNY", "other-merchant"), Collections.emptyMap()).contains("FAIL"));
        verifyNoInteractions(invite);
        assertTrue(stub.handlePayCallback(body("DEP1", "CNY", "fixture-merchant"), Collections.emptyMap()).contains("FAIL"));
        verifyNoInteractions(invite); // A fake SDK body alone is not a verified reference.
        var reader=mock(WechatVerifiedPaymentFactService.class);
        ReflectionTestUtils.setField(stub,"depositPaymentFacts",reader);
        var original=new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact(
                com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.PAYMENT,"fixture-merchant","wx-original",
                "DEP1","DEP1","DEP1","tx","tx","APP","CNY",100,100,java.time.Instant.ofEpochSecond(1700000000),null,"SUCCESS");
        var reference=new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(7L,original.factHash());
        when(reader.findPaymentSuccess("fixture-merchant","DEP1","tx"))
                .thenReturn(new WechatVerifiedPaymentFactService.Stored(reference,original));
        when(invite.markDepositPaid("DEP1", 100L, "tx",reference)).thenReturn(true);
        assertTrue(stub.handlePayCallback(body("DEP1", "CNY", "fixture-merchant"), Collections.emptyMap()).contains("SUCCESS"));
        verify(invite).markDepositPaid("DEP1", 100L, "tx",reference);

        when(registration.handleWechatPayCallback(anyMap())).thenReturn("SUCCESS");
        assertTrue(stub.handlePayCallback(body("REG1", "CNY", "fixture-merchant"), Collections.emptyMap()).contains("SUCCESS"));
        verify(registration).handleWechatPayCallback(argThat(facts -> "REG1".equals(facts.get("out_trade_no"))));
    }

    private String body(String outTradeNo, String currency, String merchant) {
        var ref=proof.issue(outTradeNo,100,"tx");
        return "{\"verified_fact_id\":"+ref.id()+",\"verified_fact_hash\":\""+ref.hash()+"\",\"out_trade_no\":\"" + outTradeNo + "\",\"transaction_id\":\"tx\",\"trade_state\":\"SUCCESS\","
                + "\"total\":100,\"currency\":\"" + currency + "\",\"mchid\":\"" + merchant + "\"}";
    }
}

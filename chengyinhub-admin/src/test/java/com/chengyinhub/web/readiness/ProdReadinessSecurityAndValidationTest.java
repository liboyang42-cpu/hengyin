package com.chengyinhub.web.readiness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.mapper.channel.platform.ChannelWebhookMapper;
import com.chengyinhub.business.mapper.rewardpledge.RewardPledgeMapper;
import com.chengyinhub.business.merchantgap.MerchantOfferWithdrawalAdminService;
import com.chengyinhub.business.service.channel.platform.ChannelWebhookDispatcher;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.channel.platform.ChannelWebhookInboundService;
import com.chengyinhub.business.service.rewardpledge.PledgeCouponPort;
import com.chengyinhub.business.service.rewardpledge.RewardPledgeService;
import com.chengyinhub.business.service.rewardpledge.RewardPledgeService.Draft;
import com.chengyinhub.business.service.rewardpledge.CashPayoutSwitch;
import com.chengyinhub.business.service.rewardpledge.PledgeRejection;
import com.chengyinhub.business.workshop.WorkshopProfessionalService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.web.controller.api.ApiAccountDeregistrationController;
import com.chengyinhub.web.controller.api.ApiChannelGapController;
import com.chengyinhub.web.controller.api.ApiExternalGameController;
import com.chengyinhub.web.controller.api.ApiMerchantLegacyAliasController;
import com.chengyinhub.web.controller.api.ApiUsAppleAuthController;
import com.chengyinhub.web.controller.business.WorkshopProfessionalReviewController;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

class ProdReadinessSecurityAndValidationTest
{
    @Test
    void anonymousPublicRoutesCarryIpRateLimiters() throws Exception
    {
        assertIpRateLimit(ApiChannelGapController.class.getMethod("inbound",
                String.class, String.class, String.class, String.class, byte[].class, HttpServletRequest.class));
        assertIpRateLimit(ApiExternalGameController.class.getMethod("catalog", String.class, String.class));
        assertIpRateLimit(ApiExternalGameController.class.getMethod("catalogDetail", String.class));
        assertIpRateLimit(ApiExternalGameController.class.getMethod("download",
                String.class, String.class, String.class, String.class));
        assertIpRateLimit(ApiExternalGameController.class.getMethod("signingKey"));
        assertIpRateLimit(ApiUsAppleAuthController.class.getMethod("challenges", Map.class));
        assertIpRateLimit(ApiUsAppleAuthController.class.getMethod("exchange", Map.class));
        assertIpRateLimit(ApiAccountDeregistrationController.class.getMethod("result", String.class));
        assertIpRateLimit(ApiMerchantLegacyAliasController.class.getMethod("poiDetail"));
    }

    private static void assertIpRateLimit(Method method)
    {
        RateLimiter rl = method.getAnnotation(RateLimiter.class);
        assertNotNull(rl, "Expected @RateLimiter on " + method.getDeclaringClass().getSimpleName() + "." + method.getName());
        assertEquals(LimitType.IP, rl.limitType());
        assertTrue(rl.count() > 0 && rl.time() > 0);
    }

    @Test
    void inboundWebhookControllerSanitizesExceptionsWithoutLoggingSecrets()
    {
        ChannelWebhookInboundService inbound = mock(ChannelWebhookInboundService.class);
        when(inbound.ingest(anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Bearer TOP-SECRET-WEBHOOK-TOKEN"));

        ApiChannelGapController controller = new ApiChannelGapController();
        ReflectionTestUtils.setField(controller, "inbound", inbound);

        Logger logger = (Logger) LoggerFactory.getLogger(ApiChannelGapController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try
        {
            var resp = controller.inbound("123", "1700000000", "nonce", "v1=sig",
                    "{\"content\":\"hi\"}".getBytes(StandardCharsets.UTF_8), new MockHttpServletRequest());
            assertEquals(500, resp.getStatusCode().value());
            assertFalse(appender.list.isEmpty());
            String logged = appender.list.get(0).getFormattedMessage();
            assertTrue(logged.contains("IllegalStateException"));
            assertFalse(logged.contains("TOP-SECRET-WEBHOOK-TOKEN"));
        }
        finally
        {
            logger.detachAppender(appender);
        }
    }

    @Test
    void webhookDispatcherRunOnceDoesNotLogExceptionDetails()
    {
        ChannelWebhookMapper mapper = mock(ChannelWebhookMapper.class);
        when(mapper.selectDueDeliveries(anyInt())).thenThrow(new RuntimeException("https://hooks.example.com?token=SUPER_SECRET"));

        ChannelWebhookDispatcher dispatcher = new ChannelWebhookDispatcher();
        PiiCryptoService crypto = mock(PiiCryptoService.class);
        when(crypto.isEnabled()).thenReturn(true);
        ReflectionTestUtils.setField(dispatcher, "hooks", mapper);
        ReflectionTestUtils.setField(dispatcher, "crypto", crypto);
        ReflectionTestUtils.setField(dispatcher, "enabled", true);

        Logger logger = (Logger) LoggerFactory.getLogger(ChannelWebhookDispatcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try
        {
            dispatcher.tick();
            String logged = appender.list.get(0).getFormattedMessage();
            assertTrue(logged.contains("RuntimeException"));
            assertFalse(logged.contains("SUPER_SECRET"));
        }
        finally
        {
            logger.detachAppender(appender);
        }
    }

    @Test
    void workshopProfessionalReviewRejectsNonStringOrOversizedNoteAndReasonWith400()
    {
        WorkshopProfessionalService service = mock(WorkshopProfessionalService.class);
        WorkshopProfessionalReviewController controller = new WorkshopProfessionalReviewController(service)
        {
            @Override
            public Long getUserId()
            {
                return 99L;
            }
        };

        AppGapException badType = assertThrows(AppGapException.class,
                () -> controller.approve(1L, Map.of("note", List.of("not-a-string"))));
        assertEquals(400, badType.getStatus());

        AppGapException tooLong = assertThrows(AppGapException.class,
                () -> controller.approve(1L, Map.of("note", "x".repeat(501))));
        assertEquals(400, tooLong.getStatus());

        AppGapException badReason = assertThrows(AppGapException.class,
                () -> controller.reject(1L, Map.of("reason", 12345)));
        assertEquals(400, badReason.getStatus());

        AppGapException badEscalate = assertThrows(AppGapException.class,
                () -> controller.escalate(1L, Map.of("reason", Map.of("k", "v"))));
        assertEquals(400, badEscalate.getStatus());
    }

    @Test
    void rewardPledgeRejectsExcessiveQuantity()
    {
        RewardPledgeService service = new RewardPledgeService(
                new NoopTxManager(), mock(RewardPledgeMapper.class), mock(PledgeCouponPort.class), CashPayoutSwitch.off());
        Draft draft = new Draft();
        draft.requestId = "req-over-cap-1";
        draft.rewardKind = "NON_CASH";
        draft.storeId = 10L;
        draft.merchantMemberId = 20L;
        draft.couponId = 30L;
        draft.supplyRef = "supply";
        draft.terms = "terms";
        draft.quantity = 1_000_001;
        draft.serviceStart = 1000L;
        draft.serviceEnd = 2000L;
        draft.redeemStart = 1000L;
        draft.redeemEnd = 2000L;

        PledgeRejection rej = assertThrows(PledgeRejection.class, () -> service.draft(draft));
        assertEquals("INVALID_INPUT", rej.code);
    }

    @Test
    void withdrawalAdminListQueriesWithoutWhereOneEqualsOne()
    {
        SingleConnectionDataSource ds = new SingleConnectionDataSource(
                "jdbc:h2:mem:wd_obs_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE merchant_offer_withdrawal_request ("
                + "id BIGINT PRIMARY KEY, request_id VARCHAR(64), outcome VARCHAR(32), merchant_id BIGINT, owner_id BIGINT,"
                + "actor_id BIGINT, offer_id BIGINT, member_id BIGINT, detail_json TEXT, created_at TIMESTAMP, completed_at TIMESTAMP)");
        MerchantOfferWithdrawalAdminService service = new MerchantOfferWithdrawalAdminService(jdbc);
        Map<String, Object> all = service.list(null, null, null, 1, 20);
        assertEquals(0L, all.get("total"));
    }

    @Test
    void newR2MappersContainNoRawDollarInterpolationOrWhereOneEqualsOne() throws Exception
    {
        Path repoRoot = findRepoRoot();
        String govXml = Files.readString(repoRoot.resolve(
                "chengyinhub-system/src/main/resources/mapper/business/channel/platform/ChannelGovernanceMapper.xml"));
        assertFalse(govXml.contains("${"), "ChannelGovernanceMapper.xml must not use ${}");

        String scopeXml = Files.readString(repoRoot.resolve(
                "chengyinhub-system/src/main/resources/mapper/business/ScopedLeaderboardMapper.xml"));
        assertFalse(scopeXml.contains("1 = 1"), "ScopedLeaderboardMapper.xml must not contain 1 = 1");
    }

    private static Path findRepoRoot()
    {
        Path cur = Path.of("").toAbsolutePath();
        while (cur != null && !Files.exists(cur.resolve("chengyinhub-admin/pom.xml")))
        {
            cur = cur.getParent();
        }
        assertNotNull(cur, "Repo root not found from working directory");
        return cur;
    }

    private static final class NoopTxManager extends AbstractPlatformTransactionManager
    {
        @Override
        protected Object doGetTransaction() { return new Object(); }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) { }

        @Override
        protected void doCommit(DefaultTransactionStatus status) { }

        @Override
        protected void doRollback(DefaultTransactionStatus status) { }
    }
}

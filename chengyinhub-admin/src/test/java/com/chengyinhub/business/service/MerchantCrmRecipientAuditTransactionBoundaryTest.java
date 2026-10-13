package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantCrmCampaign;
import com.chengyinhub.business.domain.MerchantCrmCampaignRecipient;
import com.chengyinhub.business.domain.MerchantCrmMarketingConsent;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantCrmCampaignMapper;
import com.chengyinhub.business.mapper.MerchantCrmMarketingConsentMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.MerchantCrmCouponGrantAdapter;
import com.chengyinhub.business.service.impl.MerchantCrmDeliveryGuardService;
import com.chengyinhub.business.service.impl.MerchantCrmDeliveryOutcomeUnknownException;
import com.chengyinhub.business.service.impl.MerchantCrmNotificationAdapter;
import com.chengyinhub.business.service.impl.MerchantCrmRecipientDeliveryServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MerchantCrmRecipientAuditTransactionBoundaryTest {
    private static final long OWNER = 900L;
    private static final long MERCHANT_ROW = 77L;
    private static final long CAMPAIGN = 8L;
    private static final long RECIPIENT = 9L;
    private static final long MEMBER = 41L;

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:crm_recipient_audit_tx_" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        jdbc.execute("create table campaign(id bigint primary key, merchant_id bigint not null, "
                + "owner_member_id bigint not null, actor_id bigint not null, actor_role varchar(32) not null, "
                + "channel varchar(32) not null)");
        jdbc.execute("create table recipient(id bigint primary key, campaign_id bigint not null, "
                + "merchant_id bigint not null, member_id bigint not null, channel varchar(32) not null, "
                + "status varchar(32) not null, attempt_count int not null, failure_code varchar(64), "
                + "message_receipt_id bigint, coupon_history_id bigint)");
        jdbc.update("insert into campaign(id,merchant_id,owner_member_id,actor_id,actor_role,channel) "
                + "values (?,?,?,?,?,?)", CAMPAIGN, MERCHANT_ROW, OWNER, OWNER,
                "MERCHANT_OWNER", MerchantCrmMarketingConsent.IN_APP);
        jdbc.update("insert into recipient(id,campaign_id,merchant_id,member_id,channel,status,attempt_count) "
                        + "values (?,?,?,?,?,?,0)", RECIPIENT, CAMPAIGN, MERCHANT_ROW, MEMBER,
                MerchantCrmMarketingConsent.IN_APP, MerchantCrmCampaignRecipient.PENDING);
    }

    @Test
    void negativeControlWithoutSpringProxyCommitsSkippedTerminalStateBeforeAuditFailure() {
        MerchantCrmRecipientDeliveryServiceImpl target = target(false);

        assertThrows(ServiceException.class, () -> target.deliver(MERCHANT_ROW, CAMPAIGN, RECIPIENT));

        assertEquals(MerchantCrmCampaignRecipient.SKIPPED_NO_CONSENT, independentStatus());
        assertEquals(1, independentAttemptCount());
    }

    @Test
    void springProxyRollsBackSkippedTerminalStateWhenAuditWriteReturnsZero() {
        IMerchantCrmRecipientDeliveryService service = proxy(target(false));

        assertThrows(ServiceException.class, () -> service.deliver(MERCHANT_ROW, CAMPAIGN, RECIPIENT));

        assertPendingFromIndependentConnection();
    }

    @Test
    void springProxyRollsBackDeliveredTerminalStateWhenAuditWriteReturnsZero() {
        IMerchantCrmRecipientDeliveryService service = proxy(target(true));

        assertThrows(ServiceException.class, () -> service.deliver(MERCHANT_ROW, CAMPAIGN, RECIPIENT));

        assertPendingFromIndependentConnection();
    }

    @Test
    void springProxyRollsBackFailedTerminalStateWhenAuditWriteReturnsZero() {
        IMerchantCrmRecipientDeliveryService service = proxy(target(false));

        assertThrows(ServiceException.class, () -> service.recordFailure(MERCHANT_ROW, CAMPAIGN, RECIPIENT,
                new MerchantCrmDeliveryOutcomeUnknownException()));

        assertPendingFromIndependentConnection();
    }

    private MerchantCrmRecipientDeliveryServiceImpl target(boolean consentGranted) {
        MerchantCrmCampaignMapper campaigns = databaseBackedCampaignMapper();
        MerchantCrmMarketingConsentMapper consents = mock(MerchantCrmMarketingConsentMapper.class);
        when(consents.selectLatestOptIn(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(consentGranted ? new MerchantCrmMarketingConsent() : null);
        MerchantCrmDeliveryGuardService guards = mock(MerchantCrmDeliveryGuardService.class);
        MerchantCrmDeliveryGuardService.Claim reserved =
                MerchantCrmDeliveryGuardService.Claim.acquired(101L, "lease-token", 0L);
        MerchantCrmDeliveryGuardService.Claim dispatching =
                MerchantCrmDeliveryGuardService.Claim.acquired(101L, "lease-token", 1L);
        when(guards.claim(anyLong(), anyLong(), anyLong(), anyLong(), anyString())).thenReturn(reserved);
        when(guards.beginDispatch(reserved)).thenReturn(dispatching);
        MerchantCrmNotificationAdapter notification = mock(MerchantCrmNotificationAdapter.class);
        when(notification.send(anyLong(), any(MerchantCrmCampaign.class))).thenReturn(66L);
        BusinessAuditEventMapper audits = mock(BusinessAuditEventMapper.class);
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(0);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(MERCHANT_ROW);
        merchant.setMemberId(OWNER);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        MmsMerchantMapper merchants = mock(MmsMerchantMapper.class);
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT_ROW)).thenReturn(merchant);

        MerchantCrmRecipientDeliveryServiceImpl target = new MerchantCrmRecipientDeliveryServiceImpl();
        ReflectionTestUtils.setField(target, "campaignMapper", campaigns);
        ReflectionTestUtils.setField(target, "merchantMapper", merchants);
        ReflectionTestUtils.setField(target, "consentMapper", consents);
        ReflectionTestUtils.setField(target, "notificationAdapter", notification);
        ReflectionTestUtils.setField(target, "couponGrantAdapter", mock(MerchantCrmCouponGrantAdapter.class));
        ReflectionTestUtils.setField(target, "deliveryGuardService", guards);
        ReflectionTestUtils.setField(target, "auditMapper", audits);
        return target;
    }

    private MerchantCrmCampaignMapper databaseBackedCampaignMapper() {
        MerchantCrmCampaignMapper mapper = mock(MerchantCrmCampaignMapper.class);
        when(mapper.selectRecipientForUpdate(anyLong(), anyLong(), anyLong()))
                .thenAnswer(call -> recipient());
        when(mapper.selectOwnedCampaign(anyLong(), anyLong())).thenAnswer(call -> campaign());
        when(mapper.markRecipientSkipped(anyLong(), anyLong(), anyLong(),
                anyString(), anyString(), anyString())).thenAnswer(call -> jdbc.update(
                "update recipient set status=?,attempt_count=attempt_count+1,failure_code=? "
                        + "where merchant_id=? and campaign_id=? and id=? and status='PENDING'",
                call.getArgument(3), call.getArgument(4), call.getArgument(0),
                call.getArgument(1), call.getArgument(2)));
        when(mapper.markRecipientDelivered(anyLong(), anyLong(), anyLong(), any(), any()))
                .thenAnswer(call -> jdbc.update(
                        "update recipient set status='DELIVERED',attempt_count=attempt_count+1,"
                                + "message_receipt_id=?,coupon_history_id=? "
                                + "where merchant_id=? and campaign_id=? and id=? and status='PENDING'",
                        call.getArgument(3), call.getArgument(4), call.getArgument(0),
                        call.getArgument(1), call.getArgument(2)));
        when(mapper.markRecipientFailed(anyLong(), anyLong(), anyLong(), anyString(), anyString()))
                .thenAnswer(call -> jdbc.update(
                        "update recipient set status='FAILED',attempt_count=attempt_count+1,failure_code=? "
                                + "where merchant_id=? and campaign_id=? and id=? and status='PENDING'",
                        call.getArgument(3), call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        return mapper;
    }

    private IMerchantCrmRecipientDeliveryService proxy(MerchantCrmRecipientDeliveryServiceImpl target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(IMerchantCrmRecipientDeliveryService.class);
        factory.addAdvice(new TransactionInterceptor(transactions,
                new AnnotationTransactionAttributeSource()));
        return (IMerchantCrmRecipientDeliveryService) factory.getProxy();
    }

    private MerchantCrmCampaign campaign() {
        return jdbc.queryForObject("select * from campaign where id=?", new Object[] { CAMPAIGN }, (rs, rowNum) -> {
            MerchantCrmCampaign row = new MerchantCrmCampaign();
            row.setId(rs.getLong("id"));
            row.setOwnerMemberId(rs.getLong("owner_member_id"));
            row.setMerchantRowId(rs.getLong("merchant_id"));
            row.setActorMemberId(rs.getLong("actor_id"));
            row.setActorRoleCode(rs.getString("actor_role"));
            row.setChannel(rs.getString("channel"));
            return row;
        });
    }

    private MerchantCrmCampaignRecipient recipient() {
        return jdbc.queryForObject("select * from recipient where id=?", new Object[] { RECIPIENT }, (rs, rowNum) -> {
            MerchantCrmCampaignRecipient row = new MerchantCrmCampaignRecipient();
            row.setId(rs.getLong("id"));
            row.setCampaignId(rs.getLong("campaign_id"));
            row.setMerchantId(rs.getLong("merchant_id"));
            row.setMemberId(rs.getLong("member_id"));
            row.setChannel(rs.getString("channel"));
            row.setStatus(rs.getString("status"));
            row.setAttemptCount(rs.getInt("attempt_count"));
            return row;
        });
    }

    private void assertPendingFromIndependentConnection() {
        assertEquals(MerchantCrmCampaignRecipient.PENDING, independentStatus());
        assertEquals(0, independentAttemptCount());
    }

    private String independentStatus() {
        return String.valueOf(independentScalar("select status from recipient where id=9"));
    }

    private int independentAttemptCount() {
        return ((Number) independentScalar("select attempt_count from recipient where id=9")).intValue();
    }

    private Object independentScalar(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getObject(1);
        } catch (Exception e) {
            throw new AssertionError("独立连接读回失败", e);
        }
    }
}

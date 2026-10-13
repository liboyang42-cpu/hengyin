package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantCrmCampaign;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantCrmCampaignMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.MerchantCrmCampaignClaimTransactionService;
import com.chengyinhub.business.service.impl.MerchantCrmCampaignServiceImpl;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MerchantCrmCampaignClaimTransactionBoundaryTest {
    private static final long OWNER = 900L;
    private static final long MERCHANT = 77L;
    private static final long PRINCIPAL = OWNER;
    private static final long CAMPAIGN = 8L;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private MerchantCrmCampaignMapper campaignMapper;
    private BusinessAuditEventMapper auditMapper;
    private AtomicBoolean auditSucceeds;
    private AtomicBoolean crashBeforeDelivery;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:crm_campaign_claim_tx;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        jdbc.execute("drop all objects");
        jdbc.execute("create table campaign(id bigint primary key, merchant_id bigint not null, "
                + "owner_member_id bigint not null, segment_id bigint not null, channel varchar(32) not null, "
                + "status varchar(32) not null, processing_token varchar(64), processing_lease_until timestamp, "
                + "version bigint not null)");
        jdbc.execute("create table recipient(id bigint primary key, campaign_id bigint not null, "
                + "merchant_id bigint not null, status varchar(32) not null, failure_code varchar(64), "
                + "failure_message varchar(255), attempt_count int not null)");
        jdbc.execute("create table audit(id bigint auto_increment primary key, owner_id bigint not null, "
                + "actor_id bigint not null, action varchar(64) not null)");
        auditSucceeds = new AtomicBoolean(true);
        crashBeforeDelivery = new AtomicBoolean(false);
        campaignMapper = databaseBackedMapper();
        auditMapper = databaseBackedAuditMapper();
    }

    @Test
    void dispatchAuditFailureRollsBackPreciseClaim() {
        insertCampaign(MerchantCrmCampaign.READY);
        auditSucceeds.set(false);

        ServiceException failure = assertThrows(ServiceException.class,
                () -> claimTransactions().claimDispatch(campaign(), PRINCIPAL,
                        MerchantRoleCode.MERCHANT_MANAGER, "dispatch-token", 300));

        assertEquals(409, failure.getCode());
        assertEquals(MerchantCrmCampaign.READY, text("select status from campaign where id=8"));
        assertNull(jdbc.queryForObject("select processing_token from campaign where id=8", String.class));
        assertEquals(0L, number("select version from campaign where id=8"));
        assertEquals(0L, number("select count(*) from audit"));
    }

    @Test
    void retryAuditFailureRollsBackClaimAndRecipientReset() {
        insertCampaign(MerchantCrmCampaign.PARTIAL_FAILED);
        insertFailedRecipient();
        auditSucceeds.set(false);

        ServiceException failure = assertThrows(ServiceException.class,
                () -> claimTransactions().claimRetry(campaign(), PRINCIPAL,
                        MerchantRoleCode.MERCHANT_MANAGER, "retry-token", 300, "retry-request-1"));

        assertEquals(409, failure.getCode());
        assertEquals(MerchantCrmCampaign.PARTIAL_FAILED, text("select status from campaign where id=8"));
        assertNull(jdbc.queryForObject("select processing_token from campaign where id=8", String.class));
        assertEquals("FAILED", text("select status from recipient where id=81"));
        assertEquals("CHANNEL_FAILED", text("select failure_code from recipient where id=81"));
        assertEquals(0L, number("select count(*) from audit"));
    }

    @Test
    void acceptedRetryCrashLeavesPendingForStaleRecoveryAndDeliveryRunsOutsideClaimTransaction() {
        insertCampaign(MerchantCrmCampaign.PARTIAL_FAILED);
        insertCouponNotIssuedRecipient();
        crashBeforeDelivery.set(true);
        MerchantCrmCampaignServiceImpl service = campaignService(claimTransactions());

        assertThrows(IllegalStateException.class, () -> service.retry(PRINCIPAL, CAMPAIGN));

        assertEquals(MerchantCrmCampaign.PROCESSING, text("select status from campaign where id=8"));
        assertEquals("PENDING", text("select status from recipient where id=81"));
        assertEquals(1L, number("select count(*) from audit where actor_id=900 and action='CRM_CAMPAIGN_RETRIED'"));

        jdbc.update("update campaign set processing_lease_until=dateadd('SECOND',-1,current_timestamp) where id=8");
        crashBeforeDelivery.set(false);

        assertEquals(1, service.recoverStaleProcessing());
        assertEquals("DELIVERED", text("select status from recipient where id=81"));
        assertEquals(MerchantCrmCampaign.SUCCESS, text("select status from campaign where id=8"));
    }

    @Test
    void staleInvalidationAuditFailureRollsBackTerminalTransition() {
        MerchantCrmCampaign stale = insertStaleProcessingCampaign();
        auditSucceeds.set(false);

        ServiceException failure = assertThrows(ServiceException.class,
                () -> claimTransactions().invalidateStale(stale));

        assertEquals(409, failure.getCode());
        assertEquals(MerchantCrmCampaign.PROCESSING, text("select status from campaign where id=8"));
        assertEquals("stale-token", text("select processing_token from campaign where id=8"));
        assertEquals(0L, number("select version from campaign where id=8"));
        assertEquals(0L, number("select count(*) from audit"));
    }

    @Test
    void staleInvalidationUsesVersionCasAndWritesTerminalAuditAtomically() {
        MerchantCrmCampaign stale = insertStaleProcessingCampaign();

        assertEquals(true, claimTransactions().invalidateStale(stale));

        assertEquals(MerchantCrmCampaign.NO_ELIGIBLE, text("select status from campaign where id=8"));
        assertNull(jdbc.queryForObject("select processing_token from campaign where id=8", String.class));
        assertNull(jdbc.queryForObject("select processing_lease_until from campaign where id=8", java.sql.Timestamp.class));
        assertEquals(1L, number("select version from campaign where id=8"));
        assertEquals(1L, number("select count(*) from audit where owner_id=900 and actor_id=900 "
                + "and action='CRM_CAMPAIGN_INVALIDATED'"));

        assertFalse(claimTransactions().invalidateStale(stale));
        assertEquals(1L, number("select count(*) from audit"));
    }

    @Test
    void claimedInvalidationAuditFailureRollsBackTokenFencedTerminalTransition() {
        MerchantCrmCampaign claimed = insertClaimedProcessingCampaign();
        auditSucceeds.set(false);

        ServiceException failure = assertThrows(ServiceException.class,
                () -> claimTransactions().invalidateClaimed(claimed, "claimed-token"));

        assertEquals(409, failure.getCode());
        assertEquals(MerchantCrmCampaign.PROCESSING, text("select status from campaign where id=8"));
        assertEquals("claimed-token", text("select processing_token from campaign where id=8"));
        assertEquals(0L, number("select version from campaign where id=8"));
        assertEquals(0L, number("select count(*) from audit"));
    }

    @Test
    void claimedInvalidationUsesExactTokenAndWritesTerminalAuditAtomically() {
        MerchantCrmCampaign claimed = insertClaimedProcessingCampaign();

        assertFalse(claimTransactions().invalidateClaimed(claimed, "other-worker-token"));
        assertEquals(MerchantCrmCampaign.PROCESSING, text("select status from campaign where id=8"));
        assertEquals(0L, number("select count(*) from audit"));

        assertEquals(true, claimTransactions().invalidateClaimed(claimed, "claimed-token"));

        assertEquals(MerchantCrmCampaign.NO_ELIGIBLE, text("select status from campaign where id=8"));
        assertNull(jdbc.queryForObject("select processing_token from campaign where id=8", String.class));
        assertNull(jdbc.queryForObject("select processing_lease_until from campaign where id=8", java.sql.Timestamp.class));
        assertEquals(1L, number("select version from campaign where id=8"));
        assertEquals(1L, number("select count(*) from audit where owner_id=900 and actor_id=900 "
                + "and action='CRM_CAMPAIGN_INVALIDATED'"));
    }

    private MerchantCrmCampaignClaimTransactionService claimTransactions() {
        MerchantCrmCampaignClaimTransactionService target = new MerchantCrmCampaignClaimTransactionService();
        ReflectionTestUtils.setField(target, "campaignMapper", campaignMapper);
        ReflectionTestUtils.setField(target, "auditMapper", auditMapper);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        return (MerchantCrmCampaignClaimTransactionService) factory.getProxy();
    }

    private MerchantCrmCampaignServiceImpl campaignService(
            MerchantCrmCampaignClaimTransactionService claimTransactions) {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PRINCIPAL, MerchantPermission.MARKETING_WRITE)).thenReturn(
                new MerchantAccessContext(PRINCIPAL, MERCHANT, OWNER, MerchantRoleCode.MERCHANT_OWNER));
        IMerchantCrmRecipientDeliveryService delivery = mock(IMerchantCrmRecipientDeliveryService.class);
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "外部投递不得位于 campaign claim 短事务中");
            jdbc.update("update recipient set status='DELIVERED' where id=?", new Object[] { call.getArgument(2) });
            return null;
        }).when(delivery).deliver(anyLong(), anyLong(), anyLong());

        MerchantCrmCampaignServiceImpl service = new MerchantCrmCampaignServiceImpl();
        ReflectionTestUtils.setField(service, "accessService", access);
        ReflectionTestUtils.setField(service, "campaignMapper", campaignMapper);
        ReflectionTestUtils.setField(service, "recipientDeliveryService", delivery);
        ReflectionTestUtils.setField(service, "claimTransactionService", claimTransactions);
        MmsMerchantMapper merchantMapper = mock(MmsMerchantMapper.class);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(MERCHANT);
        merchant.setMemberId(OWNER);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        when(merchantMapper.selectMmsMerchantById(MERCHANT)).thenReturn(merchant);
        ReflectionTestUtils.setField(service, "merchantMapper", merchantMapper);
        return service;
    }

    private MerchantCrmCampaignMapper databaseBackedMapper() {
        MerchantCrmCampaignMapper mapper = mock(MerchantCrmCampaignMapper.class);
        when(mapper.selectOwnedCampaign(anyLong(), anyLong())).thenAnswer(call -> selectCampaign());
        when(mapper.markCampaignProcessing(anyLong(), anyLong(), anyString(), anyString(), anyInt()))
                .thenAnswer(call -> jdbc.update("update campaign set status='PROCESSING',processing_token=?,"
                                + "processing_lease_until=dateadd('SECOND',?,current_timestamp),version=version+1 "
                                + "where merchant_id=? and id=? and status=?",
                        new Object[] { call.getArgument(3), call.getArgument(4), call.getArgument(0),
                                call.getArgument(1), call.getArgument(2) }));
        when(mapper.resetRetryableRecipients(anyLong(), anyLong())).thenAnswer(call -> jdbc.update(
                "update recipient set status='PENDING',failure_code=null,failure_message=null "
                        + "where merchant_id=? and campaign_id=? and status in "
                        + "('FAILED','SKIPPED_FREQUENCY','RETRYABLE_BUSY') "
                        + "and coalesce(failure_code,'')<>'DELIVERY_OUTCOME_UNKNOWN' and attempt_count<20",
                new Object[] { call.getArgument(0), call.getArgument(1) }));
        when(mapper.selectPendingRecipientIds(anyLong(), anyLong(), anyInt())).thenAnswer(call -> {
            if (crashBeforeDelivery.get()) throw new IllegalStateException("simulated crash after retry claim");
            return jdbc.queryForList("select id from recipient where merchant_id=? and campaign_id=? "
                            + "and status='PENDING' order by id limit ?", Long.class,
                    call.getArgument(0), call.getArgument(1), call.getArgument(2));
        });
        when(mapper.selectStaleProcessingCampaigns(anyInt())).thenAnswer(call -> {
            List<MerchantCrmCampaign> rows = jdbc.query("select * from campaign where status='PROCESSING' "
                            + "and processing_lease_until<current_timestamp order by id limit ?",
                    new Object[] { call.getArgument(0) }, (rs, rowNum) -> campaign(rs));
            return rows;
        });
        when(mapper.reclaimStaleCampaign(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyInt()))
                .thenAnswer(call -> jdbc.update("update campaign set processing_token=?,"
                                + "processing_lease_until=dateadd('SECOND',?,current_timestamp),version=version+1 "
                                + "where merchant_id=? and id=? and owner_member_id=? and status='PROCESSING' "
                                + "and processing_lease_until<current_timestamp and version=?",
                        new Object[] { call.getArgument(4), call.getArgument(5), call.getArgument(0),
                                call.getArgument(1), call.getArgument(2), call.getArgument(3) }));
        when(mapper.invalidateStaleCampaign(anyLong(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(call -> jdbc.update("update campaign set status='NO_ELIGIBLE',"
                                + "processing_token=null,processing_lease_until=null,version=version+1 "
                                + "where merchant_id=? and id=? and owner_member_id=? and status='PROCESSING' "
                                + "and processing_lease_until<current_timestamp and version=?",
                        new Object[] { call.getArgument(0), call.getArgument(1),
                                call.getArgument(2), call.getArgument(3) }));
        when(mapper.invalidateClaimedCampaign(anyLong(), anyLong(), anyLong(), anyString()))
                .thenAnswer(call -> jdbc.update("update campaign set status='NO_ELIGIBLE',"
                                + "processing_token=null,processing_lease_until=null,version=version+1 "
                                + "where merchant_id=? and id=? and owner_member_id=? and status='PROCESSING' "
                                + "and processing_token=?",
                        new Object[] { call.getArgument(0), call.getArgument(1),
                                call.getArgument(2), call.getArgument(3) }));
        when(mapper.renewCampaignLease(anyLong(), anyLong(), anyString(), anyInt())).thenAnswer(call -> jdbc.update(
                "update campaign set processing_lease_until=dateadd('SECOND',?,current_timestamp) "
                        + "where merchant_id=? and id=? and status='PROCESSING' and processing_token=?",
                new Object[] { call.getArgument(3), call.getArgument(0), call.getArgument(1),
                        call.getArgument(2) }));
        when(mapper.finalizeCampaign(anyLong(), anyLong(), anyString())).thenAnswer(call -> jdbc.update(
                "update campaign set status='SUCCESS',processing_token=null,processing_lease_until=null "
                        + "where merchant_id=? and id=? and status='PROCESSING' and processing_token=?",
                new Object[] { call.getArgument(0), call.getArgument(1), call.getArgument(2) }));
        return mapper;
    }

    private BusinessAuditEventMapper databaseBackedAuditMapper() {
        BusinessAuditEventMapper mapper = mock(BusinessAuditEventMapper.class);
        when(mapper.insertBusinessAuditEvent(any())).thenAnswer(call -> {
            if (!auditSucceeds.get()) return 0;
            BusinessAuditEvent event = call.getArgument(0);
            return jdbc.update("insert into audit(owner_id,actor_id,action) values (?,?,?)",
                    event.getOwnerMemberId(), event.getActorMemberId(), event.getAction());
        });
        return mapper;
    }

    private MerchantCrmCampaign selectCampaign() {
        return jdbc.queryForObject("select * from campaign where id=8", (rs, rowNum) -> campaign(rs));
    }

    private MerchantCrmCampaign campaign(ResultSet rs) throws SQLException {
        MerchantCrmCampaign row = new MerchantCrmCampaign();
        row.setId(rs.getLong("id"));
        row.setOwnerMemberId(rs.getLong("owner_member_id"));
        row.setMerchantRowId(rs.getLong("merchant_id"));
        row.setSegmentId(rs.getLong("segment_id"));
        row.setChannel(rs.getString("channel"));
        row.setStatus(rs.getString("status"));
        row.setProcessingToken(rs.getString("processing_token"));
        row.setVersion(rs.getLong("version"));
        row.setRecipientCount(1);
        return row;
    }

    private MerchantCrmCampaign campaign() {
        return selectCampaign();
    }

    private void insertCampaign(String status) {
        jdbc.update("insert into campaign(id,merchant_id,owner_member_id,segment_id,channel,status,version) "
                + "values (8,77,900,5,'IN_APP',?,0)", status);
    }

    private void insertFailedRecipient() {
        jdbc.update("insert into recipient(id,campaign_id,merchant_id,status,failure_code,failure_message,attempt_count) "
                + "values (81,8,77,'FAILED','CHANNEL_FAILED','timeout',1)");
    }

    private void insertCouponNotIssuedRecipient() {
        jdbc.update("insert into recipient(id,campaign_id,merchant_id,status,failure_code,failure_message,attempt_count) "
                + "values (81,8,77,'FAILED','COUPON_NOT_ISSUED','not issued',1)");
    }

    private MerchantCrmCampaign insertStaleProcessingCampaign() {
        insertCampaign(MerchantCrmCampaign.PROCESSING);
        jdbc.update("update campaign set processing_token='stale-token', "
                + "processing_lease_until=dateadd('SECOND',-1,current_timestamp) where id=8");
        return campaign();
    }

    private MerchantCrmCampaign insertClaimedProcessingCampaign() {
        insertCampaign(MerchantCrmCampaign.PROCESSING);
        jdbc.update("update campaign set processing_token='claimed-token', "
                + "processing_lease_until=dateadd('SECOND',300,current_timestamp) where id=8");
        return campaign();
    }

    private long number(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private String text(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }
}

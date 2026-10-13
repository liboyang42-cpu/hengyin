package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsRegistrationScanMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.business.service.impl.VerificationRiskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistrationVerificationAuditTransactionBoundaryTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:registration_actor_audit_tx;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        jdbc.execute("drop all objects");
        jdbc.execute("create table registration(id bigint primary key,verification_status int not null)");
        jdbc.execute("create table settlement(id bigint auto_increment primary key,registration_id bigint not null,payee_id bigint not null)");
        jdbc.execute("create table scan_ledger(id bigint auto_increment primary key,registration_id bigint not null,owner_member_id bigint not null)");
        jdbc.execute("create table audit(id bigint auto_increment primary key,owner_member_id bigint not null,actor_member_id bigint not null)");
        jdbc.update("insert into registration(id,verification_status) values (5001,0)");
    }

    @Test
    void auditFailureRollsBackVerificationSettlementAndLedger() {
        ICmsRegistrationService service = service(false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.verifyAndSettle(registration(), 9001L, 9001L, 1,
                        7002L, "MERCHANT_CHECKIN"));

        assertEquals("核销审计写入失败", error.getMessage());
        assertEquals(0, number("select verification_status from registration where id=5001"));
        assertEquals(0, number("select count(*) from settlement"));
        assertEquals(0, number("select count(*) from scan_ledger"));
        assertEquals(0, number("select count(*) from audit"));
    }

    @Test
    void successCommitsOwnerBusinessFactsAndActualActorAuditTogether() {
        ICmsRegistrationService service = service(true);

        assertTrue(service.verifyAndSettle(registration(), 9001L, 9001L, 1,
                7002L, "MERCHANT_CHECKIN"));

        assertEquals(1, number("select verification_status from registration where id=5001"));
        assertEquals(1, number("select count(*) from settlement where payee_id=9001"));
        assertEquals(1, number("select count(*) from scan_ledger where owner_member_id=9001"));
        assertEquals(1, number("select count(*) from audit where owner_member_id=9001 and actor_member_id=7002"));
    }

    @SuppressWarnings("unchecked")
    private ICmsRegistrationService service(boolean auditSucceeds) {
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        CmsRegistrationScanMapper scans = mock(CmsRegistrationScanMapper.class);
        ISettlementEntryService settlements = mock(ISettlementEntryService.class);
        BusinessAuditEventMapper audits = mock(BusinessAuditEventMapper.class);

        when(registrations.verifyIfNotVerified(5001L, 9001L)).thenAnswer(call -> jdbc.update(
                "update registration set verification_status=1 where id=5001 and verification_status=0"));
        when(scans.selectCmsRegistrationScanList(any())).thenReturn(Collections.emptyList());
        when(scans.insertCmsRegistrationScan(any())).thenAnswer(call -> {
            CmsRegistrationScan row = call.getArgument(0);
            return jdbc.update("insert into scan_ledger(registration_id,owner_member_id) values (?,?)",
                    row.getRegistrationId(), row.getMemberId());
        });
        org.mockito.Mockito.doAnswer(call -> {
            CmsRegistration row = call.getArgument(0);
            Long payee = call.getArgument(1);
            jdbc.update("insert into settlement(registration_id,payee_id) values (?,?)",
                    row.getId(), payee);
            return null;
        }).when(settlements).recordPaidRegistration(any(), any());
        when(audits.insertBusinessAuditEvent(any())).thenAnswer(call -> {
            if (!auditSucceeds) return 0;
            BusinessAuditEvent event = call.getArgument(0);
            return jdbc.update("insert into audit(owner_member_id,actor_member_id) values (?,?)",
                    event.getOwnerMemberId(), event.getActorMemberId());
        });

        CmsRegistrationServiceImpl target = new CmsRegistrationServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(target, "growthService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IGrowthService.class));
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(target, "cmsRegistrationScanMapper", scans);
        ReflectionTestUtils.setField(target, "settlementEntryService", settlements);
        ReflectionTestUtils.setField(target, "businessAuditEventMapper", audits);
        ReflectionTestUtils.setField(target, "merchantReferralBountyService",
                mock(MerchantReferralBountyService.class));
        ReflectionTestUtils.setField(target, "verificationRiskService",
                mock(VerificationRiskService.class));

        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(ICmsRegistrationService.class);
        factory.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        return (ICmsRegistrationService) factory.getProxy();
    }

    private CmsRegistration registration() {
        CmsRegistration row = new CmsRegistration();
        row.setId(5001L);
        row.setOwnerType(1L);
        row.setOwnerId(300L);
        row.setPaymentStatus(2);
        return row;
    }

    private int number(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}

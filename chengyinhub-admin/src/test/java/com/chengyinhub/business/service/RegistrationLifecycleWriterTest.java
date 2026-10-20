package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual writer classes with isolated external collaborators and transactional JDBC state. */
class RegistrationLifecycleWriterTest extends RegistrationLifecycleAuditTransactionTest {
    private JdbcTemplate jdbc;
    private CmsRegistrationMapper registrations;
    private CmsRegistrationServiceImpl service;
    private boolean siblings;

    @BeforeEach
    void writers() {
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("alter table cms_registration add member_id bigint default 23");
        jdbc.execute("alter table cms_registration add update_time timestamp default current_timestamp");
        jdbc.execute("alter table cms_registration add payment_time timestamp");
        jdbc.execute("alter table cms_registration add transaction_id varchar(64)");
        registrations = mock(CmsRegistrationMapper.class);
        when(registrations.selectCmsRegistrationById(anyLong())).thenAnswer(i -> row(i.getArgument(0)));
        when(registrations.selectCmsRegistrationByIdForUpdate(anyLong())).thenAnswer(i -> {
            jdbc.queryForList("select id from cms_registration where id=? for update", new Object[]{i.getArgument(0)});
            return row(i.getArgument(0));
        });
        when(registrations.selectByRegistrationNo(anyString())).thenAnswer(i -> row(12L));
        when(registrations.selectByParentOrderNo(anyString())).thenAnswer(i -> Arrays.asList(row(12L), row(13L)));
        when(registrations.claimPaymentSuccess(anyLong(), anyString(), any(Date.class))).thenAnswer(i ->
                jdbc.update("update cms_registration set payment_status=2,registration_status=2,"
                        + "payment_time=?,update_time=?,transaction_id=? where id=? and payment_status in (0,1) and registration_status=1",
                        i.getArgument(2), i.getArgument(2), i.getArgument(1), i.getArgument(0)));
        when(registrations.repairHistoricalPaidHalfState(anyLong(), anyString(), any(), any(Date.class)))
                .thenAnswer(i -> jdbc.update("update cms_registration set registration_status=2,update_time=?,transaction_id=? "
                        + "where id=? and payment_status=2 and registration_status=1", i.getArgument(3), i.getArgument(1), i.getArgument(0)));
        when(registrations.updateCmsRegistration(any())).thenAnswer(i -> {
            CmsRegistration r = i.getArgument(0);
            return jdbc.update("update cms_registration set payment_status=coalesce(?,payment_status),"
                    + "registration_status=coalesce(?,registration_status),member_id=coalesce(?,member_id),update_time=? where id=?",
                    r.getPaymentStatus(), r.getRegistrationStatus(), r.getMemberId(), r.getUpdateTime(), r.getId());
        });
        when(registrations.claimRegistrationRefunded(anyLong(), anyString(), any(Date.class))).thenAnswer(i ->
                jdbc.update("update cms_registration set payment_status=4,registration_status=3,update_time=? "
                        + "where id=? and payment_status=2 and registration_status=2", new Object[]{i.getArgument(2), i.getArgument(0)}));
        when(registrations.closeExpired(anyLong(), anyString(), anyInt())).thenAnswer(i ->
                jdbc.update("update cms_registration set registration_status=?,update_time=current_timestamp "
                        + "where id=? and payment_status=0 and registration_status=1", new Object[]{i.getArgument(2), i.getArgument(0)}));
        when(registrations.closeExpiredPaying(anyLong(), anyString(), anyInt())).thenAnswer(i ->
                jdbc.update("update cms_registration set payment_status=3,registration_status=?,update_time=current_timestamp "
                        + "where id=? and payment_status=1 and registration_status=1", new Object[]{i.getArgument(2), i.getArgument(0)}));
        service = new CmsRegistrationServiceImpl();
        inject(service, "cmsRegistrationMapper", registrations);
        inject(service, "settlementEntryService", mock(ISettlementEntryService.class));
        inject(service, "umsMemberMapper", mock(UmsMemberMapper.class));
        inject(service, "cmsPointsSettingService", mock(ICmsPointsSettingService.class));
        inject(service, "perkLedgerService", mock(IPerkLedgerService.class));
    }

    @Test
    void actualZeroAmountConfirmationAndReplayDoNotBackfillOrDuplicate() throws Exception {
        transact((c, audit) -> {
            inject(service, "businessAuditEventMapper", audit);
            assertEquals("SUCCESS", service.completeZeroAmountPayment("R12"));
            assertEquals("SUCCESS", service.completeZeroAmountPayment("R12"));
        });
        assertEquals(1, count("business_audit_event"));
        assertEquals(1, count("cms_registration where id=12 and registration_status=2"));
    }

    @Test
    void actualHalfStateRepairPreservesOldPaymentTimestampAndDoesNotRecreateOnReplay() throws Exception {
        Timestamp oldPayment = Timestamp.valueOf("2026-01-02 03:04:05");
        jdbc.update("update cms_registration set payment_status=2,registration_status=1,payment_time=? where id=12", oldPayment);
        transact((c, audit) -> {
            inject(service, "businessAuditEventMapper", audit);
            assertEquals("SUCCESS", ReflectionTestUtils.invokeMethod(service, "completePaymentSuccess", row(12L), "T1"));
            assertEquals("SUCCESS", ReflectionTestUtils.invokeMethod(service, "completePaymentSuccess", row(12L), "T1"));
        });
        assertEquals(1, count("business_audit_event"));
        String json = jdbc.queryForObject("select after_json from business_audit_event", String.class);
        com.fasterxml.jackson.databind.JsonNode event = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertEquals(oldPayment.toInstant().toString(), event.get("payment_recorded_at").asText());
        assertNotEquals(event.get("payment_recorded_at").asText(), event.get("occurred_at").asText());
        assertFalse(event.get("metric_eligible").asBoolean());
    }

    @Test
    void actualMainAndSiblingClaimsRollBackOnSecondAuditFailure() throws Exception {
        siblings = true;
        final BusinessAuditEventMapper[] failingAudit = new BusinessAuditEventMapper[1];
        RuntimeException failure = assertThrows(RuntimeException.class, () -> transact((c, audit) -> {
            BusinessAuditEventMapper fail = mock(BusinessAuditEventMapper.class,
                    org.mockito.AdditionalAnswers.delegatesTo(audit));
            doAnswer(i -> {
                BusinessAuditEvent e = i.getArgument(0);
                if (e.getDomainId() == 13L) throw new IllegalStateException("audit failure");
                return audit.insertBusinessAuditEvent(e);
            }).when(fail).insertBusinessAuditEvent(any());
            failingAudit[0] = fail;
            inject(service, "businessAuditEventMapper", fail);
            service.completeZeroAmountPayment("R12");
        }));
        assertTrue(failure.toString().contains("audit failure"), failure.toString());
        verify(failingAudit[0]).insertBusinessAuditEvent(argThat(e -> Long.valueOf(13L).equals(e.getDomainId())));
        assertEquals(0, count("business_audit_event"));
        assertEquals(2, count("cms_registration where registration_status=1 and payment_status=0"));
    }

    @Test
    void actualGenericNoopDoesNotEmitButRepeatedStateCycleDoes() throws Exception {
        transact((c, audit) -> {
            inject(service, "businessAuditEventMapper", audit);
            CmsRegistration update = new CmsRegistration(); update.setId(12L);
            assertEquals(1, service.updateCmsRegistration(update));
            update.setRegistrationStatus(3); service.updateCmsRegistration(update);
            update.setRegistrationStatus(1); service.updateCmsRegistration(update);
            update.setRegistrationStatus(3); service.updateCmsRegistration(update);
        });
        assertEquals(3, count("business_audit_event"));
        assertEquals(3, jdbc.queryForObject("select count(distinct request_id) from business_audit_event", Integer.class));
        assertEquals(0, count("business_audit_event where action='REGISTRATION_CONFIRMED'"));
    }

    @Test
    void actualExpiredAndPayingSiblingClosureCapturePersistedStatus() throws Exception {
        siblings = true;
        jdbc.update("update cms_registration set payment_status=1 where id=12");
        transact((c, audit) -> {
            inject(service, "businessAuditEventMapper", audit);
            assertTrue(service.settlePayingOne(12L, "ORGANIZER_CANCEL", "test", 3));
            assertFalse(service.settlePayingOne(12L, "ORGANIZER_CANCEL", "test", 3));
        });
        assertEquals(2, count("business_audit_event where action='REGISTRATION_CANCELLED'"));
    }

    @Test
    void actualUnpaidExpiryCapturesOnce() throws Exception {
        transact((c, audit) -> {
            inject(service, "businessAuditEventMapper", audit);
            assertTrue(service.closeExpiredOne(12L));
            assertFalse(service.closeExpiredOne(12L));
        });
        assertEquals(1, count("business_audit_event where action='REGISTRATION_EXPIRED'"));
    }

    @Test
    void actualTeamGroupPurePointsRefundRollsBackOnAuditFailure() throws Exception {
        jdbc.update("update cms_registration set payment_status=2,registration_status=2 where id=12");
        TeamGroupServiceImpl team = new TeamGroupServiceImpl();
        inject(team, "cmsRegistrationMapper", registrations);
        inject(team, "firstReviewPointsClawback", mock(FirstReviewPointsClawbackService.class));
        inject(team, "perkLedgerService", mock(IPerkLedgerService.class));
        final BusinessAuditEventMapper[] failingAudit = new BusinessAuditEventMapper[1];
        RuntimeException failure = assertThrows(RuntimeException.class, () -> transact((c, audit) -> {
            BusinessAuditEventMapper fail = mock(BusinessAuditEventMapper.class);
            when(fail.insertBusinessAuditEvent(any())).thenThrow(new IllegalStateException("audit failure"));
            failingAudit[0] = fail;
            inject(team, "registrationLifecycleAuditMapper", fail);
            ReflectionTestUtils.invokeMethod(team, "refundOne", row(12L), "ticket", "reason");
        }));
        assertTrue(failure.toString().contains("audit failure"), failure.toString());
        verify(failingAudit[0]).insertBusinessAuditEvent(argThat(e -> Long.valueOf(12L).equals(e.getDomainId())));
        verify(registrations).claimRegistrationRefunded(eq(12L), eq("auto-refund"), any(Date.class));
        assertEquals(1, count("cms_registration where id=12 and payment_status=2 and registration_status=2"));
        assertEquals(0, count("business_audit_event"));
    }

    @Test
    void actualRefundApprovalKeepsCancellationDistinctFromPayout() throws Exception {
        jdbc.update("update cms_registration set payment_status=2,registration_status=2 where id=12");
        RefundServiceImpl refunds = new RefundServiceImpl();
        inject(refunds, "cmsRegistrationMapper", registrations);
        inject(refunds, "firstReviewPointsClawback", mock(FirstReviewPointsClawbackService.class));
        inject(refunds, "merchantReferralBountyService", mock(MerchantReferralBountyService.class));
        inject(refunds, "perkLedgerService", mock(IPerkLedgerService.class));
        transact((c, audit) -> {
            inject(refunds, "registrationLifecycleAuditMapper", audit);
            ReflectionTestUtils.invokeMethod(refunds, "markRegistrationRefunded", row(12L));
        });
        assertEquals(1, count("business_audit_event where action='REGISTRATION_CANCELLED'"));
        assertEquals(0, count("business_audit_event where action='REFUND_SUCCEEDED'"));
    }

    private CmsRegistration row(Long id) {
        return jdbc.query("select * from cms_registration where id=?", rs -> {
            if (!rs.next()) return null;
            CmsRegistration r = new CmsRegistration();
            r.setId(id); r.setMemberId(rs.getLong("member_id"));
            r.setPaymentStatus(rs.getInt("payment_status")); r.setRegistrationStatus(rs.getInt("registration_status"));
            r.setUpdateTime(rs.getTimestamp("update_time")); r.setPaymentTime(rs.getTimestamp("payment_time"));
            r.setTransactionId(rs.getString("transaction_id"));
            r.setPayableAmount(BigDecimal.ZERO); r.setWechatPaymentAmount(BigDecimal.ZERO);
            r.setVerificationStatus(0); r.setQrcodeUrl("already-generated");
            if (siblings) r.setParentOrderNo("ORDER");
            return r;
        }, id);
    }

    private static void inject(Object target, String field, Object value) {
        ReflectionTestUtils.setField(target, field, value);
    }
}

package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Legacy financial unit fixtures: real transaction advice, explicit persisted CAS result.
 * Audit persistence/rollback is separately covered by RegistrationLifecycleWriterTest.
 */
public final class RegistrationLifecycleFixture {
    private RegistrationLifecycleFixture() { }

    @SuppressWarnings("unchecked")
    public static <T> T transactional(T target) {
        BusinessAuditEventMapper audit = mock(BusinessAuditEventMapper.class);
        when(audit.insertBusinessAuditEvent(any())).thenAnswer(invocation -> {
            BusinessAuditEvent event = invocation.getArgument(0);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertNotNull(event.getTargetId());
            assertTrue(event.getAfterJson().contains("\"metric_eligible\":false"));
            assertTrue(event.getAfterJson().contains("\"attribution_status\":\"UNKNOWN\""));
            return 1;
        });
        ReflectionTestUtils.setField(target,
                target instanceof com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl
                        ? "businessAuditEventMapper" : "registrationLifecycleAuditMapper", audit);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:lifecycle_legacy_" + UUID.randomUUID());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
                new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    public static CmsRegistration persisted(CmsRegistration source, int payment, int status, Date at) {
        assertNotNull(source, "A persisted fixture must have a source row");
        assertNotNull(source.getId());
        CmsRegistration result = new CmsRegistration();
        BeanUtils.copyProperties(source, result);
        result.setPaymentStatus(payment);
        result.setRegistrationStatus(status);
        result.setUpdateTime(at);
        return result;
    }

    /** claimRegistrationRefunded SQL writes (4,3, supplied updateTime).
     * Keep the old fixture's successful-CAS premise, but only expose evidence after that call.
     */
    public static void refundWinner(CmsRegistrationMapper mapper, CmsRegistration source) {
        AtomicReference<CmsRegistration> stored = new AtomicReference<>();
        when(mapper.selectCmsRegistrationByIdForUpdate(source.getId()))
                .thenAnswer(invocation -> stored.get());
        when(mapper.claimRegistrationRefunded(eq(source.getId()), any(), any()))
                .thenAnswer(invocation -> {
                    stored.set(persisted(source, 4, 3, invocation.getArgument(2, Date.class)));
                    return 1;
                });
    }
}

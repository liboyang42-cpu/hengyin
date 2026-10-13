package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantCrmExportTask;
import com.chengyinhub.business.domain.vo.MerchantCrmExportFile;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantCrmExportTaskMapper;
import com.chengyinhub.business.service.impl.MerchantCrmExportServiceImpl;
import com.chengyinhub.business.domain.vo.MerchantCrmExportRowVO;
import com.chengyinhub.business.service.support.MerchantCrmPrivateExcelWriter;
import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MerchantCrmExportAuditTransactionBoundaryTest {
    private static final long OWNER = 900L;
    private static final long NEW_OWNER = 902L;
    private static final long MERCHANT_ROW = 77L;
    private static final long TASK = 88L;
    private static final String RAW_TOKEN = "expected-download-token-12345678901234567890";
    private static final String STORAGE_KEY = "12345678-1234-1234-1234-123456789abc.xlsx"; // gitleaks:allow -- deterministic temp-file fixture

    @TempDir Path temp;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private AtomicBoolean fileExistedBeforeCommit;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:crm_export_audit_tx_" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        fileExistedBeforeCommit = new AtomicBoolean(false);
        jdbc.execute("create table export_task(id bigint primary key, merchant_id bigint not null, "
                + "merchant_owner_member_id bigint not null, "
                + "status varchar(32) not null, expires_at timestamp not null, token_hash varchar(64) not null, "
                + "storage_key varchar(64) null, downloaded_at timestamp null, download_count int not null)");
        jdbc.update("insert into export_task(id,merchant_id,merchant_owner_member_id,status,expires_at,token_hash,storage_key,"
                        + "downloaded_at,download_count) values (?,?,?,?,?,?,?,null,0)",
                TASK, MERCHANT_ROW, OWNER, MerchantCrmExportTask.SUCCESS,
                new Date(System.currentTimeMillis() + 600_000L), sha256(RAW_TOKEN), STORAGE_KEY);
        new RuoYiConfig().setProfile(temp.resolve("public-profile").toString());
        // 2026-09-15 集成第二段:publish-rules 给 authorizeDownload 加了下载时授权复核(裁决 21),
        // 它会真去解析这份 xlsx,解析不了就 fail-closed 拒绝下载。原来的 3 字节占位文件因此
        // 在 authorizeDownload 就被拒,本用例根本走不到它要验的 completeDownload 事务边界。
        // 按生产同一条写法造一份「零行」真表:没有写过手机号的导出本就不需要复核授权,
        // 复核逻辑自身的覆盖在 MerchantCrmExportServiceImplTest,这里不绕过它、只是不触发它。
        new MerchantCrmPrivateExcelWriter(STORAGE_KEY)
                .exportExcel(new java.util.ArrayList<MerchantCrmExportRowVO>(), "客户CRM");
    }

    @Test
    void negativeControlWithoutSpringProxyCommitsCompletionMarkerBeforeAuditFailure() {
        MerchantCrmExportServiceImpl target = target();
        MerchantCrmExportFile authorized = target.authorizeDownload(OWNER, TASK, RAW_TOKEN);

        assertEquals(0, independentDownloadCount(), "授权回执不能冒充已完成下载");
        assertThrows(ServiceException.class, () -> target.completeDownload(authorized));

        assertEquals(1, independentDownloadCount());
        assertNotNull(independentDownloadedAt());
    }

    @Test
    void springProxyRollsBackDownloadMarkerWhenAuditWriteReturnsZero() {
        IMerchantCrmExportService service = proxy(target());
        MerchantCrmExportFile authorized = service.authorizeDownload(OWNER, TASK, RAW_TOKEN);

        assertEquals(0, independentDownloadCount(), "授权回执不能冒充已完成下载");
        assertThrows(ServiceException.class, () -> service.completeDownload(authorized));

        assertEquals(0, independentDownloadCount());
        assertNull(independentDownloadedAt());
    }

    @Test
    void springProxyCommitsOwnerMismatchInvalidationWhileReturningNotFound() {
        IMerchantCrmExportService service = proxy(reboundOwnerTarget());

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.authorizeDownload(NEW_OWNER, TASK, RAW_TOKEN));

        assertEquals(404, error.getCode());
        assertEquals(MerchantCrmExportTask.FAILED, independentScalar(
                "select status from export_task where id=88"));
        assertNull(independentScalar("select storage_key from export_task where id=88"));
        assertEquals("", independentScalar("select token_hash from export_task where id=88"));
        assertTrue(fileExistedBeforeCommit.get(), "事务提交前不能先删除数据库仍引用的私密文件");
        assertFalse(Files.exists(MerchantCrmPrivateExcelWriter.storageFile(STORAGE_KEY).toPath()));
    }

    private MerchantCrmExportServiceImpl target() {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(OWNER, MerchantPermission.CRM_EXPORT)).thenReturn(
                new MerchantAccessContext(OWNER, MERCHANT_ROW, OWNER, MerchantRoleCode.MERCHANT_OWNER));
        MerchantCrmExportTaskMapper tasks = mock(MerchantCrmExportTaskMapper.class);
        when(tasks.selectOwnedTask(MERCHANT_ROW, OWNER, TASK)).thenAnswer(call -> exportTask());
        when(tasks.markDownloaded(anyLong(), anyLong(), anyLong(), any(Date.class))).thenAnswer(call -> {
            Date downloadedAt = call.getArgument(3);
            return jdbc.update("update export_task set downloaded_at=?,download_count=download_count+1 "
                            + "where merchant_id=? and merchant_owner_member_id=? and id=? "
                            + "and status='SUCCESS' and expires_at>?",
                    downloadedAt, call.getArgument(0), call.getArgument(1), call.getArgument(2), downloadedAt);
        });
        BusinessAuditEventMapper audits = mock(BusinessAuditEventMapper.class);
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(0);

        MerchantCrmExportServiceImpl target = new MerchantCrmExportServiceImpl();
        ReflectionTestUtils.setField(target, "merchantAccessService", access);
        ReflectionTestUtils.setField(target, "merchantCrmExportTaskMapper", tasks);
        ReflectionTestUtils.setField(target, "businessAuditEventMapper", audits);
        return target;
    }

    private MerchantCrmExportServiceImpl reboundOwnerTarget() {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(NEW_OWNER, MerchantPermission.CRM_EXPORT)).thenReturn(
                new MerchantAccessContext(NEW_OWNER, MERCHANT_ROW, NEW_OWNER,
                        MerchantRoleCode.MERCHANT_OWNER));
        MerchantCrmExportTaskMapper tasks = mock(MerchantCrmExportTaskMapper.class);
        when(tasks.selectOwnedTask(MERCHANT_ROW, NEW_OWNER, TASK)).thenReturn(null);
        when(tasks.selectMerchantTaskInternal(MERCHANT_ROW, TASK)).thenAnswer(call -> exportTask());
        when(tasks.invalidateOwnerMismatch(anyLong(), any(), any(Date.class))).thenAnswer(call -> {
            int updated = jdbc.update("update export_task set status='FAILED',storage_key=null,token_hash='' where id=?",
                    call.getArgument(0, Long.class));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    fileExistedBeforeCommit.set(Files.exists(
                            MerchantCrmPrivateExcelWriter.storageFile(STORAGE_KEY).toPath()));
                }
            });
            return updated;
        });

        MerchantCrmExportServiceImpl target = new MerchantCrmExportServiceImpl();
        ReflectionTestUtils.setField(target, "merchantAccessService", access);
        ReflectionTestUtils.setField(target, "merchantCrmExportTaskMapper", tasks);
        return target;
    }

    private IMerchantCrmExportService proxy(MerchantCrmExportServiceImpl target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(IMerchantCrmExportService.class);
        factory.addAdvice(new TransactionInterceptor(transactions,
                new AnnotationTransactionAttributeSource()));
        return (IMerchantCrmExportService) factory.getProxy();
    }

    private MerchantCrmExportTask exportTask() {
        return jdbc.queryForObject("select * from export_task where id=?", new Object[] { TASK }, (rs, rowNum) -> {
            MerchantCrmExportTask row = new MerchantCrmExportTask();
            row.setId(rs.getLong("id"));
            row.setOwnerMemberId(rs.getLong("merchant_owner_member_id"));
            row.setMerchantRowId(rs.getLong("merchant_id"));
            row.setRequesterMemberId(OWNER);
            row.setRequesterRoleCode(MerchantRoleCode.MERCHANT_OWNER);
            row.setIncludePaidAmount(1);
            row.setStatus(rs.getString("status"));
            row.setExpiresAt(rs.getTimestamp("expires_at"));
            row.setTokenHash(rs.getString("token_hash"));
            row.setStorageKey(rs.getString("storage_key"));
            row.setVersion(0);
            return row;
        });
    }

    private int independentDownloadCount() {
        return ((Number) independentScalar("select download_count from export_task where id=88")).intValue();
    }

    private Object independentDownloadedAt() {
        return independentScalar("select downloaded_at from export_task where id=88");
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

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte item : digest) hex.append(String.format("%02x", item & 0xff));
        return hex.toString();
    }
}

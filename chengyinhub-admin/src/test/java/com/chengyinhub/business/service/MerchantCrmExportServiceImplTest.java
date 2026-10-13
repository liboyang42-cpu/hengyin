package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.domain.MerchantCrmExportTask;
import com.chengyinhub.business.domain.dto.MerchantCrmCustomerQuery;
import com.chengyinhub.business.domain.dto.MerchantCrmExportRequest;
import com.chengyinhub.business.domain.vo.MerchantCrmCustomerRowVO;
import com.chengyinhub.business.domain.vo.MerchantCrmExportFile;
import com.chengyinhub.business.domain.vo.MerchantCrmExportRowVO;
import com.chengyinhub.business.domain.vo.MerchantCrmExportTaskVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.mapper.MerchantCrmAppMapper;
import com.chengyinhub.business.mapper.MerchantCrmExportTaskMapper;
import com.chengyinhub.business.service.impl.MerchantCrmExportServiceImpl;
import com.chengyinhub.business.service.impl.ConsentRecordServiceImpl;
import com.chengyinhub.business.service.support.MerchantCrmPrivateExcelWriter;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.FileInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantCrmExportServiceImplTest {
    private static final long OWNER = 900L;
    private static final long NEW_OWNER = 902L;
    private static final long MERCHANT = 77L;
    private static final long PRINCIPAL = OWNER;
    private static final long OPERATOR_PRINCIPAL = 901L;
    @TempDir Path temp;
    private IMerchantAccessService access;
    private MerchantCrmExportTaskMapper taskMapper;
    private MerchantCrmAppMapper crmMapper;
    private BusinessAuditEventMapper auditMapper;
    private ConsentRecordMapper consentMapper;
    private MerchantCrmExportServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        access = mock(IMerchantAccessService.class);
        taskMapper = mock(MerchantCrmExportTaskMapper.class);
        crmMapper = mock(MerchantCrmAppMapper.class);
        auditMapper = mock(BusinessAuditEventMapper.class);
        consentMapper = mock(ConsentRecordMapper.class);
        service = new MerchantCrmExportServiceImpl();
        ReflectionTestUtils.setField(service, "merchantAccessService", access);
        ReflectionTestUtils.setField(service, "merchantCrmExportTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "merchantCrmAppMapper", crmMapper);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);
        ConsentRecordServiceImpl consentService = new ConsentRecordServiceImpl();
        ReflectionTestUtils.setField(consentService, "consentRecordMapper", consentMapper);
        ReflectionTestUtils.setField(service, "consentRecordService", consentService);
        ComplianceProperties compliance = new ComplianceProperties();
        Map<String, String> versions = new HashMap<>();
        versions.put("activity_host_data_sharing", "v2.1");
        compliance.setDocVersions(versions);
        ReflectionTestUtils.setField(service, "complianceProperties", compliance);
        ConsentRecord agreed = new ConsentRecord();
        agreed.setDocVersion("v2.1");
        agreed.setEventType("AGREE");
        when(consentMapper.selectLatest(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq("activity_host_data_sharing"),
                org.mockito.ArgumentMatchers.eq("activity_signup"))).thenReturn(agreed);
        ReflectionTestUtils.setField(service, "tokenSecret", "crm-export-test-secret");
        new RuoYiConfig().setProfile(temp.resolve("public-profile").toString());
        when(access.require(PRINCIPAL, MerchantPermission.CRM_EXPORT)).thenReturn(ownerContext());
    }

    @Test
    void createUsesResolvedOwnerAndStoresOnlyTokenHash() {
        when(taskMapper.insertTask(any())).thenAnswer(invocation -> {
            ((MerchantCrmExportTask) invocation.getArgument(0)).setId(88L);
            return 1;
        });

        MerchantCrmExportTaskVO out = service.create(PRINCIPAL, request());

        ArgumentCaptor<MerchantCrmExportTask> saved = ArgumentCaptor.forClass(MerchantCrmExportTask.class);
        verify(taskMapper).insertTask(saved.capture());
        assertEquals(OWNER, saved.getValue().getOwnerMemberId());
        assertEquals(MERCHANT, saved.getValue().getMerchantRowId());
        assertEquals(PRINCIPAL, saved.getValue().getRequesterMemberId());
        assertEquals(1, saved.getValue().getIncludePaidAmount());
        assertEquals(64, saved.getValue().getTokenHash().length());
        assertNotEquals(out.getDownloadToken(), saved.getValue().getTokenHash());
        assertTrue(out.getDownloadToken().length() >= 40);
    }

    @Test
    void exactReplayReturnsSameDownloadTokenAfterLostFirstResponse() {
        AtomicReference<MerchantCrmExportTask> saved = new AtomicReference<>();
        when(taskMapper.selectByRequestId(MERCHANT, "export-request-1"))
                .thenAnswer(invocation -> saved.get());
        when(taskMapper.insertTask(any())).thenAnswer(invocation -> {
            MerchantCrmExportTask row = invocation.getArgument(0);
            row.setId(88L);
            saved.set(row);
            return 1;
        });

        MerchantCrmExportTaskVO first = service.create(PRINCIPAL, request());
        MerchantCrmExportTaskVO replay = service.create(PRINCIPAL, request());

        assertEquals(first.getId(), replay.getId());
        assertEquals(first.getDownloadToken(), replay.getDownloadToken());
        assertTrue(replay.getDownloadToken().length() >= 40);
    }

    @Test
    void exactReplayRejectsAndInvalidatesTaskAfterMerchantOwnerRebind() {
        AtomicReference<MerchantCrmExportTask> saved = new AtomicReference<>();
        when(taskMapper.selectByRequestId(MERCHANT, "export-request-1"))
                .thenAnswer(invocation -> saved.get());
        when(taskMapper.insertTask(any())).thenAnswer(invocation -> {
            MerchantCrmExportTask row = invocation.getArgument(0);
            row.setId(88L);
            saved.set(row);
            return 1;
        });

        service.create(PRINCIPAL, request());
        when(access.require(PRINCIPAL, MerchantPermission.CRM_EXPORT)).thenReturn(
                new MerchantAccessContext(PRINCIPAL, MERCHANT, NEW_OWNER,
                        MerchantRoleCode.MERCHANT_MANAGER));
        when(taskMapper.invalidateOwnerMismatch(org.mockito.ArgumentMatchers.eq(88L),
                org.mockito.ArgumentMatchers.eq(0), any())).thenReturn(1);

        ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.create(PRINCIPAL, request()));

        assertEquals(409, rejected.getCode());
        verify(taskMapper).invalidateOwnerMismatch(org.mockito.ArgumentMatchers.eq(88L),
                org.mockito.ArgumentMatchers.eq(0), any());
    }

    @Test
    void marketingOperatorCannotCreateExportWithBasicSegmentationRole() {
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.CRM_EXPORT))
                .thenThrow(new ServiceException("当前商家身份无该操作权限", 403));

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.create(OPERATOR_PRINCIPAL, request()));

        assertEquals(403, denied.getCode());
        verify(taskMapper, never()).insertTask(any());
    }

    @Test
    void taskLookupAndInvalidDownloadTokenFailClosedWithinOwner() throws Exception {
        when(taskMapper.selectOwnedTask(MERCHANT, OWNER, 99L)).thenReturn(null);
        ServiceException missing = assertThrows(ServiceException.class, () -> service.status(PRINCIPAL, 99L));
        assertEquals(404, missing.getCode());
        verify(taskMapper).selectOwnedTask(MERCHANT, OWNER, 99L);
        verify(taskMapper, never()).selectTaskInternal(99L);

        MerchantCrmExportTask task = successTask("expected-download-token-12345678901234567890");
        when(taskMapper.selectOwnedTask(MERCHANT, OWNER, 88L)).thenReturn(task);
        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.authorizeDownload(PRINCIPAL, 88L, "wrong-download-token-123456789012345678901"));
        assertEquals(403, denied.getCode());
        verify(taskMapper, never()).markDownloaded(any(), any(), any(), any());
        verify(auditMapper, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void authorizationDoesNotClaimCompletionAndCompletedStreamAppendsAuditWithoutToken() throws Exception {
        String rawToken = "expected-download-token-12345678901234567890";
        MerchantCrmExportTask task = successTask(rawToken);
        Path file = MerchantCrmPrivateExcelWriter.storageFile(task.getStorageKey()).toPath();
        writeEmptyWorkbook(task.getStorageKey());
        when(taskMapper.selectOwnedTask(MERCHANT, OWNER, 88L)).thenReturn(task);
        when(taskMapper.markDownloaded(any(), any(), any(), any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);

        MerchantCrmExportFile out = service.authorizeDownload(PRINCIPAL, 88L, rawToken);

        assertEquals(file.toFile().getCanonicalFile(), out.getFile().getCanonicalFile());
        verify(taskMapper, never()).markDownloaded(any(), any(), any(), any());
        verify(auditMapper, never()).insertBusinessAuditEvent(any());

        service.completeDownload(out);

        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        assertEquals("CRM_EXPORT_DOWNLOAD", audit.getValue().getAction());
        assertFalse(String.valueOf(audit.getValue().getAfterJson()).contains(rawToken));
        assertFalse(String.valueOf(audit.getValue().getRequestId()).contains(rawToken));
    }

    @Test
    void auditReceiptZeroFailsCompletionSoDownloadedMarkCannotCommitAlone() throws Exception {
        String rawToken = "expected-download-token-12345678901234567890";
        MerchantCrmExportTask task = successTask(rawToken);
        writeEmptyWorkbook(task.getStorageKey());
        when(taskMapper.selectOwnedTask(MERCHANT, OWNER, 88L)).thenReturn(task);
        when(taskMapper.markDownloaded(any(), any(), any(), any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(0);

        MerchantCrmExportFile authorized = service.authorizeDownload(PRINCIPAL, 88L, rawToken);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.completeDownload(authorized));

        assertEquals(409, error.getCode());
        verify(taskMapper).markDownloaded(any(), any(), any(), any());
        verify(auditMapper).insertBusinessAuditEvent(any());
    }

    @Test
    void marketingOperatorCannotReadOrDownloadExportTask() {
        String rawToken = "expected-download-token-12345678901234567890";
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.CRM_EXPORT))
                .thenThrow(new ServiceException("当前商家身份无该操作权限", 403));

        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.status(OPERATOR_PRINCIPAL, 88L)).getCode());
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.authorizeDownload(OPERATOR_PRINCIPAL, 88L, rawToken)).getCode());
        verify(taskMapper, never()).selectOwnedTask(any(), any(), any());
        verify(taskMapper, never()).markDownloaded(any(), any(), any(), any());
    }

    @Test
    void reboundStoreOwnerCannotReadOldOwnerExportStatus() {
        when(access.require(NEW_OWNER, MerchantPermission.CRM_EXPORT)).thenReturn(
                new MerchantAccessContext(NEW_OWNER, MERCHANT, NEW_OWNER,
                        MerchantRoleCode.MERCHANT_OWNER));
        MerchantCrmExportTask oldOwnerTask = pendingTask();
        when(taskMapper.selectOwnedTask(MERCHANT, NEW_OWNER, 88L)).thenReturn(null);
        when(taskMapper.selectMerchantTaskInternal(MERCHANT, 88L)).thenReturn(oldOwnerTask);
        when(taskMapper.invalidateOwnerMismatch(org.mockito.ArgumentMatchers.eq(88L),
                org.mockito.ArgumentMatchers.eq(0), any())).thenReturn(1);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.status(NEW_OWNER, 88L));

        assertEquals(404, error.getCode());
        verify(taskMapper).invalidateOwnerMismatch(org.mockito.ArgumentMatchers.eq(88L),
                org.mockito.ArgumentMatchers.eq(0), any());
    }

    @Test
    void reboundStoreOwnerCannotDownloadOldOwnerFileAndFileIsRemoved() throws Exception {
        when(access.require(NEW_OWNER, MerchantPermission.CRM_EXPORT)).thenReturn(
                new MerchantAccessContext(NEW_OWNER, MERCHANT, NEW_OWNER,
                        MerchantRoleCode.MERCHANT_OWNER));
        MerchantCrmExportTask oldOwnerTask = successTask(
                "expected-download-token-12345678901234567890");
        Path oldOwnerFile = MerchantCrmPrivateExcelWriter.storageFile(
                oldOwnerTask.getStorageKey()).toPath();
        Files.write(oldOwnerFile, new byte[] { 1, 2, 3 });
        when(taskMapper.selectOwnedTask(MERCHANT, NEW_OWNER, 88L)).thenReturn(null);
        when(taskMapper.selectMerchantTaskInternal(MERCHANT, 88L)).thenReturn(oldOwnerTask);
        when(taskMapper.invalidateOwnerMismatch(org.mockito.ArgumentMatchers.eq(88L),
                org.mockito.ArgumentMatchers.eq(0), any())).thenReturn(1);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.authorizeDownload(NEW_OWNER, 88L,
                        "expected-download-token-12345678901234567890"));

        assertEquals(404, error.getCode());
        assertFalse(Files.exists(oldOwnerFile), "换绑前生成的私密文件必须失效并删除");
    }

    @Test
    void completedStreamOwnerRaceInvalidatesTaskAndDeletesPrivateFile() throws Exception {
        String rawToken = "expected-download-token-12345678901234567890";
        MerchantCrmExportTask task = successTask(rawToken);
        Path file = MerchantCrmPrivateExcelWriter.storageFile(task.getStorageKey()).toPath();
        writeEmptyWorkbook(task.getStorageKey());
        when(taskMapper.selectOwnedTask(MERCHANT, OWNER, 88L)).thenReturn(task);
        when(taskMapper.markDownloaded(any(), any(), any(), any())).thenReturn(0);
        when(taskMapper.invalidateOwnerMismatch(org.mockito.ArgumentMatchers.eq(88L),
                org.mockito.ArgumentMatchers.eq(0), any())).thenReturn(1);

        MerchantCrmExportFile authorized = service.authorizeDownload(PRINCIPAL, 88L, rawToken);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.completeDownload(authorized));

        assertEquals(410, error.getCode());
        assertFalse(Files.exists(file), "流输出完成后发现换绑，旧私密文件必须删除");
        verify(auditMapper, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void sameOwnerOtherStoreCannotReadOrReuseExportTask() {
        MerchantCrmExportTask storeB = pendingTask();
        storeB.setMerchantRowId(88L);
        when(taskMapper.selectOwnedTask(OWNER, OWNER, 88L)).thenReturn(storeB);
        when(taskMapper.selectByRequestId(88L, "export-request-1")).thenReturn(storeB);
        when(taskMapper.insertTask(any())).thenAnswer(call -> {
            call.getArgument(0, MerchantCrmExportTask.class).setId(99L);
            return 1;
        });

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.status(PRINCIPAL, 88L));
        MerchantCrmExportTaskVO created = service.create(PRINCIPAL, request());

        assertEquals(404, error.getCode());
        assertEquals(99L, created.getId());
        verify(taskMapper).selectOwnedTask(MERCHANT, OWNER, 88L);
        verify(taskMapper, never()).selectOwnedTask(OWNER, OWNER, 88L);
        verify(taskMapper, never()).selectByRequestId(88L, "export-request-1");
    }

    @Test
    void runnerWritesFormulaSafeAlwaysMaskedWorkbookOutsidePublicProfileAndHidesPaidAmount() throws Exception {
        MerchantCrmExportTask pending = pendingTask();
        MerchantCrmExportTask running = pendingTask();
        running.setVersion(1);
        when(taskMapper.selectNextRunnableTask(any(), any())).thenReturn(pending);
        when(taskMapper.claimRunnableTask(org.mockito.ArgumentMatchers.eq(88L),
                org.mockito.ArgumentMatchers.eq(0), any(), any(), any()))
                .thenAnswer(invocation -> {
                    running.setStorageKey(invocation.getArgument(4));
                    return 1;
                });
        when(taskMapper.selectTaskInternal(88L)).thenReturn(running);
        MerchantCrmCustomerRowVO row = new MerchantCrmCustomerRowVO();
        row.setMemberId(41L);
        row.setName("=2+2");
        row.setPhone("13800138000");
        row.setMerchantResponsibleForContact(Boolean.TRUE);
        row.setPaidAmount(new BigDecimal("86.00"));
        row.setTier("repeat");
        when(crmMapper.selectOwnedCustomerPage(any(), any(), any(), any())).thenReturn(java.util.Collections.singletonList(row));
        when(taskMapper.finishSuccess(any(), any(), any(), any(), any())).thenReturn(1);

        assertTrue(service.runNextPending());

        ArgumentCaptor<MerchantCrmCustomerQuery> scoped = ArgumentCaptor.forClass(MerchantCrmCustomerQuery.class);
        verify(crmMapper).selectOwnedCustomerPage(any(), scoped.capture(), any(), any());
        assertEquals(MERCHANT, scoped.getValue().getMerchantId());

        ArgumentCaptor<String> storage = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).finishSuccess(org.mockito.ArgumentMatchers.eq(88L),
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(1), storage.capture(), any());
        Path workbookPath = MerchantCrmPrivateExcelWriter.storageFile(storage.getValue()).toPath();
        assertFalse(workbookPath.toRealPath().startsWith(temp.resolve("public-profile").toAbsolutePath()));
        List<String> strings = workbookStrings(workbookPath);
        assertTrue(strings.contains("\t=2+2"), "公式触发字符必须由仓内ExcelUtil转义");
        assertTrue(strings.contains("138****8000"), "手机号必须始终脱敏");
        assertFalse(strings.contains("13800138000"));
        assertFalse(strings.contains("86.00"), "无CRM_SENSITIVE_READ时金额列必须为空");
    }

    @Test
    void runnerOmitsPhoneWhenCustomerRevokedCurrentConsent() throws Exception {
        ConsentRecord revoked = new ConsentRecord();
        revoked.setDocVersion("v2.1");
        revoked.setEventType("REVOKE");
        when(consentMapper.selectLatest(42L, "activity_host_data_sharing", "activity_signup"))
                .thenReturn(revoked);

        List<String> strings = runSingleCustomerExport(42L, "撤回授权的客户", "13900139000");

        assertFalse(strings.contains("139****9000"), "撤回当前版本同意后导出不得保留脱敏手机号");
        assertFalse(strings.contains("13900139000"), "导出不得泄露完整手机号");
    }

    @Test
    void runnerOmitsPhoneWhenCurrentConsentIsMissing() throws Exception {
        when(consentMapper.selectLatest(43L, "activity_host_data_sharing", "activity_signup"))
                .thenReturn(null);

        List<String> strings = runSingleCustomerExport(43L, "未授权客户", "13700137000");

        assertFalse(strings.contains("137****7000"), "缺失当前版本同意时导出手机号必须为空");
        assertFalse(strings.contains("13700137000"));
    }

    @Test
    void runnerOmitsPhoneWhenCurrentConsentReadFails() throws Exception {
        when(consentMapper.selectLatest(44L, "activity_host_data_sharing", "activity_signup"))
                .thenThrow(new RuntimeException("consent storage unavailable"));

        List<String> strings = runSingleCustomerExport(44L, "同意读取失败客户", "13600136000");

        assertFalse(strings.contains("136****6000"), "同意读取失败时导出手机号必须 fail-closed");
        assertFalse(strings.contains("13600136000"));
    }

    @Test
    void runnerOmitsPhoneWhenClubIsResponsibleEvenWithCurrentConsent() throws Exception {
        List<String> strings = runSingleCustomerExport(45L, "俱乐部带团客户", "13500135000", false);

        assertFalse(strings.contains("135****5000"), "俱乐部负责联系时导出不得绕过组织方");
        assertFalse(strings.contains("13500135000"));
        verify(consentMapper, never()).selectLatest(45L, "activity_host_data_sharing", "activity_signup");
    }

    @Test
    void staleRunningCrashFileIsReclaimedWithoutLeavingSensitiveOrphan() throws Exception {
        String staleStorageKey = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.xlsx";
        MerchantCrmExportTask stale = pendingTask();
        stale.setStatus(MerchantCrmExportTask.RUNNING);
        stale.setVersion(1);
        stale.setStartedAt(new Date(System.currentTimeMillis() - 10 * 60_000L));
        stale.setStorageKey(staleStorageKey);
        Path staleFile = MerchantCrmPrivateExcelWriter.storageFile(staleStorageKey).toPath();
        Files.write(staleFile, new byte[] { 1, 2, 3 });

        MerchantCrmExportTask reclaimed = pendingTask();
        reclaimed.setStatus(MerchantCrmExportTask.RUNNING);
        reclaimed.setVersion(2);
        when(taskMapper.selectNextRunnableTask(any(), any())).thenReturn(stale);
        when(taskMapper.claimRunnableTask(org.mockito.ArgumentMatchers.eq(88L),
                org.mockito.ArgumentMatchers.eq(1), any(), any(), any()))
                .thenAnswer(invocation -> {
                    reclaimed.setStorageKey(invocation.getArgument(4));
                    return 1;
                });
        when(taskMapper.selectTaskInternal(88L)).thenReturn(reclaimed);
        when(crmMapper.selectOwnedCustomerPage(any(), any(), any(), any()))
                .thenReturn(java.util.Collections.emptyList());
        when(taskMapper.finishSuccess(any(), any(), any(), any(), any())).thenReturn(1);

        assertTrue(service.runNextPending());

        assertFalse(Files.exists(staleFile), "崩溃前已写入的私密导出文件必须在接管时删除");
    }

    @Test
    void runnerInvalidatesReboundOrInactiveMerchantTaskWithoutReadingOldOwnerData() throws Exception {
        String staleStorageKey = "cccccccc-cccc-cccc-cccc-cccccccccccc.xlsx";
        MerchantCrmExportTask stale = pendingTask();
        stale.setStatus(MerchantCrmExportTask.RUNNING);
        stale.setVersion(1);
        stale.setStartedAt(new Date(System.currentTimeMillis() - 10 * 60_000L));
        stale.setStorageKey(staleStorageKey);
        Path staleFile = MerchantCrmPrivateExcelWriter.storageFile(staleStorageKey).toPath();
        Files.write(staleFile, new byte[] { 1, 2, 3 });

        when(taskMapper.selectNextRunnableTask(any(), any())).thenReturn(stale);
        when(taskMapper.claimRunnableTask(org.mockito.ArgumentMatchers.eq(88L),
                org.mockito.ArgumentMatchers.eq(1), any(), any(), any())).thenReturn(0);
        when(taskMapper.invalidateOwnerMismatch(org.mockito.ArgumentMatchers.eq(88L),
                org.mockito.ArgumentMatchers.eq(1), any())).thenReturn(1);

        assertTrue(service.runNextPending());

        assertFalse(Files.exists(staleFile), "门店换绑或停用后旧 owner 的私密文件必须删除");
        verify(crmMapper, never()).selectOwnedCustomerPage(any(), any(), any(), any());
        verify(taskMapper, never()).finishSuccess(any(), any(), any(), any(), any());
    }

    @Test
    void cleanupDeletesUnreferencedPrivateExportFiles() throws Exception {
        String orphanStorageKey = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.xlsx";
        Path orphan = MerchantCrmPrivateExcelWriter.storageFile(orphanStorageKey).toPath();
        Files.write(orphan, new byte[] { 1, 2, 3 });
        when(taskMapper.selectExpirableTasks(any(), org.mockito.ArgumentMatchers.eq(100)))
                .thenReturn(java.util.Collections.emptyList());
        when(taskMapper.countByStorageKey(orphanStorageKey)).thenReturn(0);

        assertEquals(0, service.expireAndClean());

        assertFalse(Files.exists(orphan));
    }

    @Test
    void expiryCleanupDeletesPrivateFileOnlyAfterDatabaseReferenceIsCleared() throws Exception {
        String storageKey = "dddddddd-dddd-dddd-dddd-dddddddddddd.xlsx";
        MerchantCrmExportTask expired = pendingTask();
        expired.setStorageKey(storageKey);
        Path file = MerchantCrmPrivateExcelWriter.storageFile(storageKey).toPath();
        Files.write(file, new byte[] { 1, 2, 3 });
        when(taskMapper.selectExpirableTasks(any(), org.mockito.ArgumentMatchers.eq(100)))
                .thenReturn(java.util.Collections.singletonList(expired));
        when(taskMapper.markExpired(org.mockito.ArgumentMatchers.eq(88L), any()))
                .thenAnswer(call -> {
                    assertTrue(Files.exists(file), "清空数据库引用前不能删除私密文件");
                    return 1;
                });

        assertEquals(1, service.expireAndClean());

        assertFalse(Files.exists(file));
    }

    @Test
    void runnerSqlFreezesStorageKeyAndCanReclaimStaleRunningLease() throws Exception {
        java.io.InputStream input = MerchantCrmExportServiceImplTest.class.getClassLoader()
                .getResourceAsStream("mapper/business/MerchantCrmExportTaskMapper.xml");
        assertTrue(input != null, "导出任务 mapper 必须进入运行时制品");
        String xml;
        try (java.io.InputStream stream = input) {
            xml = org.springframework.util.StreamUtils.copyToString(stream, StandardCharsets.UTF_8);
        }

        assertTrue(xml.contains("selectNextRunnableTask"));
        assertTrue(xml.contains("status = 'RUNNING'"));
        assertTrue(xml.contains("started_at &lt;= #{staleBefore}"));
        assertTrue(xml.contains("storage_key = #{storageKey}"));
    }

    /**
     * R10-01 + 裁决 21(2026-09-15):撤回 CRM 共享授权(activity_host_data_sharing)后,
     * 此前生成的导出链接立即失效 —— 下载时按文件里「真写了脱敏手机号」的行逐条重判当前同意。
     *
     * <p>⚠️ 负控:删掉 authorizeDownload 里的 assertConsentBasisStillCurrent 调用 ⇒ 本用例红
     * (退回审计原状:撤回后旧凭证仍可下载、旧脱敏字段仍在)。
     */
    @Test
    void downloadBlockedAfterCustomerRevokesConsent() throws Exception {
        String rawToken = "expected-download-token-12345678901234567890";
        MerchantCrmExportTask task = successTask(rawToken);
        writeWorkbook(task.getStorageKey(), Collections.singletonList(customerRow(42L, "138****8000")));
        ConsentRecord revoked = new ConsentRecord();
        revoked.setDocVersion("v2.1");
        revoked.setEventType("REVOKE");
        when(consentMapper.selectLatest(42L, "activity_host_data_sharing", "activity_signup"))
                .thenReturn(revoked);
        when(taskMapper.selectOwnedTask(MERCHANT, OWNER, 88L)).thenReturn(task);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.authorizeDownload(PRINCIPAL, 88L, rawToken));

        assertEquals(410, error.getCode(), "实际:" + error.getMessage());
        verify(taskMapper, never()).markDownloaded(any(), any(), any(), any());
        verify(auditMapper, never()).insertBusinessAuditEvent(any());
    }

    /** 同意仍是当前版本 AGREE ⇒ 老链接照常可下;撤回校验不是「默认全拒」。 */
    @Test
    void downloadStillAllowedWhileConsentCurrent() throws Exception {
        String rawToken = "expected-download-token-12345678901234567890";
        MerchantCrmExportTask task = successTask(rawToken);
        writeWorkbook(task.getStorageKey(), Collections.singletonList(customerRow(42L, "138****8000")));
        when(taskMapper.selectOwnedTask(MERCHANT, OWNER, 88L)).thenReturn(task);

        MerchantCrmExportFile out = service.authorizeDownload(PRINCIPAL, 88L, rawToken);

        assertEquals(task.getStorageKey(), out.getStorageKey());
    }

    /**
     * 文件里本就没有该客户的手机号(生成时未授权/俱乐部负责联系)⇒ 该客户的同意状态与本文件无关;
     * 不搞「一人撤回、整表牵连」的连带封禁。这条同时钉住校验粒度 = 逐行,不是整个任务一刀切。
     */
    @Test
    void downloadNotBlockedByRowsWithoutConsentDerivedPhone() throws Exception {
        String rawToken = "expected-download-token-12345678901234567890";
        MerchantCrmExportTask task = successTask(rawToken);
        writeWorkbook(task.getStorageKey(), Collections.singletonList(customerRow(43L, null)));
        when(consentMapper.selectLatest(43L, "activity_host_data_sharing", "activity_signup"))
                .thenReturn(null);
        when(taskMapper.selectOwnedTask(MERCHANT, OWNER, 88L)).thenReturn(task);

        MerchantCrmExportFile out = service.authorizeDownload(PRINCIPAL, 88L, rawToken);

        assertEquals(task.getStorageKey(), out.getStorageKey());
    }

    private MerchantCrmExportRowVO customerRow(Long memberId, String maskedPhone) {
        MerchantCrmExportRowVO row = new MerchantCrmExportRowVO();
        row.setCustomerId(String.valueOf(memberId));
        row.setCustomerName("客户" + memberId);
        row.setMaskedPhone(maskedPhone);
        return row;
    }

    private void writeWorkbook(String storageKey, List<MerchantCrmExportRowVO> rows) {
        new MerchantCrmPrivateExcelWriter(storageKey).exportExcel(rows, "客户CRM");
    }

    private void writeEmptyWorkbook(String storageKey) {
        writeWorkbook(storageKey, Collections.emptyList());
    }

    private MerchantCrmExportRequest request() {
        MerchantCrmCustomerQuery query = new MerchantCrmCustomerQuery();
        query.setSegment("repeat");
        MerchantCrmExportRequest out = new MerchantCrmExportRequest();
        out.setQuery(query);
        out.setRequestId("export-request-1");
        return out;
    }

    private List<String> runSingleCustomerExport(Long memberId, String name, String phone) throws Exception {
        return runSingleCustomerExport(memberId, name, phone, true);
    }

    private List<String> runSingleCustomerExport(Long memberId, String name, String phone,
                                                 boolean merchantResponsibleForContact) throws Exception {
        MerchantCrmExportTask pending = pendingTask();
        MerchantCrmExportTask running = pendingTask();
        running.setVersion(1);
        when(taskMapper.selectNextRunnableTask(any(), any())).thenReturn(pending);
        when(taskMapper.claimRunnableTask(org.mockito.ArgumentMatchers.eq(88L),
                org.mockito.ArgumentMatchers.eq(0), any(), any(), any()))
                .thenAnswer(invocation -> {
                    running.setStorageKey(invocation.getArgument(4));
                    return 1;
                });
        when(taskMapper.selectTaskInternal(88L)).thenReturn(running);
        MerchantCrmCustomerRowVO row = new MerchantCrmCustomerRowVO();
        row.setMemberId(memberId);
        row.setName(name);
        row.setPhone(phone);
        row.setMerchantResponsibleForContact(merchantResponsibleForContact);
        when(crmMapper.selectOwnedCustomerPage(any(), any(), any(), any()))
                .thenReturn(java.util.Collections.singletonList(row));
        when(taskMapper.finishSuccess(any(), any(), any(), any(), any())).thenReturn(1);

        assertTrue(service.runNextPending());

        ArgumentCaptor<String> storage = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).finishSuccess(org.mockito.ArgumentMatchers.eq(88L),
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(1), storage.capture(), any());
        return workbookStrings(MerchantCrmPrivateExcelWriter.storageFile(storage.getValue()).toPath());
    }

    private MerchantCrmExportTask pendingTask() {
        MerchantCrmExportTask out = new MerchantCrmExportTask();
        out.setId(88L);
        out.setOwnerMemberId(OWNER);
        out.setMerchantRowId(77L);
        out.setRequesterMemberId(PRINCIPAL);
        out.setRequesterRoleCode(MerchantRoleCode.MERCHANT_OWNER);
        out.setFilterJson("{\"pageNum\":1,\"pageSize\":50,\"segment\":\"repeat\"}");
        out.setIncludePaidAmount(0);
        out.setStatus(MerchantCrmExportTask.PENDING);
        out.setVersion(0);
        out.setRowLimit(5000);
        out.setExpiresAt(new Date(System.currentTimeMillis() + 600000));
        return out;
    }

    private MerchantCrmExportTask successTask(String rawToken) throws Exception {
        MerchantCrmExportTask out = pendingTask();
        out.setStatus(MerchantCrmExportTask.SUCCESS);
        out.setTokenHash(hex(MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8))));
        out.setStorageKey("12345678-1234-1234-1234-123456789abc.xlsx");
        return out;
    }

    private List<String> workbookStrings(Path path) throws Exception {
        List<String> out = new ArrayList<>();
        try (FileInputStream input = new FileInputStream(path.toFile()); XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            for (Sheet sheet : workbook) for (Row row : sheet) for (Cell cell : row) {
                if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) out.add(cell.getStringCellValue());
            }
        }
        return out;
    }

    private String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte item : bytes) out.append(String.format("%02x", item & 0xff));
        return out.toString();
    }

    private MerchantAccessContext ownerContext() {
        return new MerchantAccessContext(PRINCIPAL, 77L, OWNER, MerchantRoleCode.MERCHANT_OWNER);
    }
}

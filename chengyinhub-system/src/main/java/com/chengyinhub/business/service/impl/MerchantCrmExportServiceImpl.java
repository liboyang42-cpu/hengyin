package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantCrmExportTask;
import com.chengyinhub.business.domain.dto.MerchantCrmCustomerQuery;
import com.chengyinhub.business.domain.dto.MerchantCrmExportRequest;
import com.chengyinhub.business.domain.vo.MerchantCrmCustomerRowVO;
import com.chengyinhub.business.domain.vo.MerchantCrmExportFile;
import com.chengyinhub.business.domain.vo.MerchantCrmExportRowVO;
import com.chengyinhub.business.domain.vo.MerchantCrmExportTaskVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantCrmAppMapper;
import com.chengyinhub.business.mapper.MerchantCrmExportTaskMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.business.service.IMerchantCrmExportService;
import com.chengyinhub.business.service.support.MerchantCrmPrivateExcelWriter;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class MerchantCrmExportServiceImpl implements IMerchantCrmExportService {
    private static final Logger log = LoggerFactory.getLogger(MerchantCrmExportServiceImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SIGNUP_DATA_SHARING_DOC = "activity_host_data_sharing";
    private static final String SIGNUP_DATA_SHARING_SCENE = "activity_signup";
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{6,64}");
    private static final int ROW_LIMIT = 5000;
    private static final long TOKEN_TTL_MILLIS = 15L * 60L * 1000L;
    private static final long RUNNING_LEASE_MILLIS = 5L * 60L * 1000L;

    @Autowired private IMerchantAccessService merchantAccessService;
    @Autowired private MerchantCrmExportTaskMapper merchantCrmExportTaskMapper;
    @Autowired private MerchantCrmAppMapper merchantCrmAppMapper;
    @Autowired private BusinessAuditEventMapper businessAuditEventMapper;
    @Autowired private IConsentRecordService consentRecordService;
    @Autowired private ComplianceProperties complianceProperties;
    @Value("${token.secret}") private String tokenSecret;

    @Override
    public MerchantCrmExportTaskVO create(Long principalMemberId, MerchantCrmExportRequest request) {
        MerchantAccessContext access = merchantAccessService.require(principalMemberId, MerchantPermission.CRM_EXPORT);
        String requestId = requestId(request == null ? null : request.getRequestId());
        MerchantCrmCustomerQuery query = normalizeQuery(request == null ? null : request.getQuery());
        String filterJson = writeJson(query);
        int includePaid = access.hasPermission(MerchantPermission.CRM_SENSITIVE_READ) ? 1 : 0;
        MerchantCrmExportTask replay = merchantCrmExportTaskMapper.selectByRequestId(
                access.getMerchantId(), requestId);
        if (replay != null) return exactReplay(replay, access, principalMemberId, filterJson, includePaid);

        MerchantCrmExportTask row = new MerchantCrmExportTask();
        row.setOwnerMemberId(access.getOwnerMemberId());
        row.setMerchantRowId(access.getMerchantId());
        row.setRequesterMemberId(principalMemberId);
        row.setRequesterRoleCode(access.getRoleCode());
        row.setFilterJson(filterJson);
        row.setIncludePaidAmount(includePaid);
        row.setStatus(MerchantCrmExportTask.PENDING);
        row.setVersion(0);
        row.setRowLimit(ROW_LIMIT);
        row.setRowCount(0);
        row.setRequestId(requestId);
        long expiresAt = (System.currentTimeMillis() + TOKEN_TTL_MILLIS) / 1000L * 1000L;
        row.setExpiresAt(new Date(expiresAt));
        String token = token(row);
        row.setTokenHash(sha256Hex(token));
        try {
            if (merchantCrmExportTaskMapper.insertTask(row) != 1) throw new ServiceException("创建导出任务失败", 409);
        } catch (DuplicateKeyException e) {
            MerchantCrmExportTask existing = merchantCrmExportTaskMapper.selectByRequestId(
                    access.getMerchantId(), requestId);
            return exactReplay(existing, access, principalMemberId, filterJson, includePaid);
        }
        MerchantCrmExportTaskVO out = toVO(row);
        out.setDownloadToken(token);
        return out;
    }

    @Override
    public MerchantCrmExportTaskVO status(Long principalMemberId, Long taskId) {
        MerchantAccessContext access = merchantAccessService.require(principalMemberId, MerchantPermission.CRM_EXPORT);
        MerchantCrmExportTask task = ownedTask(access, positive(taskId, "导出任务ID无效"));
        return toVO(task);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = ServiceException.class)
    public MerchantCrmExportFile authorizeDownload(Long principalMemberId, Long taskId, String rawToken) {
        MerchantAccessContext access = merchantAccessService.require(principalMemberId, MerchantPermission.CRM_EXPORT);
        MerchantCrmExportTask task = ownedTask(access, positive(taskId, "导出任务ID无效"));
        if (Integer.valueOf(1).equals(task.getIncludePaidAmount())
                && !access.hasPermission(MerchantPermission.CRM_SENSITIVE_READ)) {
            throw new ServiceException("当前角色无权下载含敏感字段的导出文件", 403);
        }
        Date now = new Date();
        if (!MerchantCrmExportTask.SUCCESS.equals(task.getStatus())) throw new ServiceException("导出文件尚未就绪", 409);
        if (task.getExpiresAt() == null || !task.getExpiresAt().after(now)) throw new ServiceException("导出文件已过期", 410);
        if (rawToken == null || rawToken.length() < 32 || rawToken.length() > 128
                || !MessageDigest.isEqual(bytes(task.getTokenHash()), bytes(sha256Hex(rawToken)))) {
            throw new ServiceException("导出下载凭证无效", 403);
        }
        if (task.getVersion() == null || task.getStorageKey() == null) {
            throw new ServiceException("导出下载事实不完整", 500);
        }
        File file = MerchantCrmPrivateExcelWriter.storageFile(task.getStorageKey());
        if (!file.isFile()) throw new ServiceException("导出文件不存在", 404);
        // R10-01 + 裁决 21(2026-09-15):撤回 CRM 共享授权后,此前生成的导出下载链接立即失效。
        // 校验放在下载时、按文件自报的证据做:只有「文件里真的写了脱敏手机号」的客户才需要
        // 当前仍有 activity_host_data_sharing 同意 —— 没写过手机号的行不受影响,不搞连带封禁。
        assertConsentBasisStillCurrent(task, file);
        return new MerchantCrmExportFile(file, "merchant-crm-" + task.getId() + ".xlsx",
                task.getId(), task.getVersion(), task.getStorageKey(),
                access.getMerchantId(), access.getOwnerMemberId(),
                access.getPrincipalMemberId(), access.getRoleCode());
    }

    /**
     * 裁决 21:下载时校验「文件里的联系方式是否仍有当前授权」。
     *
     * <p><b>为什么读文件而不是查任务表</b>:文件生成时按客户逐行判过同意(见
     * {@link #exportRows}),哪一行带手机号就是当时的授权证据 —— 制品自报出身,
     * 不需要再加一列快照,也避免「快照列没回填就永远拦不住」的新缺口。
     *
     * <p>撤回后:命中即拒(410),文件交给既有到期清理(≤15 分钟)回收;
     * 不用改任务状态 —— token 过期与撤回校验都在下载口,任何一次下载都会重新判。
     *
     * <p>读取失败 fail-closed:校验不出「无手机号」就拒绝下载,不把不确定当安全。
     */
    private void assertConsentBasisStillCurrent(MerchantCrmExportTask task, File file) {
        List<MerchantCrmExportRowVO> rows;
        try {
            rows = new MerchantCrmPrivateExcelWriter(task.getStorageKey())
                    .importExcel(new java.io.FileInputStream(file), 0);
        } catch (Exception e) {
            log.error("CRM 导出文件校验读取失败,拒绝下载 taskId={}", task.getId(), e);
            throw new ServiceException("导出文件校验失败，请重新生成", 500);
        }
        for (MerchantCrmExportRowVO row : rows == null ? new ArrayList<MerchantCrmExportRowVO>() : rows) {
            if (row == null || row.getMaskedPhone() == null || row.getMaskedPhone().trim().isEmpty()) {
                continue;
            }
            Long customerMemberId = parseMemberId(row.getCustomerId());
            if (customerMemberId == null || !hasCurrentDataSharingConsent(customerMemberId)) {
                throw new ServiceException("客户已撤回数据共享授权，导出下载链接已失效", 410);
            }
        }
    }

    private Long parseMemberId(String value) {
        if (value == null) return null;
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = ServiceException.class)
    public void completeDownload(MerchantCrmExportFile authorizedFile) {
        requireCompletionReceipt(authorizedFile);
        Date now = new Date();
        if (merchantCrmExportTaskMapper.markDownloaded(
                authorizedFile.getMerchantId(), authorizedFile.getOwnerMemberId(),
                authorizedFile.getTaskId(), now) != 1) {
            MerchantCrmExportTask stale = new MerchantCrmExportTask();
            stale.setId(authorizedFile.getTaskId());
            stale.setVersion(authorizedFile.getTaskVersion());
            stale.setStorageKey(authorizedFile.getStorageKey());
            invalidateOwnerMismatchAndDelete(stale, now);
            throw new ServiceException("导出文件已过期", 410);
        }
        appendDownloadAudit(authorizedFile, now);
    }

    @Override
    public boolean runNextPending() {
        Date now = new Date();
        Date staleBefore = new Date(now.getTime() - RUNNING_LEASE_MILLIS);
        MerchantCrmExportTask pending = merchantCrmExportTaskMapper.selectNextRunnableTask(now, staleBefore);
        if (pending == null) return false;
        String previousStorageKey = pending.getStorageKey();
        String claimedStorageKey = UUID.randomUUID().toString() + ".xlsx";
        if (merchantCrmExportTaskMapper.claimRunnableTask(
                pending.getId(), pending.getVersion(), now, staleBefore, claimedStorageKey) != 1) {
            return invalidateOwnerMismatchAndDelete(pending, now);
        }
        MerchantCrmExportTask task = merchantCrmExportTaskMapper.selectTaskInternal(pending.getId());
        int runningVersion = pending.getVersion() + 1;
        String storageKey = claimedStorageKey;
        try {
            if (task == null) throw new ServiceException("导出任务不存在", 404);
            if (!claimedStorageKey.equals(task.getStorageKey())) {
                throw new ServiceException("导出任务存储键未冻结", 409);
            }
            if (previousStorageKey != null && !previousStorageKey.equals(claimedStorageKey)) {
                deletePrivateFileAfterCommit(previousStorageKey);
            }
            MerchantCrmCustomerQuery query = normalizeQuery(JSON.readValue(task.getFilterJson(), MerchantCrmCustomerQuery.class));
            query.setMerchantId(task.getMerchantRowId());
            List<MerchantCrmCustomerRowVO> source = merchantCrmAppMapper.selectOwnedCustomerPage(
                    task.getOwnerMemberId(), query, 0, task.getRowLimit() + 1);
            if (source == null) source = new ArrayList<>();
            if (source.size() > task.getRowLimit()) throw new ServiceException("导出数据超过5000行，请缩小筛选范围", 413);
            List<MerchantCrmExportRowVO> rows = exportRows(source, Integer.valueOf(1).equals(task.getIncludePaidAmount()));
            Files.deleteIfExists(MerchantCrmPrivateExcelWriter.storageFile(storageKey).toPath());
            new MerchantCrmPrivateExcelWriter(storageKey).exportExcel(rows, "客户CRM");
            if (merchantCrmExportTaskMapper.finishSuccess(task.getId(), runningVersion,
                    rows.size(), storageKey, new Date()) != 1) {
                throw new ServiceException("导出任务状态已变化", 409);
            }
        } catch (Exception e) {
            MerchantCrmExportTask claimed = task;
            if (claimed == null) {
                claimed = new MerchantCrmExportTask();
                claimed.setId(pending.getId());
                claimed.setVersion(runningVersion);
                claimed.setStorageKey(storageKey);
            }
            if (!invalidateOwnerMismatchAndDelete(claimed, new Date())) {
                merchantCrmExportTaskMapper.finishFailed(pending.getId(), runningVersion,
                        stableFailure(e), new Date());
            }
            deletePrivateFileAfterCommit(storageKey);
            if (previousStorageKey != null && !previousStorageKey.equals(storageKey)) {
                deletePrivateFileAfterCommit(previousStorageKey);
            }
        }
        return true;
    }

    @Override
    public int expireAndClean() {
        Date now = new Date();
        List<MerchantCrmExportTask> tasks = merchantCrmExportTaskMapper.selectExpirableTasks(now, 100);
        int count = 0;
        if (tasks == null) tasks = new ArrayList<>();
        for (MerchantCrmExportTask task : tasks) {
            try {
                int expired = merchantCrmExportTaskMapper.markExpired(task.getId(), now);
                count += expired;
                if (expired == 1) {
                    deletePrivateFileAfterCommit(task.getStorageKey());
                }
            } catch (Exception ignored) {
                // 保留任务供下一次清理重试；过期校验已阻止下载。
            }
        }
        for (File file : MerchantCrmPrivateExcelWriter.storageFiles()) {
            try {
                if (merchantCrmExportTaskMapper.countByStorageKey(file.getName()) == 0) {
                    Files.deleteIfExists(file.toPath());
                }
            } catch (Exception ignored) {
                // 只清理受控 UUID 文件；数据库回读失败时 fail-closed，保留到下次。
            }
        }
        return count;
    }

    private List<MerchantCrmExportRowVO> exportRows(List<MerchantCrmCustomerRowVO> source, boolean includePaid) {
        List<MerchantCrmExportRowVO> out = new ArrayList<>();
        for (MerchantCrmCustomerRowVO row : source) {
            MerchantCrmExportRowVO item = new MerchantCrmExportRowVO();
            item.setCustomerId(String.valueOf(row.getMemberId()));
            item.setCustomerName(row.getName());
            item.setMaskedPhone(Boolean.TRUE.equals(row.getMerchantResponsibleForContact())
                    && row.getPhone() != null && hasCurrentDataSharingConsent(row.getMemberId())
                    ? maskPhone(row.getPhone()) : null);
            item.setSource(row.getSourceType());
            item.setArrivedCount(row.getArrivedCount());
            item.setPendingCount(row.getPendingCount());
            item.setRefundedCount(row.getRefundedCount());
            BigDecimal amount = row.getPaidAmount();
            item.setPaidAmount(includePaid && amount != null ? amount.toPlainString() : null);
            item.setLastTime(row.getLastTime());
            item.setSegment(row.getTier());
            out.add(item);
        }
        return out;
    }

    private boolean hasCurrentDataSharingConsent(Long customerMemberId) {
        String currentVersion = complianceProperties == null
                ? null : complianceProperties.versionOf(SIGNUP_DATA_SHARING_DOC);
        if (customerMemberId == null || currentVersion == null || currentVersion.trim().isEmpty()
                || consentRecordService == null) {
            log.error("CRM 导出客户联系方式同意校验未就绪，拒绝写入手机号，memberId={}", customerMemberId);
            return false;
        }
        try {
            return consentRecordService.hasCurrentAgreement(customerMemberId, SIGNUP_DATA_SHARING_DOC,
                    SIGNUP_DATA_SHARING_SCENE, currentVersion);
        } catch (RuntimeException e) {
            log.error("CRM 导出客户联系方式同意读取失败，拒绝写入手机号，memberId={}", customerMemberId, e);
            return false;
        }
    }

    private MerchantCrmCustomerQuery normalizeQuery(MerchantCrmCustomerQuery input) {
        MerchantCrmCustomerQuery out = new MerchantCrmCustomerQuery();
        out.setPageNum(1);
        out.setPageSize(50);
        String keyword = input == null || input.getKeyword() == null ? "" : input.getKeyword().trim();
        if (keyword.length() > 50) throw new ServiceException("搜索词过长", 400);
        out.setKeyword(keyword.isEmpty() ? null : keyword);
        String segment = input == null || input.getSegment() == null ? "" : input.getSegment().trim();
        if ("refunded".equals(segment)) segment = "abnormal";
        if (segment.isEmpty() || "all".equals(segment)) segment = null;
        if (segment != null && !Arrays.asList("pending", "repeat", "dormant", "abnormal", "new", "noted").contains(segment)) {
            throw new ServiceException("客户分群筛选无效", 400);
        }
        out.setSegment(segment);
        Long tagId = input == null ? null : input.getTagId();
        if (tagId != null && tagId <= 0) throw new ServiceException("标签筛选无效", 400);
        out.setTagId(tagId);
        Integer sourceType = input == null ? null : input.getSourceType();
        if (sourceType != null && sourceType != 1 && sourceType != 2) throw new ServiceException("来源筛选无效", 400);
        out.setSourceType(sourceType);
        LocalDate start = parseDate(input == null ? null : input.getSourceStart(), "来源开始日期无效");
        LocalDate end = parseDate(input == null ? null : input.getSourceEnd(), "来源结束日期无效");
        if (start != null && end != null && (end.isBefore(start) || start.plusDays(366).isBefore(end))) {
            throw new ServiceException("来源时间窗无效", 400);
        }
        out.setSourceStart(start == null ? null : start.toString());
        out.setSourceEnd(end == null ? null : end.toString());
        if (start != null) out.setSourceStartTime(Timestamp.valueOf(start.atStartOfDay()));
        if (end != null) out.setSourceEndExclusive(Timestamp.valueOf(end.plusDays(1).atStartOfDay()));
        return out;
    }

    private MerchantCrmExportTaskVO exactReplay(MerchantCrmExportTask row,
                                                 MerchantAccessContext access, Long actor,
                                                 String filterJson, int includePaid) {
        if (row != null && (access == null
                || !access.getMerchantId().equals(row.getMerchantRowId())
                || !access.getOwnerMemberId().equals(row.getOwnerMemberId()))) {
            invalidateOwnerMismatchAndDelete(row, new Date());
            throw new ServiceException("门店归属已变化，请使用新请求标识重建导出", 409);
        }
        if (row == null || !actor.equals(row.getRequesterMemberId())
                || !jsonEquivalent(filterJson, row.getFilterJson())
                || !Integer.valueOf(includePaid).equals(row.getIncludePaidAmount())) {
            throw new ServiceException("请求标识已用于其他导出", 409);
        }
        String token = token(row);
        if (row.getTokenHash() == null || !MessageDigest.isEqual(
                bytes(row.getTokenHash()), bytes(sha256Hex(token)))) {
            throw new ServiceException("历史导出凭证不可恢复，请使用新请求标识重建", 409);
        }
        MerchantCrmExportTaskVO out = toVO(row);
        out.setDownloadToken(token);
        return out;
    }

    private MerchantCrmExportTask ownedTask(MerchantAccessContext access, Long taskId) {
        MerchantCrmExportTask task = merchantCrmExportTaskMapper.selectOwnedTask(
                access.getMerchantId(), access.getOwnerMemberId(), taskId);
        if (task == null) {
            MerchantCrmExportTask stale = merchantCrmExportTaskMapper.selectMerchantTaskInternal(
                    access.getMerchantId(), taskId);
            invalidateOwnerMismatchAndDelete(stale, new Date());
            throw new ServiceException("导出任务不存在或不属于当前商家", 404);
        }
        return task;
    }

    private boolean invalidateOwnerMismatchAndDelete(MerchantCrmExportTask task, Date now) {
        if (task == null || task.getId() == null || task.getVersion() == null) return false;
        if (merchantCrmExportTaskMapper.invalidateOwnerMismatch(task.getId(), task.getVersion(), now) != 1) {
            return false;
        }
        deletePrivateFileAfterCommit(task.getStorageKey());
        return true;
    }

    private void deletePrivateFileAfterCommit(String storageKey) {
        if (storageKey == null) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        deletePrivateFile(storageKey);
                    }
                });
            }
            return;
        }
        deletePrivateFile(storageKey);
    }

    private void deletePrivateFile(String storageKey) {
        if (storageKey == null) return;
        try { Files.deleteIfExists(MerchantCrmPrivateExcelWriter.storageFile(storageKey).toPath()); }
        catch (Exception ignored) {
            // 数据库引用已清空；孤儿文件清理任务会在下一轮继续删除。
        }
    }

    private MerchantCrmExportTaskVO toVO(MerchantCrmExportTask row) {
        MerchantCrmExportTaskVO out = new MerchantCrmExportTaskVO();
        out.setId(row.getId());
        out.setStatus(row.getStatus());
        out.setRowCount(row.getRowCount());
        out.setErrorMessage(row.getErrorMessage());
        out.setExpiresAt(row.getExpiresAt());
        return out;
    }

    private void appendDownloadAudit(MerchantCrmExportFile authorizedFile, Date now) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("MERCHANT");
        event.setDomainId(authorizedFile.getMerchantId());
        event.setOwnerMemberId(authorizedFile.getOwnerMemberId());
        event.setActorMemberId(authorizedFile.getActorMemberId());
        event.setActorRoleCode(authorizedFile.getActorRoleCode());
        event.setAction("CRM_EXPORT_DOWNLOAD");
        event.setTargetType("CRM_EXPORT_TASK");
        event.setTargetId(authorizedFile.getTaskId());
        event.setAfterJson("{\"status\":\"SUCCESS\"}");
        event.setRequestId("crm-exp-dl-" + sha256Hex(authorizedFile.getTaskId() + ":" + authorizedFile.getActorMemberId()
                + ":" + now.getTime()).substring(0, 48));
        event.setCreateTime(now);
        if (businessAuditEventMapper.insertBusinessAuditEvent(event) != 1) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            throw new ServiceException("导出下载审计写入失败", 409);
        }
    }

    private void requireCompletionReceipt(MerchantCrmExportFile authorizedFile) {
        if (authorizedFile == null || authorizedFile.getTaskId() == null
                || authorizedFile.getTaskVersion() == null || authorizedFile.getStorageKey() == null
                || authorizedFile.getMerchantId() == null || authorizedFile.getOwnerMemberId() == null
                || authorizedFile.getActorMemberId() == null || authorizedFile.getActorRoleCode() == null
                || authorizedFile.getActorRoleCode().trim().isEmpty()) {
            throw new ServiceException("导出下载完成回执无效", 500);
        }
    }

    private String maskPhone(String value) {
        String phone = value == null ? "" : value.trim();
        if (phone.isEmpty()) return null;
        if (phone.contains("****")) return phone;
        // F15:与客户列表共用同一套脱敏实现(PiiCryptoService.maskPhone),导出不再另立一份口径。
        return PiiCryptoService.maskPhone(phone);
    }

    private String stableFailure(Exception error) {
        if (error instanceof ServiceException && ((ServiceException) error).getCode() != null
                && ((ServiceException) error).getCode() == 413) return error.getMessage();
        return "导出生成失败，请稍后重试";
    }

    private String token(MerchantCrmExportTask task) {
        if (task == null || task.getOwnerMemberId() == null || task.getMerchantRowId() == null
                || task.getRequesterMemberId() == null || task.getRequestId() == null
                || task.getIncludePaidAmount() == null || task.getExpiresAt() == null
                || task.getFilterJson() == null || tokenSecret == null || tokenSecret.trim().isEmpty()) {
            throw new ServiceException("导出凭证事实不完整", 500);
        }
        String canonical = "merchant-crm-export-v1\n" + task.getOwnerMemberId() + "\n"
                + task.getMerchantRowId() + "\n" + task.getRequesterMemberId() + "\n"
                + task.getRequestId() + "\n" + task.getIncludePaidAmount() + "\n"
                + task.getExpiresAt().getTime() + "\n" + sha256Hex(task.getFilterJson());
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("JDK 缺少 HmacSHA256", impossible);
        }
    }

    private String requestId(String value) {
        String out = value == null ? "" : value.trim();
        if (!REQUEST_ID.matcher(out).matches()) throw new ServiceException("请求标识无效", 400);
        return out;
    }

    private Long positive(Long value, String message) {
        if (value == null || value <= 0) throw new ServiceException(message, 400);
        return value;
    }

    private String writeJson(MerchantCrmCustomerQuery value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new ServiceException("导出筛选序列化失败", 500); }
    }

    private boolean jsonEquivalent(String left, String right) {
        try { return JSON.readTree(left).equals(JSON.readTree(right)); }
        catch (Exception e) { return false; }
    }

    private LocalDate parseDate(String value, String message) {
        if (value == null || value.trim().isEmpty()) return null;
        try { return LocalDate.parse(value.trim()); }
        catch (DateTimeParseException e) { throw new ServiceException(message, 400); }
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes(value));
            StringBuilder out = new StringBuilder(64);
            for (byte item : digest) out.append(String.format("%02x", item & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    private byte[] bytes(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }
}

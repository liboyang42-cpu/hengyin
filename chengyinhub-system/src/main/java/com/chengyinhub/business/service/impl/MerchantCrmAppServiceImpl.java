package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantCrmCustomerSummary;
import com.chengyinhub.business.domain.MerchantCrmInteraction;
import com.chengyinhub.business.domain.MerchantCrmNote;
import com.chengyinhub.business.domain.MerchantCrmTagAssignment;
import com.chengyinhub.business.domain.MerchantCustomerTag;
import com.chengyinhub.business.domain.dto.MerchantCrmNoteRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmBatchTagRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmTagRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmCustomerQuery;
import com.chengyinhub.business.domain.vo.MerchantCrmCustomerDetailVO;
import com.chengyinhub.business.domain.vo.MerchantCrmBatchTagResultVO;
import com.chengyinhub.business.domain.vo.MerchantCrmContactVO;
import com.chengyinhub.business.domain.vo.MerchantCrmCustomerPageVO;
import com.chengyinhub.business.domain.vo.MerchantCrmCustomerRowVO;
import com.chengyinhub.business.domain.vo.MerchantCrmSegmentCountsVO;
import com.chengyinhub.business.domain.vo.MerchantCrmProfileReconcileVO;
import com.chengyinhub.business.domain.vo.MerchantCrmTagVO;
import com.chengyinhub.business.domain.vo.MerchantCrmSystemTagVO;
import com.chengyinhub.business.domain.vo.MerchantCrmTimelineItemVO;
import com.chengyinhub.business.mapper.MerchantCrmAppMapper;
import com.chengyinhub.business.mapper.MerchantCustomerTagMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.IMerchantCrmAppService;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.business.service.IPlayerRoleService;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.sql.Timestamp;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class MerchantCrmAppServiceImpl implements IMerchantCrmAppService {
    private static final Logger log = LoggerFactory.getLogger(MerchantCrmAppServiceImpl.class);
    private static final String SIGNUP_DATA_SHARING_DOC = "activity_host_data_sharing";
    private static final String SIGNUP_DATA_SHARING_SCENE = "activity_signup";
    private static final int TIMELINE_LIMIT = 50;
    private static final int CUSTOMER_TAG_LIMIT = 20;
    /** F15:同一操作员每个自然日最多成功获取 50 次联系号码,超限拒绝。 */
    private static final int CONTACT_REVEAL_DAILY_LIMIT = 50;
    private static final String ACTION_CONTACT_REVEAL = "CRM_CONTACT_REVEAL";
    /** F15:被拒的越权/超限尝试单独一种动作,不占成功揭示的日额度,也不含号码正文。 */
    private static final String ACTION_CONTACT_REVEAL_DENIED = "CRM_CONTACT_REVEAL_DENIED";
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{6,64}");
    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");

    @Autowired private IMerchantAccessService merchantAccessService;
    @Autowired private MerchantCrmAppMapper merchantCrmAppMapper;
    @Autowired private MerchantCustomerTagMapper merchantCustomerTagMapper;
    @Autowired private BusinessAuditEventMapper businessAuditEventMapper;
    @Autowired private IConsentRecordService consentRecordService;
    @Autowired private ComplianceProperties complianceProperties;
    @Autowired private IPlayerRoleService playerRoleService;

    @Override
    public MerchantCrmCustomerPageVO listCustomers(Long principalMemberId, MerchantCrmCustomerQuery request) {
        MerchantAccessContext access = merchantAccessService.require(principalMemberId, MerchantPermission.CRM_READ);
        MerchantCrmCustomerQuery query = normalizedCustomerQuery(request);
        query.setMerchantId(access.getMerchantId());
        int pageNum = query.getPageNum();
        int pageSize = query.getPageSize();
        int offset = pageOffset(pageNum, pageSize);
        List<MerchantCrmCustomerRowVO> rows = merchantCrmAppMapper.selectOwnedCustomerPage(
                access.getOwnerMemberId(), query, offset, pageSize);
        boolean sensitive = access.hasPermission(MerchantPermission.CRM_SENSITIVE_READ);
        if (rows == null) rows = new ArrayList<>();
        for (MerchantCrmCustomerRowVO row : rows) {
            if (!sensitive) {
                row.setPaidAmount(null);
                row.setPhone(null);
                row.setContactHint("需要客户敏感信息权限");
            } else if (!Boolean.TRUE.equals(row.getMerchantResponsibleForContact())) {
                row.setPhone(null);
                row.setContactHint("由俱乐部带团，联系请找俱乐部负责人");
            } else if (row.getPhone() != null && !hasCurrentDataSharingConsent(row.getMemberId())) {
                row.setPhone(null);
                row.setContactHint("客户未授权联系方式共享");
            } else if (row.getPhone() == null) {
                row.setContactHint("客户未留电话");
            } else {
                // F15:下发前一律脱敏。Mapper 已经把 v1 密文解成明文(否则这里会脱敏成 v****Y),
                // 但明文号码绝不能进响应实体 —— 与俱乐部客户 phoneText、商家旧版客户名册、
                // 导出「手机号(脱敏)」同一口径,三处都是服务端脱敏。
                row.setPhone(PiiCryptoService.maskPhone(row.getPhone()));
            }
            row.setLastAction(lastAction(row));
            // 角色码翻成中文给分组标题用。认不出的码留 null —— 宁可这一档少一个分组,
            // 也别把 NAVIGATOR 这种内部码直接摆到商家的分组标题上。
            String roleName = playerRoleService.roleName(row.getRoleCode());
            row.setRoleName(roleName == null || roleName.isEmpty() ? null : roleName);
        }
        MerchantCrmSegmentCountsVO counts = merchantCrmAppMapper.selectOwnedSegmentCounts(
                access.getOwnerMemberId(), unfilteredCustomerQuery(query));
        MerchantCrmCustomerPageVO out = new MerchantCrmCustomerPageVO();
        out.setRows(rows);
        out.setTotal(merchantCrmAppMapper.selectOwnedCustomerCount(access.getOwnerMemberId(), query));
        out.setPageNum(pageNum);
        out.setPageSize(pageSize);
        out.setSegmentCounts(counts == null ? new MerchantCrmSegmentCountsVO() : counts);
        out.setAvailableTags(selectAvailableTags(access.getMerchantId()));
        out.setCouponDeliveryStatus("CONSENT_REQUIRED");
        out.setNotificationDeliveryStatus("CONSENT_REQUIRED");
        return out;
    }

    private boolean hasCurrentDataSharingConsent(Long customerMemberId) {
        String currentVersion = complianceProperties == null
                ? null : complianceProperties.versionOf(SIGNUP_DATA_SHARING_DOC);
        if (customerMemberId == null || currentVersion == null || currentVersion.trim().isEmpty()
                || consentRecordService == null) {
            log.error("CRM 客户联系方式同意校验未就绪，拒绝下发手机号，memberId={}", customerMemberId);
            return false;
        }
        try {
            return consentRecordService.hasCurrentAgreement(customerMemberId, SIGNUP_DATA_SHARING_DOC,
                    SIGNUP_DATA_SHARING_SCENE, currentVersion);
        } catch (RuntimeException e) {
            log.error("CRM 客户联系方式同意读取失败，拒绝下发手机号，memberId={}", customerMemberId, e);
            return false;
        }
    }

    @Override
    // noRollbackFor:被拒的越权/超限尝试也要留审计。拒绝是把 ServiceException 抛出方法,
    // 默认它会把同一事务里刚写的 DENIED 审计一起回滚掉;本方法除审计外没有任何其它写操作,
    // 因此对 ServiceException 不回滚是安全的(成功路径写审计失败仍按原样 409 fail-closed)。
    @Transactional(rollbackFor = Exception.class, noRollbackFor = ServiceException.class)
    public MerchantCrmContactVO revealContact(Long principalMemberId, Long customerMemberId, String purpose) {
        // F15 联系号码:列表/导出继续只给脱敏号,明文只从这一条显式授权的接口出。
        // 权限与列表敏感档同一枚(CRM_SENSITIVE_READ):能看脱敏号不等于能拿明文。
        MerchantAccessContext access;
        try {
            access = merchantAccessService.require(principalMemberId, MerchantPermission.CRM_SENSITIVE_READ);
        } catch (ServiceException denied) {
            // 越权尝试(无该权限/无有效商家关系)也留痕:尽力解析身份,解析不出就不带商家。
            appendContactRevealDeniedAudit(resolveMerchantIdentityQuietly(principalMemberId),
                    principalMemberId, customerMemberId, null, "permission");
            throw denied;
        }
        Long customer = requirePositive(customerMemberId, "客户ID无效");
        String contactPurpose = contactPurpose(purpose);
        MerchantCrmCustomerRowVO row = merchantCrmAppMapper.selectOwnedCustomerContact(
                access.getOwnerMemberId(), customer);
        if (row == null) {
            appendContactRevealDeniedAudit(access, principalMemberId, customer, contactPurpose, "ownership");
            throw new ServiceException("客户不存在或不属于当前商家", 404);
        }
        if (!Boolean.TRUE.equals(row.getMerchantResponsibleForContact())) {
            appendContactRevealDeniedAudit(access, principalMemberId, customer, contactPurpose, "club_led");
            throw new ServiceException("由俱乐部带团，联系请找俱乐部负责人", 403);
        }
        String phone = row.getPhone() == null ? "" : row.getPhone().trim();
        if (phone.isEmpty()) throw new ServiceException("客户未留电话", 404);
        if (!hasCurrentDataSharingConsent(customer)) {
            appendContactRevealDeniedAudit(access, principalMemberId, customer, contactPurpose, "no_consent");
            throw new ServiceException("客户未授权联系方式共享", 403);
        }
        if (countTodayContactReveals(principalMemberId) >= CONTACT_REVEAL_DAILY_LIMIT) {
            appendContactRevealDeniedAudit(access, principalMemberId, customer, contactPurpose, "daily_limit");
            throw new ServiceException("今日获取联系号码次数已达上限，请联系平台客服", 429);
        }
        appendContactRevealAudit(access, principalMemberId, customer, contactPurpose);
        MerchantCrmContactVO out = new MerchantCrmContactVO();
        out.setPhone(phone);
        return out;
    }

    /** 越权审计只做尽力而为:解析失败不回滚(也绝不能把原来的拒绝换成 503)。 */
    private MerchantAccessContext resolveMerchantIdentityQuietly(Long principalMemberId) {
        try {
            return merchantAccessService.resolve(principalMemberId);
        } catch (RuntimeException e) {
            log.warn("CRM 联系号码越权审计:解析操作者商家身份失败,actor={}", principalMemberId, e);
            return null;
        }
    }

    private String contactPurpose(String value) {
        String purpose = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!"call".equals(purpose) && !"copy".equals(purpose)) {
            throw new ServiceException("联系方式用途无效", 400);
        }
        return purpose;
    }

    /**
     * 频控计数与审计同源:每次成功揭示恰好写一条 CRM_CONTACT_REVEAL,不另建计数器。
     * 当天边界交给库侧 curdate(),避免应用与库时区不一致时窗口漂移。
     * 原子性在 Mapper 里:countActorActionToday 是 select ... for update(current read),
     * 同一操作员的并发请求会在锁上排队,后到者必须等前一个提交后再数;普通快照读会双双
     * 读到 49、双双放行,把日限捅到 51+(2026-09-15 在 MySQL 8.0.45 上实测无锁版读回 49)。
     * 读不到就拒(fail-closed)——频控不可用时不拿"没数到"当"没超限"。
     */
    private long countTodayContactReveals(Long actorMemberId) {
        try {
            return Math.max(businessAuditEventMapper.countActorActionToday(
                    actorMemberId, ACTION_CONTACT_REVEAL), 0L);
        } catch (RuntimeException e) {
            log.error("CRM 联系号码频控读取失败，拒绝下发手机号，actor={}", actorMemberId, e);
            throw new ServiceException("联系号码频控不可用，请稍后重试", 503);
        }
    }

    private void appendContactRevealAudit(MerchantAccessContext access, Long actorMemberId,
                                          Long customerMemberId, String purpose) {
        if (appendAuditEvent(access, actorMemberId, customerMemberId, ACTION_CONTACT_REVEAL,
                "purpose=" + purpose, "crm-contact-") != 1) {
            throw new ServiceException("联系方式访问审计写入失败", 409);
        }
    }

    /**
     * 被拒的越权/超限尝试:单独动作码(不占成功揭示的日额度),reason 只写用途与拒绝码,
     * 号码正文一律不落盘 —— 与成功审计同一条禁令。
     * 写失败只告警不抛出:拒绝(403/404/429)本身已经成立,不因审计不可用改写响应。
     */
    private void appendContactRevealDeniedAudit(MerchantAccessContext access, Long actorMemberId,
                                                Long customerMemberId, String purpose, String denyCode) {
        try {
            appendAuditEvent(access, actorMemberId, customerMemberId, ACTION_CONTACT_REVEAL_DENIED,
                    "purpose=" + (purpose == null ? "-" : purpose) + ";deny=" + denyCode,
                    "crm-contact-denied-");
        } catch (RuntimeException e) {
            log.error("CRM 联系号码越权/超限审计写入失败，actor={}，deny={}", actorMemberId, denyCode, e);
        }
    }

    private int appendAuditEvent(MerchantAccessContext access, Long actorMemberId, Long customerMemberId,
                                 String action, String reason, String requestIdPrefix) {
        Date now = new Date();
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("MERCHANT");
        // 权限被拒时解析不出商家(access=null):用 0 表示「无有效商家身份」,不伪造归属。
        event.setDomainId(access == null || access.getMerchantId() == null ? 0L : access.getMerchantId());
        event.setOwnerMemberId(access == null || access.getOwnerMemberId() == null
                ? 0L : access.getOwnerMemberId());
        event.setActorType(BusinessAuditEvent.ACTOR_TYPE_MEMBER);
        event.setActorMemberId(actorMemberId);
        event.setActorRoleCode(access == null || access.getRoleCode() == null
                ? "UNKNOWN" : access.getRoleCode());
        event.setAction(action);
        event.setTargetType("CRM_CUSTOMER");
        event.setTargetId(customerMemberId);
        // 审计只记「谁、何时、哪个客户、什么用途/为何被拒」;号码正文绝不进审计(表注释明令禁止)。
        event.setReason(reason);
        event.setRequestId(requestIdPrefix + sha256Hex(actorMemberId + ":" + customerMemberId
                + ":" + reason + ":" + now.getTime() + ":" + System.nanoTime()).substring(0, 44));
        event.setCreateTime(now);
        return businessAuditEventMapper.insertBusinessAuditEvent(event);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantCrmProfileReconcileVO reconcileProfiles(Long principalMemberId) {
        MerchantAccessContext access = merchantAccessService.require(principalMemberId, MerchantPermission.CRM_SEGMENT);
        Long merchant = access.getMerchantId();
        Long owner = access.getOwnerMemberId();
        int upserted = merchantCrmAppMapper.upsertOwnedCustomerProfiles(merchant, owner);
        int stale = merchantCrmAppMapper.markStaleCustomerProfiles(merchant, owner);
        long sourceCount = merchantCrmAppMapper.countOwnedRegistrationCustomers(owner);
        long activeCount = merchantCrmAppMapper.countActiveCustomerProfiles(merchant);
        MerchantCrmProfileReconcileVO out = new MerchantCrmProfileReconcileVO();
        out.setUpsertedRows(upserted);
        out.setStaleRows(stale);
        out.setSourceCustomerCount(sourceCount);
        out.setActiveProfileCount(activeCount);
        out.setConsistent(sourceCount == activeCount);
        return out;
    }

    @Override
    public MerchantCrmCustomerDetailVO detail(Long principalMemberId, Long customerMemberId) {
        MerchantAccessContext access = merchantAccessService.require(principalMemberId, MerchantPermission.CRM_READ);
        Long customer = requirePositive(customerMemberId, "客户ID无效");
        MerchantCrmCustomerSummary summary = requireOwned(access.getOwnerMemberId(), customer);
        if (!access.hasPermission(MerchantPermission.CRM_SENSITIVE_READ)) {
            summary.setPaidAmount(null);
        }
        List<MerchantCrmTagVO> tags = merchantCrmAppMapper.selectCustomerTags(access.getMerchantId(), customer);
        List<MerchantCrmInteraction> interactions = merchantCrmAppMapper.selectOwnedInteractions(
                access.getOwnerMemberId(), customer, TIMELINE_LIMIT);
        List<MerchantCrmNote> notes = merchantCrmAppMapper.selectCustomerNotes(
                access.getMerchantId(), customer, TIMELINE_LIMIT);
        List<MerchantCrmTimelineItemVO> campaignTimeline =
                merchantCrmAppMapper.selectCustomerCampaignTimeline(
                        access.getMerchantId(), customer, TIMELINE_LIMIT);

        List<MerchantCrmTimelineItemVO> timeline = new ArrayList<>();
        if (interactions != null) {
            for (MerchantCrmInteraction row : interactions) timeline.add(interactionItem(row));
        }
        if (notes != null) {
            for (MerchantCrmNote row : notes) timeline.add(noteItem(row));
        }
        if (campaignTimeline != null) {
            for (MerchantCrmTimelineItemVO row : campaignTimeline) {
                if (row == null || row.getKey() == null || row.getKey().trim().isEmpty()
                        || !"CAMPAIGN".equals(row.getType())) {
                    throw new ServiceException("客户触达回执不完整", 409);
                }
                timeline.add(row);
            }
        }
        timeline.sort(Comparator.comparing(MerchantCrmTimelineItemVO::getOccurredAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (timeline.size() > TIMELINE_LIMIT) timeline = new ArrayList<>(timeline.subList(0, TIMELINE_LIMIT));

        MerchantCrmCustomerDetailVO out = new MerchantCrmCustomerDetailVO();
        out.setSummary(summary);
        List<MerchantCrmTagVO> merchantTags = tags == null ? new ArrayList<>() : tags;
        out.setSystemTags(systemTags(summary));
        out.setMerchantTags(merchantTags);
        out.setTags(merchantTags);
        out.setTimeline(timeline);
        return out;
    }

    private List<MerchantCrmSystemTagVO> systemTags(MerchantCrmCustomerSummary summary) {
        List<MerchantCrmSystemTagVO> tags = new ArrayList<>();
        int arrived = summary == null || summary.getArrivedCount() == null ? 0 : summary.getArrivedCount();
        int pending = summary == null || summary.getPendingCount() == null ? 0 : summary.getPendingCount();
        int refunded = summary == null || summary.getRefundedCount() == null ? 0 : summary.getRefundedCount();
        if (arrived > 0) tags.add(new MerchantCrmSystemTagVO("ARRIVED", "已到店"));
        if (arrived > 1) tags.add(new MerchantCrmSystemTagVO("REPEAT", "多次到店"));
        if (pending > 0) tags.add(new MerchantCrmSystemTagVO("PENDING", "待核销"));
        if (refunded > 0) tags.add(new MerchantCrmSystemTagVO("REFUNDED", "有退款"));
        return tags;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantCrmNote addNote(Long principalMemberId, Long customerMemberId,
                                   MerchantCrmNoteRequest request) {
        MerchantAccessContext access = merchantAccessService.require(principalMemberId, MerchantPermission.CRM_SEGMENT);
        Long customer = requirePositive(customerMemberId, "客户ID无效");
        Long merchant = access.getMerchantId();
        Long owner = access.getOwnerMemberId();
        requireOwned(owner, customer);
        String content = requiredTrimmed(request == null ? null : request.getContent(), 500, "请填写跟进备注");
        String requestId = request == null ? null : requiredTrimmed(request.getRequestId(), 64, "请求标识无效");
        if (!REQUEST_ID.matcher(requestId).matches()) throw new ServiceException("请求标识无效", 400);
        Long correctsNoteId = request == null ? null : request.getCorrectsNoteId();
        if (correctsNoteId != null) {
            correctsNoteId = requirePositive(correctsNoteId, "被更正备注ID无效");
            MerchantCrmNote original = merchantCrmAppMapper.selectNoteById(merchant, customer, correctsNoteId);
            if (original == null || Integer.valueOf(1).equals(original.getHidden())) {
                throw new ServiceException("被更正备注不存在或已隐藏", 404);
            }
        }

        MerchantCrmNote existing = merchantCrmAppMapper.selectNoteByRequestId(merchant, requestId);
        if (existing != null) return exactReplay(existing, customer, content, correctsNoteId);

        MerchantCrmNote row = new MerchantCrmNote();
        row.setMerchantId(merchant);
        row.setCustomerMemberId(customer);
        row.setActorMemberId(principalMemberId);
        row.setRequestId(requestId);
        row.setCorrectsNoteId(correctsNoteId);
        row.setContent(content);
        try {
            if (merchantCrmAppMapper.insertNote(row) != 1) throw new ServiceException("跟进备注保存失败", 409);
        } catch (DuplicateKeyException e) {
            return exactReplay(merchantCrmAppMapper.selectNoteByRequestId(merchant, requestId),
                    customer, content, correctsNoteId);
        }
        MerchantCrmNote saved = merchantCrmAppMapper.selectNoteByRequestId(merchant, requestId);
        if (saved == null) {
            throw new ServiceException("跟进备注保存结果不可确认", 409);
        }
        return exactReplay(saved, customer, content, correctsNoteId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void hideNote(Long principalMemberId, Long customerMemberId, Long noteId,
                         Integer expectedVersion, String requestIdValue) {
        MerchantAccessContext access = merchantAccessService.require(principalMemberId, MerchantPermission.CRM_SEGMENT);
        Long merchant = access.getMerchantId();
        Long owner = access.getOwnerMemberId();
        Long customer = requirePositive(customerMemberId, "客户ID无效");
        Long note = requirePositive(noteId, "备注ID无效");
        if (expectedVersion == null || expectedVersion < 0) throw new ServiceException("备注版本无效", 400);
        String requestId = requiredRequestId(requestIdValue);
        requireOwned(owner, customer);
        MerchantCrmNote existing = merchantCrmAppMapper.selectNoteById(merchant, customer, note);
        if (existing == null) throw new ServiceException("备注不存在或不属于当前客户", 404);
        if (Integer.valueOf(1).equals(existing.getHidden())) {
            if (requestId.equals(existing.getHiddenRequestId())
                    && Integer.valueOf(expectedVersion + 1).equals(existing.getVersion())) return;
            throw new ServiceException("备注已隐藏或版本已变化", 409);
        }
        if (!expectedVersion.equals(existing.getVersion())
                || merchantCrmAppMapper.hideNoteCas(merchant, customer, note, expectedVersion,
                principalMemberId, requestId) != 1) {
            throw new ServiceException("备注版本已变化，请刷新后重试", 409);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantCrmTagVO assignTag(Long principalMemberId, Long customerMemberId,
                                      MerchantCrmTagRequest request) {
        MerchantAccessContext access = merchantAccessService.require(principalMemberId, MerchantPermission.CRM_SEGMENT);
        Long customer = requirePositive(customerMemberId, "客户ID无效");
        Long merchant = access.getMerchantId();
        Long owner = access.getOwnerMemberId();
        requireOwned(owner, customer);
        String name = requiredTrimmed(request == null ? null : request.getTagName(), 16, "请填写标签名称");
        String color = request == null || request.getTagColor() == null ? "#2E6D5A" : request.getTagColor().trim();
        String requestId = request == null ? null : requiredRequestId(request.getRequestId());
        if (!COLOR.matcher(color).matches()) throw new ServiceException("标签颜色无效", 400);

        String tagCode = tagCodeFor(name);
        MerchantCustomerTag tag = selectTag(merchant, tagCode);
        if (tag == null) {
            MerchantCustomerTag created = new MerchantCustomerTag();
            created.setMerchantId(merchant);
            created.setMerchantScopeVersion(2);
            created.setTagCode(tagCode);
            created.setTagName(name);
            created.setTagColor(color.toUpperCase());
            created.setSort(0);
            created.setDelFlag(0);
            try {
                merchantCustomerTagMapper.insertMerchantCustomerTag(created);
            } catch (DuplicateKeyException ignored) {
                // 同一商家并发创建同名标签，由唯一键选出唯一事实。
            }
            tag = selectTag(merchant, tagCode);
            if (tag == null && created.getId() != null) tag = created;
            if (tag == null) throw new ServiceException("标签创建失败", 409);
        }
        if (!normalizedTagName(name).equals(normalizedTagName(tag.getTagName()))) {
            throw new ServiceException("标签编码冲突", 409);
        }
        MerchantCrmTagAssignment replay = merchantCrmAppMapper.selectTagAssignmentByRequestId(merchant, requestId);
        if (replay != null) {
            requireExactTagReplay(replay, customer, tag.getId(), MerchantCrmTagAssignment.ASSIGN);
            return tagVO(tag);
        }
        lockCustomerTagState(merchant, owner, customer);
        boolean alreadyAssigned = merchantCrmAppMapper.countCustomerTag(merchant, customer, tag.getId()) > 0;
        if (!alreadyAssigned && merchantCrmAppMapper.countCustomerTags(merchant, customer) >= CUSTOMER_TAG_LIMIT) {
            throw new ServiceException("单个客户最多设置20个标签", 409);
        }
        appendTagAssignment(merchant, customer, tag.getId(), principalMemberId,
                MerchantCrmTagAssignment.ASSIGN, requestId);
        return tagVO(tag);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantCrmBatchTagResultVO batchAssignTag(Long principalMemberId,
                                                       MerchantCrmBatchTagRequest request) {
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.CRM_SEGMENT);
        if (request == null || request.getCustomerMemberIds() == null) {
            throw new ServiceException("请选择客户", 400);
        }
        Set<Long> customers = new LinkedHashSet<>(request.getCustomerMemberIds());
        if (customers.isEmpty() || customers.size() > 100 || customers.contains(null)) {
            throw new ServiceException("每次请选择1至100位客户", 400);
        }
        String batchRequestId = requiredRequestId(request.getRequestId());
        MerchantCrmTagVO tag = null;
        for (Long customer : customers) {
            requirePositive(customer, "客户ID无效");
            MerchantCrmTagRequest one = new MerchantCrmTagRequest();
            one.setTagName(request.getTagName());
            one.setTagColor(request.getTagColor());
            one.setRequestId("batch-" + sha256Hex(batchRequestId + ":" + customer).substring(0, 48));
            tag = assignTag(principalMemberId, customer, one);
        }
        MerchantCrmBatchTagResultVO out = new MerchantCrmBatchTagResultVO();
        out.setProcessedCount(customers.size());
        out.setTag(tag);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeTag(Long principalMemberId, Long customerMemberId, Long tagId, String requestIdValue) {
        MerchantAccessContext access = merchantAccessService.require(principalMemberId, MerchantPermission.CRM_SEGMENT);
        Long customer = requirePositive(customerMemberId, "客户ID无效");
        Long tag = requirePositive(tagId, "标签ID无效");
        Long merchant = access.getMerchantId();
        Long owner = access.getOwnerMemberId();
        requireOwned(owner, customer);
        String requestId = requiredRequestId(requestIdValue);
        MerchantCrmTagAssignment replay = merchantCrmAppMapper.selectTagAssignmentByRequestId(merchant, requestId);
        if (replay != null) {
            requireExactTagReplay(replay, customer, tag, MerchantCrmTagAssignment.REMOVE);
            return;
        }
        lockCustomerTagState(merchant, owner, customer);
        if (merchantCrmAppMapper.countCustomerTag(merchant, customer, tag) == 0) {
            throw new ServiceException("标签不存在或不属于当前客户", 404);
        }
        appendTagAssignment(merchant, customer, tag, principalMemberId,
                MerchantCrmTagAssignment.REMOVE, requestId);
    }

    private MerchantCrmCustomerSummary requireOwned(Long owner, Long customer) {
        MerchantCrmCustomerSummary summary = merchantCrmAppMapper.selectOwnedCustomerSummary(owner, customer);
        if (summary == null) throw new ServiceException("客户不存在或不属于当前商家", 404);
        return summary;
    }

    private MerchantCrmNote exactReplay(MerchantCrmNote existing, Long customer, String content,
                                        Long correctsNoteId) {
        if (existing == null || !customer.equals(existing.getCustomerMemberId())
                || !content.equals(existing.getContent())
                || !java.util.Objects.equals(correctsNoteId, existing.getCorrectsNoteId())) {
            throw new ServiceException("请求标识已用于其他跟进内容", 409);
        }
        return existing;
    }

    private Long requirePositive(Long value, String message) {
        if (value == null || value <= 0) throw new ServiceException(message, 400);
        return value;
    }

    private String requiredTrimmed(String value, int max, String message) {
        String out = value == null ? "" : value.trim();
        if (out.isEmpty() || out.length() > max) throw new ServiceException(message, 400);
        return out;
    }

    private String requiredRequestId(String value) {
        String out = requiredTrimmed(value, 64, "请求标识无效");
        if (!REQUEST_ID.matcher(out).matches()) throw new ServiceException("请求标识无效", 400);
        return out;
    }

    private void appendTagAssignment(Long merchant, Long customer, Long tagId, Long actor,
                                     String action, String requestId) {
        MerchantCrmTagAssignment row = new MerchantCrmTagAssignment();
        row.setMerchantId(merchant);
        row.setCustomerMemberId(customer);
        row.setTagId(tagId);
        row.setAction(action);
        row.setActorMemberId(actor);
        row.setRequestId(requestId);
        try {
            if (merchantCrmAppMapper.insertTagAssignment(row) != 1) {
                throw new ServiceException("客户标签保存失败", 409);
            }
        } catch (DuplicateKeyException e) {
            MerchantCrmTagAssignment replay = merchantCrmAppMapper.selectTagAssignmentByRequestId(merchant, requestId);
            requireExactTagReplay(replay, customer, tagId, action);
        }
    }

    private void requireExactTagReplay(MerchantCrmTagAssignment row, Long customer, Long tagId, String action) {
        if (row == null || !customer.equals(row.getCustomerMemberId()) || !tagId.equals(row.getTagId())
                || !action.equals(row.getAction())) {
            throw new ServiceException("请求标识已用于其他标签操作", 409);
        }
    }

    private void lockCustomerTagState(Long merchant, Long owner, Long customer) {
        merchantCrmAppMapper.upsertOwnedCustomerProfile(merchant, owner, customer);
        Long locked = merchantCrmAppMapper.selectCustomerProfileForUpdate(merchant, customer);
        if (!customer.equals(locked)) {
            throw new ServiceException("客户画像锁定失败，请稍后重试", 409);
        }
    }

    private MerchantCrmTimelineItemVO interactionItem(MerchantCrmInteraction row) {
        MerchantCrmTimelineItemVO out = new MerchantCrmTimelineItemVO();
        String type = row.getType() == null ? "REGISTERED" : row.getType();
        out.setKey("registration-" + row.getRegistrationId());
        out.setType(type);
        String project = row.getProjectTitle() == null || row.getProjectTitle().trim().isEmpty()
                ? "线下活动" : "「" + row.getProjectTitle().trim() + "」";
        if ("ARRIVED".equals(type)) out.setTitle("到店核销");
        else if ("REFUNDED".equals(type)) out.setTitle("发生退款");
        else out.setTitle("报名活动");
        out.setDescription(project);
        out.setOccurredAt(row.getOccurredAt());
        return out;
    }

    private MerchantCrmTimelineItemVO noteItem(MerchantCrmNote row) {
        MerchantCrmTimelineItemVO out = new MerchantCrmTimelineItemVO();
        out.setKey("note-" + row.getId());
        boolean correction = row.getCorrectsNoteId() != null;
        out.setType(correction ? "NOTE_CORRECTION" : "NOTE");
        out.setTitle(correction ? "更正跟进" : "团队跟进");
        out.setDescription(row.getContent());
        out.setOccurredAt(row.getCreateTime());
        out.setNoteId(row.getId());
        out.setNoteVersion(row.getVersion());
        out.setCorrectsNoteId(row.getCorrectsNoteId());
        return out;
    }

    private MerchantCustomerTag selectTag(Long merchant, String tagCode) {
        MerchantCustomerTag query = new MerchantCustomerTag();
        query.setMerchantId(merchant);
        query.setMerchantScopeVersion(2);
        query.setTagCode(tagCode);
        List<MerchantCustomerTag> rows = merchantCustomerTagMapper.selectTagList(query);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private List<MerchantCrmTagVO> selectAvailableTags(Long merchant) {
        MerchantCustomerTag query = new MerchantCustomerTag();
        query.setMerchantId(merchant);
        query.setMerchantScopeVersion(2);
        query.setDelFlag(0);
        List<MerchantCustomerTag> rows = merchantCustomerTagMapper.selectTagList(query);
        List<MerchantCrmTagVO> out = new ArrayList<>();
        if (rows != null) for (MerchantCustomerTag row : rows) out.add(tagVO(row));
        return out;
    }

    /**
     * 分段计数用的「全量口径」查询:只保留 merchant 归属,筛选项一律不带。
     *
     * <p>页头「N 位 · 本月新增 M」说的是这个商家一共有多少客户,不是当前筛选命中多少
     * —— 底部那条「当前筛选 · X 人」才是筛选口径,两行不该是同一个数(CU-M-102:搜一个
     * 不存在的名字,页头跟着变「0 位 · 本月新增 0」,商家会把筛选结果读成客户总量)。
     *
     * <p>必须是**新对象**而不是把原 query 的字段置 null:同一个 query 还要给分页名单和
     * total 用,那两处照旧得带着筛选条件。
     */
    private MerchantCrmCustomerQuery unfilteredCustomerQuery(MerchantCrmCustomerQuery filtered) {
        MerchantCrmCustomerQuery fullScope = new MerchantCrmCustomerQuery();
        fullScope.setMerchantId(filtered.getMerchantId());
        return fullScope;
    }

    private MerchantCrmCustomerQuery normalizedCustomerQuery(MerchantCrmCustomerQuery input) {
        MerchantCrmCustomerQuery out = new MerchantCrmCustomerQuery();
        int pageNum = input == null || input.getPageNum() == null ? 1 : input.getPageNum();
        int pageSize = input == null || input.getPageSize() == null ? 20 : input.getPageSize();
        if (pageNum < 1 || pageSize < 1 || pageSize > 50) throw new ServiceException("分页参数无效", 400);
        out.setPageNum(pageNum);
        out.setPageSize(pageSize);
        String keyword = input == null || input.getKeyword() == null ? "" : input.getKeyword().trim();
        if (keyword.length() > 50) throw new ServiceException("搜索词过长", 400);
        out.setKeyword(keyword.isEmpty() ? null : keyword);
        String segment = input == null || input.getSegment() == null ? "" : input.getSegment().trim();
        if ("refunded".equals(segment)) segment = "abnormal";
        if (segment.isEmpty() || "all".equals(segment)) segment = null;
        if (segment != null && !java.util.Arrays.asList("pending", "repeat", "dormant", "abnormal", "new", "noted").contains(segment)) {
            throw new ServiceException("客户分群筛选无效", 400);
        }
        out.setSegment(segment);
        Long tagId = input == null ? null : input.getTagId();
        if (tagId != null && tagId <= 0) throw new ServiceException("标签筛选无效", 400);
        out.setTagId(tagId);
        Integer sourceType = input == null ? null : input.getSourceType();
        if (sourceType != null && sourceType != 1 && sourceType != 2) {
            throw new ServiceException("来源筛选无效", 400);
        }
        out.setSourceType(sourceType);
        LocalDate start = parseDate(input == null ? null : input.getSourceStart(), "来源开始日期无效");
        LocalDate end = parseDate(input == null ? null : input.getSourceEnd(), "来源结束日期无效");
        if (start != null) out.setSourceStartTime(Timestamp.valueOf(start.atStartOfDay()));
        if (end != null) out.setSourceEndExclusive(Timestamp.valueOf(end.plusDays(1).atStartOfDay()));
        if (start != null && end != null && (end.isBefore(start) || start.plusDays(366).isBefore(end))) {
            throw new ServiceException("来源时间窗无效", 400);
        }
        return out;
    }

    private int pageOffset(int pageNum, int pageSize) {
        long offset = (pageNum - 1L) * pageSize;
        if (offset > Integer.MAX_VALUE) throw new ServiceException("分页参数无效", 400);
        return (int) offset;
    }

    private LocalDate parseDate(String value, String error) {
        if (value == null || value.trim().isEmpty()) return null;
        try { return LocalDate.parse(value.trim()); }
        catch (DateTimeParseException e) { throw new ServiceException(error, 400); }
    }

    private String lastAction(MerchantCrmCustomerRowVO row) {
        if (row == null) return "";
        if (Integer.valueOf(0).compareTo(row.getPendingCount() == null ? 0 : row.getPendingCount()) < 0) return "有待核销报名";
        if (Integer.valueOf(0).compareTo(row.getRefundedCount() == null ? 0 : row.getRefundedCount()) < 0) return "发生退款";
        if (Integer.valueOf(0).compareTo(row.getArrivedCount() == null ? 0 : row.getArrivedCount()) < 0) return "最近已到店";
        return "已报名活动";
    }

    private MerchantCrmTagVO tagVO(MerchantCustomerTag row) {
        MerchantCrmTagVO out = new MerchantCrmTagVO();
        out.setId(row.getId());
        out.setTagName(row.getTagName());
        out.setTagColor(row.getTagColor());
        return out;
    }

    private String tagCodeFor(String tagName) {
        return "app_" + sha256Hex(normalizedTagName(tagName)).substring(0, 48);
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : digest) hex.append(String.format("%02x", item & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    private String normalizedTagName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

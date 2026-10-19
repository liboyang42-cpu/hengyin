package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.UmsMemberIdentity;
import com.chengyinhub.business.domain.UmsMemberIdentityChange;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.UmsMemberIdentityChangeMapper;
import com.chengyinhub.business.mapper.UmsMemberIdentityMapper;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.IdCardUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.uuid.IdUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布者实名改绑(X036):自助提交申请单 → 平台人工审核 → 通过后旧证失效,全程留不可变审计。
 *
 * <p>与 {@link MemberIdentityService} 同口径:
 * <ul>
 *   <li>只做格式与校验位,<b>不</b>做权威二要素核验(是否接入需合规与成本决策);</li>
 *   <li>姓名与号码永不回显,进度接口只回状态、时间与驳回意见;</li>
 *   <li>审核通过是唯一能改写 ums_member_identity 的路径,申请人自己不能覆盖。</li>
 * </ul>
 */
@Service
public class MemberIdentityChangeService {

    public static final String CONSENT_SCENE = "identity_change";
    static final String AUDIT_DOMAIN = "MEMBER_IDENTITY";
    static final String AUDIT_TARGET = "IDENTITY_CHANGE";
    private static final int NAME_MIN = 2;
    private static final int NAME_MAX = 20;
    private static final int REASON_MAX = 200;
    private static final int NOTE_MAX = 200;

    @Autowired
    private UmsMemberIdentityMapper identityMapper;

    @Autowired
    private UmsMemberIdentityChangeMapper changeMapper;

    @Autowired
    private IConsentRecordService consentRecordService;

    @Autowired
    private BusinessAuditEventMapper businessAuditEventMapper;

    /** 申请人提交改绑。必须已登记、新证与现登记不完全相同、同一时刻只有一张待审单。 */
    @Transactional(rollbackFor = Exception.class)
    public Long submit(Long memberId, String realName, String idCard, String reason,
                       boolean consent, String ipAddress) {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录");
        String name = realName == null ? "" : realName.trim();
        if (name.length() < NAME_MIN || name.length() > NAME_MAX) {
            throw new ServiceException("请填写真实姓名(2-" + NAME_MAX + " 个字)");
        }
        if (StringUtils.containsAny(name, "0123456789")) throw new ServiceException("姓名里不应包含数字");
        String normalizedId = IdCardUtils.normalize(idCard);
        if (!IdCardUtils.isValid(normalizedId)) throw new ServiceException("身份证号格式不正确,请核对后重填");
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) throw new ServiceException("请填写变更原因");
        if (why.length() > REASON_MAX) throw new ServiceException("变更原因不超过 " + REASON_MAX + " 字");
        // 原因是自由文本,会进后台列表与审计:挡住把证件号写进原因的情况,避免绕开加密列。
        if (why.matches(".*\\d{6,}.*")) throw new ServiceException("变更原因里不要填写证件号或长串数字");
        if (!consent) throw new ServiceException("请先勾选同意提供真实姓名与身份证号");

        UmsMemberIdentity current = identityMapper.selectByMemberId(memberId);
        if (current == null) throw new ServiceException("还没有登记实名,请在发布或入驻时直接登记");
        if (normalizedId.equalsIgnoreCase(current.getIdCard()) && name.equals(current.getRealName())) {
            throw new ServiceException("与当前登记信息一致,无需变更");
        }

        UmsMemberIdentityChange change = new UmsMemberIdentityChange();
        change.setMemberId(memberId);
        change.setRealName(name);
        change.setIdCard(normalizedId);
        change.setOldIdCardHash(current.getIdCardHash());
        change.setReason(why);
        change.setCreateTime(new Date());
        try {
            changeMapper.insertChange(change);
        } catch (DuplicateKeyException e) {
            throw new ServiceException("已有一张改绑申请在审核中,请等待审核结果或先撤回");
        }
        consentRecordService.record(memberId, MemberIdentityService.CONSENT_DOC_TYPE,
                MemberIdentityService.CONSENT_DOC_VERSION, CONSENT_SCENE, "AGREE",
                IdUtils.fastSimpleUUID(), ipAddress);
        audit(change.getId(), memberId, BusinessAuditEvent.ACTOR_TYPE_MEMBER, memberId, "MEMBER",
                "IDENTITY_CHANGE_SUBMIT", null, UmsMemberIdentityChange.STATUS_PENDING, null);
        return change.getId();
    }

    /** 申请人撤回自己的待审单。 */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long memberId, Long changeId) {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录");
        if (changeId == null || changeId <= 0) throw new ServiceException("参数缺失");
        UmsMemberIdentityChange change = changeMapper.selectByIdForUpdate(changeId);
        if (change == null || !memberId.equals(change.getMemberId())
                || !UmsMemberIdentityChange.STATUS_PENDING.equals(change.getStatus())) {
            throw new ServiceException("没有可撤回的改绑申请");
        }
        if (changeMapper.finishPending(changeId, memberId, UmsMemberIdentityChange.STATUS_CANCELLED,
                null, null) != 1) {
            throw new ServiceException("没有可撤回的改绑申请");
        }
        audit(changeId, memberId, BusinessAuditEvent.ACTOR_TYPE_MEMBER, memberId, "MEMBER",
                "IDENTITY_CHANGE_CANCEL", UmsMemberIdentityChange.STATUS_PENDING,
                UmsMemberIdentityChange.STATUS_CANCELLED, null);
    }

    /** 申请人看进度:只回状态/时间/驳回意见,无任何 PII。没有申请单返回 null。 */
    public Map<String, Object> latest(Long memberId) {
        if (memberId == null || memberId <= 0) return null;
        UmsMemberIdentityChange change = changeMapper.selectLatestByMember(memberId);
        if (change == null) return null;
        Map<String, Object> data = new HashMap<>();
        data.put("id", change.getId());
        data.put("status", change.getStatus());
        data.put("createTime", change.getCreateTime());
        data.put("reviewTime", change.getReviewTime());
        if (UmsMemberIdentityChange.STATUS_REJECTED.equals(change.getStatus())) {
            data.put("reviewNote", change.getReviewNote());
        }
        return data;
    }

    /** 平台审核通过:新证写回 ums_member_identity(旧证密文被覆盖即失效),申请单清密文。 */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long reviewerUserId, Long changeId, String note) {
        String reviewNote = checkReviewer(reviewerUserId, changeId, note, false);
        UmsMemberIdentityChange change = lockPending(changeId);
        UmsMemberIdentity current = identityMapper.selectByMemberId(change.getMemberId());
        if (current == null) throw new ServiceException("申请人当前没有实名登记(可能已注销),不能通过,请驳回");
        if (change.getRealName() == null || change.getIdCard() == null) {
            throw new ServiceException("申请单缺少待审信息,不能通过,请驳回");
        }
        UmsMemberIdentity next = new UmsMemberIdentity();
        next.setMemberId(change.getMemberId());
        next.setRealName(change.getRealName());
        next.setIdCard(change.getIdCard());
        next.setConsentTime(change.getCreateTime());
        next.setUpdateTime(new Date());
        if (identityMapper.rebindIdentity(next) != 1) throw new ServiceException("实名登记写回失败,请重试");
        if (changeMapper.finishPending(changeId, null, UmsMemberIdentityChange.STATUS_APPROVED,
                reviewNote, reviewerUserId) != 1) {
            throw new ServiceException("申请单状态已变化,请刷新后重试");
        }
        audit(changeId, change.getMemberId(), BusinessAuditEvent.ACTOR_TYPE_SYS_USER, reviewerUserId, "SYS_USER",
                "IDENTITY_CHANGE_APPROVE", UmsMemberIdentityChange.STATUS_PENDING,
                UmsMemberIdentityChange.STATUS_APPROVED, reviewNote);
    }

    /** 平台驳回:必须写意见(会展示给申请人),申请单清密文,现登记不动。 */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long reviewerUserId, Long changeId, String note) {
        String reviewNote = checkReviewer(reviewerUserId, changeId, note, true);
        UmsMemberIdentityChange change = lockPending(changeId);
        if (changeMapper.finishPending(changeId, null, UmsMemberIdentityChange.STATUS_REJECTED,
                reviewNote, reviewerUserId) != 1) {
            throw new ServiceException("申请单状态已变化,请刷新后重试");
        }
        audit(changeId, change.getMemberId(), BusinessAuditEvent.ACTOR_TYPE_SYS_USER, reviewerUserId, "SYS_USER",
                "IDENTITY_CHANGE_REJECT", UmsMemberIdentityChange.STATUS_PENDING,
                UmsMemberIdentityChange.STATUS_REJECTED, reviewNote);
    }

    public List<Map<String, Object>> adminList(String status) {
        return changeMapper.selectAdminList(status);
    }

    private String checkReviewer(Long reviewerUserId, Long changeId, String note, boolean noteRequired) {
        if (reviewerUserId == null || reviewerUserId <= 0) throw new ServiceException("审核人无效");
        if (changeId == null || changeId <= 0) throw new ServiceException("参数缺失");
        String value = note == null ? "" : note.trim();
        if (noteRequired && value.isEmpty()) throw new ServiceException("驳回必须填写审核意见");
        if (value.length() > NOTE_MAX) throw new ServiceException("审核意见不超过 " + NOTE_MAX + " 字");
        return value.isEmpty() ? null : value;
    }

    private UmsMemberIdentityChange lockPending(Long changeId) {
        UmsMemberIdentityChange change = changeMapper.selectByIdForUpdate(changeId);
        if (change == null || !UmsMemberIdentityChange.STATUS_PENDING.equals(change.getStatus())) {
            throw new ServiceException("申请单不存在或已处理");
        }
        return change;
    }

    /** before/after 只记状态;姓名、号码、盲索引都不进审计。 */
    private void audit(Long changeId, Long ownerMemberId, String actorType, Long actorId, String roleCode,
                       String action, String before, String after, String reason) {
        BusinessAuditEvent audit = new BusinessAuditEvent();
        audit.setDomainType(AUDIT_DOMAIN);
        audit.setDomainId(ownerMemberId);
        audit.setOwnerMemberId(ownerMemberId);
        audit.setActorType(actorType);
        audit.setActorMemberId(actorId);
        audit.setActorRoleCode(roleCode);
        audit.setAction(action);
        audit.setTargetType(AUDIT_TARGET);
        audit.setTargetId(changeId);
        audit.setBeforeJson(before == null ? null : "{\"status\":\"" + before + "\"}");
        audit.setAfterJson("{\"status\":\"" + after + "\""
                + ("IDENTITY_CHANGE_APPROVE".equals(action) ? ",\"previousIdentityInvalidated\":true" : "") + "}");
        audit.setReason(reason);
        audit.setRequestId(action.toLowerCase().replace('_', '-') + ":" + changeId);
        audit.setCreateTime(new Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(audit) != 1) {
            throw new IllegalStateException("实名改绑审计写入失败");
        }
    }
}

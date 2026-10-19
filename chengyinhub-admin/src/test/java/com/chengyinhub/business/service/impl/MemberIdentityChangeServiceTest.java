package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.UmsMemberIdentity;
import com.chengyinhub.business.domain.UmsMemberIdentityChange;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.UmsMemberIdentityChangeMapper;
import com.chengyinhub.business.mapper.UmsMemberIdentityMapper;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * X036 发布者实名改绑:自助申请单 + 人工审核 + 审计。
 * 验收口径:格式错误即拒;同证同名不算改绑;一人一张待审单;审核通过后旧证失效并留审计;
 * 驳回必须写意见;审计与进度都不含姓名、号码。
 */
class MemberIdentityChangeServiceTest {

    private static final long MEMBER_ID = 8801L;
    private static final long REVIEWER = 3L;
    private static final long CHANGE_ID = 55L;
    private static final String OLD_ID = "110105199003070017";
    private static final String NEW_ID = "110105198501010012";

    private UmsMemberIdentityMapper identityMapper;
    private UmsMemberIdentityChangeMapper changeMapper;
    private IConsentRecordService consent;
    private BusinessAuditEventMapper auditMapper;
    private MemberIdentityChangeService service;

    @BeforeEach
    void setUp() {
        identityMapper = mock(UmsMemberIdentityMapper.class);
        changeMapper = mock(UmsMemberIdentityChangeMapper.class);
        consent = mock(IConsentRecordService.class);
        auditMapper = mock(BusinessAuditEventMapper.class);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        service = new MemberIdentityChangeService();
        ReflectionTestUtils.setField(service, "identityMapper", identityMapper);
        ReflectionTestUtils.setField(service, "changeMapper", changeMapper);
        ReflectionTestUtils.setField(service, "consentRecordService", consent);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);

        UmsMemberIdentity current = new UmsMemberIdentity();
        current.setMemberId(MEMBER_ID);
        current.setRealName("张三");
        current.setIdCard(OLD_ID);
        current.setIdCardHash("oldhash");
        when(identityMapper.selectByMemberId(MEMBER_ID)).thenReturn(current);
    }

    @Test
    void 提交成功_落待审单_留单独同意与审计_审计不含证件() {
        service.submit(MEMBER_ID, "李四", NEW_ID, "更换为本人新证件", true, "127.0.0.1");

        ArgumentCaptor<UmsMemberIdentityChange> captor = ArgumentCaptor.forClass(UmsMemberIdentityChange.class);
        verify(changeMapper).insertChange(captor.capture());
        UmsMemberIdentityChange change = captor.getValue();
        assertEquals(MEMBER_ID, change.getMemberId().longValue());
        assertEquals(NEW_ID, change.getIdCard(), "明文交给 typeHandler 加密");
        assertEquals("oldhash", change.getOldIdCardHash());
        verify(consent).record(eq(MEMBER_ID), eq(MemberIdentityService.CONSENT_DOC_TYPE),
                eq(MemberIdentityService.CONSENT_DOC_VERSION), eq("identity_change"), eq("AGREE"),
                anyString(), eq("127.0.0.1"));
        BusinessAuditEvent audit = capturedAudit();
        assertEquals("IDENTITY_CHANGE_SUBMIT", audit.getAction());
        assertNoPii(audit);
    }

    @Test
    void 格式错误或未同意或原因含长数字_一律拒且不落库() {
        assertThrows(ServiceException.class, () -> service.submit(MEMBER_ID, "李四", "110105198501010013", "换证", true, null));
        assertThrows(ServiceException.class, () -> service.submit(MEMBER_ID, "李", NEW_ID, "换证", true, null));
        assertThrows(ServiceException.class, () -> service.submit(MEMBER_ID, "李四", NEW_ID, " ", true, null));
        assertThrows(ServiceException.class, () -> service.submit(MEMBER_ID, "李四", NEW_ID, "新证 110105198501", true, null));
        assertThrows(ServiceException.class, () -> service.submit(MEMBER_ID, "李四", NEW_ID, "换证", false, null));
        verify(changeMapper, never()).insertChange(any());
        verify(auditMapper, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void 未登记不能申请改绑_同证同名不算改绑() {
        when(identityMapper.selectByMemberId(9999L)).thenReturn(null);
        ServiceException none = assertThrows(ServiceException.class,
                () -> service.submit(9999L, "李四", NEW_ID, "换证", true, null));
        assertTrue(none.getMessage().contains("还没有登记"));
        ServiceException same = assertThrows(ServiceException.class,
                () -> service.submit(MEMBER_ID, "张三", OLD_ID, "换证", true, null));
        assertTrue(same.getMessage().contains("一致"));
        verify(changeMapper, never()).insertChange(any());
    }

    @Test
    void 已有待审单_库级唯一键撞车转成业务提示() {
        doThrow(new DuplicateKeyException("uk_identity_change_pending")).when(changeMapper).insertChange(any());
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.submit(MEMBER_ID, "李四", NEW_ID, "换证", true, null));
        assertTrue(e.getMessage().contains("审核中"));
        verify(consent, never()).record(any(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void 审核通过_新证写回登记_旧证失效_申请单清密文_留审计() {
        when(changeMapper.selectByIdForUpdate(CHANGE_ID)).thenReturn(pending());
        when(identityMapper.rebindIdentity(any())).thenReturn(1);
        when(changeMapper.finishPending(eq(CHANGE_ID), isNull(), eq("APPROVED"), any(), eq(REVIEWER))).thenReturn(1);

        service.approve(REVIEWER, CHANGE_ID, "电话核实本人");

        ArgumentCaptor<UmsMemberIdentity> captor = ArgumentCaptor.forClass(UmsMemberIdentity.class);
        verify(identityMapper).rebindIdentity(captor.capture());
        assertEquals(NEW_ID, captor.getValue().getIdCard());
        assertEquals("李四", captor.getValue().getRealName());
        assertEquals(MEMBER_ID, captor.getValue().getMemberId().longValue());
        BusinessAuditEvent audit = capturedAudit();
        assertEquals("IDENTITY_CHANGE_APPROVE", audit.getAction());
        assertEquals(BusinessAuditEvent.ACTOR_TYPE_SYS_USER, audit.getActorType());
        assertEquals(REVIEWER, audit.getActorMemberId().longValue());
        assertTrue(audit.getAfterJson().contains("previousIdentityInvalidated"));
        assertNoPii(audit);
    }

    @Test
    void 已处理的单不能再审_驳回必须写意见() {
        UmsMemberIdentityChange done = pending();
        done.setStatus("APPROVED");
        when(changeMapper.selectByIdForUpdate(CHANGE_ID)).thenReturn(done);
        assertThrows(ServiceException.class, () -> service.approve(REVIEWER, CHANGE_ID, null));
        assertThrows(ServiceException.class, () -> service.reject(REVIEWER, CHANGE_ID, "  "));
        verify(identityMapper, never()).rebindIdentity(any());
    }

    @Test
    void 驳回_不动现登记_留审计() {
        when(changeMapper.selectByIdForUpdate(CHANGE_ID)).thenReturn(pending());
        when(changeMapper.finishPending(eq(CHANGE_ID), isNull(), eq("REJECTED"), eq("证件照不清晰"), eq(REVIEWER))).thenReturn(1);

        service.reject(REVIEWER, CHANGE_ID, "证件照不清晰");

        verify(identityMapper, never()).rebindIdentity(any());
        BusinessAuditEvent audit = capturedAudit();
        assertEquals("IDENTITY_CHANGE_REJECT", audit.getAction());
        assertEquals("证件照不清晰", audit.getReason());
    }

    @Test
    void 只能撤回自己的待审单() {
        UmsMemberIdentityChange other = pending();
        other.setMemberId(7777L);
        when(changeMapper.selectByIdForUpdate(CHANGE_ID)).thenReturn(other);
        assertThrows(ServiceException.class, () -> service.cancel(MEMBER_ID, CHANGE_ID));
        verify(changeMapper, never()).finishPending(any(), any(), any(), any(), any());

        when(changeMapper.selectByIdForUpdate(CHANGE_ID)).thenReturn(pending());
        when(changeMapper.finishPending(CHANGE_ID, MEMBER_ID, "CANCELLED", null, null)).thenReturn(1);
        service.cancel(MEMBER_ID, CHANGE_ID);
        assertEquals("IDENTITY_CHANGE_CANCEL", capturedAudit().getAction());
    }

    @Test
    void 进度只回状态与驳回意见_不回显证件() {
        UmsMemberIdentityChange rejected = new UmsMemberIdentityChange();
        rejected.setId(CHANGE_ID);
        rejected.setStatus("REJECTED");
        rejected.setReviewNote("请补充说明");
        rejected.setCreateTime(new Date());
        when(changeMapper.selectLatestByMember(MEMBER_ID)).thenReturn(rejected);

        Map<String, Object> data = service.latest(MEMBER_ID);

        assertEquals("REJECTED", data.get("status"));
        assertEquals("请补充说明", data.get("reviewNote"));
        assertFalse(data.containsKey("idCard"));
        assertFalse(data.containsKey("realName"));
        assertNull(service.latest(null));
    }

    private UmsMemberIdentityChange pending() {
        UmsMemberIdentityChange change = new UmsMemberIdentityChange();
        change.setId(CHANGE_ID);
        change.setMemberId(MEMBER_ID);
        change.setRealName("李四");
        change.setIdCard(NEW_ID);
        change.setStatus("PENDING");
        change.setCreateTime(new Date());
        return change;
    }

    private BusinessAuditEvent capturedAudit() {
        ArgumentCaptor<BusinessAuditEvent> captor = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper, org.mockito.Mockito.atLeastOnce()).insertBusinessAuditEvent(captor.capture());
        return captor.getValue();
    }

    private static void assertNoPii(BusinessAuditEvent audit) {
        String all = String.valueOf(audit.getBeforeJson()) + audit.getAfterJson() + audit.getReason();
        assertFalse(all.contains(NEW_ID) || all.contains(OLD_ID) || all.contains("李四") || all.contains("张三"),
                "审计不得含姓名或身份证号");
    }
}

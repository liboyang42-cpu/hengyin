package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantCrmCustomerSummary;
import com.chengyinhub.business.domain.MerchantCrmInteraction;
import com.chengyinhub.business.domain.MerchantCrmNote;
import com.chengyinhub.business.domain.MerchantCrmTagAssignment;
import com.chengyinhub.business.domain.MerchantCustomerTag;
import com.chengyinhub.business.domain.dto.MerchantCrmNoteRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmBatchTagRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmCustomerQuery;
import com.chengyinhub.business.domain.dto.MerchantCrmTagRequest;
import com.chengyinhub.business.domain.vo.MerchantCrmCustomerDetailVO;
import com.chengyinhub.business.domain.vo.MerchantCrmContactVO;
import com.chengyinhub.business.domain.vo.MerchantCrmCustomerPageVO;
import com.chengyinhub.business.domain.vo.MerchantCrmCustomerRowVO;
import com.chengyinhub.business.domain.vo.MerchantCrmSegmentCountsVO;
import com.chengyinhub.business.domain.vo.MerchantCrmTagVO;
import com.chengyinhub.business.domain.vo.MerchantCrmTimelineItemVO;
import com.chengyinhub.business.mapper.MerchantCrmAppMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantCustomerTagMapper;
import com.chengyinhub.business.service.impl.MerchantCrmAppServiceImpl;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class MerchantCrmAppServiceImplTest {
    private static final long OWNER = 900L;
    private static final long PRINCIPAL = OWNER;
    private static final long OPERATOR_PRINCIPAL = 901L;
    private static final long MANAGER_PRINCIPAL = 902L;
    private static final long FINANCE_PRINCIPAL = 903L;
    private static final long MERCHANT = 77L;
    private static final long OTHER_MERCHANT = 88L;
    private static final long OTHER_OPERATOR = 904L;
    private static final long CUSTOMER = 41L;

    private IMerchantAccessService access;
    private MerchantCrmAppMapper mapper;
    private MerchantCustomerTagMapper customerTagMapper;
    private IConsentRecordService consentRecordService;
    private BusinessAuditEventMapper businessAuditEventMapper;
    private MerchantCrmAppServiceImpl service;

    @BeforeEach
    void setUp() {
        access = mock(IMerchantAccessService.class);
        mapper = mock(MerchantCrmAppMapper.class);
        customerTagMapper = mock(MerchantCustomerTagMapper.class);
        consentRecordService = mock(IConsentRecordService.class);
        businessAuditEventMapper = mock(BusinessAuditEventMapper.class);
        service = new MerchantCrmAppServiceImpl();
        ReflectionTestUtils.setField(service, "merchantAccessService", access);
        ReflectionTestUtils.setField(service, "merchantCrmAppMapper", mapper);
        ReflectionTestUtils.setField(service, "merchantCustomerTagMapper", customerTagMapper);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", businessAuditEventMapper);
        ReflectionTestUtils.setField(service, "consentRecordService", consentRecordService);
        ComplianceProperties properties = new ComplianceProperties();
        properties.setDocVersions(Collections.singletonMap("activity_host_data_sharing", "v2.1"));
        ReflectionTestUtils.setField(service, "complianceProperties", properties);
        // 2026-09-05 客户行补出 roleName(定向广播按角色分组)。这里注入真实实现而不是 mock:
        // roleCode → 中文名是一张固定映射表,mock 掉就等于把「认不出的码留 null」那条也一起 mock 了。
        ReflectionTestUtils.setField(service, "playerRoleService",
                new com.chengyinhub.business.service.impl.PlayerRoleServiceImpl());
        when(consentRecordService.hasCurrentAgreement(anyLong(), eq("activity_host_data_sharing"),
                eq("activity_signup"), eq("v2.1"))).thenReturn(true);
        when(access.require(PRINCIPAL, MerchantPermission.CRM_READ)).thenReturn(context());
        when(access.require(PRINCIPAL, MerchantPermission.CRM_SEGMENT)).thenReturn(context());
        when(access.require(PRINCIPAL, MerchantPermission.CRM_SENSITIVE_READ)).thenReturn(context());
        when(mapper.selectCustomerProfileForUpdate(MERCHANT, CUSTOMER)).thenReturn(CUSTOMER);
        // 频控计数默认 0、审计默认写成功;拒绝路径由各用例显式覆写。
        when(businessAuditEventMapper.countActorActionToday(anyLong(), any())).thenReturn(0L);
        when(businessAuditEventMapper.insertBusinessAuditEvent(any())).thenReturn(1);
    }

    @Test
    void detailReadsOnlyTheResolvedOwnerCustomerAndBuildsOneTimeline() {
        MerchantCrmCustomerSummary summary = summary();
        MerchantCrmInteraction registration = new MerchantCrmInteraction();
        registration.setType("ARRIVED");
        registration.setOccurredAt(new Date(1_000L));
        MerchantCrmNote note = note(8L, "记得无糖", "note-1");
        note.setCreateTime(new Date(2_000L));
        MerchantCrmTagVO tag = tagVO(3L, "高频复购");
        MerchantCrmTimelineItemVO campaign = new MerchantCrmTimelineItemVO();
        campaign.setKey("campaign-18");
        campaign.setType("CAMPAIGN");
        campaign.setTitle("周末活动提醒");
        campaign.setDescription("站内通知 · 已送达");
        campaign.setOccurredAt(new Date(3_000L));
        when(mapper.selectOwnedCustomerSummary(OWNER, CUSTOMER)).thenReturn(summary);
        when(mapper.selectOwnedInteractions(OWNER, CUSTOMER, 50)).thenReturn(Collections.singletonList(registration));
        when(mapper.selectCustomerNotes(MERCHANT, CUSTOMER, 50)).thenReturn(Collections.singletonList(note));
        when(mapper.selectCustomerTags(MERCHANT, CUSTOMER)).thenReturn(Collections.singletonList(tag));
        when(mapper.selectCustomerCampaignTimeline(MERCHANT, CUSTOMER, 50))
                .thenReturn(Collections.singletonList(campaign));

        MerchantCrmCustomerDetailVO out = service.detail(PRINCIPAL, CUSTOMER);

        verify(access).require(PRINCIPAL, MerchantPermission.CRM_READ);
        verify(mapper).selectOwnedCustomerSummary(OWNER, CUSTOMER);
        verify(mapper).selectOwnedInteractions(OWNER, CUSTOMER, 50);
        assertSame(summary, out.getSummary());
        assertEquals("CAMPAIGN", out.getTimeline().get(0).getType());
        assertEquals("ARRIVED", out.getSystemTags().get(0).getCode());
        assertEquals(1, out.getMerchantTags().size());
        assertEquals(1, out.getTags().size(), "旧客户端 tags 兼容字段需与 merchantTags 同源");
    }

    @Test
    void crossMerchantCustomerFailsClosedBeforeTagsNotesOrTimeline() {
        when(mapper.selectOwnedCustomerSummary(OWNER, CUSTOMER)).thenReturn(null);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.detail(PRINCIPAL, CUSTOMER));

        assertEquals(404, error.getCode());
        verify(mapper, never()).selectCustomerTags(any(), any());
        verify(mapper, never()).selectCustomerNotes(any(), any(), any(Integer.class));
        verify(mapper, never()).selectOwnedInteractions(any(), any(), any(Integer.class));
        verify(mapper, never()).selectCustomerCampaignTimeline(any(), any(), any(Integer.class));
    }

    @Test
    void marketingOperatorCanReadAndWriteOwnerScopedCrmWithPrincipalAudit() {
        MerchantAccessContext marketing = new MerchantAccessContext(
                OPERATOR_PRINCIPAL, MERCHANT, OWNER, MerchantRoleCode.MERCHANT_MARKETING);
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.CRM_READ)).thenReturn(marketing);
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.CRM_SEGMENT)).thenReturn(marketing);
        MerchantCrmCustomerSummary visible = summary();
        when(mapper.selectOwnedCustomerSummary(OWNER, CUSTOMER)).thenReturn(visible);
        when(mapper.selectOwnedInteractions(OWNER, CUSTOMER, 50)).thenReturn(Collections.emptyList());
        when(mapper.selectCustomerNotes(MERCHANT, CUSTOMER, 50)).thenReturn(Collections.emptyList());
        when(mapper.selectCustomerTags(MERCHANT, CUSTOMER)).thenReturn(Collections.emptyList());
        when(mapper.selectNoteByRequestId(MERCHANT, "operator-note-1"))
                .thenReturn(null, note(8L, "记得无糖", "operator-note-1"));
        when(mapper.insertNote(any(MerchantCrmNote.class))).thenReturn(1);

        MerchantCrmCustomerDetailVO detail = service.detail(OPERATOR_PRINCIPAL, CUSTOMER);
        service.addNote(OPERATOR_PRINCIPAL, CUSTOMER,
                noteRequest("记得无糖", "operator-note-1"));

        assertNull(detail.getSummary().getPaidAmount(), "营销角色无敏感读取权限时金额必须脱敏");
        ArgumentCaptor<MerchantCrmNote> saved = ArgumentCaptor.forClass(MerchantCrmNote.class);
        verify(mapper).insertNote(saved.capture());
        assertEquals(MERCHANT, saved.getValue().getMerchantId());
        assertEquals(OPERATOR_PRINCIPAL, saved.getValue().getActorMemberId());
    }

    @Test
    void managerOperatorCanReconcileProfilesWithinResolvedOwnerScope() {
        MerchantAccessContext manager = new MerchantAccessContext(
                MANAGER_PRINCIPAL, MERCHANT, OWNER, MerchantRoleCode.MERCHANT_MANAGER);
        when(access.require(MANAGER_PRINCIPAL, MerchantPermission.CRM_SEGMENT)).thenReturn(manager);
        when(mapper.upsertOwnedCustomerProfiles(MERCHANT, OWNER)).thenReturn(2);
        when(mapper.markStaleCustomerProfiles(MERCHANT, OWNER)).thenReturn(1);
        when(mapper.countOwnedRegistrationCustomers(OWNER)).thenReturn(3L);
        when(mapper.countActiveCustomerProfiles(MERCHANT)).thenReturn(3L);

        assertEquals(2, service.reconcileProfiles(MANAGER_PRINCIPAL).getUpsertedRows());

        verify(mapper).upsertOwnedCustomerProfiles(MERCHANT, OWNER);
        verify(mapper).markStaleCustomerProfiles(MERCHANT, OWNER);
    }

    @Test
    void checkinAndFinanceRolesAreDeniedByExactCrmPermissionsBeforeDataAccess() {
        MerchantAccessContext checkin = new MerchantAccessContext(
                OPERATOR_PRINCIPAL, MERCHANT, OWNER, MerchantRoleCode.MERCHANT_CHECKIN);
        MerchantAccessContext finance = new MerchantAccessContext(
                FINANCE_PRINCIPAL, MERCHANT, OWNER, MerchantRoleCode.MERCHANT_FINANCE);
        assertFalse(checkin.hasPermission(MerchantPermission.CRM_READ));
        assertFalse(finance.hasPermission(MerchantPermission.CRM_SEGMENT));
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.CRM_READ))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));
        when(access.require(FINANCE_PRINCIPAL, MerchantPermission.CRM_SEGMENT))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));

        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.detail(OPERATOR_PRINCIPAL, CUSTOMER)).getCode());
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.addNote(FINANCE_PRINCIPAL, CUSTOMER,
                        noteRequest("无权备注", "denied-note-1"))).getCode());

        verify(mapper, never()).selectOwnedCustomerSummary(any(), any());
        verify(mapper, never()).insertNote(any());
    }

    @Test
    void noteUsesOwnerAndPrincipalAndExactReplayIsIdempotent() {
        when(mapper.selectOwnedCustomerSummary(OWNER, CUSTOMER)).thenReturn(summary());
        when(mapper.selectNoteByRequestId(MERCHANT, "note-1")).thenReturn(null, note(8L, "记得无糖", "note-1"));
        when(mapper.insertNote(any(MerchantCrmNote.class))).thenReturn(1);

        MerchantCrmNote created = service.addNote(PRINCIPAL, CUSTOMER,
                noteRequest("  记得无糖  ", "note-1"));

        assertEquals(8L, created.getId());
        ArgumentCaptor<MerchantCrmNote> saved = ArgumentCaptor.forClass(MerchantCrmNote.class);
        verify(mapper).insertNote(saved.capture());
        assertEquals(MERCHANT, saved.getValue().getMerchantId());
        assertEquals(CUSTOMER, saved.getValue().getCustomerMemberId());
        assertEquals(PRINCIPAL, saved.getValue().getActorMemberId());
        assertEquals("记得无糖", saved.getValue().getContent());

        when(mapper.selectNoteByRequestId(MERCHANT, "note-1")).thenReturn(note(8L, "记得无糖", "note-1"));
        assertEquals(8L, service.addNote(PRINCIPAL, CUSTOMER,
                noteRequest("记得无糖", "note-1")).getId());
    }

    @Test
    void noteInsertWithoutAuthoritativeReadbackFailsClosedInsteadOfReturningLocalRow() {
        when(mapper.selectOwnedCustomerSummary(OWNER, CUSTOMER)).thenReturn(summary());
        when(mapper.selectNoteByRequestId(MERCHANT, "note-missing-readback"))
                .thenReturn(null, null);
        when(mapper.insertNote(any(MerchantCrmNote.class))).thenReturn(1);

        ServiceException error = assertThrows(ServiceException.class, () -> service.addNote(
                PRINCIPAL, CUSTOMER, noteRequest("无法回读", "note-missing-readback")));

        assertEquals(409, error.getCode());
        verify(mapper).insertNote(any(MerchantCrmNote.class));
    }

    @Test
    void changedPayloadWithTheSameNoteRequestIdFailsClosed() {
        when(mapper.selectOwnedCustomerSummary(OWNER, CUSTOMER)).thenReturn(summary());
        when(mapper.selectNoteByRequestId(MERCHANT, "note-1")).thenReturn(note(8L, "记得无糖", "note-1"));

        ServiceException error = assertThrows(ServiceException.class, () -> service.addNote(
                PRINCIPAL, CUSTOMER, noteRequest("改成少冰", "note-1")));

        assertEquals(409, error.getCode());
        verify(mapper, never()).insertNote(any());
    }

    @Test
    void correctionAppendsANewNoteLinkedToTheOriginalWithoutChangingOriginalContent() {
        MerchantCrmNote original = note(8L, "记得无糖", "note-1");
        MerchantCrmNote corrected = note(9L, "更正：正常糖", "note-correct-1");
        corrected.setCorrectsNoteId(8L);
        when(mapper.selectOwnedCustomerSummary(OWNER, CUSTOMER)).thenReturn(summary());
        when(mapper.selectNoteById(MERCHANT, CUSTOMER, 8L)).thenReturn(original);
        when(mapper.selectNoteByRequestId(MERCHANT, "note-correct-1")).thenReturn(null, corrected);
        when(mapper.insertNote(any(MerchantCrmNote.class))).thenReturn(1);
        MerchantCrmNoteRequest request = noteRequest("更正：正常糖", "note-correct-1");
        request.setCorrectsNoteId(8L);

        service.addNote(PRINCIPAL, CUSTOMER, request);

        ArgumentCaptor<MerchantCrmNote> saved = ArgumentCaptor.forClass(MerchantCrmNote.class);
        verify(mapper).insertNote(saved.capture());
        assertEquals(8L, saved.getValue().getCorrectsNoteId());
        assertEquals("记得无糖", original.getContent(), "纠错不得原地改写原备注");
        verify(mapper, never()).hideNoteCas(any(), any(), any(), any(), any(), any());
    }

    @Test
    void hidingANoteUsesOwnerScopedCompareAndSetAndKeepsContentImmutable() {
        MerchantCrmNote original = note(8L, "记得无糖", "note-1");
        original.setVersion(2);
        original.setHidden(0);
        when(mapper.selectOwnedCustomerSummary(OWNER, CUSTOMER)).thenReturn(summary());
        when(mapper.selectNoteById(MERCHANT, CUSTOMER, 8L)).thenReturn(original);
        when(mapper.hideNoteCas(MERCHANT, CUSTOMER, 8L, 2, PRINCIPAL, "note-hide-1")).thenReturn(1);

        service.hideNote(PRINCIPAL, CUSTOMER, 8L, 2, "note-hide-1");

        verify(mapper).hideNoteCas(MERCHANT, CUSTOMER, 8L, 2, PRINCIPAL, "note-hide-1");
        assertEquals("记得无糖", original.getContent(), "隐藏只改元数据，不改原文");
    }

    @Test
    void tagAssignmentUsesCrmSegmentPermissionAndOwnerScopedJoin() {
        when(mapper.selectOwnedCustomerSummary(OWNER, CUSTOMER)).thenReturn(summary());
        when(mapper.countCustomerTags(MERCHANT, CUSTOMER)).thenReturn(0);
        when(customerTagMapper.selectTagList(any(MerchantCustomerTag.class))).thenReturn(
                Collections.emptyList(), Collections.singletonList(tag(3L, "高频复购")));
        when(customerTagMapper.insertMerchantCustomerTag(any(MerchantCustomerTag.class))).thenReturn(1);
        when(mapper.insertTagAssignment(any(MerchantCrmTagAssignment.class))).thenReturn(1);

        MerchantCrmTagVO out = service.assignTag(PRINCIPAL, CUSTOMER,
                tagRequest("  高频复购  ", "#2E6D5A"));

        verify(access).require(PRINCIPAL, MerchantPermission.CRM_SEGMENT);
        verify(mapper).upsertOwnedCustomerProfile(MERCHANT, OWNER, CUSTOMER);
        verify(mapper).selectCustomerProfileForUpdate(MERCHANT, CUSTOMER);
        ArgumentCaptor<MerchantCrmTagAssignment> assignment = ArgumentCaptor.forClass(MerchantCrmTagAssignment.class);
        verify(mapper).insertTagAssignment(assignment.capture());
        assertEquals(MERCHANT, assignment.getValue().getMerchantId());
        assertEquals(CUSTOMER, assignment.getValue().getCustomerMemberId());
        assertEquals(PRINCIPAL, assignment.getValue().getActorMemberId());
        assertEquals(MerchantCrmTagAssignment.ASSIGN, assignment.getValue().getAction());
        assertEquals("高频复购", out.getTagName());
        ArgumentCaptor<MerchantCustomerTag> definition = ArgumentCaptor.forClass(MerchantCustomerTag.class);
        verify(customerTagMapper).insertMerchantCustomerTag(definition.capture());
        assertEquals(MERCHANT, definition.getValue().getMerchantId());
        assertEquals("app_67f7b6d11cbfbe4a03617657e53d65e53c12789614a63380", definition.getValue().getTagCode());
    }

    @Test
    void removeTagCannotDeleteAnotherMerchantOrCustomersAssignment() {
        when(mapper.selectOwnedCustomerSummary(OWNER, CUSTOMER)).thenReturn(summary());
        when(mapper.countCustomerTag(MERCHANT, CUSTOMER, 3L)).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.removeTag(PRINCIPAL, CUSTOMER, 3L, "tag-remove-1"));

        assertEquals(404, error.getCode());
        verify(mapper, never()).insertTagAssignment(any());
    }

    @Test
    void removeTagAppendsAnAuditedRemoveEventInsteadOfDeletingHistory() {
        when(mapper.selectOwnedCustomerSummary(OWNER, CUSTOMER)).thenReturn(summary());
        when(mapper.countCustomerTag(MERCHANT, CUSTOMER, 3L)).thenReturn(1);
        when(mapper.insertTagAssignment(any(MerchantCrmTagAssignment.class))).thenReturn(1);

        service.removeTag(PRINCIPAL, CUSTOMER, 3L, "tag-remove-1");

        ArgumentCaptor<MerchantCrmTagAssignment> saved = ArgumentCaptor.forClass(MerchantCrmTagAssignment.class);
        verify(mapper).insertTagAssignment(saved.capture());
        assertEquals(MerchantCrmTagAssignment.REMOVE, saved.getValue().getAction());
        assertEquals(PRINCIPAL, saved.getValue().getActorMemberId());
        assertEquals("tag-remove-1", saved.getValue().getRequestId());
    }

    @Test
    void concurrentSameTagDefinitionUsesLegacyUniqueTagCodeThenContinuesAssignment() {
        when(mapper.selectOwnedCustomerSummary(OWNER, CUSTOMER)).thenReturn(summary());
        when(customerTagMapper.selectTagList(any(MerchantCustomerTag.class))).thenReturn(
                Collections.emptyList(), Collections.singletonList(tag(3L, "高频复购")));
        doThrow(new DuplicateKeyException("uk_merchant_tag_code"))
                .when(customerTagMapper).insertMerchantCustomerTag(any(MerchantCustomerTag.class));
        when(mapper.insertTagAssignment(any(MerchantCrmTagAssignment.class))).thenReturn(1);

        MerchantCrmTagVO out = service.assignTag(PRINCIPAL, CUSTOMER,
                tagRequest("高频复购", "#2E6D5A"));

        assertEquals(3L, out.getId());
        verify(mapper).insertTagAssignment(any(MerchantCrmTagAssignment.class));
    }

    /* 定向广播按「队伍 / 角色」分组(小程序 buildCastGroups)。teamName 由 SQL 直出;
       roleName 由 roleCode 翻译 —— 认不出的码必须留 null,不能把 NAVIGATOR 这种内部码
       直接摆到商家看到的分组标题上,也不能编一个名字。 */
    @Test
    void 客户行的角色码翻成中文认不出的留null() {
        MerchantCrmCustomerRowVO navigator = new MerchantCrmCustomerRowVO();
        navigator.setMemberId(CUSTOMER);
        navigator.setMerchantResponsibleForContact(Boolean.TRUE);
        navigator.setTeamName("夜行小队");
        navigator.setRoleCode("NAVIGATOR");
        MerchantCrmCustomerRowVO unknown = new MerchantCrmCustomerRowVO();
        unknown.setMemberId(CUSTOMER + 1);
        unknown.setMerchantResponsibleForContact(Boolean.TRUE);
        unknown.setRoleCode("SOMETHING_NEW");
        MerchantCrmCustomerRowVO none = new MerchantCrmCustomerRowVO();
        none.setMemberId(CUSTOMER + 2);
        none.setMerchantResponsibleForContact(Boolean.TRUE);
        when(mapper.selectOwnedCustomerPage(any(), any(), any(), any()))
                .thenReturn(java.util.Arrays.asList(navigator, unknown, none));
        when(mapper.selectOwnedCustomerCount(any(), any())).thenReturn(3L);
        when(mapper.selectOwnedSegmentCounts(any(), any())).thenReturn(new MerchantCrmSegmentCountsVO());
        when(customerTagMapper.selectTagList(any())).thenReturn(Collections.emptyList());

        MerchantCrmCustomerPageVO out = service.listCustomers(PRINCIPAL, new MerchantCrmCustomerQuery());

        assertEquals("夜行小队", out.getRows().get(0).getTeamName(), "队伍名必须原样透传给分组");
        assertEquals("导航员", out.getRows().get(0).getRoleName());
        assertNull(out.getRows().get(1).getRoleName(), "认不出的角色码不能漏成内部码,也不能编名字");
        assertNull(out.getRows().get(2).getRoleName(), "没有角色的客户不进角色分组");
        assertNull(out.getRows().get(2).getTeamName(), "没进过队的客户不进队伍分组");
    }

    @Test
    void ownerCustomerListIncludesSensitiveFieldsAndConsentStatus() {
        MerchantCrmCustomerRowVO row = new MerchantCrmCustomerRowVO();
        row.setMemberId(CUSTOMER);
        row.setPhone("13800138000");
        row.setMerchantResponsibleForContact(Boolean.TRUE);
        row.setPaidAmount(new BigDecimal("86.00"));
        when(mapper.selectOwnedCustomerPage(any(), any(), any(), any())).thenReturn(Collections.singletonList(row));
        when(mapper.selectOwnedCustomerCount(any(), any())).thenReturn(1L);
        when(mapper.selectOwnedSegmentCounts(any(), any())).thenReturn(new MerchantCrmSegmentCountsVO());
        when(customerTagMapper.selectTagList(any())).thenReturn(Collections.emptyList());

        MerchantCrmCustomerPageVO out = service.listCustomers(PRINCIPAL, new MerchantCrmCustomerQuery());

        assertEquals(new BigDecimal("86.00"), out.getRows().get(0).getPaidAmount());
        // F15(2026-09-15):有权且已同意时下发的也是服务端脱敏号,与导出同口径,明文不出服务端。
        assertEquals("138****8000", out.getRows().get(0).getPhone());
        assertEquals("CONSENT_REQUIRED", out.getCouponDeliveryStatus());
        assertEquals("CONSENT_REQUIRED", out.getNotificationDeliveryStatus());
        ArgumentCaptor<MerchantCrmCustomerQuery> scoped = ArgumentCaptor.forClass(MerchantCrmCustomerQuery.class);
        verify(mapper).selectOwnedCustomerPage(any(), scoped.capture(), any(), any());
        assertEquals(MERCHANT, scoped.getValue().getMerchantId());
        ArgumentCaptor<MerchantCustomerTag> available = ArgumentCaptor.forClass(MerchantCustomerTag.class);
        verify(customerTagMapper).selectTagList(available.capture());
        assertEquals(MERCHANT, available.getValue().getMerchantId());
        assertEquals(2, available.getValue().getMerchantScopeVersion());
    }

    @Test
    void ownerCustomerListWithoutCurrentConsentFailsClosedForPhone() {
        MerchantCrmCustomerRowVO row = new MerchantCrmCustomerRowVO();
        row.setMemberId(CUSTOMER);
        row.setPhone("13800138000");
        row.setMerchantResponsibleForContact(Boolean.TRUE);
        when(consentRecordService.hasCurrentAgreement(CUSTOMER, "activity_host_data_sharing",
                "activity_signup", "v2.1")).thenReturn(false);
        when(mapper.selectOwnedCustomerPage(any(), any(), any(), any()))
                .thenReturn(Collections.singletonList(row));
        when(mapper.selectOwnedCustomerCount(any(), any())).thenReturn(1L);
        when(mapper.selectOwnedSegmentCounts(any(), any())).thenReturn(new MerchantCrmSegmentCountsVO());
        when(customerTagMapper.selectTagList(any())).thenReturn(Collections.emptyList());

        MerchantCrmCustomerPageVO out = service.listCustomers(PRINCIPAL, new MerchantCrmCustomerQuery());

        assertNull(out.getRows().get(0).getPhone());
        assertEquals("客户未授权联系方式共享", out.getRows().get(0).getContactHint());
    }

    @Test
    void clubLedCustomerPhoneFailsClosedBeforeConsentLookup() {
        MerchantCrmCustomerRowVO row = new MerchantCrmCustomerRowVO();
        row.setMemberId(CUSTOMER);
        row.setPhone("13800138000");
        row.setMerchantResponsibleForContact(Boolean.FALSE);
        when(mapper.selectOwnedCustomerPage(any(), any(), any(), any()))
                .thenReturn(Collections.singletonList(row));
        when(mapper.selectOwnedCustomerCount(any(), any())).thenReturn(1L);
        when(mapper.selectOwnedSegmentCounts(any(), any())).thenReturn(new MerchantCrmSegmentCountsVO());
        when(customerTagMapper.selectTagList(any())).thenReturn(Collections.emptyList());

        MerchantCrmCustomerPageVO out = service.listCustomers(PRINCIPAL, new MerchantCrmCustomerQuery());

        assertNull(out.getRows().get(0).getPhone());
        assertEquals("由俱乐部带团，联系请找俱乐部负责人", out.getRows().get(0).getContactHint());
        verify(consentRecordService, never()).hasCurrentAgreement(anyLong(), any(), any(), any());
    }

    /* ---- F15 联系号码接口:列表继续只给脱敏号,明文只从显式接口出。 ---- */

    @Test
    void revealContactReturnsPlaintextAndAuditsWhoWhichCustomerAndPurpose() {
        when(mapper.selectOwnedCustomerContact(OWNER, CUSTOMER)).thenReturn(contactRow("13800138000", true));

        MerchantCrmContactVO out = service.revealContact(PRINCIPAL, CUSTOMER, "call");

        assertEquals("13800138000", out.getPhone(), "授权链全过时该接口才下发明文");
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(businessAuditEventMapper).insertBusinessAuditEvent(audit.capture());
        BusinessAuditEvent event = audit.getValue();
        assertEquals("MERCHANT", event.getDomainType());
        assertEquals(MERCHANT, event.getDomainId());
        assertEquals(OWNER, event.getOwnerMemberId());
        assertEquals(BusinessAuditEvent.ACTOR_TYPE_MEMBER, event.getActorType());
        assertEquals(PRINCIPAL, event.getActorMemberId());
        assertEquals(MerchantRoleCode.MERCHANT_OWNER, event.getActorRoleCode());
        assertEquals("CRM_CONTACT_REVEAL", event.getAction());
        assertEquals("CRM_CUSTOMER", event.getTargetType());
        assertEquals(CUSTOMER, event.getTargetId());
        assertEquals("purpose=call", event.getReason());
        assertNotNull(event.getRequestId());
        assertNull(event.getBeforeJson(), "审计正文不允许夹带号码等敏感内容");
        assertNull(event.getAfterJson(), "审计正文不允许夹带号码等敏感内容");
    }

    @Test
    void revealContactDeniesRolesWithoutSensitivePermissionBeforeReadingCustomer() {
        when(access.require(PRINCIPAL, MerchantPermission.CRM_SENSITIVE_READ))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revealContact(PRINCIPAL, CUSTOMER, "call"));

        assertEquals(403, error.getCode());
        verify(mapper, never()).selectOwnedCustomerContact(any(), any());
        BusinessAuditEvent denied = capturedDeniedAudit("permission");
        assertEquals(0L, denied.getDomainId(), "解析不出商家身份时不得伪造归属");
        assertEquals("UNKNOWN", denied.getActorRoleCode());
        assertEquals(CUSTOMER, denied.getTargetId());
        verify(access).resolve(PRINCIPAL);
    }

    @Test
    void revealContactRejectsCustomerOutsideMerchantScopeWithDeniedAudit() {
        when(mapper.selectOwnedCustomerContact(OWNER, CUSTOMER)).thenReturn(null);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revealContact(PRINCIPAL, CUSTOMER, "copy"));

        assertEquals(404, error.getCode());
        verify(consentRecordService, never()).hasCurrentAgreement(anyLong(), any(), any(), any());
        BusinessAuditEvent denied = capturedDeniedAudit("ownership");
        assertEquals(MERCHANT, denied.getDomainId());
        assertEquals(OWNER, denied.getOwnerMemberId());
        assertEquals(CUSTOMER, denied.getTargetId());
    }

    @Test
    void revealContactRejectsClubLedCustomerBeforeConsentLookup() {
        when(mapper.selectOwnedCustomerContact(OWNER, CUSTOMER)).thenReturn(contactRow("13800138000", false));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revealContact(PRINCIPAL, CUSTOMER, "call"));

        assertEquals(403, error.getCode());
        assertEquals("由俱乐部带团，联系请找俱乐部负责人", error.getMessage());
        verify(consentRecordService, never()).hasCurrentAgreement(anyLong(), any(), any(), any());
        capturedDeniedAudit("club_led");
    }

    @Test
    void revealContactRejectsWithoutCurrentConsentWithDeniedAudit() {
        when(mapper.selectOwnedCustomerContact(OWNER, CUSTOMER)).thenReturn(contactRow("13800138000", true));
        when(consentRecordService.hasCurrentAgreement(CUSTOMER, "activity_host_data_sharing",
                "activity_signup", "v2.1")).thenReturn(false);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revealContact(PRINCIPAL, CUSTOMER, "call"));

        assertEquals(403, error.getCode());
        assertEquals("客户未授权联系方式共享", error.getMessage());
        capturedDeniedAudit("no_consent");
    }

    @Test
    void revealContactRejectsCustomerWithoutPhoneWithoutConsentLookup() {
        when(mapper.selectOwnedCustomerContact(OWNER, CUSTOMER)).thenReturn(contactRow(null, true));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revealContact(PRINCIPAL, CUSTOMER, "call"));

        assertEquals(404, error.getCode());
        verify(consentRecordService, never()).hasCurrentAgreement(anyLong(), any(), any(), any());
        verify(businessAuditEventMapper, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void revealContactRejectsUnknownPurposeBeforeReadingCustomer() {
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revealContact(PRINCIPAL, CUSTOMER, "spam"));

        assertEquals(400, error.getCode());
        verify(mapper, never()).selectOwnedCustomerContact(any(), any());
        verify(businessAuditEventMapper, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void revealContactAllowsTheLastRevealOfTheDayAndRejectsTheNextOne() {
        when(mapper.selectOwnedCustomerContact(OWNER, CUSTOMER)).thenReturn(contactRow("13800138000", true));
        when(businessAuditEventMapper.countActorActionToday(eq(PRINCIPAL), eq("CRM_CONTACT_REVEAL")))
                .thenReturn(49L);
        assertEquals("13800138000", service.revealContact(PRINCIPAL, CUSTOMER, "call").getPhone(),
                "第 50 次仍在日上限内,不能提前拒");

        when(businessAuditEventMapper.countActorActionToday(eq(PRINCIPAL), eq("CRM_CONTACT_REVEAL")))
                .thenReturn(50L);
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revealContact(PRINCIPAL, CUSTOMER, "call"));
        assertEquals(429, error.getCode());
        ArgumentCaptor<BusinessAuditEvent> audits = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(businessAuditEventMapper, times(2)).insertBusinessAuditEvent(audits.capture());
        assertEquals("CRM_CONTACT_REVEAL", audits.getAllValues().get(0).getAction());
        BusinessAuditEvent denied = audits.getAllValues().get(1);
        assertEquals("CRM_CONTACT_REVEAL_DENIED", denied.getAction(),
                "超限尝试也要留审计,但用独立动作码,不占成功揭示日额度");
        assertEquals("purpose=call;deny=daily_limit", denied.getReason());
    }

    /**
     * 越权/超限审计必须与成功审计同一条「不含号码正文」的禁令:reason 只允许用途+拒绝码,
     * before/after 一律为空,request_id 不得把号码编进去。
     */
    private BusinessAuditEvent capturedDeniedAudit(String denyCode) {
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(businessAuditEventMapper).insertBusinessAuditEvent(audit.capture());
        BusinessAuditEvent event = audit.getValue();
        assertEquals("CRM_CONTACT_REVEAL_DENIED", event.getAction());
        assertEquals("MERCHANT", event.getDomainType());
        assertEquals("CRM_CUSTOMER", event.getTargetType());
        assertEquals("MEMBER", event.getActorType());
        assertEquals(PRINCIPAL, event.getActorMemberId());
        assertTrue(event.getReason().endsWith("deny=" + denyCode), event.getReason());
        assertNull(event.getBeforeJson(), "越权/超限审计不得夹带号码等敏感内容");
        assertNull(event.getAfterJson(), "越权/超限审计不得夹带号码等敏感内容");
        assertNotNull(event.getRequestId());
        assertFalse(event.getRequestId().contains("13800138000"), "request_id 不得编进号码");
        return event;
    }

    @Test
    void revealContactFailsClosedWhenAuditCannotBeWritten() {
        when(mapper.selectOwnedCustomerContact(OWNER, CUSTOMER)).thenReturn(contactRow("13800138000", true));
        when(businessAuditEventMapper.insertBusinessAuditEvent(any())).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.revealContact(PRINCIPAL, CUSTOMER, "call"));

        assertEquals(409, error.getCode());
    }

    private MerchantCrmCustomerRowVO contactRow(String phone, boolean responsible) {
        MerchantCrmCustomerRowVO out = new MerchantCrmCustomerRowVO();
        out.setMemberId(CUSTOMER);
        out.setPhone(phone);
        out.setMerchantResponsibleForContact(responsible);
        return out;
    }

    @Test
    void customerListAcceptsNewAndNotedFigmaFilters() {
        when(mapper.selectOwnedCustomerPage(any(), any(), any(), any())).thenReturn(Collections.emptyList());
        when(mapper.selectOwnedCustomerCount(any(), any())).thenReturn(0L);
        when(mapper.selectOwnedSegmentCounts(any(), any())).thenReturn(new MerchantCrmSegmentCountsVO());
        when(customerTagMapper.selectTagList(any())).thenReturn(Collections.emptyList());
        MerchantCrmCustomerQuery newCustomers = new MerchantCrmCustomerQuery();
        newCustomers.setSegment("new");
        MerchantCrmCustomerQuery notedCustomers = new MerchantCrmCustomerQuery();
        notedCustomers.setSegment("noted");

        service.listCustomers(PRINCIPAL, newCustomers);
        service.listCustomers(PRINCIPAL, notedCustomers);

        ArgumentCaptor<MerchantCrmCustomerQuery> scoped = ArgumentCaptor.forClass(MerchantCrmCustomerQuery.class);
        verify(mapper, times(2)).selectOwnedCustomerPage(any(), scoped.capture(), any(), any());
        assertEquals("new", scoped.getAllValues().get(0).getSegment());
        assertEquals("noted", scoped.getAllValues().get(1).getSegment());
    }

    @Test
    void listRejectsPageOffsetOverflowBeforeMapperQuery() {
        MerchantCrmCustomerQuery query = new MerchantCrmCustomerQuery();
        query.setPageNum(Integer.MAX_VALUE);
        query.setPageSize(50);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.listCustomers(PRINCIPAL, query));

        assertEquals(400, error.getCode());
        verify(mapper, never()).selectOwnedCustomerPage(any(), any(), any(), any());
    }

    @Test
    void batchTagDeduplicatesCustomersAndReusesLockedSingleCustomerPath() {
        when(mapper.selectOwnedCustomerSummary(OWNER, CUSTOMER)).thenReturn(summary());
        when(customerTagMapper.selectTagList(any())).thenReturn(Collections.singletonList(tag(3L, "高频复购")));
        when(mapper.insertTagAssignment(any())).thenReturn(1);
        MerchantCrmBatchTagRequest request = new MerchantCrmBatchTagRequest();
        request.setCustomerMemberIds(Arrays.asList(CUSTOMER, CUSTOMER));
        request.setTagName("高频复购");
        request.setTagColor("#2E6D5A");
        request.setRequestId("batch-request-1");

        assertEquals(1, service.batchAssignTag(PRINCIPAL, request).getProcessedCount());

        verify(mapper, times(1)).insertTagAssignment(any());
        verify(mapper).upsertOwnedCustomerProfile(MERCHANT, OWNER, CUSTOMER);
        verify(mapper).selectCustomerProfileForUpdate(MERCHANT, CUSTOMER);
    }

    @Test
    void twentyFirstTagFailsAfterTakingPerCustomerLock() {
        when(mapper.selectOwnedCustomerSummary(OWNER, CUSTOMER)).thenReturn(summary());
        when(customerTagMapper.selectTagList(any())).thenReturn(Collections.singletonList(tag(3L, "高频复购")));
        when(mapper.countCustomerTags(MERCHANT, CUSTOMER)).thenReturn(20);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.assignTag(PRINCIPAL, CUSTOMER, tagRequest("高频复购", "#2E6D5A")));

        assertEquals(409, error.getCode());
        verify(mapper).selectCustomerProfileForUpdate(MERCHANT, CUSTOMER);
        verify(mapper, never()).insertTagAssignment(any());
    }

    @Test
    void sameOwnerOtherStoreCannotReuseNoteRequestOrTagDefinition() {
        MerchantAccessContext otherStore = new MerchantAccessContext(
                OTHER_OPERATOR, OTHER_MERCHANT, OWNER, MerchantRoleCode.MERCHANT_MANAGER);
        when(access.require(OTHER_OPERATOR, MerchantPermission.CRM_SEGMENT)).thenReturn(otherStore);
        when(mapper.selectOwnedCustomerSummary(OWNER, CUSTOMER)).thenReturn(summary());
        MerchantCrmNote storeANote = note(8L, "A店跟进", "shared-note-1");
        MerchantCrmNote storeBNote = note(9L, "B店跟进", "shared-note-1");
        storeBNote.setMerchantId(OTHER_MERCHANT);
        when(mapper.selectNoteByRequestId(OWNER, "shared-note-1")).thenReturn(storeANote);
        when(mapper.selectNoteByRequestId(OTHER_MERCHANT, "shared-note-1"))
                .thenReturn(null, storeBNote);
        when(mapper.insertNote(any())).thenReturn(1);
        when(customerTagMapper.selectTagList(any())).thenAnswer(call -> {
            MerchantCustomerTag query = call.getArgument(0);
            if (OWNER == query.getMerchantId()) {
                return Collections.singletonList(tag(3L, "高频复购"));
            }
            return Collections.emptyList();
        });
        when(customerTagMapper.insertMerchantCustomerTag(any())).thenAnswer(call -> {
            ((MerchantCustomerTag) call.getArgument(0)).setId(4L);
            return 1;
        });
        when(mapper.selectCustomerProfileForUpdate(OTHER_MERCHANT, CUSTOMER)).thenReturn(CUSTOMER);
        when(mapper.insertTagAssignment(any())).thenReturn(1);

        assertEquals(9L, service.addNote(OTHER_OPERATOR, CUSTOMER,
                noteRequest("B店跟进", "shared-note-1")).getId());
        assertEquals(4L, service.assignTag(OTHER_OPERATOR, CUSTOMER,
                tagRequest("高频复购", "#2E6D5A")).getId());

        ArgumentCaptor<MerchantCrmNote> note = ArgumentCaptor.forClass(MerchantCrmNote.class);
        verify(mapper).insertNote(note.capture());
        assertEquals(OTHER_MERCHANT, note.getValue().getMerchantId());
        ArgumentCaptor<MerchantCustomerTag> definition = ArgumentCaptor.forClass(MerchantCustomerTag.class);
        verify(customerTagMapper).insertMerchantCustomerTag(definition.capture());
        assertEquals(OTHER_MERCHANT, definition.getValue().getMerchantId());
        verify(mapper, never()).selectNoteByRequestId(MERCHANT, "shared-note-1");
        verify(mapper, never()).selectCustomerProfileForUpdate(MERCHANT, CUSTOMER);
    }

    private MerchantAccessContext context() {
        return new MerchantAccessContext(PRINCIPAL, MERCHANT, OWNER, MerchantRoleCode.MERCHANT_OWNER);
    }

    private MerchantCrmCustomerSummary summary() {
        MerchantCrmCustomerSummary out = new MerchantCrmCustomerSummary();
        out.setCustomerMemberId(CUSTOMER);
        out.setDisplayName("林青");
        out.setArrivedCount(2);
        out.setPendingCount(1);
        out.setRefundedCount(0);
        out.setPaidAmount(new BigDecimal("86.00"));
        return out;
    }

    private MerchantCrmNote note(Long id, String content, String requestId) {
        MerchantCrmNote out = new MerchantCrmNote();
        out.setId(id);
        out.setMerchantId(MERCHANT);
        out.setCustomerMemberId(CUSTOMER);
        out.setActorMemberId(PRINCIPAL);
        out.setContent(content);
        out.setRequestId(requestId);
        out.setHidden(0);
        out.setVersion(0);
        return out;
    }

    private MerchantCustomerTag tag(Long id, String name) {
        MerchantCustomerTag out = new MerchantCustomerTag();
        out.setId(id);
        out.setMerchantId(MERCHANT);
        out.setTagCode("app_67f7b6d11cbfbe4a03617657e53d65e53c12789614a63380");
        out.setTagName(name);
        out.setTagColor("#2E6D5A");
        return out;
    }

    private MerchantCrmTagVO tagVO(Long id, String name) {
        MerchantCrmTagVO out = new MerchantCrmTagVO();
        out.setId(id);
        out.setTagName(name);
        out.setTagColor("#2E6D5A");
        return out;
    }

    private MerchantCrmNoteRequest noteRequest(String content, String requestId) {
        MerchantCrmNoteRequest out = new MerchantCrmNoteRequest();
        out.setContent(content);
        out.setRequestId(requestId);
        return out;
    }

    private MerchantCrmTagRequest tagRequest(String name, String color) {
        MerchantCrmTagRequest out = new MerchantCrmTagRequest();
        out.setTagName(name);
        out.setTagColor(color);
        out.setRequestId("tag-assign-1");
        return out;
    }
}

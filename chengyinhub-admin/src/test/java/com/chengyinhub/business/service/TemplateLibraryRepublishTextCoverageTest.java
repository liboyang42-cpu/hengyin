package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import com.chengyinhub.business.service.support.TemplateLibraryRepublishReview;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.controller.api.ApiTemplateController;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Exact initial-publish free-text coverage on the real republish controller and
 * service, with mocked persistence/security only. These deterministic schedules
 * neither call a moderation provider nor claim database concurrency coverage.
 * This same source compiles on the previous implementation for old-red/new-green.
 */
class TemplateLibraryRepublishTextCoverageTest {
    private static final Long TEMPLATE = 71L;
    private static final Long OWNER = 900L;
    private static final Long OPERATOR = 901L;
    private static final String OWNER_OPENID = "test-owner-openid";
    private static final String DENIED = "内容不合规";
    private static final String UNAVAILABLE = "上架复检暂不可用，请刷新后重试";
    private static final String[] FREE_TEXT_FIELDS = {
            "title", "description", "players", "usageLocation", "requiredMaterials",
            "ruleInstructions", "questionName", "questionAnswer", "feedbackText", "medalName",
            "questionA", "questionB", "questionC", "questionD"
    };

    @Test void playersOnlyDenialBlocksBeforeLocks() { assertFieldDenial("players"); }
    @Test void usageLocationOnlyDenialBlocksBeforeLocks() { assertFieldDenial("usageLocation"); }
    @Test void requiredMaterialsOnlyDenialBlocksBeforeLocks() { assertFieldDenial("requiredMaterials"); }
    @Test void ruleInstructionsOnlyDenialBlocksBeforeLocks() { assertFieldDenial("ruleInstructions"); }
    @Test void questionNameOnlyDenialBlocksBeforeLocks() { assertFieldDenial("questionName"); }
    @Test void questionAnswerOnlyDenialBlocksBeforeLocks() { assertFieldDenial("questionAnswer"); }
    @Test void feedbackTextOnlyDenialBlocksBeforeLocks() { assertFieldDenial("feedbackText"); }
    @Test void medalNameOnlyDenialBlocksBeforeLocks() { assertFieldDenial("medalName"); }
    @Test void questionAOnlyDenialBlocksBeforeLocks() { assertFieldDenial("questionA"); }
    @Test void questionBOnlyDenialBlocksBeforeLocks() { assertFieldDenial("questionB"); }
    @Test void questionCOnlyDenialBlocksBeforeLocks() { assertFieldDenial("questionC"); }
    @Test void questionDOnlyDenialBlocksBeforeLocks() { assertFieldDenial("questionD"); }

    private static void assertFieldDenial(String property) {
        CmsTemplateLibrary read = row(171);
        CmsTemplateLibrary locked = row(171);
        read.setDescription("safe-description");
        locked.setDescription("safe-description");
        String marker = "reject-only-" + property;
        set(read, property, marker);
        set(locked, property, marker);
        Fixture fixture = new Fixture(list(read), list(locked));
        when(fixture.security.checkText(contains(marker), eq(OWNER_OPENID))).thenReturn(DENIED);

        assertRejected(fixture.enable(), DENIED);
        verify(fixture.security).checkText(joined(read), OWNER_OPENID);
        fixture.assertNoLocksOrWrites();
    }

    @Test void unchangedFourteenFieldsUseInitialPublishOrderThenNarrativeBeforeAnyLocks() {
        CmsTemplateLibrary read = populated(171, "safe");
        CmsTemplateLibrary locked = populated(171, "safe");
        Fixture fixture = new Fixture(list(read), list(locked));
        assertSuccess(fixture.enable());

        InOrder order = inOrder(fixture.library, fixture.security,
                fixture.canonicalMapper, fixture.projectionMapper);
        order.verify(fixture.library).selectCmsTemplateLibraryList(any());
        order.verify(fixture.security).checkText(expectedJoined("safe"), OWNER_OPENID);
        order.verify(fixture.security).checkText("safe-narrative", OWNER_OPENID);
        order.verify(fixture.canonicalMapper).selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(TEMPLATE));
        order.verify(fixture.projectionMapper).selectByMemberTemplateForUpdate(TEMPLATE);
        order.verify(fixture.library).updateCmsTemplateLibrary(locked);
        verifyNoMoreInteractions(fixture.security);
        assertEquals(1L, locked.getPublishStatus());
        assertEquals(0L, fixture.canonical.getPublishStatus());
        verifyNoInteractions(fixture.tasks, fixture.canonicalService);
    }

    @Test void reviewedFieldListIsFrozenOrderedAndUnmodifiableAndLegacyGettersRemain() throws Exception {
        CmsTemplateLibrary original = populated(171, "frozen");
        List<CmsTemplateLibrary> input = new ArrayList<>(list(original));
        TemplateLibraryRepublishReview review = TemplateLibraryRepublishReview.freeze(TEMPLATE, OWNER, input);
        TemplateLibraryRepublishReview.ReviewedText text = review.getReviewedTexts().get(0);
        // Reflection keeps this test source compilable on the old implementation.
        Object result = text.getClass().getMethod("getFreeTextFields").invoke(text);
        assertTrue(result instanceof List, "free-text API must expose a List");
        @SuppressWarnings("unchecked") List<String> fields = (List<String>) result;
        List<String> expected = values("frozen");
        assertEquals(expected, fields);
        for (String property : FREE_TEXT_FIELDS) set(original, property, "mutated-" + property);
        original.setAdvancedConfigJson("{}");
        input.clear();
        assertEquals(expected, fields);
        assertEquals("frozen-title", text.getTitle());
        assertEquals("frozen-description", text.getDescription());
        assertEquals(narrative("frozen"), text.getAdvancedConfigJson());
        assertThrows(UnsupportedOperationException.class, () -> fields.set(0, "replacement"));
        assertThrows(UnsupportedOperationException.class, () -> fields.add("extra"));
        assertThrows(UnsupportedOperationException.class, fields::clear);
        assertTrue(review.matches(TEMPLATE, OWNER, list(populated(171, "frozen"))));
        assertFalse(review.matches(TEMPLATE, OWNER, list(original)));
    }

    @Test void nullEmptyAndWhitespaceOptionalFieldsPreserveOrdinarySuccess() {
        for (String optional : Arrays.asList(null, "", "  \t ")) {
            CmsTemplateLibrary read = row(171);
            CmsTemplateLibrary locked = row(171);
            for (int i = 1; i < FREE_TEXT_FIELDS.length; i++) {
                set(read, FREE_TEXT_FIELDS[i], optional);
                set(locked, FREE_TEXT_FIELDS[i], optional);
            }
            Fixture fixture = new Fixture(list(read), list(locked));
            assertSuccess(fixture.enable());
            verify(fixture.security).checkText("safe-title-171", OWNER_OPENID);
            verifyNoMoreInteractions(fixture.security);
            verify(fixture.library).updateCmsTemplateLibrary(locked);
        }
    }

    @Test void entirelyBlankFreeTextDoesNotInventAModerationCall() {
        CmsTemplateLibrary read = row(171);
        CmsTemplateLibrary locked = row(171);
        for (int i = 0; i < FREE_TEXT_FIELDS.length; i++) {
            String value = i % 3 == 0 ? null : i % 3 == 1 ? "" : " \t ";
            set(read, FREE_TEXT_FIELDS[i], value);
            set(locked, FREE_TEXT_FIELDS[i], value);
        }
        Fixture fixture = new Fixture(list(read), list(locked));
        assertSuccess(fixture.enable());
        verifyNoInteractions(fixture.security);
        verify(fixture.library).updateCmsTemplateLibrary(locked);
    }

    @Test void laterBlankTitleAndDescriptionCannotSkipAnOmittedFieldDenial() {
        CmsTemplateLibrary second = row(172);
        second.setTitle(" ");
        second.setDescription(null);
        second.setRuleInstructions("reject-second-rules");
        CmsTemplateLibrary lockedSecond = row(172);
        lockedSecond.setTitle(" ");
        lockedSecond.setDescription(null);
        lockedSecond.setRuleInstructions("reject-second-rules");
        Fixture fixture = new Fixture(list(row(171), second), list(row(171), lockedSecond));
        when(fixture.security.checkText("reject-second-rules", OWNER_OPENID)).thenReturn(DENIED);
        assertRejected(fixture.enable(), DENIED);
        InOrder order = inOrder(fixture.security);
        order.verify(fixture.security).checkText("safe-title-171", OWNER_OPENID);
        order.verify(fixture.security).checkText("reject-second-rules", OWNER_OPENID);
        fixture.assertNoLocksOrWrites();
    }

    @Test void firstNetworkCallCannotMutateAnyLaterFrozenFreeTextOrNarrativeOrBatchMembership() {
        CmsTemplateLibrary first = populated(171, "first");
        CmsTemplateLibrary second = populated(172, "second");
        List<CmsTemplateLibrary> input = new ArrayList<>(list(first, second));
        Fixture fixture = new Fixture(input, list(populated(171, "first"), populated(172, "second")));
        AtomicBoolean firstCall = new AtomicBoolean(true);
        when(fixture.security.checkText(anyString(), eq(OWNER_OPENID))).thenAnswer(invocation -> {
            if (firstCall.getAndSet(false)) {
                for (CmsTemplateLibrary source : list(first, second)) {
                    for (String field : FREE_TEXT_FIELDS) set(source, field, "mutated-unreviewed-" + field);
                    source.setAdvancedConfigJson(narrative("mutated-unreviewed"));
                }
                input.clear();
            }
            return null;
        });
        assertSuccess(fixture.enable());
        InOrder order = inOrder(fixture.security);
        order.verify(fixture.security).checkText(expectedJoined("first"), OWNER_OPENID);
        order.verify(fixture.security).checkText("first-narrative", OWNER_OPENID);
        order.verify(fixture.security).checkText(expectedJoined("second"), OWNER_OPENID);
        order.verify(fixture.security).checkText("second-narrative", OWNER_OPENID);
        verifyNoMoreInteractions(fixture.security);
        verify(fixture.security, never()).checkText(contains("mutated-unreviewed"), any());
        verify(fixture.library, times(2)).updateCmsTemplateLibrary(any());
    }

    @Test void everyOmittedFieldMutationAfterReviewStartsStillFailsContentBinding() {
        for (String property : Arrays.copyOfRange(FREE_TEXT_FIELDS, 2, FREE_TEXT_FIELDS.length)) {
            CmsTemplateLibrary read = row(171);
            CmsTemplateLibrary locked = row(171);
            set(read, property, "before-" + property);
            set(locked, property, "before-" + property);
            Fixture fixture = new Fixture(list(read), list(locked));
            when(fixture.security.checkText(anyString(), eq(OWNER_OPENID))).thenAnswer(invocation -> {
                set(read, property, "after-" + property);
                set(locked, property, "after-" + property);
                return null;
            });
            assertRejected(fixture.enable(), UNAVAILABLE);
            verify(fixture.canonicalMapper).selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(TEMPLATE));
            verify(fixture.projectionMapper).selectByMemberTemplateForUpdate(TEMPLATE);
            fixture.assertNoWrites();
        }
    }

    @Test void addedLockedProjectionStillInvalidatesTheCompleteReviewedBatch() {
        Fixture fixture = new Fixture(list(populated(171, "safe")),
                list(populated(171, "safe"), row(172)));
        assertRejected(fixture.enable(), UNAVAILABLE);
        fixture.assertNoWrites();
    }

    @Test void narrativeDenialAfterFourteenFieldPassStillStopsBeforeLocks() {
        Fixture fixture = new Fixture(list(populated(171, "safe")), list(populated(171, "safe")));
        when(fixture.security.checkText("safe-narrative", OWNER_OPENID)).thenReturn(DENIED);
        AjaxResult result = fixture.enable();
        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains(DENIED));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("enter.opener"));
        verify(fixture.security).checkText(expectedJoined("safe"), OWNER_OPENID);
        fixture.assertNoLocksOrWrites();
    }

    @Test void providerExceptionFailsClosedBeforeLocksWithoutLeakingProviderDetails() {
        Fixture fixture = new Fixture(list(populated(171, "safe")), list(populated(171, "safe")));
        when(fixture.security.checkText(anyString(), eq(OWNER_OPENID)))
                .thenThrow(new IllegalStateException("private-provider-detail"));
        AjaxResult result = fixture.enable();
        assertRejected(result, "更新模板库状态失败，请稍后重试");
        assertFalse(result.toString().contains("private-provider-detail"));
        fixture.assertNoLocksOrWrites();
    }

    @Test void laterBlankTitleProviderExceptionAlsoPreventsEarlierProjectionWrite() {
        CmsTemplateLibrary read = row(172);
        read.setTitle(null);
        read.setQuestionD("later-only-question-d");
        CmsTemplateLibrary locked = row(172);
        locked.setTitle(null);
        locked.setQuestionD("later-only-question-d");
        Fixture fixture = new Fixture(list(row(171), read), list(row(171), locked));
        when(fixture.security.checkText("later-only-question-d", OWNER_OPENID))
                .thenThrow(new IllegalStateException("provider-failed"));
        assertRejected(fixture.enable(), "更新模板库状态失败，请稍后重试");
        verify(fixture.security).checkText("safe-title-171", OWNER_OPENID);
        fixture.assertNoLocksOrWrites();
    }

    @Test void merchantReviewUsesResolvedOwnerForQueryTextIdentityAndServiceOwnership() {
        Fixture fixture = new Fixture(list(populated(171, "merchant")), list(populated(171, "merchant")));
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(OPERATOR, MerchantPermission.PROJECT_MANAGE)).thenReturn(
                new MerchantAccessContext(OPERATOR, 77L, OWNER, MerchantRoleCode.MERCHANT_MANAGER));
        ReflectionTestUtils.setField(fixture.controller, "merchantAccessService", access);
        doReturn(OPERATOR).when(fixture.controller).getAppUserId();
        assertSuccess(fixture.controller.updateTemplateLibraryStatus("71", "1", "MERCHANT"));
        verify(access).require(OPERATOR, MerchantPermission.PROJECT_MANAGE);
        ArgumentCaptor<CmsTemplateLibrary> query = ArgumentCaptor.forClass(CmsTemplateLibrary.class);
        verify(fixture.library).selectCmsTemplateLibraryList(query.capture());
        assertEquals(TEMPLATE, query.getValue().getMemberTemplateId());
        assertEquals(OWNER, query.getValue().getMemberId());
        assertEquals(0, query.getValue().getDelFlag());
        verify(fixture.members).selectUmsMemberById(OWNER);
        verify(fixture.members, never()).selectUmsMemberById(OPERATOR);
        verify(fixture.security).checkText(expectedJoined("merchant"), OWNER_OPENID);
        verify(fixture.security).checkText("merchant-narrative", OWNER_OPENID);
        verifyNoMoreInteractions(fixture.security);
        verify(fixture.library).updateCmsTemplateLibrary(fixture.locked.get(0));
    }

    @Test void legacyEnablingWithoutEvidenceStillCannotBypassModeration() {
        Fixture fixture = new Fixture(list(row(171)), list(row(171)));
        ServiceException error = assertThrows(ServiceException.class,
                () -> fixture.service.updateTemplateLibraryStatus(TEMPLATE, 1, OWNER));
        assertEquals(UNAVAILABLE, error.getMessage());
        fixture.assertNoWrites();
    }

    @Test void withdrawalIgnoresUnsafeTextAndDoesNotReadOrModerateProjections() {
        CmsTemplateLibrary approved = populated(171, "unsafe");
        approved.setPublishStatus(1L);
        CmsTemplateLibrary pending = populated(172, "unsafe");
        pending.setPublishStatus(1L);
        pending.setStatus(0L);
        Fixture fixture = new Fixture(list(row(171)), list(approved, pending));
        fixture.canonical.setStatus(0L);
        fixture.canonical.setCheckStatus(0);
        assertSuccess(fixture.controller.updateTemplateLibraryStatus("71", "0", null));
        verify(fixture.library, never()).selectCmsTemplateLibraryList(any());
        verifyNoInteractions(fixture.security, fixture.members);
        assertEquals(0L, approved.getPublishStatus());
        assertEquals(1L, approved.getStatus());
        assertEquals(0L, pending.getPublishStatus());
        assertEquals(2L, pending.getStatus());
        verify(fixture.library).updateCmsTemplateLibrary(approved);
        verify(fixture.library).updateCmsTemplateLibrary(pending);
        verify(fixture.tasks).cancelOpenTemplateTasks(AuditTask.BIZ_TEMPLATE_LIBRARY, 171L);
        verify(fixture.tasks).cancelOpenTemplateTasks(AuditTask.BIZ_TEMPLATE_LIBRARY, 172L);
        verifyNoInteractions(fixture.canonicalService);
    }

    private static CmsTemplateLibrary row(long id) {
        CmsTemplateLibrary row = new CmsTemplateLibrary();
        row.setId(id);
        row.setMemberTemplateId(TEMPLATE);
        row.setMemberId(OWNER);
        row.setDelFlag(0);
        row.setStatus(1L);
        row.setPublishStatus(0L);
        row.setTitle("safe-title-" + id);
        return row;
    }

    private static CmsTemplateLibrary populated(long id, String prefix) {
        CmsTemplateLibrary row = row(id);
        for (String property : FREE_TEXT_FIELDS) set(row, property, prefix + "-" + property);
        row.setAdvancedConfigJson(narrative(prefix));
        return row;
    }

    private static String narrative(String prefix) {
        return "{\"enter\":{\"opener\":\"" + prefix + "-narrative\"}}";
    }

    private static void set(CmsTemplateLibrary row, String property, String value) {
        new BeanWrapperImpl(row).setPropertyValue(property, value);
    }

    private static List<String> values(String prefix) {
        List<String> values = new ArrayList<>();
        for (String field : FREE_TEXT_FIELDS) values.add(prefix + "-" + field);
        return values;
    }

    private static String expectedJoined(String prefix) {
        return IWxContentSecurityService.joinText(values(prefix).toArray(new String[0]));
    }

    private static String joined(CmsTemplateLibrary row) {
        BeanWrapperImpl bean = new BeanWrapperImpl(row);
        String[] values = new String[FREE_TEXT_FIELDS.length];
        for (int i = 0; i < values.length; i++) values[i] = (String) bean.getPropertyValue(FREE_TEXT_FIELDS[i]);
        return IWxContentSecurityService.joinText(values);
    }

    private static List<CmsTemplateLibrary> list(CmsTemplateLibrary... rows) { return Arrays.asList(rows); }
    private static void assertSuccess(AjaxResult result) {
        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
    }
    private static void assertRejected(AjaxResult result, String message) {
        assertNotEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        assertEquals(message, result.get(AjaxResult.MSG_TAG));
    }

    private static final class Fixture {
        final TemplatePublishServiceImpl service = new TemplatePublishServiceImpl();
        final ApiTemplateController controller = spy(new ApiTemplateController());
        final CmsMemberTemplateMapper canonicalMapper = mock(CmsMemberTemplateMapper.class);
        final CmsTemplateLibraryMapper projectionMapper = mock(CmsTemplateLibraryMapper.class);
        final AuditTaskMapper tasks = mock(AuditTaskMapper.class);
        final ICmsTemplateLibraryService library = mock(ICmsTemplateLibraryService.class);
        final ICmsMemberTemplateService canonicalService = mock(ICmsMemberTemplateService.class);
        final IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        final IUmsMemberService members = mock(IUmsMemberService.class);
        final CmsMemberTemplate canonical = new CmsMemberTemplate();
        final List<CmsTemplateLibrary> locked;

        Fixture(List<CmsTemplateLibrary> read, List<CmsTemplateLibrary> locked) {
            this.locked = locked;
            canonical.setId(TEMPLATE);
            canonical.setMemberId(OWNER);
            canonical.setDelFlag(0);
            canonical.setStatus(1L);
            canonical.setCheckStatus(1);
            canonical.setPublishStatus(0L);
            when(canonicalMapper.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(TEMPLATE)))
                    .thenReturn(Collections.singletonList(canonical));
            when(projectionMapper.selectByMemberTemplateForUpdate(TEMPLATE)).thenReturn(locked);
            when(library.selectCmsTemplateLibraryList(any())).thenReturn(read);
            when(library.updateCmsTemplateLibrary(any())).thenReturn(1);
            UmsMember owner = new UmsMember();
            owner.setWxOpenId(OWNER_OPENID);
            when(members.selectUmsMemberById(OWNER)).thenReturn(owner);
            ReflectionTestUtils.setField(service, "memberTemplateMapper", canonicalMapper);
            ReflectionTestUtils.setField(service, "libraryMapper", projectionMapper);
            ReflectionTestUtils.setField(service, "auditTaskMapper", tasks);
            ReflectionTestUtils.setField(service, "templateLibraryService", library);
            ReflectionTestUtils.setField(service, "memberTemplateService", canonicalService);
            doReturn(OWNER).when(controller).getAppUserId();
            ReflectionTestUtils.setField(controller, "templateLibraryService", library);
            ReflectionTestUtils.setField(controller, "templatePublishService", service);
            ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
            ReflectionTestUtils.setField(controller, "umsMemberService", members);
        }

        AjaxResult enable() { return controller.updateTemplateLibraryStatus("71", "1", null); }

        void assertNoLocksOrWrites() {
            verifyNoInteractions(canonicalMapper, projectionMapper);
            assertNoWrites();
        }

        void assertNoWrites() {
            verify(library, never()).updateCmsTemplateLibrary(any());
            verifyNoInteractions(tasks, canonicalService);
            assertEquals(0L, canonical.getPublishStatus());
            for (CmsTemplateLibrary row : locked) {
                assertEquals(0L, row.getPublishStatus(), "projection mutated before whole-batch validation");
                assertNull(row.getReason());
                assertNull(row.getUpdateTime());
            }
        }
    }
}

package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import com.chengyinhub.business.service.support.TemplateLibraryRepublishReview;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.controller.api.ApiTemplateController;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P119 review-to-write binding regressions. Real controller, service and evidence
 * code run against explicitly modeled persistence reads and locks. These are
 * deterministic unit/integration schedules, not MySQL transaction/concurrency
 * tests or executions of the intervening platform-approval workflow.
 */
class TemplateLibraryRepublishBindingTest {
    private static final Long TEMPLATE = 71L;
    private static final Long OWNER = 900L;
    private static final String UNAVAILABLE = "上架复检暂不可用，请刷新后重试";

    private static CmsTemplateLibrary row(long id) {
        CmsTemplateLibrary row = new CmsTemplateLibrary();
        row.setId(id);
        row.setMemberTemplateId(TEMPLATE);
        row.setMemberId(OWNER);
        row.setDelFlag(0);
        row.setStatus(1L);
        row.setPublishStatus(0L);
        row.setTitle("reviewed-title-" + id);
        return row;
    }

    private static List<CmsTemplateLibrary> rows(CmsTemplateLibrary... rows) {
        return Arrays.asList(rows);
    }

    private static TemplateLibraryRepublishReview freeze(CmsTemplateLibrary... rows) {
        return TemplateLibraryRepublishReview.freeze(TEMPLATE, OWNER, rows(rows));
    }

    private static boolean matches(TemplateLibraryRepublishReview review, CmsTemplateLibrary... rows) {
        return review.matches(TEMPLATE, OWNER, rows(rows));
    }

    @Test void unchangedDistinctInstancesAndOrderOnlyChangesMatch() {
        TemplateLibraryRepublishReview review = freeze(row(171), row(172));
        assertTrue(matches(review, row(171), row(172)));
        assertTrue(matches(review, row(172), row(171)));
    }

    @Test void freezeDoesNotRetainMutableDomainObjectsOrTheInputList() {
        CmsTemplateLibrary original = row(171);
        original.setDescription("reviewed-description");
        original.setAdvancedConfigJson("{\"enter\":{\"opener\":\"reviewed-narrative\"}}");
        List<CmsTemplateLibrary> input = new ArrayList<>(rows(original));
        TemplateLibraryRepublishReview review = TemplateLibraryRepublishReview.freeze(TEMPLATE, OWNER, input);
        original.setId(199L);
        original.setMemberId(999L);
        original.setTitle("mutated-title");
        original.setDescription("mutated-description");
        original.setAdvancedConfigJson("{}");
        input.clear();

        CmsTemplateLibrary unchanged = row(171);
        unchanged.setDescription("reviewed-description");
        unchanged.setAdvancedConfigJson("{\"enter\":{\"opener\":\"reviewed-narrative\"}}");
        assertTrue(matches(review, unchanged));
        assertFalse(matches(review, original));
        assertEquals(1, review.getReviewedTexts().size());
        assertEquals("reviewed-title-171", review.getReviewedTexts().get(0).getTitle());
        assertEquals("reviewed-description", review.getReviewedTexts().get(0).getDescription());
        assertEquals(unchanged.getAdvancedConfigJson(), review.getReviewedTexts().get(0).getAdvancedConfigJson());
        assertThrows(UnsupportedOperationException.class, () -> review.getReviewedTexts().clear());
    }

    @Test void freezeRejectsNullEmptyAndNullElementBatchesWithoutContentDisclosure() {
        assertThrows(ServiceException.class, () -> TemplateLibraryRepublishReview.freeze(TEMPLATE, OWNER, null));
        assertThrows(ServiceException.class, () -> TemplateLibraryRepublishReview.freeze(TEMPLATE, OWNER, Collections.emptyList()));
        CmsTemplateLibrary secret = row(171);
        secret.setTitle("private-template-text-must-not-leak");
        ServiceException error = assertThrows(ServiceException.class, () -> freeze(secret, null));
        assertEquals(UNAVAILABLE, error.getMessage());
        assertFalse(error.getMessage().contains(secret.getTitle()));
    }

    @Test void freezeRejectsInvalidExpectedOwnerAndTemplateCoordinates() {
        for (Long invalid : Arrays.asList(null, 0L, -1L)) {
            assertThrows(ServiceException.class,
                    () -> TemplateLibraryRepublishReview.freeze(invalid, OWNER, rows(row(171))), "template=" + invalid);
            assertThrows(ServiceException.class,
                    () -> TemplateLibraryRepublishReview.freeze(TEMPLATE, invalid, rows(row(171))), "owner=" + invalid);
        }
    }

    @Test void freezeRejectsMalformedForeignDeletedAndDuplicateProjectionRows() {
        List<Consumer<CmsTemplateLibrary>> invalidRows = Arrays.asList(
                r -> r.setId(null), r -> r.setId(0L), r -> r.setId(-1L),
                r -> r.setMemberId(null), r -> r.setMemberId(0L), r -> r.setMemberId(901L),
                r -> r.setMemberTemplateId(null), r -> r.setMemberTemplateId(0L), r -> r.setMemberTemplateId(72L),
                r -> r.setDelFlag(null), r -> r.setDelFlag(1), r -> r.setDelFlag(2));
        for (int i = 0; i < invalidRows.size(); i++) {
            CmsTemplateLibrary invalid = row(172);
            invalidRows.get(i).accept(invalid);
            assertThrows(ServiceException.class, () -> freeze(row(171), invalid), "malformed row variant " + i);
        }
        assertThrows(ServiceException.class, () -> freeze(row(171), row(171)));
    }

    @Test void matchesFailsClosedForMalformedCoordinatesListsAndRows() {
        TemplateLibraryRepublishReview review = freeze(row(171));
        for (Long invalid : Arrays.asList(null, 0L, -1L, 72L)) {
            assertFalse(review.matches(invalid, OWNER, rows(row(171))), "template=" + invalid);
        }
        for (Long invalid : Arrays.asList(null, 0L, -1L, 901L)) {
            assertFalse(review.matches(TEMPLATE, invalid, rows(row(171))), "owner=" + invalid);
        }
        assertFalse(review.matches(TEMPLATE, OWNER, null));
        assertFalse(review.matches(TEMPLATE, OWNER, Collections.emptyList()));
        assertFalse(matches(review, row(171), null));
        assertFalse(matches(review, row(171), row(171)));
        List<Consumer<CmsTemplateLibrary>> invalidRows = Arrays.asList(
                r -> r.setId(null), r -> r.setId(0L), r -> r.setId(-1L),
                r -> r.setMemberId(null), r -> r.setMemberId(0L), r -> r.setMemberId(901L),
                r -> r.setMemberTemplateId(null), r -> r.setMemberTemplateId(0L), r -> r.setMemberTemplateId(72L),
                r -> r.setDelFlag(null), r -> r.setDelFlag(1));
        for (int i = 0; i < invalidRows.size(); i++) {
            CmsTemplateLibrary invalid = row(171);
            invalidRows.get(i).accept(invalid);
            assertFalse(matches(review, invalid), "malformed row variant " + i);
        }
    }

    @Test void evidenceBindsTheExpectedOwnerAndTemplateEvenIfEveryRowIsReassociated() {
        TemplateLibraryRepublishReview review = freeze(row(171));
        CmsTemplateLibrary reassociated = row(171);
        reassociated.setMemberId(901L);
        reassociated.setMemberTemplateId(72L);
        assertFalse(review.matches(72L, 901L, rows(reassociated)));
    }

    @Test void completeActiveSetRejectsAddedMissingReplacedAndDeletedRows() {
        TemplateLibraryRepublishReview review = freeze(row(171), row(172));
        assertFalse(matches(review, row(171)));
        assertFalse(matches(review, row(171), row(172), row(173)));
        CmsTemplateLibrary replacement = row(173);
        replacement.setTitle(row(172).getTitle());
        assertFalse(matches(review, row(171), replacement));
        CmsTemplateLibrary deleted = row(172);
        deleted.setDelFlag(2);
        assertFalse(matches(review, row(171), deleted));
    }

    @Test void unrelatedValidDeletedHistoryDoesNotAlterTheCompleteActiveSet() {
        CmsTemplateLibrary deleted = row(172);
        deleted.setDelFlag(2);
        deleted.setTitle("historical-content");
        assertTrue(matches(freeze(row(171)), deleted, row(171)));
        CmsTemplateLibrary deletedOnly = row(171);
        deletedOnly.setDelFlag(2);
        assertFalse(matches(freeze(row(171)), deletedOnly));
    }

    @Test void deletedHistoryCannotHideDuplicateProjectionIds() {
        CmsTemplateLibrary deletedActiveDuplicate = row(171);
        deletedActiveDuplicate.setDelFlag(2);
        CmsTemplateLibrary deletedFirst = row(172);
        deletedFirst.setDelFlag(2);
        CmsTemplateLibrary deletedSecond = row(172);
        deletedSecond.setDelFlag(2);
        TemplateLibraryRepublishReview review = freeze(row(171));
        assertFalse(matches(review, row(171), deletedActiveDuplicate));
        assertFalse(matches(review, deletedActiveDuplicate, row(171)));
        assertFalse(matches(review, row(171), deletedFirst, deletedSecond));
        for (List<CmsTemplateLibrary> locked : Arrays.asList(
                rows(row(171), deletedActiveDuplicate), rows(row(171), deletedFirst, deletedSecond))) {
            ServiceFixture fixture = new ServiceFixture(locked.toArray(new CmsTemplateLibrary[0]));
            assertThrows(ServiceException.class, () -> fixture.enable(review));
            fixture.assertNoSideEffects();
        }
    }

    @Test void allCurrentMarketContentFieldsAreBoundIndividually() {
        // Explicit contract: these are the content properties currently present on
        // CmsTemplateLibrary and included in TemplateAuditSnapshot.market.
        String[] properties = ("categoryId title description imgUrl players usageLocation requiredMaterials "
                + "duration difficulty ruleInstructions merchantGuide rewardRulesJson validationMethod sensorType "
                + "sensorConfig questionName questionAnswer feedbackText couponId medalImg medalName medalStyle "
                + "questionA questionB questionC questionD correctAnswer questionImg questionAudio questionOptionMediaJson "
                + "preferenceJson advancedConfigJson activityCategoryids storyText storyImg storyJson hint1 hint2 "
                + "answerReveal photoRequireDesc photoReview audioUrl audioDuration packType").split(" ");
        TemplateLibraryRepublishReview review = freeze(row(171));
        for (String property : properties) {
            CmsTemplateLibrary changed = row(171);
            BeanWrapper bean = new BeanWrapperImpl(changed);
            assertTrue(bean.isWritableProperty(property), "fixture no longer models " + property);
            Class<?> type = bean.getPropertyType(property);
            Object value = type == Long.class ? Long.valueOf(37L)
                    : type == Integer.class ? Integer.valueOf(37) : "changed-" + property;
            bean.setPropertyValue(property, value);
            assertFalse(matches(review, changed), "unbound content field: " + property);
        }
    }

    @Test void countersTimestampsAndLifecycleMetadataAreOutsideTheContentSnapshot() {
        CmsTemplateLibrary changed = row(171);
        changed.setUseNum(200);
        changed.setRating("4.9");
        changed.setCreateTime(new Date(123L));
        changed.setUpdateTime(new Date(456L));
        changed.setCreateBy("creator");
        changed.setUpdateBy("reviewer");
        changed.setStatus(0L);
        changed.setPublishStatus(1L);
        changed.setReason("pending-new-review");
        changed.setIsSync(1);
        assertTrue(matches(freeze(row(171)), changed));
    }

    @Test void approvedUnchangedBatchPublishesOnlyAfterCanonicalThenProjectionLocks() {
        CmsTemplateLibrary first = row(171);
        CmsTemplateLibrary second = row(172);
        ServiceFixture fixture = new ServiceFixture(first, second);
        assertTrue(fixture.enable(freeze(row(172), row(171))));
        InOrder order = inOrder(fixture.canonicalMapper, fixture.projectionMapper, fixture.library);
        order.verify(fixture.canonicalMapper).selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(TEMPLATE));
        order.verify(fixture.projectionMapper).selectByMemberTemplateForUpdate(TEMPLATE);
        order.verify(fixture.library).updateCmsTemplateLibrary(first);
        order.verify(fixture.library).updateCmsTemplateLibrary(second);
        assertEquals(1L, first.getPublishStatus());
        assertEquals(1L, second.getPublishStatus());
        assertEquals(0L, fixture.canonical.getPublishStatus());
        verifyNoInteractions(fixture.tasks, fixture.canonicalService);
    }

    @Test void legacyThreeArgumentEnablingCannotBypassReviewEvidence() {
        ServiceFixture fixture = new ServiceFixture(row(171));
        ServiceException failure = assertThrows(ServiceException.class,
                () -> fixture.service.updateTemplateLibraryStatus(TEMPLATE, 1, OWNER));
        assertEquals(UNAVAILABLE, failure.getMessage());
        fixture.assertNoSideEffects();
    }

    @Test void reviewedOverloadRejectsNullEvidenceBeforeAnyWrite() {
        ServiceFixture fixture = new ServiceFixture(row(171));
        assertThrows(ServiceException.class, () -> fixture.enable(null));
        fixture.assertNoSideEffects();
    }

    @Test void evidenceForAnotherOwnerOrTemplateCannotAuthorizeThisServiceCall() {
        CmsTemplateLibrary foreignOwner = row(171);
        foreignOwner.setMemberId(901L);
        CmsTemplateLibrary foreignTemplate = row(171);
        foreignTemplate.setMemberTemplateId(72L);
        for (TemplateLibraryRepublishReview review : Arrays.asList(
                TemplateLibraryRepublishReview.freeze(TEMPLATE, 901L, rows(foreignOwner)),
                TemplateLibraryRepublishReview.freeze(72L, OWNER, rows(foreignTemplate)))) {
            ServiceFixture fixture = new ServiceFixture(row(171));
            assertThrows(ServiceException.class, () -> fixture.enable(review));
            fixture.assertNoSideEffects();
        }
    }

    @Test void everyLaterContentMismatchIsRejectedBeforeEarlierRowsAreTouched() {
        List<Consumer<CmsTemplateLibrary>> changes = Arrays.asList(
                r -> r.setTitle("newly-approved-title"),
                r -> r.setDescription("newly-approved-description"),
                r -> r.setAdvancedConfigJson("{\"enter\":{\"opener\":\"newly-approved-narrative\"}}"),
                r -> r.setRuleInstructions("newly-approved-rules"),
                r -> r.setRewardRulesJson("{\"newlyApprovedReward\":true}"));
        for (int i = 0; i < changes.size(); i++) {
            CmsTemplateLibrary changed = row(172);
            changes.get(i).accept(changed);
            ServiceFixture fixture = new ServiceFixture(row(171), changed);
            ServiceException failure = assertThrows(ServiceException.class,
                    () -> fixture.enable(freeze(row(171), row(172))), "content mutation " + i);
            assertEquals(UNAVAILABLE, failure.getMessage());
            fixture.assertNoSideEffects();
        }
    }

    @Test void additionalMissingReplacementAndDeletedActiveRowsAllPreventServiceWrites() {
        CmsTemplateLibrary deleted = row(172);
        deleted.setDelFlag(2);
        List<List<CmsTemplateLibrary>> schedules = Arrays.asList(
                rows(row(171), row(172), row(173)), rows(row(171)),
                rows(row(171), row(173)), rows(row(171), deleted));
        for (List<CmsTemplateLibrary> locked : schedules) {
            ServiceFixture fixture = new ServiceFixture(locked.toArray(new CmsTemplateLibrary[0]));
            assertThrows(ServiceException.class, () -> fixture.enable(freeze(row(171), row(172))));
            fixture.assertNoSideEffects();
        }
    }

    @Test void laterMalformedForeignAndDuplicateRowsPreventAllServiceWrites() {
        List<Consumer<CmsTemplateLibrary>> invalidRows = Arrays.asList(
                r -> r.setId(null), r -> r.setId(0L), r -> r.setId(171L),
                r -> r.setMemberId(null), r -> r.setMemberId(901L),
                r -> r.setMemberTemplateId(null), r -> r.setMemberTemplateId(72L),
                r -> r.setDelFlag(null), r -> r.setDelFlag(1));
        for (int i = 0; i < invalidRows.size(); i++) {
            CmsTemplateLibrary invalid = row(172);
            invalidRows.get(i).accept(invalid);
            ServiceFixture fixture = new ServiceFixture(row(171), invalid);
            assertThrows(ServiceException.class, () -> fixture.enable(freeze(row(171), row(172))), "invalid row " + i);
            fixture.assertNoSideEffects();
        }
        ServiceFixture nullRow = new ServiceFixture(row(171), null);
        assertThrows(ServiceException.class, () -> nullRow.enable(freeze(row(171), row(172))));
        nullRow.assertNoSideEffects();
    }

    @Test void pendingOrRejectedLaterMarketApprovalPreventsAllWritesDespiteMatchingContent() {
        for (Long status : Arrays.asList(null, 0L, 2L)) {
            CmsTemplateLibrary pending = row(172);
            pending.setStatus(status);
            ServiceFixture fixture = new ServiceFixture(row(171), pending);
            ServiceException error = assertThrows(ServiceException.class,
                    () -> fixture.enable(freeze(row(171), row(172))));
            assertEquals("模板广场审核通过后才能上架", error.getMessage());
            fixture.assertNoSideEffects();
        }
    }

    @Test void bothPersonalApprovalGatesRemainRequiredWithValidEvidence() {
        ServiceFixture statusPending = new ServiceFixture(row(171));
        statusPending.canonical.setStatus(0L);
        ServiceFixture checkPending = new ServiceFixture(row(171));
        checkPending.canonical.setCheckStatus(0);
        for (ServiceFixture fixture : Arrays.asList(statusPending, checkPending)) {
            ServiceException error = assertThrows(ServiceException.class, () -> fixture.enable(freeze(row(171))));
            assertEquals("模板审核通过后才能上架到玩法库", error.getMessage());
            verifyNoInteractions(fixture.projectionMapper);
            fixture.assertNoSideEffects();
        }
    }

    @Test void originalPersonalAndMissingProjectionErrorsPrecedeMissingEvidenceError() {
        ServiceFixture pending = new ServiceFixture(row(171));
        pending.canonical.setCheckStatus(0);
        assertEquals("模板审核通过后才能上架到玩法库", assertThrows(ServiceException.class,
                () -> pending.service.updateTemplateLibraryStatus(TEMPLATE, 1, OWNER)).getMessage());
        pending.assertNoSideEffects();
        ServiceFixture missing = new ServiceFixture();
        assertEquals("该模板还没有提交到模板广场，审核通过后才能上架", assertThrows(ServiceException.class,
                () -> missing.service.updateTemplateLibraryStatus(TEMPLATE, 1, OWNER)).getMessage());
        missing.assertNoSideEffects();
    }

    @Test void canonicalOwnershipAndDeletionGatesRemainRequired() {
        ServiceFixture foreign = new ServiceFixture(row(171));
        foreign.canonical.setMemberId(901L);
        assertThrows(ServiceException.class, () -> foreign.enable(freeze(row(171))));
        foreign.assertNoSideEffects();
        verifyNoInteractions(foreign.projectionMapper);
        ServiceFixture deleted = new ServiceFixture(row(171));
        deleted.canonical.setDelFlag(2);
        assertThrows(RuntimeException.class, () -> deleted.enable(freeze(row(171))));
        deleted.assertNoSideEffects();
        verifyNoInteractions(deleted.projectionMapper);
    }

    @Test void lockedCanonicalIdMustMatchTheRequestedTemplateAndReviewEvidence() {
        ServiceFixture fixture = new ServiceFixture(row(171));
        fixture.canonical.setId(72L);
        ServiceException failure = assertThrows(ServiceException.class, () -> fixture.enable(freeze(row(171))));
        assertEquals(UNAVAILABLE, failure.getMessage());
        verify(fixture.canonicalMapper).selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(TEMPLATE));
        fixture.assertNoSideEffects();
    }

    @Test void serviceAllowsOrderCounterAndTimestampOnlyChangesAndSkipsDeletedHistory() {
        CmsTemplateLibrary first = row(171);
        first.setUseNum(42);
        first.setUpdateTime(new Date(789L));
        CmsTemplateLibrary deleted = row(173);
        deleted.setDelFlag(2);
        ServiceFixture fixture = new ServiceFixture(row(172), deleted, first);
        assertTrue(fixture.enable(freeze(row(171), row(172))));
        verify(fixture.library, times(2)).updateCmsTemplateLibrary(any());
        verify(fixture.library, never()).updateCmsTemplateLibrary(deleted);
        assertEquals(0L, deleted.getPublishStatus());
        verifyNoInteractions(fixture.tasks, fixture.canonicalService);
    }

    @Test void withdrawalRemainsAvailableWithoutEvidenceOrPersonalApproval() {
        CmsTemplateLibrary approved = row(171);
        approved.setPublishStatus(1L);
        CmsTemplateLibrary pending = row(172);
        pending.setStatus(0L);
        pending.setPublishStatus(1L);
        CmsTemplateLibrary deleted = row(173);
        deleted.setDelFlag(2);
        ServiceFixture fixture = new ServiceFixture(approved, pending, deleted);
        fixture.canonical.setStatus(0L);
        fixture.canonical.setCheckStatus(0);
        assertTrue(fixture.service.updateTemplateLibraryStatus(TEMPLATE, 0, OWNER));
        assertEquals(0L, approved.getPublishStatus());
        assertEquals(1L, approved.getStatus());
        assertEquals(0L, pending.getPublishStatus());
        assertEquals(2L, pending.getStatus());
        assertEquals("作者已撤回，请重新提交广场审核", pending.getReason());
        verify(fixture.tasks).cancelOpenTemplateTasks(AuditTask.BIZ_TEMPLATE_LIBRARY, 171L);
        verify(fixture.tasks).cancelOpenTemplateTasks(AuditTask.BIZ_TEMPLATE_LIBRARY, 172L);
        verify(fixture.tasks, never()).cancelOpenTemplateTasks(AuditTask.BIZ_TEMPLATE_LIBRARY, 173L);
        verify(fixture.library).updateCmsTemplateLibrary(approved);
        verify(fixture.library).updateCmsTemplateLibrary(pending);
        verify(fixture.library, never()).updateCmsTemplateLibrary(deleted);
        verifyNoInteractions(fixture.canonicalService);
    }

    @Test void unchangedControllerReviewFinishesBeforeServiceLocksAndWrite() {
        ServiceFixture fixture = new ServiceFixture(row(171));
        ControllerFixture controller = new ControllerFixture(fixture, row(171));
        assertSuccess(controller.enable());
        InOrder order = inOrder(fixture.library, controller.security, fixture.canonicalMapper, fixture.projectionMapper);
        order.verify(fixture.library).selectCmsTemplateLibraryList(any());
        order.verify(controller.security).checkText("reviewed-title-171", null);
        order.verify(fixture.canonicalMapper).selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(TEMPLATE));
        order.verify(fixture.projectionMapper).selectByMemberTemplateForUpdate(TEMPLATE);
        order.verify(fixture.library).updateCmsTemplateLibrary(fixture.locked.get(0));
    }

    @Test void changedThenReapprovedContentIsRejectedUsingTheOldReview() {
        // The pre-fix audit's same modeled old-read/new-approved-lock schedule
        // returned success. Platform approval is modeled, never fabricated here.
        CmsTemplateLibrary reapproved = row(171);
        reapproved.setTitle("changed-and-platform-reapproved");
        ServiceFixture fixture = new ServiceFixture(reapproved);
        ControllerFixture controller = new ControllerFixture(fixture, row(171));
        assertBindingRejected(controller.enable());
        verify(controller.security).checkText("reviewed-title-171", null);
        fixture.assertNoSideEffects();
    }

    @Test void replacementThenApprovedProjectionIsRejectedUsingTheOldReview() {
        // Even identical content cannot transfer evidence to a replacement ID.
        CmsTemplateLibrary replacement = row(172);
        replacement.setTitle("reviewed-title-171");
        ServiceFixture fixture = new ServiceFixture(replacement);
        ControllerFixture controller = new ControllerFixture(fixture, row(171));
        assertBindingRejected(controller.enable());
        verify(controller.security).checkText("reviewed-title-171", null);
        fixture.assertNoSideEffects();
    }

    @Test void changedDescriptionOrNarrativeAlsoFailsThroughRealControllerAndService() {
        for (Consumer<CmsTemplateLibrary> change : Arrays.<Consumer<CmsTemplateLibrary>>asList(
                r -> r.setDescription("newly-approved-description"),
                r -> r.setAdvancedConfigJson("{\"enter\":{\"opener\":\"newly-approved-story\"}}"))) {
            CmsTemplateLibrary changed = row(171);
            change.accept(changed);
            ServiceFixture fixture = new ServiceFixture(changed);
            ControllerFixture controller = new ControllerFixture(fixture, row(171));
            assertBindingRejected(controller.enable());
            fixture.assertNoSideEffects();
        }
    }

    @Test void editStillPendingAndDeletedProjectionStayBlockedThroughController() {
        CmsTemplateLibrary pending = row(171);
        pending.setTitle("pending-edited-title");
        pending.setStatus(0L);
        CmsTemplateLibrary deleted = row(171);
        deleted.setDelFlag(2);
        for (CmsTemplateLibrary locked : rows(pending, deleted)) {
            ServiceFixture fixture = new ServiceFixture(locked);
            ControllerFixture controller = new ControllerFixture(fixture, row(171));
            assertNotEquals(200, controller.enable().get(AjaxResult.CODE_TAG));
            verify(controller.security).checkText("reviewed-title-171", null);
            fixture.assertNoSideEffects();
        }
    }

    @Test void controllerFreezesBothEvidenceAndReviewTextBeforeFirstNetworkCall() {
        CmsTemplateLibrary first = row(171);
        first.setDescription("first-description");
        first.setAdvancedConfigJson("{\"enter\":{\"opener\":\"first-old-story\"}}");
        CmsTemplateLibrary second = row(172);
        second.setDescription("second-description");
        second.setAdvancedConfigJson("{\"enter\":{\"opener\":\"second-old-story\"}}");
        CmsTemplateLibrary lockedFirst = row(171);
        lockedFirst.setDescription(first.getDescription());
        lockedFirst.setAdvancedConfigJson(first.getAdvancedConfigJson());
        CmsTemplateLibrary lockedSecond = row(172);
        lockedSecond.setDescription(second.getDescription());
        lockedSecond.setAdvancedConfigJson(second.getAdvancedConfigJson());
        ServiceFixture fixture = new ServiceFixture(lockedFirst, lockedSecond);
        ControllerFixture controller = new ControllerFixture(fixture, first, second);
        when(controller.security.checkText(eq(IWxContentSecurityService.joinText(first.getTitle(), first.getDescription())), isNull()))
                .thenAnswer(invocation -> {
                    for (CmsTemplateLibrary source : rows(first, second)) {
                        source.setTitle("mutated-unreviewed-title");
                        source.setDescription("mutated-unreviewed-description");
                        source.setAdvancedConfigJson("{\"enter\":{\"opener\":\"mutated-unreviewed-story\"}}");
                    }
                    return null;
                });
        assertSuccess(controller.enable());
        verify(controller.security).checkText(IWxContentSecurityService.joinText("reviewed-title-172", "second-description"), null);
        verify(controller.security).checkText("first-old-story", null);
        verify(controller.security).checkText("second-old-story", null);
        verify(controller.security, never()).checkText(contains("mutated-unreviewed"), any());
        verify(fixture.library, times(2)).updateCmsTemplateLibrary(any());
    }

    @Test void mutationDuringReviewCannotBeFrozenAfterTheReviewAndThenPublished() {
        CmsTemplateLibrary read = row(171);
        CmsTemplateLibrary locked = row(171);
        ServiceFixture fixture = new ServiceFixture(locked);
        ControllerFixture controller = new ControllerFixture(fixture, read);
        when(controller.security.checkText("reviewed-title-171", null)).thenAnswer(invocation -> {
            read.setTitle("changed-after-check-started");
            locked.setTitle("changed-after-check-started");
            return null;
        });
        assertBindingRejected(controller.enable());
        verify(controller.security).checkText("reviewed-title-171", null);
        fixture.assertNoSideEffects();
    }

    @Test void duplicateReadIdsAreRejectedBeforeContentTransmissionOrServiceLocks() {
        ServiceFixture fixture = new ServiceFixture(row(171));
        ControllerFixture controller = new ControllerFixture(fixture, row(171), row(171));
        assertBindingRejected(controller.enable());
        verifyNoInteractions(controller.security, fixture.canonicalMapper, fixture.projectionMapper);
        fixture.assertNoSideEffects();
    }

    @Test void failedModerationNeverReachesServiceLocksOrWrites() {
        ServiceFixture fixture = new ServiceFixture(row(171), row(172));
        ControllerFixture controller = new ControllerFixture(fixture, row(171), row(172));
        when(controller.security.checkText("reviewed-title-172", null)).thenReturn("内容不合规");
        AjaxResult result = controller.enable();
        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("内容不合规", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(fixture.canonicalMapper, fixture.projectionMapper);
        fixture.assertNoSideEffects();
    }

    @Test void retryAfterStaleEvidencePerformsAFreshReadAndFreshReview() {
        CmsTemplateLibrary changed = row(171);
        changed.setTitle("new-platform-approved-title");
        ServiceFixture fixture = new ServiceFixture(changed);
        ControllerFixture controller = new ControllerFixture(fixture, row(171));
        assertBindingRejected(controller.enable());
        fixture.assertNoSideEffects();
        CmsTemplateLibrary freshRead = row(171);
        freshRead.setTitle(changed.getTitle());
        when(fixture.library.selectCmsTemplateLibraryList(any())).thenReturn(rows(freshRead));
        assertSuccess(controller.enable());
        verify(fixture.library, times(2)).selectCmsTemplateLibraryList(any());
        verify(controller.security).checkText("reviewed-title-171", null);
        verify(controller.security).checkText("new-platform-approved-title", null);
        verify(fixture.library).updateCmsTemplateLibrary(changed);
    }

    @Test void controllerWithdrawalStillSkipsReviewReadAndContentSecurity() {
        CmsTemplateLibrary pending = row(171);
        pending.setStatus(0L);
        ServiceFixture fixture = new ServiceFixture(pending);
        ControllerFixture controller = new ControllerFixture(fixture, row(171));
        assertSuccess(controller.controller.updateTemplateLibraryStatus("71", "0", null));
        verify(fixture.library, never()).selectCmsTemplateLibraryList(any());
        verifyNoInteractions(controller.security);
        verify(fixture.library).updateCmsTemplateLibrary(pending);
        verify(fixture.tasks).cancelOpenTemplateTasks(AuditTask.BIZ_TEMPLATE_LIBRARY, 171L);
    }

    private static void assertSuccess(AjaxResult result) {
        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
    }

    private static void assertBindingRejected(AjaxResult result) {
        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(UNAVAILABLE, result.get(AjaxResult.MSG_TAG));
    }

    private static final class ServiceFixture {
        final TemplatePublishServiceImpl service = new TemplatePublishServiceImpl();
        final CmsMemberTemplateMapper canonicalMapper = mock(CmsMemberTemplateMapper.class);
        final CmsTemplateLibraryMapper projectionMapper = mock(CmsTemplateLibraryMapper.class);
        final AuditTaskMapper tasks = mock(AuditTaskMapper.class);
        final ICmsTemplateLibraryService library = mock(ICmsTemplateLibraryService.class);
        final ICmsMemberTemplateService canonicalService = mock(ICmsMemberTemplateService.class);
        final CmsMemberTemplate canonical = new CmsMemberTemplate();
        final List<CmsTemplateLibrary> locked;

        ServiceFixture(CmsTemplateLibrary... lockedRows) {
            locked = rows(lockedRows);
            canonical.setId(TEMPLATE);
            canonical.setMemberId(OWNER);
            canonical.setDelFlag(0);
            canonical.setStatus(1L);
            canonical.setCheckStatus(1);
            canonical.setPublishStatus(0L);
            when(canonicalMapper.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(TEMPLATE)))
                    .thenReturn(Collections.singletonList(canonical));
            when(projectionMapper.selectByMemberTemplateForUpdate(TEMPLATE)).thenReturn(locked);
            when(library.updateCmsTemplateLibrary(any())).thenReturn(1);
            ReflectionTestUtils.setField(service, "memberTemplateMapper", canonicalMapper);
            ReflectionTestUtils.setField(service, "libraryMapper", projectionMapper);
            ReflectionTestUtils.setField(service, "auditTaskMapper", tasks);
            ReflectionTestUtils.setField(service, "templateLibraryService", library);
            ReflectionTestUtils.setField(service, "memberTemplateService", canonicalService);
        }

        boolean enable(TemplateLibraryRepublishReview review) {
            return service.updateTemplateLibraryStatus(TEMPLATE, 1, OWNER, review);
        }

        void assertNoSideEffects() {
            verify(library, never()).updateCmsTemplateLibrary(any());
            verifyNoInteractions(tasks, canonicalService);
            assertEquals(0L, canonical.getPublishStatus());
            for (CmsTemplateLibrary row : locked) {
                if (row != null) {
                    assertEquals(0L, row.getPublishStatus(), "projection was mutated before batch validation");
                    assertNull(row.getReason(), "projection reason was mutated before batch validation");
                    assertNull(row.getUpdateTime(), "projection timestamp was mutated before batch validation");
                }
            }
        }
    }

    private static final class ControllerFixture {
        final ApiTemplateController controller = spy(new ApiTemplateController());
        final IWxContentSecurityService security = mock(IWxContentSecurityService.class);

        ControllerFixture(ServiceFixture fixture, CmsTemplateLibrary... readRows) {
            doReturn(OWNER).when(controller).getAppUserId();
            when(fixture.library.selectCmsTemplateLibraryList(any())).thenReturn(rows(readRows));
            ReflectionTestUtils.setField(controller, "templateLibraryService", fixture.library);
            ReflectionTestUtils.setField(controller, "templatePublishService", fixture.service);
            ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
            ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        }

        AjaxResult enable() {
            return controller.updateTemplateLibraryStatus("71", "1", null);
        }
    }
}

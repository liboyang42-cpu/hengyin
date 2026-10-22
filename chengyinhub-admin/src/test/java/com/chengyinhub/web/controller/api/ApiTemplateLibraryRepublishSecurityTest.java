package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.support.TemplateLibraryRepublishReview;

import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.service.ICmsTemplateLibraryService;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** P119/PA02/W09.4: retryable, fail-closed review of the actual owner template projections. */
class ApiTemplateLibraryRepublishSecurityTest {
    private ApiTemplateController controller;
    private ICmsTemplateLibraryService library;
    private ITemplatePublishService publish;
    private IWxContentSecurityService security;

    @BeforeEach void setup() {
        controller = spy(new ApiTemplateController());
        doReturn(900L).when(controller).getAppUserId();
        library = mock(ICmsTemplateLibraryService.class);
        publish = mock(ITemplatePublishService.class);
        security = mock(IWxContentSecurityService.class);
        ReflectionTestUtils.setField(controller, "templateLibraryService", library);
        ReflectionTestUtils.setField(controller, "templatePublishService", publish);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        when(publish.updateTemplateLibraryStatus(eq(71L), eq(1), eq(900L), any(TemplateLibraryRepublishReview.class))).thenReturn(true);
    }
    private CmsTemplateLibrary row(long id) {
        CmsTemplateLibrary row = new CmsTemplateLibrary();
        row.setId(id); row.setMemberTemplateId(71L); row.setMemberId(900L); row.setDelFlag(0);
        row.setTitle("title" + id); row.setDescription("description" + id);
        return row;
    }
    private AjaxResult enable() { return controller.updateTemplateLibraryStatus("71", "1", null); }
    private void rejected(AjaxResult result) {
        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(publish);
    }
    @Test void distinctLibraryIdUsesOwnerAndMemberTemplateCoordinates() {
        when(library.selectCmsTemplateLibraryList(any())).thenReturn(Collections.singletonList(row(171)));
        assertEquals(200, enable().get(AjaxResult.CODE_TAG));
        ArgumentCaptor<CmsTemplateLibrary> query = ArgumentCaptor.forClass(CmsTemplateLibrary.class);
        verify(library).selectCmsTemplateLibraryList(query.capture());
        assertEquals(71L, query.getValue().getMemberTemplateId());
        assertEquals(900L, query.getValue().getMemberId());
        assertEquals(0, query.getValue().getDelFlag());
        verify(library, never()).selectCmsTemplateLibraryById(anyLong());
        verify(security).checkText(eq(IWxContentSecurityService.joinText("title171", "description171")), isNull());
        verify(publish).updateTemplateLibraryStatus(eq(71L), eq(1), eq(900L), any(TemplateLibraryRepublishReview.class));
    }
    @Test void allValidProjectionsAreReviewed() {
        when(library.selectCmsTemplateLibraryList(any())).thenReturn(Arrays.asList(row(171), row(172)));
        assertEquals(200, enable().get(AjaxResult.CODE_TAG));
        verify(security, times(2)).checkText(anyString(), isNull());
        verify(publish, times(1)).updateTemplateLibraryStatus(eq(71L), eq(1), eq(900L), any(TemplateLibraryRepublishReview.class));
    }
    @Test void secondProjectionRejectionBlocksWholeWrite() {
        when(library.selectCmsTemplateLibraryList(any())).thenReturn(Arrays.asList(row(171), row(172)));
        when(security.checkText(contains("title172"), isNull())).thenReturn("内容不合规");
        AjaxResult result = enable(); rejected(result);
        assertEquals("内容不合规", result.get(AjaxResult.MSG_TAG));
    }
    @Test void advancedNarrativeStillReviewedAndCanReject() {
        CmsTemplateLibrary row = row(171); row.setAdvancedConfigJson("{\"enter\":{\"opener\":\"blocked narrative\"}}");
        when(library.selectCmsTemplateLibraryList(any())).thenReturn(Collections.singletonList(row));
        when(security.checkText(contains("blocked narrative"), isNull())).thenReturn("内容不合规");
        rejected(enable()); verify(security, times(2)).checkText(contains("blocked narrative"), isNull());
    }
    @Test void nullListBlocksWrite() {
        when(library.selectCmsTemplateLibraryList(any())).thenReturn(null);
        rejected(enable()); verifyNoInteractions(security);
    }
    @Test void emptyListBlocksWrite() {
        when(library.selectCmsTemplateLibraryList(any())).thenReturn(Collections.emptyList());
        rejected(enable()); verifyNoInteractions(security);
    }
    @Test void readExceptionBlocksWriteAndDoesNotExposeException() {
        when(library.selectCmsTemplateLibraryList(any())).thenThrow(new IllegalStateException("private-database-detail"));
        AjaxResult result = enable(); rejected(result);
        assertEquals("上架复检暂不可用，请刷新后重试", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(security);
    }
    @Test void retryAfterReadFailureUsesFreshProjection() {
        when(library.selectCmsTemplateLibraryList(any())).thenThrow(new IllegalStateException("offline"))
                .thenReturn(Collections.singletonList(row(171)));
        rejected(enable()); assertEquals(200, enable().get(AjaxResult.CODE_TAG));
        verify(library, times(2)).selectCmsTemplateLibraryList(any());
        verify(publish, times(1)).updateTemplateLibraryStatus(eq(71L), eq(1), eq(900L), any(TemplateLibraryRepublishReview.class));
    }
    @Test void foreignOwnerBlocksBeforeAnyContentTransmission() {
        CmsTemplateLibrary foreign = row(172); foreign.setMemberId(901L);
        when(library.selectCmsTemplateLibraryList(any())).thenReturn(Arrays.asList(row(171), foreign));
        rejected(enable()); verifyNoInteractions(security);
    }
    @Test void foreignTemplateBlocksBeforeAnyContentTransmission() {
        CmsTemplateLibrary foreign = row(172); foreign.setMemberTemplateId(72L);
        when(library.selectCmsTemplateLibraryList(any())).thenReturn(Arrays.asList(row(171), foreign));
        rejected(enable()); verifyNoInteractions(security);
    }
    @Test void deletedProjectionBlocksWrite() {
        CmsTemplateLibrary deleted = row(171); deleted.setDelFlag(2);
        when(library.selectCmsTemplateLibraryList(any())).thenReturn(Collections.singletonList(deleted));
        rejected(enable()); verifyNoInteractions(security);
    }
    @Test void nullProjectionBlocksWrite() {
        when(library.selectCmsTemplateLibraryList(any())).thenReturn(Arrays.asList(row(171), null));
        rejected(enable()); verifyNoInteractions(security);
    }
    @Test void invalidProjectionIdBlocksWrite() {
        when(library.selectCmsTemplateLibraryList(any())).thenReturn(Collections.singletonList(row(0)));
        rejected(enable()); verifyNoInteractions(security);
    }
    @Test void invalidTemplateIdNeverReadsOrWrites() {
        rejected(controller.updateTemplateLibraryStatus("invalid", "1", null));
        verifyNoInteractions(library, security);
    }
    @Test void moderationFailureBlocksWrite() {
        when(library.selectCmsTemplateLibraryList(any())).thenReturn(Collections.singletonList(row(171)));
        when(security.checkText(anyString(), isNull())).thenThrow(new IllegalStateException("offline"));
        rejected(enable());
    }
    @Test void withdrawalRemainsAvailableWithoutReviewReads() {
        when(publish.updateTemplateLibraryStatus(71L, 0, 900L)).thenReturn(true);
        assertEquals(200, controller.updateTemplateLibraryStatus("71", "0", null).get(AjaxResult.CODE_TAG));
        verifyNoInteractions(library, security);
        verify(publish).updateTemplateLibraryStatus(71L, 0, 900L);
    }
}

package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.support.LegacyCmsTemplateBodyReader;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controller gate integration only. Reader SQL/locking and production MySQL are separate acceptance. */
class ApiTemplateProvenanceGateTest {
    private static final Long VIEWER = 88L;
    private static final Long TEMPLATE = 401L;
    private ApiTemplateController controller;
    private LegacyCmsTemplateBodyReader reader;
    private ICmsMemberTemplateService directService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiTemplateController());
        reader = mock(LegacyCmsTemplateBodyReader.class);
        directService = mock(ICmsMemberTemplateService.class);
        doReturn(VIEWER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "legacyTemplateBodyReader", reader);
        ReflectionTestUtils.setField(controller, "memberTemplateService", directService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
    }

    private CmsMemberTemplate legacy(Long owner, boolean draft) {
        CmsMemberTemplate row = new CmsMemberTemplate();
        row.setId(TEMPLATE);
        row.setMemberId(owner);
        row.setDelFlag(0);
        row.setDraftStatus(draft ? 0 : 1);
        row.setStatus(draft ? 0L : 1L);
        row.setPublishStatus(draft ? 0L : 1L);
        row.setValidationMethod(1);
        row.setQuestionAnswer("private answer");
        row.setCorrectAnswer("A");
        row.setHint1("private hint");
        row.setHint2("second hint");
        row.setAnswerReveal("private reveal");
        row.setMerchantGuide("private merchant guide");
        return row;
    }

    @Test
    void blockedAndMissingAreIndistinguishableAndNeverFallBackToDirectBodyRead() {
        when(reader.findLegacy(TEMPLATE)).thenReturn(null);
        when(reader.findLegacy(402L)).thenReturn(null);
        AjaxResult blocked = controller.myInfo("401");
        AjaxResult missing = controller.myInfo("402");
        assertEquals("模版不存在", blocked.get("msg"));
        assertEquals(missing.get("code"), blocked.get("code"));
        assertEquals(missing.get("msg"), blocked.get("msg"));
        assertNull(blocked.get("data"));
        assertNull(missing.get("data"));
        verify(reader).findLegacy(TEMPLATE);
        verify(reader).findLegacy(402L);
        verifyNoInteractions(directService);
    }

    @Test
    void legacyOwnerDraftStillReturnsEditableSecrets() {
        when(reader.findLegacy(TEMPLATE)).thenReturn(legacy(VIEWER, true));
        AjaxResult result = controller.myInfo("401");
        assertEquals(200, result.get("code"));
        CmsMemberTemplate data = (CmsMemberTemplate) result.get("data");
        assertEquals("private answer", data.getQuestionAnswer());
        assertEquals("private hint", data.getHint1());
        assertEquals("private merchant guide", data.getMerchantGuide());
        verifyNoInteractions(directService);
    }

    @Test
    void legacyPublicNonOwnerStillStripsAllExistingSecretFields() {
        when(reader.findLegacy(TEMPLATE)).thenReturn(legacy(99L, false));
        AjaxResult result = controller.myInfo("401");
        assertEquals(200, result.get("code"));
        CmsMemberTemplate data = (CmsMemberTemplate) result.get("data");
        assertNull(data.getQuestionAnswer());
        assertNull(data.getCorrectAnswer());
        assertNull(data.getHint1());
        assertNull(data.getHint2());
        assertNull(data.getAnswerReveal());
        assertNull(data.getMerchantGuide());
        verifyNoInteractions(directService);
    }

    @Test
    void databaseFailurePropagatesWithoutFallbackBodyOrMissingSuccess() {
        IllegalStateException failure = new IllegalStateException("synthetic database failure");
        when(reader.findLegacy(TEMPLATE)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> controller.myInfo("401")));
        verifyNoInteractions(directService);
    }

    @Test
    void invalidIdRemainsRejectedBeforeReaderIsCalled() {
        assertNull(controller.myInfo("0").get("data"));
        assertNull(controller.myInfo("-1").get("data"));
        assertNull(controller.myInfo("invalid").get("data"));
        verifyNoInteractions(reader, directService);
    }
}

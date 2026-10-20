package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.mapper.LegacyCmsProvenanceMapper;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import com.chengyinhub.business.service.support.LegacyCmsProvenanceGuard;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real service and real guard with mocked collaborators; not database lock/rollback acceptance. */
class TemplateExistingProvenanceWriteTest {
    @Test void nullRequestRetainsExistingValidationErrorWithoutAnyReads() {
        for (boolean draft : new boolean[] {false, true}) {
            Fixture f = new Fixture();
            assertEquals("模板不能为空", assertThrows(IllegalArgumentException.class, () -> f.save(draft, null)).getMessage());
            verifyNoInteractions(f.templates, f.provenance); f.noSideEffects();
        }
    }

    @Test void blockedMissingAndUnknownProvenanceStopBeforeNormalizationOrSideEffects() {
        for (boolean draft : new boolean[] {false, true}) for (Integer state : new Integer[] {1, null, 9}) {
            Fixture f = new Fixture(); when(f.provenance.selectLegacyBlockState(10L)).thenReturn(state);
            CmsTemplatePublishRequest request = f.request(999L);
            request.setAdvancedConfigJson("not valid json");
            assertThrows(ServiceException.class, () -> f.save(draft, request));
            assertEquals(999L, request.getOriginalTemplateId());
            f.noSideEffects();
            InOrder order = inOrder(f.templates, f.provenance);
            order.verify(f.templates).selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(10L));
            order.verify(f.provenance).selectLegacyBlockState(10L);
        }
    }

    @Test void ownerDeletedAndMissingChecksPrecedeProvenanceAndNormalization() {
        for (boolean draft : new boolean[] {false, true}) for (String failure : new String[] {"owner", "deleted", "missing"}) {
            Fixture f = new Fixture();
            if ("owner".equals(failure)) f.existing.setMemberId(8L);
            if ("deleted".equals(failure)) f.existing.setDelFlag(2);
            if ("missing".equals(failure)) f.existing = null;
            CmsTemplatePublishRequest request = f.request(999L); request.setAdvancedConfigJson("bad json");
            assertThrows(RuntimeException.class, () -> f.save(draft, request));
            verifyNoInteractions(f.provenance); f.noSideEffects();
        }
    }

    @Test void databaseErrorsPropagateWithoutWriteOrFallback() {
        for (boolean draft : new boolean[] {false, true}) {
            Fixture f = new Fixture(); RuntimeException failure = new IllegalStateException("database unavailable");
            when(f.provenance.selectLegacyBlockState(10L)).thenThrow(failure);
            assertSame(failure, assertThrows(RuntimeException.class, () -> f.save(draft, f.request(999L))));
            f.noSideEffects();
        }
    }

    @Test void lockedSourceIncludingNullAndZeroCannotBeRewrittenByClient() {
        for (boolean draft : new boolean[] {false, true}) for (Long original : new Long[] {null, 0L, 500L}) {
            for (Long incoming : new Long[] {null, 0L, 999L}) {
                Fixture f = new Fixture(); f.existing.setOriginalTemplateId(original);
                CmsTemplatePublishRequest request = f.request(incoming);
                assertEquals(10L, f.save(draft, request));
                assertEquals(original, request.getOriginalTemplateId());
                assertEquals(original, f.saved.getOriginalTemplateId());
                verify(f.provenance).selectLegacyBlockState(10L);
                verify(f.libraryService, never()).selectCmsTemplateLibraryById(999L);
                verify(f.memberService, never()).insertCmsMemberTemplate(any());
            }
        }
    }

    @Test void secretBackfillAndAdoptedRewardUseLockedSourceBeforeNormalization() throws Exception {
        for (boolean draft : new boolean[] {false, true}) {
            Fixture f = new Fixture(); f.existing.setOriginalTemplateId(500L);
            CmsTemplateLibrary source = new CmsTemplateLibrary();
            source.setId(500L); source.setMemberTemplateId(600L); source.setDelFlag(0);
            source.setStatus(1L); source.setPublishStatus(1L);
            String stripped = "{\"schemaVersion\":1,\"blindTaste\":{\"enabled\":true,\"title\":\"taste\",\"xp\":15,"
                    + "\"options\":[{\"key\":\"A\",\"label\":\"one\"},{\"key\":\"B\",\"label\":\"two\"}]}}";
            source.setAdvancedConfigJson(stripped.replace("\"xp\":15", "\"xp\":15,\"answerKey\":\"B\""));
            when(f.libraryService.selectCmsTemplateLibraryById(500L)).thenReturn(source);
            CmsMemberTemplate canonical = new CmsMemberTemplate();
            canonical.setId(600L); canonical.setDelFlag(0); canonical.setStatus(1L); canonical.setPublishStatus(1L);
            canonical.setMedalName("original reward"); canonical.setMedalStyle("enamel");
            when(f.memberService.selectCmsMemberTemplateById(600L)).thenReturn(canonical);
            CmsTemplatePublishRequest request = f.request(999L); request.setAdvancedConfigJson(stripped);
            request.setMedalName("untrusted reward");
            f.save(draft, request);
            assertEquals("B", new ObjectMapper().readTree(f.saved.getAdvancedConfigJson()).path("blindTaste").path("answerKey").asText());
            assertEquals("original reward", f.saved.getMedalName());
            verify(f.libraryService, never()).selectCmsTemplateLibraryById(999L);
            InOrder order = inOrder(f.templates, f.provenance, f.libraryService);
            order.verify(f.templates).selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(10L));
            order.verify(f.provenance).selectLegacyBlockState(10L);
            order.verify(f.libraryService, atLeastOnce()).selectCmsTemplateLibraryById(500L);
        }
    }

    @Test void newRowsRetainExistingCreationSourcePolicyWithoutExistingGuard() {
        for (boolean draft : new boolean[] {false, true}) for (Long source : new Long[] {null, 0L, 500L}) {
            Fixture f = new Fixture(); CmsTemplatePublishRequest request = f.request(source); request.setId(null);
            f.save(draft, request);
            assertEquals(source == null ? Long.valueOf(0) : source, f.saved.getOriginalTemplateId());
            verifyNoInteractions(f.provenance);
            verify(f.memberService).insertCmsMemberTemplate(any());
        }
    }

    private static final class Fixture {
        final TemplatePublishServiceImpl service = new TemplatePublishServiceImpl();
        final CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
        final LegacyCmsProvenanceMapper provenance = mock(LegacyCmsProvenanceMapper.class);
        final CmsTemplateLibraryMapper projections = mock(CmsTemplateLibraryMapper.class);
        final AuditTaskMapper auditMapper = mock(AuditTaskMapper.class);
        final ICmsMemberTemplateService memberService = mock(ICmsMemberTemplateService.class);
        final ICmsTemplateLibraryService libraryService = mock(ICmsTemplateLibraryService.class);
        final IAuditTaskService auditService = mock(IAuditTaskService.class);
        final ICapabilityGuard capabilities = mock(ICapabilityGuard.class);
        CmsMemberTemplate existing = new CmsMemberTemplate();
        CmsMemberTemplate saved;
        Fixture() {
            existing.setId(10L); existing.setMemberId(7L); existing.setDelFlag(0);
            ReflectionTestUtils.setField(service, "memberTemplateMapper", templates);
            ReflectionTestUtils.setField(service, "legacyCmsProvenanceGuard", new LegacyCmsProvenanceGuard(provenance));
            ReflectionTestUtils.setField(service, "libraryMapper", projections);
            ReflectionTestUtils.setField(service, "auditTaskMapper", auditMapper);
            ReflectionTestUtils.setField(service, "memberTemplateService", memberService);
            ReflectionTestUtils.setField(service, "templateLibraryService", libraryService);
            ReflectionTestUtils.setField(service, "auditTaskService", auditService);
            ReflectionTestUtils.setField(service, "capabilityGuard", capabilities);
            when(provenance.selectLegacyBlockState(10L)).thenReturn(0);
            when(templates.selectCmsMemberTemplateByIdsForUpdate(any())).thenAnswer(call -> {
                CmsMemberTemplate row = saved == null ? existing : saved;
                return row == null ? Collections.emptyList() : Collections.singletonList(row);
            });
            when(projections.selectByMemberTemplateForUpdate(any())).thenReturn(Collections.emptyList());
            when(memberService.updateCmsMemberTemplate(any())).thenAnswer(call -> { saved = call.getArgument(0); return 1; });
            when(memberService.insertCmsMemberTemplate(any())).thenAnswer(call -> {
                saved = call.getArgument(0); saved.setId(10L); return 1;
            });
        }
        CmsTemplatePublishRequest request(Long source) {
            CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
            request.setId(10L); request.setTitle("template"); request.setOriginalTemplateId(source); return request;
        }
        Long save(boolean draft, CmsTemplatePublishRequest request) {
            return draft ? service.saveOrUpdateDraft(request, 7L, "owner") : service.publishTemplateWithTransaction(request, 7L, "owner");
        }
        void noSideEffects() {
            verifyNoInteractions(memberService, libraryService, projections, auditMapper, auditService, capabilities);
        }
    }
}

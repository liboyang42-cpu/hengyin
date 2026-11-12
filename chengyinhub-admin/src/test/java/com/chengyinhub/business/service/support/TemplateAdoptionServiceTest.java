package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.dto.TemplateAdoptionCommand;
import com.chengyinhub.business.mapper.TemplateAdoptionReceiptMapper;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TemplateAdoptionServiceTest {
    @Test void canonicalLongAndSourceFingerprintCoverAuthorContentNotCounters() {
        var service = new TemplateAdoptionService(mock(TemplateAdoptionReceiptMapper.class),mock(ITemplatePublishService.class),mock(PlatformTransactionManager.class));
        assertEquals(Long.MAX_VALUE,TemplateAdoptionService.id("9223372036854775807"));
        for(String bad:List.of("0","01","1.0","9223372036854775808","-1")) assertThrows(ServiceException.class,()->TemplateAdoptionService.id(bad));
        CmsTemplateLibrary source=new CmsTemplateLibrary();source.setId(7L);source.setTitle("title");source.setQuestionAnswer("a");
        String first=service.sourceVersion(source);source.setQuestionAnswer("b");assertNotEquals(first,service.sourceVersion(source));
        source.setQuestionAnswer("a");source.setStatus(3L);assertEquals(first,service.sourceVersion(source));
        assertEquals("9007199254740993",TemplateAdoptionWire.strings(Map.of("templateId",9007199254740993L),"templateId").path("templateId").asText());
    }
    @Test void inheritedHashIgnoresObjectOrderButNotMechanicChangesAndStillValidatesSchema() {
        String timer = "{\"schemaVersion\":1,\"timer\":{\"enabled\":true,"
                + "\"durationSeconds\":60,\"timeoutResult\":\"EXPIRED\"}}";
        String reordered = "{\"timer\":{\"timeoutResult\":\"EXPIRED\",\"durationSeconds\":60,"
                + "\"enabled\":true}, \"schemaVersion\":1}";
        assertEquals(TemplateAdoptionService.inheritedAdvancedConfigHash(timer),
                TemplateAdoptionService.inheritedAdvancedConfigHash(reordered));
        assertNotEquals(TemplateAdoptionService.inheritedAdvancedConfigHash(timer),
                TemplateAdoptionService.inheritedAdvancedConfigHash(timer.replace(":60", ":90")));
        assertThrows(IllegalArgumentException.class,
                () -> TemplateAdoptionService.inheritedAdvancedConfigHash("{\"schemaVersion\":999}"));
        assertEquals(TemplateAdoptionService.inheritedAdvancedConfigHash(null),
                TemplateAdoptionService.inheritedAdvancedConfigHash(""));
    }

    @Test void completedInheritanceQueryPreservesOwnerDraftSourceAndConfigFingerprint() {
        var mapper = mock(TemplateAdoptionReceiptMapper.class);
        var service = new TemplateAdoptionService(mapper, mock(ITemplatePublishService.class),
                mock(PlatformTransactionManager.class));
        Long draftId = 9007199254740993L;
        String hash = "a".repeat(64);
        when(mapper.countCompletedInheritedConfig(7L, draftId, 9L, hash)).thenReturn(2);

        assertTrue(service.hasCompletedInheritedConfig(7L, draftId, 9L, hash));
        assertFalse(service.hasCompletedInheritedConfig(8L, draftId, 9L, hash));
        assertFalse(service.hasCompletedInheritedConfig(7L, draftId + 1, 9L, hash));
        assertFalse(service.hasCompletedInheritedConfig(7L, draftId, 10L, hash));
        assertFalse(service.hasCompletedInheritedConfig(7L, draftId, 9L, "b".repeat(64)));
        verify(mapper).countCompletedInheritedConfig(7L, draftId, 9L, hash);
        verify(mapper).countCompletedInheritedConfig(8L, draftId, 9L, hash);
        verify(mapper).countCompletedInheritedConfig(7L, draftId + 1, 9L, hash);
        verify(mapper).countCompletedInheritedConfig(7L, draftId, 10L, hash);
        verify(mapper).countCompletedInheritedConfig(7L, draftId, 9L, "b".repeat(64));
        verifyNoMoreInteractions(mapper);
    }

    @Test void completedInheritanceQueryPropagatesStorageFailure() {
        var mapper = mock(TemplateAdoptionReceiptMapper.class);
        var service = new TemplateAdoptionService(mapper, mock(ITemplatePublishService.class),
                mock(PlatformTransactionManager.class));
        var failure = new org.springframework.dao.DataAccessResourceFailureException("controlled receipt read failure");
        when(mapper.countCompletedInheritedConfig(7L, 91L, 9L, "a".repeat(64))).thenThrow(failure);

        assertSame(failure, assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> service.hasCompletedInheritedConfig(7L, 91L, 9L, "a".repeat(64))));
        verify(mapper).countCompletedInheritedConfig(7L, 91L, 9L, "a".repeat(64));
        verifyNoMoreInteractions(mapper);
    }

    @Test void sourceCopyCompletesExactReceiptAndReplayNeverCreatesAnotherDraft() {
        var mapper = mock(TemplateAdoptionReceiptMapper.class);
        var drafts = mock(ITemplatePublishService.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        var row = new HashMap<String, Object>();
        when(mapper.reserve(anyMap())).thenAnswer(i -> {
            if (row.isEmpty()) { row.putAll(i.getArgument(0)); row.put("status", "PENDING"); }
            return 1;
        });
        when(mapper.lock(eq(1L), anyString())).thenAnswer(i -> new HashMap<>(row));
        when(mapper.read(eq(1L), anyString())).thenAnswer(i -> new HashMap<>(row));
        when(mapper.finish(eq(1L), anyString(), anyString(), any(), any(), any(), any())).thenAnswer(i -> {
            row.put("status", i.getArgument(2)); row.put("draftId", i.getArgument(3));
            row.put("reason", i.getArgument(4)); row.put("draftVersion", i.getArgument(5));
            row.put("inheritedConfigHash", i.getArgument(6));
            return 1;
        });
        CmsTemplateLibrary source = new CmsTemplateLibrary();
        source.setId(7L); source.setTitle("公开玩法"); source.setDelFlag(0);
        source.setStatus(1L); source.setMemberTemplateId(17L);
        var canonical = new com.chengyinhub.business.domain.CmsMemberTemplate();
        canonical.setId(17L); canonical.setMemberId(2L); canonical.setDelFlag(0); canonical.setStatus(1L);
        when(mapper.sourceCanonicalId(7L)).thenReturn(17L);
        when(mapper.lockDraft(17L)).thenReturn(canonical);
        when(mapper.lockSource(7L)).thenReturn(source);
        var created = new com.chengyinhub.business.domain.CmsMemberTemplate();
        created.setId(9007199254740993L); created.setMemberId(2L); created.setOriginalTemplateId(7L);
        when(mapper.lockDraft(created.getId())).thenReturn(created);
        when(drafts.saveOrUpdateDraft(any(), eq(2L), eq("actor"))).thenAnswer(i -> {
            com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest request = i.getArgument(0);
            assertEquals(7L, request.getOriginalTemplateId());
            assertEquals(Boolean.TRUE, request.getAdoptSourceOnly());
            return created.getId();
        });
        var service = new TemplateAdoptionService(mapper, drafts, manager);
        String version = (String) service.source(1L, 2L, "7").get("sourceVersion");
        var command = new TemplateAdoptionCommand();
        command.setRequestId("source_copy_request"); command.setOriginalTemplateId("7");
        command.setSourceVersion(version);

        var completed = service.adopt(1L, 2L, command, "actor");
        assertEquals("COMPLETED", completed.get("status"));
        assertEquals("9007199254740993", completed.get("draftId"));
        assertEquals(version, completed.get("sourceVersion"));
        assertTrue(service.matchesDraft(completed, created));
        assertEquals(TemplateAdoptionService.inheritedAdvancedConfigHash(created.getAdvancedConfigJson()),
                row.get("inheritedConfigHash"));
        assertFalse(completed.containsKey("inheritedConfigHash"), "私有配置指纹不进入恢复回执");
        source.setStatus(3L);
        verify(mapper, times(1)).finish(eq(1L), eq(command.getRequestId()), eq("COMPLETED"),
                eq(created.getId()), org.mockito.ArgumentMatchers.isNull(), anyString(),
                eq(TemplateAdoptionService.inheritedAdvancedConfigHash(created.getAdvancedConfigJson())));
        clearInvocations(mapper);
        assertEquals(completed, service.receipt(1L, 2L, "", command.getRequestId()));
        assertEquals(completed, service.adopt(1L, 2L, command, "actor"));
        verify(drafts, times(1)).saveOrUpdateDraft(any(), eq(2L), eq("actor"));
        verify(mapper, never()).finish(any(), anyString(), anyString(), any(), any(), any(), any());
        verify(mapper, never()).lockSource(any());
        assertThrows(ServiceException.class, () -> service.receipt(1L, 3L, "", command.getRequestId()));
    }

    @Test void completedReplayUsesOriginalActorOwnerFingerprintAndNeverRequiresSource() {
        var mapper=mock(TemplateAdoptionReceiptMapper.class);var drafts=mock(ITemplatePublishService.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());
        var row=new HashMap<String,Object>();
        when(mapper.reserve(anyMap())).thenAnswer(i->{if(row.isEmpty()){row.putAll(i.getArgument(0));row.put("status","COMPLETED");row.put("draftId",9007199254740993L);}return 1;});
        when(mapper.lock(eq(1L),anyString())).thenAnswer(i->new HashMap<>(row));
        var service=new TemplateAdoptionService(mapper,drafts,manager);
        var command=new TemplateAdoptionCommand();command.setRequestId("adopt_original_request");command.setOriginalTemplateId("7");command.setScope("");command.setSourceVersion("a".repeat(64));
        assertEquals("9007199254740993",service.adopt(1L,2L,command,"actor").get("draftId"));
        assertEquals("9007199254740993",service.adopt(1L,2L,command,"actor").get("draftId"));
        command.setSourceVersion("b".repeat(64));assertThrows(ServiceException.class,()->service.adopt(1L,2L,command,"actor"));
        command.setSourceVersion("a".repeat(64));assertThrows(ServiceException.class,()->service.adopt(1L,3L,command,"actor"));
        verify(mapper,never()).lockSource(any());verifyNoInteractions(drafts);
    }
}

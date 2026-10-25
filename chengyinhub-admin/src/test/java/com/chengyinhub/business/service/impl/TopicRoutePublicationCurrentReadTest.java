package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Mapper-port unit tests only. The transaction flag selects a branch; it proves no database lock.
 * Native lock/read-view cases live in TopicPublicationBindingMySqlIT. */
class TopicRoutePublicationCurrentReadTest {
    @Test
    void writablePublicationReusesOneCurrentProjectionForRulesNarrativeAndAnswers() throws Exception {
        Fixture f = new Fixture();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            String frozen = f.compiler.freezePublication(f.topic, null);
            assertEquals("A", new ObjectMapper().readTree(frozen).path("nodeAnswers").path("11").path("questionAnswer").asText());
            var locks = inOrder(f.topics, f.chapters, f.nodes, f.templates);
            locks.verify(f.topics).selectCmsTopicByIdForUpdate(10L);
            locks.verify(f.chapters).selectByTopicForUpdate(10L);
            locks.verify(f.nodes).selectByTopicForUpdate(10L);
            locks.verify(f.templates).selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(701L));
            verify(f.chapters, times(1)).selectByTopicForUpdate(10L);
            verify(f.nodes, times(1)).selectByTopicForUpdate(10L);
            verify(f.templates, times(1)).selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(701L));
            verify(f.chapters, never()).selectCmsTopicChapterList(any());
            verify(f.nodes, never()).selectCmsTopicNodeList(any());
            verify(f.templates, never()).selectCmsMemberTemplateByIds(any());
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void previewRetainsItsNonlockingReadContract() {
        Fixture f = new Fixture();
        assertDoesNotThrow(() -> f.compiler.freezePublication(f.topic, null));
        verify(f.topics, never()).selectCmsTopicByIdForUpdate(any());
        verify(f.chapters, never()).selectByTopicForUpdate(any());
        verify(f.nodes, never()).selectByTopicForUpdate(any());
        verify(f.templates, never()).selectCmsMemberTemplateByIdsForUpdate(any());
        verify(f.nodes, atLeastOnce()).selectCmsTopicNodeList(any());
        verify(f.templates, atLeastOnce()).selectCmsMemberTemplateByIds(any());
    }

    @Test
    void aFailedCompileDoesNotRetainThePreviousProjectionForTheNextOperation() throws Exception {
        Fixture f = new Fixture();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            f.topic.setThemeRuntimeConfigJson("{");
            assertThrows(IllegalArgumentException.class, () -> f.compiler.freezePublication(f.topic, null));
            f.topic.setThemeRuntimeConfigJson(null);
            CmsMemberTemplate next = new CmsMemberTemplate();
            next.setId(701L); next.setValidationMethod(1); next.setQuestionAnswer("C");
            when(f.templates.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(701L)))
                    .thenReturn(Collections.singletonList(next));
            String frozen = f.compiler.freezePublication(f.topic, null);
            assertEquals("C", new ObjectMapper().readTree(frozen).path("nodeAnswers").path("11").path("questionAnswer").asText());
            verify(f.templates, times(2)).selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(701L));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void writablePublicationUsesTheCurrentParentInsteadOfTheCallersOldReadView() throws Exception {
        Fixture f = new Fixture();
        CmsTopic stale = new CmsTopic();
        stale.setId(10L); stale.setRouteMode("BRANCH_GRAPH"); stale.setThemeRuntimeConfigJson("{");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            String frozen = f.compiler.freezePublication(stale, null);
            assertEquals("LINEAR", new ObjectMapper().readTree(frozen).path("routeMode").asText());
            assertEquals("A", new ObjectMapper().readTree(frozen).path("nodeAnswers").path("11").path("questionAnswer").asText());
            assertEquals("{", stale.getThemeRuntimeConfigJson(), "do not mutate the caller's old view");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static final class Fixture {
        final CmsTopicMapper topics = mock(CmsTopicMapper.class);
        final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
        final CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
        final CmsTopic topic = new CmsTopic();
        final TopicRoutePublicationCompiler compiler;
        Fixture() {
            topic.setId(10L); topic.setRouteMode("LINEAR");
            CmsTopicNode node = new CmsTopicNode();
            node.setId(11L); node.setTopicId(10L); node.setTemplateId(701L); node.setStatus(1L);
            CmsMemberTemplate current = new CmsMemberTemplate();
            current.setId(701L); current.setValidationMethod(1); current.setQuestionAnswer("A");
            CmsMemberTemplate snapshot = new CmsMemberTemplate();
            snapshot.setId(701L); snapshot.setValidationMethod(1); snapshot.setQuestionAnswer("B");
            when(topics.selectCmsTopicByIdForUpdate(10L)).thenReturn(topic);
            when(chapters.selectByTopicForUpdate(10L)).thenReturn(Collections.emptyList());
            when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
            when(nodes.selectByTopicForUpdate(10L)).thenReturn(Collections.singletonList(node));
            when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
            when(templates.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(701L)))
                    .thenReturn(Collections.singletonList(current));
            when(templates.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(snapshot));
            compiler = new TopicRoutePublicationCompiler(nodes, templates, chapters, null, Clock.systemUTC(),
                    new ObjectMapper(), new TopicRouteConfigValidator(), topics);
        }
    }
}

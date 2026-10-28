package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Source-port tests; transaction flags and mapper doubles are not MySQL lock/read-view evidence. */
class TopicStateKeyRefCurrentContentTest {
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
    private final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
    private final CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
    private TopicStateKeyRefService service;
    private CmsTopic topic; private CmsTopicNode node; private CmsTopicChapter chapter; private CmsMemberTemplate current;
    private boolean oldActive, oldReadOnly;

    @BeforeEach void prepare() {
        oldActive = TransactionSynchronizationManager.isActualTransactionActive();
        oldReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        topic = new CmsTopic(); topic.setId(10L);
        node = new CmsTopicNode(); node.setId(11L); node.setTopicId(10L); node.setStatus(1L); node.setTemplateId(101L); node.setName("原地点");
        chapter = new CmsTopicChapter(); chapter.setId(20L); chapter.setTopicId(10L);
        current = new CmsMemberTemplate(); current.setId(101L); current.setAdvancedConfigJson(block("clue.current"));
        CmsMemberTemplate old = new CmsMemberTemplate(); old.setId(101L); old.setAdvancedConfigJson(block("clue.old"));
        when(topics.selectCmsTopicByIdForUpdate(10L)).thenReturn(topic);
        when(nodes.selectByTopicForUpdate(10L)).thenReturn(Collections.singletonList(node));
        when(chapters.selectByTopicForUpdate(10L)).thenReturn(Collections.singletonList(chapter));
        when(templates.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(101L))).thenReturn(Collections.singletonList(current));
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
        when(templates.selectCmsMemberTemplateById(101L)).thenReturn(old);
        service = new TopicStateKeyRefService(nodes, templates, chapters);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
    }
    @AfterEach void restore() {
        TransactionSynchronizationManager.setActualTransactionActive(oldActive);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(oldReadOnly);
    }

    @Test void formalCollectionUsesCurrentChildrenAndNeverOrdinaryRrInputs() {
        var refs = service.collectForPublication(topic);
        assertEquals(Collections.singleton("clue.current"), refs.getRefsByNode().get(11L));
        assertTrue(refs.getParseErrors().isEmpty());
        var locks = inOrder(topics, chapters, nodes, templates);
        locks.verify(topics).selectCmsTopicByIdForUpdate(10L);
        locks.verify(chapters).selectByTopicForUpdate(10L);
        locks.verify(nodes).selectByTopicForUpdate(10L);
        locks.verify(templates).selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(101L));
        verify(nodes, never()).selectCmsTopicNodeList(any());
        verify(chapters, never()).selectCmsTopicChapterList(any());
        verify(templates, never()).selectCmsMemberTemplateById(any());
    }

    @Test void aCapturedCurrentInputFeedsTheOriginalParserWithoutAnyMoreSql() {
        var content = service.currentForPublication(topic);
        clearInvocations(topics, chapters, nodes, templates);
        assertEquals(Collections.singleton("clue.current"), service.collectCurrent(content).getRefsByNode().get(11L));
        verifyNoInteractions(topics, chapters, nodes, templates);
        assertThrows(UnsupportedOperationException.class, () -> content.nodes().clear());
    }

    @Test void previewRetainsItsOriginalOrdinaryPathAndResult() {
        assertEquals(Collections.singleton("clue.old"), service.collect(topic).getRefsByNode().get(11L));
        verify(topics, never()).selectCmsTopicByIdForUpdate(any());
        verify(nodes, never()).selectByTopicForUpdate(any());
        verify(chapters, never()).selectByTopicForUpdate(any());
        verify(templates, never()).selectCmsMemberTemplateByIdsForUpdate(any());
    }

    @Test void currentParsingPreservesBlockAndChapterErrorKindsAndDisabledNodeSemantics() {
        current.setAdvancedConfigJson("{"); chapter.setBlocksJson("{");
        var refs = service.collectForPublication(topic);
        assertEquals(2, refs.getParseErrors().size());
        assertEquals(TopicStateKeyRefService.KIND_BLOCK, refs.getParseErrors().get(0).getKind());
        assertEquals(TopicStateKeyRefService.KIND_CHAPTER_BLOCKS, refs.getParseErrors().get(1).getKind());
        node.setStatus(0L); chapter.setBlocksJson(null);
        assertTrue(service.collectForPublication(topic).getParseErrors().isEmpty());
        assertTrue(service.collectForPublication(topic).getRefsByNode().isEmpty());
    }

    @Test void missingTransactionParentCrossTopicOrMissingTemplateNeverFallsBackToPreview() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(IllegalStateException.class, () -> service.collectForPublication(topic));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(IllegalStateException.class, () -> service.collectForPublication(topic));
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        node.setTopicId(99L);
        assertThrows(IllegalStateException.class, () -> service.collectForPublication(topic));
        node.setTopicId(10L);
        when(topics.selectCmsTopicByIdForUpdate(10L)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.collectForPublication(topic));
        when(topics.selectCmsTopicByIdForUpdate(10L)).thenReturn(topic);
        when(templates.selectCmsMemberTemplateByIdsForUpdate(any())).thenReturn(Collections.emptyList());
        assertThrows(IllegalStateException.class, () -> service.collectForPublication(topic));
        verify(nodes, never()).selectCmsTopicNodeList(any());
    }

    private static String block(String key) {
        return "{\"requires\":[{\"var\":\"" + key + "\",\"op\":\"EQ\",\"value\":1}]}";
    }
}

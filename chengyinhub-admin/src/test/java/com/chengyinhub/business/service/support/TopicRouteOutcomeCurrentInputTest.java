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

/** Parser/input-port tests. Requires the narrow shared outcome adapter; no native RR claim. */
class TopicRouteOutcomeCurrentInputTest {
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
    private final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
    private final CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
    private TopicStateKeyRefService collector; private TopicRouteOutcomeReader reader;
    private CmsTopic topic; private CmsMemberTemplate current;
    private boolean active, readOnly;

    @BeforeEach void fixture() {
        active = TransactionSynchronizationManager.isActualTransactionActive();
        readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        topic = new CmsTopic(); topic.setId(10L);
        CmsTopicNode node = new CmsTopicNode(); node.setId(11L); node.setTopicId(10L); node.setStatus(1L); node.setTemplateId(101L);
        current = template("CURRENT");
        when(topics.selectCmsTopicByIdForUpdate(10L)).thenReturn(topic);
        when(nodes.selectByTopicForUpdate(10L)).thenReturn(Collections.singletonList(node));
        when(chapters.selectByTopicForUpdate(10L)).thenReturn(Collections.emptyList());
        when(templates.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(101L))).thenReturn(Collections.singletonList(current));
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        when(templates.selectCmsMemberTemplateById(101L)).thenReturn(template("OLD"));
        collector = new TopicStateKeyRefService(nodes, templates, chapters);
        ReflectionTestUtils.setField(collector, "cmsTopicMapper", topics);
        reader = new TopicRouteOutcomeReader(nodes, templates);
    }
    @AfterEach void restore() {
        TransactionSynchronizationManager.setActualTransactionActive(active);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
    }

    @Test void formalOutcomesConsumeCapturedCurrentTemplatesWithoutMoreSql() {
        var content = collector.currentForPublication(topic);
        clearInvocations(topics, nodes, chapters, templates);
        Map<Long, Set<String>> outcomes = reader.declaredRouteOutcomes(content);
        assertEquals(Collections.singleton("ADVANCED_RESULT:CURRENT"), outcomes.get(11L));
        verifyNoInteractions(topics, nodes, chapters, templates);
        assertThrows(UnsupportedOperationException.class, () -> outcomes.get(11L).clear());
    }

    @Test void previewKeepsTheOriginalOrdinaryOutcomeInputs() {
        assertEquals(Collections.singleton("ADVANCED_RESULT:OLD"), reader.declaredRouteOutcomes(10L).get(11L));
        verify(topics, never()).selectCmsTopicByIdForUpdate(any());
        verify(nodes, never()).selectByTopicForUpdate(any());
        verify(templates, never()).selectCmsMemberTemplateByIdsForUpdate(any());
    }

    @Test void currentMalformedOutcomeKeepsTheOriginalExceptionAndCause() {
        current.setAdvancedConfigJson("{");
        var content = collector.currentForPublication(topic);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> reader.declaredRouteOutcomes(content));
        assertEquals("高级玩法 outcomeCode 声明无效", error.getMessage());
        assertNotNull(error.getCause());
    }

    private CmsMemberTemplate template(String outcome) {
        CmsMemberTemplate template = new CmsMemberTemplate(); template.setId(101L);
        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"branch\":{\"enabled\":true,\"startStepId\":\"end\",\"steps\":["
                + "{\"id\":\"end\",\"terminal\":true,\"outcomeCode\":\"" + outcome + "\"}]}}");
        return template;
    }
}

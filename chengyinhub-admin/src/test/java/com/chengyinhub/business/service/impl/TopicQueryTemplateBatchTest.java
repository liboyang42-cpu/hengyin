package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IMerchantChapterApplicationService;
import com.chengyinhub.business.service.support.TopicSizeLimits;
import com.chengyinhub.system.service.ISysCategoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Query-call counts and actual production XML contract; no real MySQL/runtime performance claim. */
class TopicQueryTemplateBatchTest {
    private final TopicQueryService service = new TopicQueryService();
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
    private final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
    private final CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
    private final CmsTopicTemplateMetaMapper metadata = mock(CmsTopicTemplateMetaMapper.class);
    private final ISysCategoryService categories = mock(ISysCategoryService.class);
    private final IMerchantChapterApplicationService applications = mock(IMerchantChapterApplicationService.class);

    TopicQueryTemplateBatchTest() {
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapters);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodes);
        ReflectionTestUtils.setField(service, "cmsMemberTemplateMapper", templates);
        ReflectionTestUtils.setField(service, "cmsTopicTemplateMetaMapper", metadata);
        ReflectionTestUtils.setField(service, "sysCategoryService", categories);
        ReflectionTestUtils.setField(service, "merchantChapterApplicationService", applications);
        CmsTopic topic = new CmsTopic(); topic.setId(42L); topic.setProductType(1);
        when(topics.selectPublicTopicTemplateById(42L)).thenReturn(topic);
    }

    @Test void unorderedBatchRowsPreserveOriginalNodeOrderMissingRowsAndFirstEncounterGameOrder() throws Exception {
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(Arrays.asList(chapter(11L), chapter(12L), chapter(13L)));
        List<CmsTopicNode> first = Arrays.asList(node("one", 201L), node("two", 202L), node("repeat", 201L),
                node("none", null), node("zero", 0L), node("negative", -1L), node("missing", 203L));
        List<CmsTopicNode> second = Arrays.asList(node("cross-repeat", 202L), node("three", 204L));
        when(nodes.selectCmsTopicNodeList(any())).thenAnswer(i -> switch (((CmsTopicNode) i.getArgument(0)).getChapterId().intValue()) {
            case 11 -> first; case 12 -> second; default -> Collections.emptyList();
        });
        when(templates.selectCmsMemberTemplateByIds(Arrays.asList(201L, 202L, 203L)))
                .thenReturn(Arrays.asList(template(202L), template(201L))); // Deliberately reverse SQL result order.
        when(templates.selectCmsMemberTemplateByIds(Arrays.asList(202L, 204L)))
                .thenReturn(Arrays.asList(template(204L), template(202L)));

        var detail = service.buildPublicTopicTemplateDetail(42L, null, false, null);
        assertEquals(Arrays.asList("one", "two", "repeat", "none", "zero", "negative", "missing"),
                detail.getChapters().get(0).getNodes().stream().map(v -> v.getName()).toList());
        assertEquals(Arrays.asList("type-201", "type-202", "type-201", null, null, null, null),
                detail.getChapters().get(0).getNodes().stream().map(v -> v.getInteractionType()).toList());
        assertEquals(Arrays.asList(201L, 202L, 204L), detail.getGames().stream().map(v -> v.getId()).toList());
        assertEquals(0, detail.getChapters().get(2).getNodeCount());
        // Literal shadow of the former singleton projection's seven allowed game fields, not batch SQL row order.
        String expected = "[{\"id\":201,\"title\":\"game-201\",\"imgUrl\":\"image-201\",\"players\":\"2\",\"duration\":5,\"difficulty\":\"easy\",\"interactionType\":\"type-201\"},"
                + "{\"id\":202,\"title\":\"game-202\",\"imgUrl\":\"image-202\",\"players\":\"2\",\"duration\":5,\"difficulty\":\"easy\",\"interactionType\":\"type-202\"},"
                + "{\"id\":204,\"title\":\"game-204\",\"imgUrl\":\"image-204\",\"players\":\"2\",\"duration\":5,\"difficulty\":\"easy\",\"interactionType\":\"type-204\"}]";
        ObjectMapper json = new ObjectMapper();
        assertEquals(json.readTree(expected), json.readTree(json.writeValueAsString(detail.getGames())));
        assertFalse(json.writeValueAsString(detail).contains("private-answer"));
        assertEquals(6, java.util.stream.Stream.concat(first.stream(), second.stream())
                .filter(n -> n.getTemplateId() != null && n.getTemplateId() > 0).count(), "former singleton SQL count");
        verify(templates, times(1)).selectCmsMemberTemplateByIds(Arrays.asList(201L, 202L, 203L));
        verify(templates, times(1)).selectCmsMemberTemplateByIds(Arrays.asList(202L, 204L));
        verifyNoMoreInteractions(templates); verifyNoInteractions(categories, applications);
        verify(topics).selectPublicTopicTemplateById(42L);
        verify(topics).selectPublicNodeCountsByTopicIds(Collections.singletonList(42L));
        verify(chapters).selectCmsTopicChapterList(any()); verify(nodes, times(3)).selectCmsTopicNodeList(any());
        verify(metadata).selectByTopicId(42L); verify(metadata).selectChapterMetaByTopicId(42L);
        verifyNoMoreInteractions(topics, chapters, nodes, metadata); // This fixture: 14 former SQL calls -> 10 planned calls.
        assertEquals(Arrays.asList("one", "two", "repeat", "none", "zero", "negative", "missing"),
                first.stream().map(CmsTopicNode::getName).toList(), "input rows must not be sorted or rewritten");
    }

    @Test void batchSizeUsesExistingNodeLimitWithoutTruncatingOversizedLegacyRows() {
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter(11L)));
        for (int count : new int[]{TopicSizeLimits.MAX_NODES, TopicSizeLimits.MAX_NODES * 2 + 1}) {
            reset(templates);
            List<CmsTopicNode> chapterNodes = new ArrayList<>(); List<Long> ids = new ArrayList<>();
            for (long id = 1; id <= count; id++) { ids.add(id); chapterNodes.add(node("node-" + id, id)); }
            when(nodes.selectCmsTopicNodeList(any())).thenReturn(chapterNodes);
            when(templates.selectCmsMemberTemplateByIds(any())).thenAnswer(i -> {
                List<Long> batch = i.getArgument(0);
                assertFalse(batch.isEmpty()); assertTrue(batch.size() <= TopicSizeLimits.MAX_NODES);
                return batch.stream().map(TopicQueryTemplateBatchTest::template).toList();
            });
            var detail = service.buildPublicTopicTemplateDetail(42L, null, false, null);
            assertEquals(count, detail.getGames().size()); assertEquals(count, detail.getChapters().get(0).getNodeCount());
            assertEquals(ids, detail.getGames().stream().map(v -> v.getId()).toList());
            for (int offset = 0; offset < count; offset += TopicSizeLimits.MAX_NODES)
                verify(templates).selectCmsMemberTemplateByIds(ids.subList(offset, Math.min(offset + TopicSizeLimits.MAX_NODES, count)));
            verifyNoMoreInteractions(templates);
        }
    }

    @Test void emptyNullOrNonpositiveTemplateSetsNeverExecuteAnEmptyInQuery() {
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(Arrays.asList(chapter(11L), chapter(12L)));
        when(nodes.selectCmsTopicNodeList(any())).thenAnswer(i ->
                ((CmsTopicNode) i.getArgument(0)).getChapterId() == 11L
                        ? Arrays.asList(node("none", null), node("zero", 0L), node("negative", -1L)) : null);
        var detail = service.buildPublicTopicTemplateDetail(42L, null, false, null);
        assertEquals(Arrays.asList(3, 0), detail.getChapters().stream().map(v -> v.getNodeCount()).toList());
        assertTrue(detail.getGames().isEmpty()); verifyNoInteractions(templates);
    }

    @Test void batchStorageErrorPropagatesAndLaterExplicitReadCanRecoverWithoutNegativeCaching() {
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter(11L)));
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node("one", 201L)));
        IllegalStateException failure = new IllegalStateException("controlled query failure");
        when(templates.selectCmsMemberTemplateByIds(Collections.singletonList(201L)))
                .thenThrow(failure).thenReturn(Collections.singletonList(template(201L)));
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.buildPublicTopicTemplateDetail(42L, null, false, null)));
        assertEquals(201L, service.buildPublicTopicTemplateDetail(42L, null, false, null).getGames().get(0).getId());
        verify(templates, times(2)).selectCmsMemberTemplateByIds(Collections.singletonList(201L));
        verifyNoMoreInteractions(templates);
    }

    @Test void actualProductionBatchXmlUsesSameProjectionAndResultMapWithOnlyParameterizedIdPredicateChanged() throws Exception {
        Configuration config = new Configuration();
        config.getTypeAliasRegistry().registerAlias("CmsMemberTemplate", CmsMemberTemplate.class);
        config.getTypeAliasRegistry().registerAlias("MemberTemplateVO", MemberTemplateVO.class);
        String resource = "mapper/business/CmsMemberTemplateMapper.xml";
        try (var input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        String ns = "com.chengyinhub.business.mapper.CmsMemberTemplateMapper.";
        var single = config.getMappedStatement(ns + "selectCmsMemberTemplateById");
        var batch = config.getMappedStatement(ns + "selectCmsMemberTemplateByIds");
        var one = single.getBoundSql(201L);
        var many = batch.getBoundSql(Collections.singletonMap("ids", Arrays.asList(201L, 202L)));
        String a = one.getSql().replaceAll("\\s+", " ").trim();
        String b = many.getSql().replaceAll("\\s+", " ").trim();
        int x = a.lastIndexOf("where id"), y = b.lastIndexOf("where id"); assertTrue(x > 0 && y > 0);
        assertEquals(a.substring(0, x), b.substring(0, y));
        assertEquals("where id = ?", a.substring(x));
        assertTrue(b.substring(y).matches("where id in\\s*\\(\\s*\\?\\s*,\\s*\\?\\s*\\)"));
        assertSame(single.getResultMaps().get(0), batch.getResultMaps().get(0));
        assertEquals(single.isUseCache(), batch.isUseCache()); assertEquals(single.isFlushCacheRequired(), batch.isFlushCacheRequired());
        assertEquals(1, one.getParameterMappings().size()); assertEquals(2, many.getParameterMappings().size());
    }

    private static CmsTopicChapter chapter(Long id) { CmsTopicChapter row = new CmsTopicChapter(); row.setId(id); return row; }
    private static CmsTopicNode node(String name, Long id) { CmsTopicNode row = new CmsTopicNode(); row.setName(name); row.setTemplateId(id); return row; }
    private static CmsMemberTemplate template(Long id) {
        CmsMemberTemplate row = new CmsMemberTemplate(); row.setId(id); row.setTitle("game-" + id); row.setImgUrl("image-" + id);
        row.setPlayers("2"); row.setDuration(5L); row.setDifficulty("easy"); row.setInteractionType("type-" + id);
        row.setQuestionAnswer("private-answer"); return row;
    }
}

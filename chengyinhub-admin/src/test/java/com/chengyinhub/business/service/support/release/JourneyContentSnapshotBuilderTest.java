package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.support.JourneyContentSnapshotBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JourneyContentSnapshotBuilderTest {
    private final JourneyContentSnapshotBuilder builder = new JourneyContentSnapshotBuilder();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void branchGoldenMatchesOriginalBytes() throws Exception { golden("branch", fixture(true, true)); }
    @Test void linearGoldenMatchesOriginalBytes() throws Exception { golden("linear", fixture(false, true)); }
    @Test void openingOnlyGoldenMatchesOriginalBytes() throws Exception {
        Fixture f = fixture(false, true); f.nodes.clear(); f.templates.clear(); f.configs.clear();
        golden("opening-only", f);
    }
    @Test void noTemplateAndLegacyMissingKeysMatchOriginalBytes() throws Exception {
        Fixture f = fixture(false, false); f.nodes.get(0).setTemplateId(null); f.snapshot = "{\"schemaVersion\":1,\"legacyField\":true}";
        golden("legacy-no-template", f);
    }
    @Test void differentialCasesPreserveMalformedAndMissingDataSemantics() {
        for (boolean branch : new boolean[]{false, true}) {
            for (String config : Arrays.asList(null, "", "{", "[]", "null", "{\"check\":{\"enabled\":true}}")) {
                for (String blocks : Arrays.asList(null, "{", "[]", "[{\"type\":\"text\",\"content\":\"old\"}]")) {
                    Fixture f = fixture(branch, false); f.configs.put(101L, config);
                    f.chapters.get(1).setBlocksJson(blocks);
                    assertEquals(old(f), actual(f));
                }
            }
        }
    }
    @Test void missingTemplateRetainsEmptyAnswerObject() throws Exception {
        Fixture f = fixture(true, false); f.templates.clear();
        assertEquals(old(f), actual(f));
        assertEquals(0, JSON.readTree(actual(f)).path("nodeAnswers").path("1").size());
    }
    @Test void emptyChapterAndStateReadsMatchOriginal() {
        Fixture f = fixture(false, false); f.chapters = null; f.keys = null;
        assertEquals(old(f), actual(f));
    }
    @Test void invalidSnapshotFailureMatchesOriginal() {
        for (String snapshot : Arrays.asList(null, "{", "[]", "null", "")) {
            Fixture f = fixture(true, false); f.snapshot = snapshot; f.invalidNull = true;
            assertSameFailure(f);
        }
    }
    @Test void missingOpeningFailsWithSameError() {
        Fixture f = fixture(true, true); f.chapters.clear(); assertSameFailure(f);
    }
    @Test void inputOrderIsPreservedRatherThanSilentlySortedById() throws Exception {
        Fixture f = fixture(false, false); Collections.reverse(f.nodes);
        assertEquals(2, JSON.readTree(builder.linearSnapshot(f.nodes)).path("startNodeId").asInt());
        assertEquals(old(f), actual(f));
    }
    @Test void answersAndRoleSpecificBlocksRemainServerSnapshotContent() throws Exception {
        Fixture f = fixture(true, true);
        String value = actual(f); assertEquals(old(f), value);
        assertEquals("secret", JSON.readTree(value).path("nodeAnswers").path("1").path("questionAnswer").asText());
        assertTrue(value.contains("detective"));
        assertFalse(value.contains("rewardRulesJson"));
    }

    @Test void runtimeUsesSharedBuilderWithOriginalThreeChapterObservations() {
        Fixture f = fixture(true, true);
        CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
        ThemeStateKeyMapper keys = mock(ThemeStateKeyMapper.class);
        List<CmsTopicChapter> thought = new ArrayList<>(f.chapters);
        thought.set(1, chapter(12L, 2L, "changed for second read", "[]"));
        List<CmsTopicChapter> opening = Collections.singletonList(chapter(11L, 1L, "third read", "[]"));
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(f.chapters, thought, opening);
        when(keys.selectByTopicId(10L)).thenReturn(f.keys);
        com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl runtime =
                new com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl(null,null,null,null,null,null);
        org.springframework.test.util.ReflectionTestUtils.setField(runtime, "cmsTopicChapterMapper", chapters);
        org.springframework.test.util.ReflectionTestUtils.setField(runtime, "themeStateKeyMapper", keys);
        String result = org.springframework.test.util.ReflectionTestUtils.invokeMethod(runtime, "freezeJourney",
                f.snapshot, f.topic, f.nodes, f.configs, f.templates);
        org.mockito.InOrder order = inOrder(chapters, keys);
        order.verify(chapters).selectCmsTopicChapterList(any());
        order.verify(keys).selectByTopicId(10L);
        order.verify(chapters, times(2)).selectCmsTopicChapterList(any());
        order.verifyNoMoreInteractions();
        LegacyJourneySnapshotOracle oracle = new LegacyJourneySnapshotOracle();
        oracle.cmsTopicChapterMapper = mock(CmsTopicChapterMapper.class); oracle.themeStateKeyMapper = keys;
        when(oracle.cmsTopicChapterMapper.selectCmsTopicChapterList(any())).thenReturn(f.chapters, thought, opening);
        assertEquals(oracle.freezeJourney(f.snapshot,f.topic,f.nodes,f.configs,f.templates),result);
    }
    @Test void runtimeRejectsMalformedSnapshotBeforeAnySourceRead() {
        com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl runtime =
                new com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl(null,null,null,null,null,null);
        CmsTopicChapterMapper chapters=mock(CmsTopicChapterMapper.class); ThemeStateKeyMapper keys=mock(ThemeStateKeyMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(runtime,"cmsTopicChapterMapper",chapters);
        org.springframework.test.util.ReflectionTestUtils.setField(runtime,"themeStateKeyMapper",keys);
        Fixture f=fixture(true,false);
        assertThrows(IllegalStateException.class, () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(runtime,
                "freezeJourney","{",f.topic,f.nodes,f.configs,f.templates));
        verifyNoInteractions(chapters,keys);
    }

    private void golden(String name, Fixture f) throws Exception {
        String old = old(f); assertEquals(old, actual(f));
        Path path = Paths.get("src/test/resources/contracts/content-capture/" + name + ".json");
        assertEquals(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), old);
    }
    private void assertSameFailure(Fixture f) {
        RuntimeException before = assertThrows(RuntimeException.class, () -> old(f));
        RuntimeException after = assertThrows(RuntimeException.class, () -> actual(f));
        assertEquals(before.getClass(), after.getClass()); assertEquals(before.getMessage(), after.getMessage());
    }
    private String actual(Fixture f) {
        String snapshot = f.snapshot != null || f.invalidNull ? f.snapshot : builder.linearSnapshot(f.nodes);
        return builder.freezeJourney(snapshot, f.topic, f.nodes, f.configs, f.templates, f.keys, f.chapters);
    }
    private String old(Fixture f) {
        LegacyJourneySnapshotOracle oracle = new LegacyJourneySnapshotOracle();
        oracle.cmsTopicChapterMapper = mock(CmsTopicChapterMapper.class);
        oracle.themeStateKeyMapper = mock(ThemeStateKeyMapper.class);
        when(oracle.cmsTopicChapterMapper.selectCmsTopicChapterList(any())).thenReturn(f.chapters);
        when(oracle.themeStateKeyMapper.selectByTopicId(10L)).thenReturn(f.keys);
        String snapshot = f.snapshot != null || f.invalidNull ? f.snapshot : oracle.linearSnapshot(f.nodes);
        return oracle.freezeJourney(snapshot, f.topic, f.nodes, f.configs, f.templates);
    }
    static Fixture fixture(boolean branch, boolean opening) {
        Fixture f = new Fixture(); f.topic.setId(10L); f.topic.setConfigVersion(4L); f.topic.setMemberId(7L);
        f.topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true}");
        f.topic.setJourneyStoryJson(opening ? " {\"schemaVersion\":1,\"openingChapterId\":11,\"ending\":{\"endings\":[{\"chapterId\":13}]}} " : null);
        f.topic.setCompleteRuleJson("{\"type\":\"ALL\"}");
        f.nodes.add(node(1L, 12L, 101L, 1L)); f.nodes.add(node(2L, 12L, null, 2L));
        CmsMemberTemplate t = new CmsMemberTemplate(); t.setId(101L); t.setMemberId(7L);
        t.setValidationMethod(1); t.setPhotoReview(1); t.setQuestionAnswer("secret"); t.setCorrectAnswer("A");
        t.setAnswerReveal("reveal"); t.setQuestionA("A"); t.setQuestionB(""); t.setQuestionC("C"); t.setQuestionD("D");
        t.setPreferenceJson("{\"mode\":\"pick\"}"); t.setSensorType("steps"); t.setSensorConfig("{\"min\":20}");
        t.setAdvancedConfigJson("{\"requires\":[{\"var\":\"clue.key\",\"op\":\"EQ\",\"value\":1}],\"effects\":[{\"op\":\"SET\",\"var\":\"clue.key\",\"value\":1}],\"brief\":{\"question\":\"ignored template story\"}}");
        f.templates.put(101L,t); f.configs.put(101L,t.getAdvancedConfigJson());
        f.chapters.add(chapter(13L, 3L, "Ending", "[]"));
        f.chapters.add(chapter(12L, 2L, "Main", "[{\"type\":\"text\",\"beat\":\"brief\",\"field\":\"question\",\"content\":\"Find it\",\"nodeId\":1},{\"type\":\"text\",\"content\":\"role text\",\"roleKey\":\"detective\"},{\"type\":\"node\",\"nodeId\":1}]"));
        f.chapters.add(chapter(11L, 1L, "Opening", "[{\"type\":\"text\",\"content\":\"Start here\"}]"));
        ThemeStateKey k=new ThemeStateKey(); k.setId(31L); k.setTopicId(10L); k.setStateKey("clue.key"); k.setKeyType("flag"); k.setScope("run"); k.setLabel("Key"); k.setInitialValue(0); f.keys.add(k);
        if(branch) f.snapshot="{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2],\"variables\":{},\"edges\":[{\"id\":\"go\",\"fromNodeId\":1,\"toNodeId\":2,\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]}";
        f.topic.setRouteGraphJson(f.snapshot); f.topic.setRouteMode(branch?"BRANCH_GRAPH":"LINEAR");
        return f;
    }
    static CmsTopicNode node(long id, Long chapter, Long template, Long sort) {
        CmsTopicNode n=new CmsTopicNode(); n.setId(id); n.setTopicId(10L); n.setChapterId(chapter); n.setTemplateId(template); n.setSortId(sort); n.setStatus(1L); return n;
    }
    static CmsTopicChapter chapter(long id, long sort, String name, String blocks) {
        CmsTopicChapter c=new CmsTopicChapter(); c.setId(id); c.setTopicId(10L); c.setSortId(sort); c.setName(name); c.setRequired(1); c.setBlocksJson(blocks); c.setDescription("fallback"); return c;
    }
    static class Fixture {
        CmsTopic topic=new CmsTopic(); List<CmsTopicNode> nodes=new ArrayList<>(); List<CmsTopicChapter> chapters=new ArrayList<>();
        List<ThemeStateKey> keys=new ArrayList<>(); Map<Long,CmsMemberTemplate> templates=new LinkedHashMap<>(); Map<Long,String> configs=new LinkedHashMap<>(); String snapshot; boolean invalidNull;
    }
}

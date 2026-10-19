package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TopicPublicationEquivalenceTest {
    static final String[] GATES={"assertStateKeysRegistered","assertThoughtRefsResolvable","assertJourneyStoryReferences",
            "assertBlockNarrativeNpcReferences","assertBlockSysVarReferences"};
    static final String[] CONFIGS={null,"{}","{","[]",
        "{\"enter\":{\"npcId\":7}}","{\"enter\":{\"npcId\":99},\"outcome\":{\"npcId\":99}}",
        "{\"requires\":[{\"var\":\"clue.page\",\"op\":\"EQ\",\"value\":1}]}",
        "{\"requires\":[{\"var\":\"sys.mistakes.99\",\"op\":\"GTE\",\"value\":1}]}",
        "{\"requires\":[{\"type\":\"NODE_COMPLETED\",\"nodeId\":99}]}",
        "{\"branch\":{\"enabled\":true}}"};
    static final String[] STORIES={null,"{}","{","[]",
        "{\"schemaVersion\":1,\"call\":{\"npcId\":7,\"opener\":\"Hi\"},\"stages\":[{\"chapterId\":11}]}",
        "{\"schemaVersion\":1,\"call\":{\"npcId\":99,\"opener\":\"Hi\"}}",
        "{\"schemaVersion\":1,\"stages\":[{\"chapterId\":99}]}",
        "{\"schemaVersion\":1,\"ending\":{\"endings\":[{\"code\":\"a\",\"title\":\"A\",\"fallback\":true}],\"optionalClues\":[\"clue.page\"]}}"};

    @Test void goldenLegacyOutcomesAndQueries() throws Exception {
        StringBuilder golden=new StringBuilder();
        for(int i=0;i<CONFIGS.length;i++) for(String gate:GATES) {
            Fixture f=new Fixture(); f.template.setAdvancedConfigJson(CONFIGS[i]);
            golden.append("config[").append(i).append("]/").append(gate).append("\n").append(f.run(true,gate)).append("\n");
        }
        for(int i=0;i<STORIES.length;i++) for(String gate:GATES) {
            Fixture f=new Fixture(); f.topic.setJourneyStoryJson(STORIES[i]);
            golden.append("story[").append(i).append("]/").append(gate).append("\n").append(f.run(true,gate)).append("\n");
        }
        Path p=Paths.get("src/test/resources/publication-validation-0addcba.txt");
        assertEquals(new String(Files.readAllBytes(p),StandardCharsets.UTF_8),golden.toString());
    }
    @Test void frozenOracleMatchesProductionAcrossMalformedAndForeignReferences() {
        for(String config:CONFIGS) for(String story:STORIES) for(String gate:GATES) {
            Fixture old=new Fixture(); old.template.setAdvancedConfigJson(config);old.topic.setJourneyStoryJson(story);
            Fixture current=new Fixture(); current.template.setAdvancedConfigJson(config);current.topic.setJourneyStoryJson(story);
            assertEquals(old.run(true,gate),current.run(false,gate),gate+" config="+config+" story="+story);
        }
    }
    @Test void preservesCompatibilityForMissingCollaboratorsAndMalformedChapters() {
        for(String blocks:Arrays.asList(null,"[]","{","[{\"type\":\"thought\",\"thoughtKey\":\"unregistered\"}]"))
            for(String absent:Arrays.asList("none","themeStateKeyService","topicStateKeyRefService","cmsTopicChapterMapper","cmsMemberTemplateMapper","npcProfileMapper"))
                for(String gate:GATES) {
                    Fixture old=new Fixture();old.chapter.setBlocksJson(blocks);old.absent=absent;
                    Fixture current=new Fixture();current.chapter.setBlocksJson(blocks);current.absent=absent;
                    assertEquals(old.run(true,gate),current.run(false,gate),gate+" absent="+absent+" blocks="+blocks);
                }
    }
    @Test void autoRegistrationOrderAndRulesTypeErrorsMatchFrozenOracle() {
        for (String config : Arrays.asList("{\"requires\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.remember\"}]}",
                "{\"requires\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":99}]}",
                "{\"requires\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.remember\"},{\"var\":\"clue.missing\",\"op\":\"EQ\",\"value\":1}]}")) {
            Fixture old = new Fixture(); old.template.setAdvancedConfigJson(config);
            Fixture current = new Fixture(); current.template.setAdvancedConfigJson(config);
            assertEquals(old.run(true,GATES[0]),current.run(false,GATES[0]));
        }
        Fixture f = new Fixture(); f.template.setAdvancedConfigJson("{\"requires\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.remember\"}]}");
        assertEquals("OK\n[nodes:10:1, template:101, chapters:10, keys:10, register:tag.remember, keys:10]",f.run(false,GATES[0]));
    }
    @Test void injectedReadAndRegistrationFailuresKeepOldErrorBoundaryAndQueryOrder() {
        for (String failure : Arrays.asList("nodes", "template", "templates", "chapters", "npcs", "keys", "register"))
            for (String gate : GATES) {
                Fixture old = new Fixture(); Fixture current = new Fixture();
                for (Fixture f : Arrays.asList(old,current)) {
                    f.topic.setJourneyStoryJson(STORIES[4]);
                    f.template.setAdvancedConfigJson("{\"enter\":{\"npcId\":7},\"requires\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.remember\"}]}");
                    f.failure = failure;
                }
                assertEquals(old.run(true,gate),current.run(false,gate),gate+" failed="+failure);
            }
    }
    @Test void repeatedNodeObservationsAreNotCollapsedIntoOneLiveRead() {
        for (String gate : Arrays.asList(GATES[2],GATES[3])) {
            Fixture old = new Fixture(); Fixture current = new Fixture();
            for (Fixture f : Arrays.asList(old,current)) {
                f.topic.setJourneyStoryJson(STORIES[4]); f.template.setAdvancedConfigJson("{\"enter\":{\"npcId\":7}}");
                f.changeSecondNodes = true;
            }
            String expected = old.run(true,gate);
            assertTrue(expected.contains("npcs:4:77"));
            assertEquals(expected,current.run(false,gate));
        }
    }
    @Test void duplicateTemplateReadsAndRegisteredRelationTypesRemainEquivalent() {
        for (String type : Arrays.asList("relation", "flag")) {
            Fixture old = new Fixture(); Fixture current = new Fixture();
            for (Fixture f : Arrays.asList(old,current)) {
                f.duplicateNode = true;
                f.topic.setJourneyStoryJson("{\"schemaVersion\":1,\"ending\":{\"endings\":[{\"code\":\"a\",\"title\":\"A\",\"fallback\":true}],\"epilogues\":[{\"npcId\":7,\"relationKey\":\"relation.arong\",\"ally\":\"a\",\"neutral\":\"b\",\"guarded\":\"c\"}]}}");
                ThemeStateKey k = new ThemeStateKey(); k.setStateKey("relation.arong"); k.setKeyType(type); f.keys.add(k);
            }
            assertEquals(old.run(true,GATES[0]),current.run(false,GATES[0]));
            assertEquals(2,current.calls.stream().filter(c -> c.equals("template:101")).count());
        }
    }
    @Test void missingCollectorResultCannotBecomeTheCompatibilityFallback() {
        Fixture old=new Fixture();Fixture current=new Fixture();old.nullCollection=true;current.nullCollection=true;
        String expected=old.run(true,GATES[0]);
        assertTrue(expected.startsWith("java.lang.NullPointerException:"));
        // JDK 21's helpful NPE names the frozen test class; only undo that test-only rename.
        assertEquals(expected.replace("LegacyTopicStateKeyRefService$", "TopicStateKeyRefService$"),current.run(false,GATES[0]));
    }
    @Test void loadedCollectorMatchesFrozenCollectorWithoutDatabaseReads() {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        for(String config:CONFIGS) for(String story:STORIES) for(String blocks:Arrays.asList(null,"[]","{","[{\"type\":\"thought\",\"thoughtKey\":\"missing\"}]")) {
            Fixture f=new Fixture();f.template.setAdvancedConfigJson(config);f.topic.setJourneyStoryJson(story);f.chapter.setBlocksJson(blocks);
            CmsTopicNodeMapper nm=mock(CmsTopicNodeMapper.class);CmsMemberTemplateMapper tm=mock(CmsMemberTemplateMapper.class);CmsTopicChapterMapper cm=mock(CmsTopicChapterMapper.class);
            when(nm.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(f.node));when(tm.selectCmsMemberTemplateById(101L)).thenReturn(f.template);when(cm.selectCmsTopicChapterList(any())).thenReturn(Arrays.asList(f.chapter));
            Object expected=new LegacyTopicStateKeyRefService(nm,tm,cm).collect(f.topic);
            clearInvocations(nm,tm,cm);
            TopicStateKeyRefService.RefCollection loaded=TopicStateKeyRefService.collectLoaded(f.topic,Arrays.asList(f.node),Collections.singletonMap(101L,f.template),Arrays.asList(f.chapter));
            // This oracle predates theme runtime; these legacy fixtures deliberately have none.
            assertNull(f.topic.getThemeRuntimeConfigJson());
            assertTrue(loaded.getThemeRuntimeKeys().isEmpty());
            com.fasterxml.jackson.databind.node.ObjectNode legacyProjection=json.valueToTree(loaded);
            assertEquals(json.createArrayNode(),legacyProjection.remove("themeRuntimeKeys"));
            assertEquals(json.valueToTree(expected),legacyProjection);verifyNoInteractions(nm,tm,cm);
        }
    }
    static final class Fixture {
        final CmsTopic topic=new CmsTopic(); final CmsTopicNode node=new CmsTopicNode();
        final CmsTopicChapter chapter=new CmsTopicChapter(); final CmsMemberTemplate template=new CmsMemberTemplate();
        final List<String> calls=new ArrayList<>(); final List<ThemeStateKey> keys=new ArrayList<>();
        String absent="none", failure="none";
        boolean changeSecondNodes, duplicateNode, nullCollection;
        int nodeReads;
        void call(String name) { calls.add(name); if (name.split(":")[0].equals(failure)) throw new IllegalStateException("injected "+failure); }
        Fixture() { topic.setId(10L);node.setId(1L);node.setTopicId(10L);node.setStatus(1L);node.setName("A");node.setTemplateId(101L);node.setMerchantMemberId(8L);
            chapter.setId(11L);chapter.setTopicId(10L);template.setId(101L);template.setAdvancedConfigJson("{}"); }
        String run(boolean legacy,String gate) {
            CmsTopicNodeMapper nm=mock(CmsTopicNodeMapper.class);
            when(nm.selectCmsTopicNodeList(any())).thenAnswer(i->{CmsTopicNode q=i.getArgument(0);call("nodes:"+q.getTopicId()+":"+q.getStatus());
                nodeReads++;
                if(changeSecondNodes && nodeReads>1) { CmsTopicNode newer=new CmsTopicNode();newer.setId(77L);newer.setMerchantMemberId(88L);return Arrays.asList(newer); }
                return duplicateNode?Arrays.asList(node,node):Arrays.asList(node);});
            CmsTopicChapterMapper cm=mock(CmsTopicChapterMapper.class);
            when(cm.selectCmsTopicChapterList(any())).thenAnswer(i->{call("chapters:"+((CmsTopicChapter)i.getArgument(0)).getTopicId());return Arrays.asList(chapter);});
            CmsMemberTemplateMapper tm=mock(CmsMemberTemplateMapper.class);
            when(tm.selectCmsMemberTemplateById(anyLong())).thenAnswer(i->{call("template:"+i.getArgument(0));return template;});
            when(tm.selectCmsMemberTemplateByIds(anyList())).thenAnswer(i->{call("templates:"+i.getArgument(0));return Arrays.asList(template);});
            NpcProfileMapper np=mock(NpcProfileMapper.class);
            when(np.selectEnabledByScope(anyInt(),anyLong())).thenAnswer(i->{call("npcs:"+i.getArgument(0)+":"+i.getArgument(1));NpcProfile p=new NpcProfile();p.setId(7L);return Arrays.asList(p);});
            ThemeStateKeyService ks=mock(ThemeStateKeyService.class);
            when(ks.listByTopic(anyLong())).thenAnswer(i->{call("keys:"+i.getArgument(0));return keys;});
            doAnswer(i->{List<ThemeStateKey> newKeys=i.getArgument(1);for(ThemeStateKey k:newKeys){call("register:"+k.getStateKey());keys.add(k);}return null;}).when(ks).register(anyLong(),anyList());
            Object service=legacy?new LegacyTopicPublicationOracle():new CmsTopicServiceImpl();
            if (!legacy) {
                // Existing content/owner fixtures represent legacy rows; provenance negatives have their own suite.
                com.chengyinhub.business.mapper.LegacyCmsProvenanceMapper legacyProvenance = org.mockito.Mockito.mock(
                        com.chengyinhub.business.mapper.LegacyCmsProvenanceMapper.class);
                org.mockito.Mockito.when(legacyProvenance.selectLegacyBlockState(org.mockito.ArgumentMatchers.anyLong())).thenReturn(0);
                org.springframework.test.util.ReflectionTestUtils.setField(service, "legacyCmsProvenanceGuard",
                        new com.chengyinhub.business.service.support.LegacyCmsProvenanceGuard(legacyProvenance));
                // This frozen oracle still covers unrelated ordering/errors. Give its deliberately
                // equal-ID fixtures real directory evidence; distinct-ID behavior has separate regression tests.
                MmsMerchantMapper merchants = mock(MmsMerchantMapper.class);
                when(merchants.selectMmsMerchantList(any(MmsMerchant.class))).thenAnswer(i -> {
                    Long member = ((MmsMerchant)i.getArgument(0)).getMemberId();
                    MmsMerchant row = new MmsMerchant(); row.setId(member); row.setMemberId(member);
                    row.setStatus(1L); row.setAccountStatus(1L); row.setDelFlag(0);
                    return Collections.singletonList(row);
                });
                ReflectionTestUtils.setField(service, "merchantNpcIdentityDirectory", new MerchantNpcIdentityDirectory(merchants));
            }
            ReflectionTestUtils.setField(service,"cmsTopicNodeMapper",nm);ReflectionTestUtils.setField(service,"cmsTopicChapterMapper",cm);
            ReflectionTestUtils.setField(service,"cmsMemberTemplateMapper",tm);ReflectionTestUtils.setField(service,"npcProfileMapper",np);ReflectionTestUtils.setField(service,"themeStateKeyService",ks);
            ReflectionTestUtils.setField(service,"topicStateKeyRefService",legacy?new LegacyTopicStateKeyRefService(nm,tm,cm):new TopicStateKeyRefService(nm,tm,cm));
            if(nullCollection) ReflectionTestUtils.setField(service,"topicStateKeyRefService",legacy?mock(LegacyTopicStateKeyRefService.class):mock(TopicStateKeyRefService.class));
            if(!"none".equals(absent)) ReflectionTestUtils.setField(service,absent,null);
            String result="OK";
            try {ReflectionTestUtils.invokeMethod(service,gate,topic);}
            catch(RuntimeException e){result=e.getClass().getName()+":"+(e instanceof ServiceException?((ServiceException)e).getCode()+":":"")+e.getMessage();}
            return result+"\n"+calls;
        }
    }
}

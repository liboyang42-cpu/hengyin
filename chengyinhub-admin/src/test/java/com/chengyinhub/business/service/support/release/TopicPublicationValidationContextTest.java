package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TopicPublicationValidationContextTest {
    @Test void referenceFreeContentDoesNotRequireAiAndCannotBecomeApproved() throws Exception {
        ContentCaptureRows r=clean(); ContentCaptureCandidate c=new ContentCaptureBuilder().build(r);
        JsonNode report=report(c);
        assertEquals("CHECKED_SUBSET",report.path("status").asText());
        assertFalse(report.path("completePublicationValidation").asBoolean());
        assertEquals("NOT_CAPTURED",report.path("npcScopeEvidence").asText());
        assertTrue(c.serverOnlyJson().contains("ASSET_RIGHTS"));
        assertTrue(c.serverOnlyJson().contains("REGISTERED_RULE_REFERENCES"));
        assertThrows(IllegalStateException.class,c::requireCompleteRelease);
    }
    @Test void capturedOptionTagMustExistWithoutOrdinaryPublicationMutation() throws Exception {
        ContentCaptureRows r=clean(); r.templates.get(101L).setAdvancedConfigJson("{\"requires\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.remember\"}]}");
        int before=r.keys.size(); JsonNode blocked=report(new ContentCaptureBuilder().build(r));
        assertEquals("BLOCKED",gate(blocked,"STATE_KEYS").path("status").asText());
        assertTrue(gate(blocked,"STATE_KEYS").path("message").asText().contains("tag.remember"));
        assertEquals(before,r.keys.size());
        ThemeStateKey key=new ThemeStateKey();key.setTopicId(10L);key.setStateKey("tag.remember");key.setKeyType("tag");key.setScope("run");r.keys.add(key);
        assertEquals("CHECKED",gate(report(new ContentCaptureBuilder().build(r)),"STATE_KEYS").path("status").asText());
    }
    @Test void foreignStoryChapterCannotPassLoadedContext() throws Exception {
        ContentCaptureRows r=clean();r.topic.setJourneyStoryJson("{\"schemaVersion\":1,\"stages\":[{\"chapterId\":999}]}");
        assertBlocked(r,"JOURNEY_STORY_REFERENCES","章节");
    }
    @Test void absentNpcEvidenceBlocksReferencesInsteadOfUsingLegacyNullSkip() throws Exception {
        ContentCaptureRows r=clean();r.topic.setJourneyStoryJson("{\"schemaVersion\":1,\"call\":{\"npcId\":7,\"opener\":\"Hi\"}}");
        assertBlocked(r,"JOURNEY_STORY_REFERENCES","npcId");
        r=clean();r.templates.get(101L).setAdvancedConfigJson("{\"enter\":{\"npcId\":7}}");
        assertBlocked(r,"NARRATIVE_NPC_REFERENCES","npcId=7");
    }
    @Test void chapterOnlyNpcReferenceCannotDisappearWhenNoNodeHasTemplate() throws Exception {
        ContentCaptureRows r=clean();for(CmsTopicNode n:r.nodes)n.setTemplateId(null);r.templates.clear();
        r.chapters.get(0).setBlocksJson("[{\"type\":\"text\",\"key\":\"line1\",\"beat\":\"enter\",\"nodeId\":1,\"field\":\"opener\",\"content\":\"Hello\",\"npcId\":7}]");
        assertBlocked(r,"NARRATIVE_NPC_REFERENCES","npcId=7");
    }
    @Test void foreignSysAndCompletedNodeReferencesUseExistingValidator() throws Exception {
        ContentCaptureRows r=clean();r.templates.get(101L).setAdvancedConfigJson("{\"requires\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":999},{\"var\":\"sys.mistakes.999\",\"op\":\"GTE\",\"value\":1}]}");
        assertBlocked(r,"BLOCK_NODE_REFERENCES","NODE_COMPLETED");
        assertBlocked(r,"BLOCK_NODE_REFERENCES","系统变量");
    }
    @Test void undeclaredThoughtAndMalformedBlocksStayFailClosed() throws Exception {
        ContentCaptureRows r=clean();r.chapters.get(0).setBlocksJson("[{\"type\":\"thought\",\"key\":\"t1\",\"thoughtKey\":\"missing\"}]");
        assertBlocked(r,"THOUGHT_REFERENCES","missing");
        r=clean();r.chapters.get(0).setBlocksJson("{");final ContentCaptureRows broken=r;
        assertThrows(IllegalArgumentException.class,()->new ContentCaptureBuilder().build(broken));
    }
    @Test void malformedRuleShapeReportsBlockerEvenThoughJsonIsAnObject() throws Exception {
        ContentCaptureRows r=clean();r.topic.setJourneyStoryJson("{\"schemaVersion\":999}");
        assertBlocked(r,"STATE_KEYS","无法解析");
    }
    @Test void crossTopicRowsFailBeforeTheyCanMasqueradeAsValidationEvidence() {
        ContentCaptureRows r=clean();r.chapters.get(0).setTopicId(99L);reject(r);
        r=clean();r.nodes.get(0).setTopicId(99L);reject(r);
        r=clean();r.keys.get(0).setTopicId(99L);reject(r);
        r=clean();r.templates.clear();reject(r);
    }
    @Test void loadedCollectorHasNoMissingTemplateFallback() {
        ContentCaptureRows r=clean();r.templates.clear();
        assertThrows(IllegalArgumentException.class,()->TopicStateKeyRefService.collectLoaded(r.topic,r.nodes,r.templates,r.chapters));
        assertThrows(IllegalArgumentException.class,()->TopicStateKeyRefService.collectLoaded(r.topic,null,r.templates,r.chapters));
    }
    @Test void sameRowsYieldStableReportAndLaterMutationCannotChangeReturnedCandidate() throws Exception {
        ContentCaptureRows r=clean();ContentCaptureCandidate first=new ContentCaptureBuilder().build(r);String before=first.serverOnlyJson();
        assertEquals(first.contentHash(),new ContentCaptureBuilder().build(clean()).contentHash());
        r.templates.get(101L).setAdvancedConfigJson("{\"enter\":{\"npcId\":999}}");
        ContentCaptureCandidate second=new ContentCaptureBuilder().build(r);
        assertEquals(before,first.serverOnlyJson());assertNotEquals(first.contentHash(),second.contentHash());
        assertEquals("BLOCKED",report(second).path("status").asText());
    }
    static ContentCaptureRows clean() {
        ContentCaptureRows r=ContentCaptureBuilderTest.rows();r.topic.setJourneyRulesJson(null);r.topic.setJourneyStoryJson(null);
        for(CmsMemberTemplate t:r.templates.values())t.setAdvancedConfigJson("{}");
        for(CmsTopicChapter c:r.chapters)c.setBlocksJson("[]");
        return r;
    }
    static JsonNode report(ContentCaptureCandidate c) throws Exception {return CanonicalContentJson.JSON.readTree(c.serverOnlyJson()).at("/components/publicationReferenceChecks/content");}
    static JsonNode gate(JsonNode report,String name) {for(JsonNode check:report.path("checks"))if(name.equals(check.path("gate").asText()))return check;throw new AssertionError(name);}
    static void assertBlocked(ContentCaptureRows r,String name,String fragment)throws Exception {
        ContentCaptureCandidate c=new ContentCaptureBuilder().build(r);JsonNode g=gate(report(c),name);
        assertEquals("BLOCKED",g.path("status").asText(),g.toString());assertTrue(g.path("message").asText().contains(fragment),g.toString());
        assertTrue(c.serverOnlyJson().contains("PUBLICATION_REFERENCE_BLOCKERS"));
    }
    static void reject(ContentCaptureRows r){assertThrows(IllegalArgumentException.class,()->new ContentCaptureBuilder().build(r));}
}

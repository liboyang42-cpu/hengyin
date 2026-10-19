package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Selected scene story and its dependent chapter snapshots must agree. */
class OWN_C06_24Test {
    private final ObjectMapper json=new ObjectMapper();
    private TopicRouteRuntimeServiceImpl service;
    private CmsTopic topic;
    @BeforeEach void setup() throws Exception {
        service=new TopicRouteRuntimeServiceImpl();CmsTopicChapterMapper chapters=mock(CmsTopicChapterMapper.class);
        ReflectionTestUtils.setField(service,"cmsTopicChapterMapper",chapters);
        topic=new CmsTopic();topic.setId(20L);topic.setJourneyStoryJson(story(50,90,"global").toString());
        List<CmsTopicChapter> rows=new ArrayList<>();
        for(long id:new long[]{50,70,80,90,91,92}) {
            CmsTopicChapter row=new CmsTopicChapter();row.setId(id);row.setTopicId(20L);row.setName("chapter-"+id);
            row.setSortId(id);row.setRequired(1);row.setBlocksJson("[{\"type\":\"text\",\"content\":\"body-"+id+"\"}]");rows.add(row);
        }
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(rows);
    }
    private ObjectNode story(long opening,long ending,String code) {
        ObjectNode value=json.createObjectNode();value.put("schemaVersion",1);value.put("openingChapterId",opening);
        ObjectNode end=value.putObject("ending").putArray("endings").addObject();
        end.put("code",code);end.put("title",code);end.put("chapterId",ending);end.put("fallback",true);return value;
    }
    private ObjectNode config() {
        ObjectNode value=json.createObjectNode();value.put("schemaVersion",1);value.put("currentScene","a");
        value.putArray("scenes").addObject().put("id","a").put("name","Scene A");return value;
    }
    private JsonNode freeze(int index) throws Exception {
        String snapshot=ReflectionTestUtils.invokeMethod(service,"freezeJourney","{}",topic,
                Collections.<CmsTopicNode>emptyList(),Collections.<Long,String>emptyMap(),
                Collections.<Long,CmsMemberTemplate>emptyMap(),index);
        return json.readTree(snapshot);
    }
    @Test void sceneOpeningAndEndingBodiesMatchSelectedFrozenStory() throws Exception {
        ObjectNode config=config();((ObjectNode)config.path("scenes").get(0)).set("journeyStory",story(70,91,"scene"));
        topic.setPublishedThemeRuntimeConfigJson(config.toString());JsonNode snapshot=freeze(0);
        assertEquals(70,snapshot.path("openingChapter").path("chapterId").asInt());
        assertEquals("chapter-70",snapshot.path("openingChapter").path("name").asText());
        assertTrue(snapshot.path("endingChapters").path("91").toString().contains("body-91"));
        assertTrue(snapshot.path("endingChapters").has("90"),"Global endings remain private snapshots");
        for(JsonNode chapter:snapshot.path("thoughtChapters")) {
            assertNotEquals(90,chapter.path("chapterId").asInt());assertNotEquals(91,chapter.path("chapterId").asInt());
        }
        assertEquals(70,json.readTree(snapshot.path("journeyStory").asText()).path("openingChapterId").asInt());
    }
    @Test void sceneWithoutStoryKeepsGlobalOpeningAndEnding() throws Exception {
        topic.setPublishedThemeRuntimeConfigJson(config().toString());JsonNode snapshot=freeze(0);
        assertEquals(50,snapshot.path("openingChapter").path("chapterId").asInt());
        assertTrue(snapshot.path("endingChapters").has("90"));
        assertEquals(topic.getJourneyStoryJson(),snapshot.path("journeyStory").asText());
    }
    @Test void existingRoundRobinSelectionStillChoosesTheRequestedRunScene() throws Exception {
        ObjectNode config=config();config.put("rotation","ROUND_ROBIN");
        ((ObjectNode)config.path("scenes").get(0)).set("journeyStory",story(70,91,"a"));
        ObjectNode second=((ArrayNode)config.path("scenes")).addObject();second.put("id","b");second.put("name","Scene B");
        second.set("journeyStory",story(80,92,"b"));topic.setPublishedThemeRuntimeConfigJson(config.toString());
        JsonNode snapshot=freeze(1);assertEquals("b",snapshot.path("themeConfig").path("sceneId").asText());
        assertEquals(80,snapshot.path("openingChapter").path("chapterId").asInt());assertTrue(snapshot.path("endingChapters").has("92"));
    }
}

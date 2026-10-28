package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import java.beans.PropertyDescriptor;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 同一配置图；显示文案与数值从 variants.json 读取，服务不认识主题名称。 */
public class GameplayReskinFixture {
    static final ObjectMapper JSON = new ObjectMapper();
    final Map<Long,CmsTopic> topics = new LinkedHashMap<>();
    final Map<Long,CmsTopicChapter> chapters = new LinkedHashMap<>();
    final Map<Long,CmsTopicNode> nodes = new LinkedHashMap<>();
    final Map<Long,CmsMemberTemplate> templates = new LinkedHashMap<>();
    final Map<String,GameStoryState> owners = new LinkedHashMap<>();
    final Map<String,GameCommandReceipt> receipts = new HashMap<>();
    final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
    final CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
    final CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
    final CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
    final GameSessionRuntimeMapper gameMapper = mock(GameSessionRuntimeMapper.class);
    final PlayTeamMapper teamMapper = mock(PlayTeamMapper.class);
    final ThemeStateKeyMapper keyMapper = mock(ThemeStateKeyMapper.class);
    final CmsTopicServiceImpl publisher = new CmsTopicServiceImpl();
    final TopicRouteRuntimeServiceImpl route;
    final GameSessionRuntimeServiceImpl runtime = new GameSessionRuntimeServiceImpl();
    // Author prototypes are not persisted rows and must never acquire another theme's identity.
    final List<ThemeStateKey> keys = new ArrayList<>();
    private final Map<Long, Map<String, ThemeStateKey>> declarations = new LinkedHashMap<>();
    private long declarationSequence = 20000;
    GameSession session;
    CmsTopic topic;
    JsonNode variant, snapshot;
    ICmsTopicService authoring=publisher;
    IGameSessionRuntimeService play=runtime;
    private CmsTopicMapper activeTopics;
    private GameSessionRuntimeMapper activeGames;
    private ThemeStateKeyService keyService;
    private org.mybatis.spring.SqlSessionTemplate sql;
    private long sequence = 10000;
    private int request;

    static JsonNode resource(String name) throws Exception {
        try(InputStream input=GameplayReskinFixture.class.getClassLoader().getResourceAsStream("gameplay-reskin/"+name)) {
            assertNotNull(input,name);return JSON.readTree(input);
        }
    }
    static List<JsonNode> variants() throws Exception {
        List<JsonNode> result=new ArrayList<>();resource("variants.json").forEach(result::add);return result;
    }
    static void patch(Object current,Object update) {
        BeanWrapper target=new BeanWrapperImpl(current),source=new BeanWrapperImpl(update);
        for(PropertyDescriptor property:source.getPropertyDescriptors()) {
            String name=property.getName();if("class".equals(name)||!target.isWritableProperty(name))continue;
            Object value=source.getPropertyValue(name);if(value!=null)target.setPropertyValue(name,value);
        }
    }
    public GameplayReskinFixture() throws Exception {
        for(Field field:CmsTopicServiceImpl.class.getDeclaredFields()) if(field.isAnnotationPresent(Autowired.class))
            com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(publisher, field.getName(),mock(field.getType()));
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wireRelocatedCollaborators(publisher, publicationField -> org.mockito.Mockito.mock(publicationField.getType()));
        ReflectionTestUtils.setField(publisher,"cmsTopicMapper",topicMapper);
        ReflectionTestUtils.setField(publisher,"cmsTopicChapterMapper",chapterMapper);
        ReflectionTestUtils.setField(publisher,"cmsTopicNodeMapper",nodeMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(publisher, "cmsMemberTemplateMapper",templateMapper);
        // CORE OWN-C03-15：自办活动插入后须生成有效身份，否则 fail-closed；夹具按真实插入回填 id。
        CmsActivityMapper shellActivities=(CmsActivityMapper)ReflectionTestUtils.getField(publisher,"cmsActivityMapper");
        when(shellActivities.insertCmsActivity(any())).thenAnswer(i->{((com.chengyinhub.business.domain.CmsActivity)i.getArgument(0)).setId(880001L);return 1;});
        OmsTicketMapper freeTickets = mock(OmsTicketMapper.class);
        MerchantChapterOfferMapper noOffers = mock(MerchantChapterOfferMapper.class);
        when(freeTickets.selectOmsTicketList(any())).thenReturn(Collections.emptyList());
        when(noOffers.selectByTopic(anyLong())).thenReturn(Collections.emptyList());
        wireAuthoringGuards(topicMapper, chapterMapper, nodeMapper, templateMapper, freeTickets, noOffers);
        when(keyMapper.lockTopicForStateKeyWrite(anyLong())).thenAnswer(call -> {
            CmsTopic parent = topics.get(call.getArgument(0)); return parent == null ? null : parent.getId();
        });
        when(keyMapper.upsertThemeStateKey(any())).thenAnswer(call -> persistDeclaration(call.getArgument(0)));
        when(keyMapper.selectByTopicId(anyLong())).thenAnswer(call -> storedDeclarations(call.getArgument(0)));
        when(keyMapper.selectByTopicIdForUpdate(anyLong())).thenAnswer(call -> storedDeclarations(call.getArgument(0)));
        when(keyMapper.selectByTopicIdAndStateKey(anyLong(), anyString()))
                .thenAnswer(call -> storedDeclaration(call.getArgument(0), call.getArgument(1)));
        when(keyMapper.selectByTopicIdAndStateKeyForUpdate(anyLong(), anyString()))
                .thenAnswer(call -> storedDeclaration(call.getArgument(0), call.getArgument(1)));
        keyService=new ThemeStateKeyService(keyMapper);
        ReflectionTestUtils.setField(publisher,"themeStateKeyService",keyService);
        TopicStateKeyRefService currentReferences=new TopicStateKeyRefService(nodeMapper,templateMapper,chapterMapper);
        ReflectionTestUtils.setField(currentReferences,"cmsTopicMapper",topicMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(publisher, "topicStateKeyRefService",currentReferences);
        for(String name:Arrays.asList("rescue","load_ok","end01","end02","end03","end04","ev01","ev02","ev03","ev04","ev05")) {
            ThemeStateKey key=new ThemeStateKey();key.setStateKey("clue."+name);key.setKeyType("flag");key.setScope("run");key.setInitialValue(0);keys.add(key);
        }
        when(topicMapper.insertCmsTopic(any())).thenAnswer(call->{CmsTopic row=call.getArgument(0);row.setId(++sequence);row.setLifecycle(0);topics.put(row.getId(),row);return 1;});
        when(topicMapper.selectCmsTopicById(anyLong())).thenAnswer(call->topics.get(call.getArgument(0)));
        when(topicMapper.selectCmsTopicByIdForUpdate(anyLong())).thenAnswer(call->topics.get(call.getArgument(0)));
        when(topicMapper.updateCmsTopic(any())).thenAnswer(call->{CmsTopic row=call.getArgument(0);patch(topics.get(row.getId()),row);return 1;});
        when(topicMapper.clearDraftIfDraft(anyLong())).thenReturn(1);when(topicMapper.selectTopicIsDraft(anyLong())).thenReturn(1);
        when(topicMapper.publishRouteSnapshot(anyLong(),anyString(),any())).thenAnswer(call->{CmsTopic row=topics.get(call.getArgument(0));row.setPublishedRouteGraphJson(call.getArgument(1));row.setPublishedConfigVersion(1L);row.setPublishedThemeRuntimeConfigJson(row.getThemeRuntimeConfigJson());return 1;});
        when(chapterMapper.insertCmsTopicChapter(any())).thenAnswer(call->{CmsTopicChapter row=call.getArgument(0);row.setId(++sequence);chapters.put(row.getId(),row);return 1;});
        when(chapterMapper.selectCmsTopicChapterList(any())).thenAnswer(call->{CmsTopicChapter q=call.getArgument(0);List<CmsTopicChapter> result=new ArrayList<>();for(CmsTopicChapter row:chapters.values())if(row.getTopicId().equals(q.getTopicId()))result.add(row);return result;});
        when(chapterMapper.selectByTopicForUpdate(anyLong())).thenAnswer(call -> chapters.values().stream()
                .filter(row -> row.getTopicId().equals(call.getArgument(0))).toList());
        when(nodeMapper.insertCmsTopicNode(any())).thenAnswer(call->{CmsTopicNode row=call.getArgument(0);row.setId(++sequence);nodes.put(row.getId(),row);return 1;});
        when(nodeMapper.selectCmsTopicNodeList(any())).thenAnswer(call->{CmsTopicNode q=call.getArgument(0);List<CmsTopicNode> result=new ArrayList<>();for(CmsTopicNode row:nodes.values())if(row.getTopicId().equals(q.getTopicId()))result.add(row);return result;});
        when(nodeMapper.selectByTopicForUpdate(anyLong())).thenAnswer(call -> nodes.values().stream()
                .filter(row -> row.getTopicId().equals(call.getArgument(0))).toList());
        when(nodeMapper.selectCmsTopicNodeById(anyLong())).thenAnswer(call->nodes.get(call.getArgument(0)));
        when(nodeMapper.deleteCmsTopicNodeById(anyLong())).thenAnswer(call -> nodes.remove(call.getArgument(0)) == null ? 0 : 1);
        when(chapterMapper.deleteCmsTopicChapterById(anyLong())).thenAnswer(call -> chapters.remove(call.getArgument(0)) == null ? 0 : 1);
        when(templateMapper.selectCmsMemberTemplateById(anyLong())).thenAnswer(call->templates.get(call.getArgument(0)));
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenAnswer(call->{List<CmsMemberTemplate> result=new ArrayList<>();for(Long id:(List<Long>)call.getArgument(0))if(templates.containsKey(id))result.add(templates.get(id));return result;});
        when(templateMapper.selectCmsMemberTemplateByIdsForUpdate(any())).thenAnswer(call -> {
            List<Long> ids=call.getArgument(0);
            return ids.stream().map(templates::get).filter(Objects::nonNull).toList();
        });
        for(JsonNode raw:resource("reference.json").path("templates")) {
            CmsMemberTemplate template=new CmsMemberTemplate();template.setId(raw.path("id").asLong());template.setMemberId(7L);template.setStatus(1L);template.setDraftStatus(1);template.setTitle(raw.path("title").asText());template.setValidationMethod(raw.path("validationMethod").asInt());template.setAdvancedConfigJson(new AdvancedGameConfigValidator().validateAndNormalize(raw.path("config").toString()));template.setQuestionAnswer(raw.path("answer").asText(null));templates.put(template.getId(),template);
        }
        route=new TopicRouteRuntimeServiceImpl(mock(TopicRouteSessionMapper.class),topicMapper,nodeMapper,templateMapper,mock(PlayerNodeProgressMapper.class),null,mock(CmsRegistrationMapper.class),null,mock(IShopDayProofService.class),()->1L,()->10,Clock.systemUTC(),RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(route,"cmsTopicChapterMapper",chapterMapper);ReflectionTestUtils.setField(route,"themeStateKeyMapper",keyMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(publisher, "topicRouteRuntimeService",route);
        activeTopics=topicMapper;activeGames=gameMapper;configureRuntime();
        // Use the same platform transaction/proxy mechanism as useSql; connections remain unit doubles.
        javax.sql.DataSource data=mock(javax.sql.DataSource.class);
        when(data.getConnection()).thenAnswer(call -> {
            java.sql.Connection connection=mock(java.sql.Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);return connection;
        });
        var tx=new org.springframework.jdbc.datasource.DataSourceTransactionManager(data);
        var advice=new org.springframework.transaction.interceptor.TransactionInterceptor(tx,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var keyProxy=new org.springframework.aop.framework.ProxyFactory(keyService);keyProxy.setProxyTargetClass(true);keyProxy.addAdvice(advice);
        keyService=(ThemeStateKeyService)keyProxy.getProxy();
        ReflectionTestUtils.setField(publisher,"themeStateKeyService",keyService);
        var authorProxy=new org.springframework.aop.framework.ProxyFactory(publisher);authorProxy.setInterfaces(ICmsTopicService.class);authorProxy.addAdvice(advice);
        authoring=(ICmsTopicService)authorProxy.getProxy();
        var gameProxy=new org.springframework.aop.framework.ProxyFactory(runtime);gameProxy.setInterfaces(IGameSessionRuntimeService.class);gameProxy.addAdvice(advice);
        play=(IGameSessionRuntimeService)gameProxy.getProxy();
    }

    /** Unit model of ThemeStateKeyMapper.xml: unique (topic_id,state_key), generated id, id order. */
    private int persistDeclaration(ThemeStateKey input) {
        assertNotNull(input.getTopicId());
        assertTrue(topics.containsKey(input.getTopicId()), "声明必须属于实际已创建的主题");
        Map<String, ThemeStateKey> rows = declarations.computeIfAbsent(input.getTopicId(), ignored -> new LinkedHashMap<>());
        ThemeStateKey previous = rows.get(input.getStateKey());
        ThemeStateKey next = copyDeclaration(input);
        if (previous == null) {
            next.setId(++declarationSequence);
            input.setId(next.getId()); // Same insert-generated identity consumed by the real writer.
        } else {
            // Duplicate registration does not replace identity/type/scope/create_time.
            next.setId(previous.getId());
            next.setKeyType(previous.getKeyType());
            next.setScope(previous.getScope());
            next.setCreateTime(previous.getCreateTime());
            if (next.getMinValue() == null) next.setMinValue(previous.getMinValue());
            if (next.getMaxValue() == null) next.setMaxValue(previous.getMaxValue());
            if (next.getDisplayType() == null) next.setDisplayType(previous.getDisplayType());
            if (next.getColor() == null) next.setColor(previous.getColor());
            if (next.getIcon() == null) next.setIcon(previous.getIcon());
            if (next.getSortOrder() == null) next.setSortOrder(previous.getSortOrder());
            if (next.getVisible() == null) next.setVisible(previous.getVisible());
            if (next.getPersistScope() == null) next.setPersistScope(previous.getPersistScope());
            if (next.getRulesJson() == null) next.setRulesJson(previous.getRulesJson());
        }
        rows.put(next.getStateKey(), copyDeclaration(next));
        return previous == null ? 1 : 2;
    }

    private List<ThemeStateKey> storedDeclarations(Long topicId) {
        Map<String, ThemeStateKey> rows = declarations.get(topicId);
        if (rows == null) return new ArrayList<>();
        List<ThemeStateKey> result = new ArrayList<>();
        for (ThemeStateKey row : rows.values()) result.add(copyDeclaration(row));
        result.sort(Comparator.comparing(ThemeStateKey::getId));
        return result;
    }

    private ThemeStateKey storedDeclaration(Long topicId, String stateKey) {
        Map<String, ThemeStateKey> rows = declarations.get(topicId);
        ThemeStateKey row = rows == null ? null : rows.get(stateKey);
        return row == null ? null : copyDeclaration(row);
    }

    private static ThemeStateKey copyDeclaration(ThemeStateKey row) {
        ThemeStateKey copy = new ThemeStateKey();
        org.springframework.beans.BeanUtils.copyProperties(row, copy);
        if (row.getCreateTime() != null) copy.setCreateTime(new Date(row.getCreateTime().getTime()));
        if (row.getUpdateTime() != null) copy.setUpdateTime(new Date(row.getUpdateTime().getTime()));
        return copy;
    }

    void registerDeclarations(Long topicId, List<ThemeStateKey> definitions) {
        List<ThemeStateKey> inputs = new ArrayList<>();
        for (ThemeStateKey definition : definitions) inputs.add(copyDeclaration(definition));
        keyService.register(topicId, inputs); // Real validation, parent fence and original Spring transaction.
    }

    private void wireAuthoringGuards(CmsTopicMapper topics, CmsTopicChapterMapper chapters,
                                     CmsTopicNodeMapper nodes, CmsMemberTemplateMapper templates,
                                     OmsTicketMapper tickets, MerchantChapterOfferMapper offers) {
        AppMediaPublicationGuard media = MediaPublicationTestFixtures.template(7L, 1L);
        if (sql != null) {
            AppMediaReferenceGuard references = new AppMediaReferenceGuard();
            ReflectionTestUtils.setField(references, "mapper", sql.getMapper(AppMediaAssetMapper.class));
            ReflectionTestUtils.setField(media, "references", references);
        }
        ReflectionTestUtils.setField(media, "topics", topics);
        ReflectionTestUtils.setField(media, "chapters", chapters);
        ReflectionTestUtils.setField(media, "nodes", nodes);
        ReflectionTestUtils.setField(media, "templates", templates);
        ReflectionTestUtils.setField(publisher, "mediaPublicationGuard", media);
        ReflectionTestUtils.setField(publisher, "commercialTopicPolicy", new CommercialTopicPolicy(tickets, chapters, offers, topics));
        ReflectionTestUtils.setField(publisher, "supplyReviewService", new TopicSupplyReviewService(
                mock(AuditTaskMapper.class), mock(AuditRecordMapper.class), topics, chapters));
    }

    TopicCreateDTO definition(JsonNode skin) throws Exception {
        ObjectNode author=resource("reference.json").with("themeRuntimeConfig").deepCopy();
        author.put("hpName",skin.path("hpName").asText()).put("actionName",skin.path("actionName").asText()).put("actionLimit",skin.path("actionLimit").asInt());
        int item=0;for(JsonNode row:author.path("items"))((ObjectNode)row).put("name",skin.path("itemNames").path(item++).asText());
        ((ObjectNode)author.path("items").path(0)).put("delta",skin.path("rationDelta").asInt());
        ((ObjectNode)author.path("gameplayRuntime").path("survival").path("zeroChallenge")).put("restoreTo",skin.path("restoreTo").asInt());
        ObjectNode facility=(ObjectNode)author.path("gameplayRuntime").path("facilities").path(0);facility.put("hpDelta",skin.path("facilityDelta").asInt());
        ((ObjectNode)facility.path("resourceCosts").path(0)).put("itemId",skin.path("facilityItem").asText()).put("quantity",skin.path("facilityCost").asInt());
        int ending=0;for(JsonNode row:author.path("endings"))((ObjectNode)row).put("title",skin.path("endingNames").path(ending++).asText());
        for(JsonNode quest:author.path("gameplayRuntime").path("quests")) {
            JsonNode numbers=skin.path("questNumbers").path(quest.path("questKey").asText());
            numbers.fields().forEachRemaining(entry->{assertTrue(entry.getValue().isInt());((ObjectNode)quest).set(entry.getKey(),entry.getValue());});
        }
        int endingCount=skin.path("endingCount").asInt(4);
        ArrayNode endings=JSON.createArrayNode();for(int i=0;i<endingCount;i++)endings.add(author.path("endings").path(i));endings.add(author.path("endings").path(4));author.set("endings",endings);
        ObjectNode vote=JSON.readTree(templates.get(503L).getAdvancedConfigJson()).deepCopy();
        ArrayNode plans=JSON.createArrayNode();for(int i=0;i<endingCount;i++) {ObjectNode plan=vote.path("interactionTools").path("plans").path(i).deepCopy();plan.put("title",skin.path("endingNames").path(i).asText());plans.add(plan);}
        vote.with("interactionTools").set("plans",plans);
        author.put("currentScene","reference");ObjectNode scene=author.putArray("scenes").addObject().put("id","reference").put("name","本局配置");scene.putObject("nodeConfigs").set("7001",vote);
        ObjectNode bindings=author.putObject("templateBindings");
        for(JsonNode ch:resource("reference.json").path("chapters"))for(JsonNode node:ch.path("nodes")) {
            CmsMemberTemplate template=templates.get(node.path("templateId").asLong());if(template==null)continue;
            JsonNode config=JSON.readTree(template.getAdvancedConfigJson()),slots=config.path("interactionRules").path("resourceSlots");if(!slots.isArray())continue;
            ObjectNode binding=bindings.putObject(node.path("id").asText()).put("templateId",template.getId()).put("slotDigest",GameplayTemplateRules.slotDigest(slots));
            binding.putObject("values");binding.putObject("items");
            for(JsonNode slot:slots)if("VALUE".equals(slot.path("kind").asText()))binding.with("values").put(slot.path("key").asText(),"sys.hp");
            else binding.with("items").put(slot.path("key").asText(),"aid".equals(slot.path("key").asText())?"medical":"food".equals(slot.path("key").asText())?"ration":slot.path("key").asText());
        }
        ObjectNode rules=JSON.createObjectNode().put("schemaVersion",1).put("stateEnabled",true).put("checkEnabled",false);
        rules.putObject("hp").put("enabled",true).put("init",skin.path("initialHp").asInt()).put("max",skin.path("maxHp").asInt());rules.putObject("luck").put("enabled",false);
        rules.putObject("recovery").put("stationEndIfHurt",0).put("stageRestHpFloor",0).put("stageRestLuckFloor",0);
        TopicCreateDTO dto=new TopicCreateDTO();dto.setName(skin.path("name").asText());dto.setSaveDraft(1);dto.setPublishMode("simple");dto.setProductType(1);
        dto.setThemeRuntimeConfig(author.toString());dto.setJourneyRules(rules.toString());
        dto.setChapters(Arrays.asList(JSON.treeToValue(resource("reference.json").path("chapters"),ChapterDTO[].class)));
        return dto;
    }
    void publish(JsonNode skin) throws Exception {
        variant=skin;
        TopicCreateDTO identity=new TopicCreateDTO();identity.setName(skin.path("name").asText());identity.setSaveDraft(1);identity.setPublishMode("simple");identity.setProductType(1);
        Long id=com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(authoring).createTopic(identity,7L,"配置作者");
        registerDeclarations(id,keys);
        TopicUpdateDTO update=JSON.convertValue(definition(skin),TopicUpdateDTO.class);update.setId(id);
        assertThrows(RuntimeException.class,()->com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(authoring).updateTopicByOwner(update,8L,"外来作者"));
        assertEquals(id,com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(authoring).updateTopicByOwner(update,7L,"配置作者"));
        topic=activeTopics.selectCmsTopicById(id);assertNotNull(topic);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(authoring).publishDraft(id));topic=activeTopics.selectCmsTopicById(id);snapshot=JSON.readTree(topic.getPublishedRouteGraphJson());
        assertFalse(snapshot.toString().contains("resourceSlots"));assertFalse(snapshot.toString().contains("templateBindings"));
        assertSameFrozenTheme(snapshot.path("themeConfig"),JSON.readTree(route.freezePublishedJourney(topic)).path("themeConfig"));
    }

    static void assertSameFrozenTheme(JsonNode expected, JsonNode actual) {
        assertEquals(comparableFrozenTheme(expected), comparableFrozenTheme(actual));
    }

    /** 生成设施由对象键重建，MySQL JSON 不保留对象键序；其余字段及所有内部数组仍严格比较。 */
    private static ObjectNode comparableFrozenTheme(JsonNode theme) {
        assertTrue(theme.isObject(), "冻结主题必须为对象");
        ObjectNode copy = theme.deepCopy();
        JsonNode facilities = copy.path("gameplayRuntime").path("facilities");
        assertTrue(facilities.isArray(), "冻结设施必须为数组");
        ArrayNode ordered = JSON.createArrayNode();
        Map<String, JsonNode> generated = new TreeMap<>();
        Set<String> keys = new HashSet<>();
        boolean generatedSeen = false;
        for (JsonNode facility : facilities) {
            assertTrue(facility.isObject(), "设施必须为对象");
            String key = facility.path("facilityKey").asText();
            assertFalse(key.isEmpty(), "设施标识不能为空");
            assertTrue(keys.add(key), "设施标识重复: " + key);
            if (key.startsWith("tpl_node_")) {
                generatedSeen = true;
                assertTrue(facility.path("operationNodeId").isIntegralNumber(), "生成设施缺少真实节点绑定");
                assertEquals("tpl_node_" + facility.path("operationNodeId").asLong(), key);
                assertEquals(key, facility.path("settlementSlot").asText(), "生成设施槽必须匹配节点");
                assertFalse(facility.path("actKey").asText().isEmpty(), "生成设施缺少真实幕绑定");
                generated.put(key, facility);
            } else {
                assertFalse(generatedSeen, "手工设施须保持在生成设施之前");
                ordered.add(facility);
            }
        }
        generated.values().forEach(ordered::add);
        ((ObjectNode) copy.path("gameplayRuntime")).set("facilities", ordered);
        return copy;
    }
    private void configureRuntime() {
        ReflectionTestUtils.setField(runtime,"runtimeMapper",gameMapper);ReflectionTestUtils.setField(runtime,"playTeamMapper",teamMapper);ReflectionTestUtils.setField(runtime,"topicRouteRuntimeService",route);
        CmsActivityMapper activities=mock(CmsActivityMapper.class);ReflectionTestUtils.setField(runtime,"activityMapper",activities);
        when(activities.selectCmsActivityById(70L)).thenAnswer(call->{CmsActivity activity=new CmsActivity();activity.setId(70L);activity.setTopicId(topic.getId());activity.setClubId(40L);activity.setStatus(1L);activity.setDelFlag(0);activity.setStartDate(new Date(System.currentTimeMillis()-3600000));activity.setEndDate(new Date(System.currentTimeMillis()+3600000));return activity;});
        IPlayerNodeProgressService progress=mock(IPlayerNodeProgressService.class);ReflectionTestUtils.setField(runtime,"playerNodeProgressService",progress);
        when(progress.selectPlayerNodeProgressList(any())).thenAnswer(call->{PlayerNodeProgress row=call.getArgument(0);row.setCheckinTime(new Date());return Collections.singletonList(row);});
        IClubAccessService access=mock(IClubAccessService.class);ReflectionTestUtils.setField(runtime,"clubAccessService",access);
        when(access.require(eq(88L),eq(40L),eq(70L),anyString())).thenReturn(new ClubAccessContext(88L,40L,88L,70L,Collections.singleton(ClubRoleCode.EVENT_LEAD)));
        when(gameMapper.selectSessionByActivity(70L)).thenAnswer(call->session);when(gameMapper.selectSessionByActivityForUpdate(70L)).thenAnswer(call->session);
        when(teamMapper.hasPaidRegistration(anyLong(),eq(2),eq(70L))).thenReturn(1);
        when(gameMapper.selectActiveTeamId(eq(70L),anyLong())).thenAnswer(call->team(call.getArgument(1)));
        when(gameMapper.isActiveTeam(eq(70L),anyLong())).thenReturn(1);when(gameMapper.isActiveTeamMember(eq(70L),anyLong(),anyLong())).thenReturn(1);
        when(teamMapper.selectPlayTeamById(anyLong())).thenAnswer(call->teamRow(call.getArgument(0)));when(teamMapper.selectPlayTeamByIdForUpdate(anyLong())).thenAnswer(call->teamRow(call.getArgument(0)));
        when(gameMapper.selectStoryState(eq(50L),anyString(),anyLong())).thenAnswer(call->owners.get(call.getArgument(1)+":"+call.getArgument(2)));
        when(gameMapper.selectStoryStates(50L)).thenAnswer(call->new ArrayList<>(owners.values()));
        when(gameMapper.updateStoryStateCas(any())).thenAnswer(call->{GameStoryState row=call.getArgument(0);row.setRevision(row.getRevision()+1);owners.put(row.getOwnerType()+":"+row.getOwnerId(),row);return 1;});
        when(gameMapper.insertStoryState(any())).thenAnswer(call->{GameStoryState row=call.getArgument(0);row.setId(++sequence);owners.put(row.getOwnerType()+":"+row.getOwnerId(),row);return 1;});
        when(gameMapper.updateSessionCas(anyLong(),anyInt(),anyString(),anyString(),any(),any(),any())).thenReturn(1);
        when(gameMapper.selectReceipt(eq(70L),anyLong(),anyString())).thenAnswer(call->receipts.get(call.getArgument(1)+":"+call.getArgument(2)));
        when(gameMapper.selectReceiptForUpdate(eq(70L),anyLong(),anyString())).thenAnswer(call->receipts.get(call.getArgument(1)+":"+call.getArgument(2)));
        when(gameMapper.insertReceiptClaim(any())).thenAnswer(call->{GameCommandReceipt row=call.getArgument(0);row.setId(++sequence);receipts.put(row.getActorMemberId()+":"+row.getRequestId(),row);return 1;});when(gameMapper.completeReceipt(any())).thenReturn(1);
        when(gameMapper.selectStation(eq(50L),anyLong())).thenAnswer(call->{GameStationRun station=new GameStationRun();station.setGameSessionId(50L);station.setActivityId(70L);station.setNodeId(call.getArgument(1));station.setStatus("ACTIVE");station.setSourceType("CHAPTER_NODE");station.setMerchantMemberId(30L);station.setServiceStartAt(new Date(System.currentTimeMillis()-3600000));station.setServiceEndAt(new Date(System.currentTimeMillis()+3600000));return station;});
    }
    void useSql(org.mybatis.spring.SqlSessionTemplate database) {
        sql=database;activeTopics=sql.getMapper(CmsTopicMapper.class);activeGames=sql.getMapper(GameSessionRuntimeMapper.class);
        CmsTopicNodeMapper realNodes=sql.getMapper(CmsTopicNodeMapper.class);CmsTopicChapterMapper realChapters=sql.getMapper(CmsTopicChapterMapper.class);CmsMemberTemplateMapper realTemplates=sql.getMapper(CmsMemberTemplateMapper.class);
        ThemeStateKeyMapper realKeys=sql.getMapper(ThemeStateKeyMapper.class);
        var keysProxy = new org.springframework.aop.framework.ProxyFactory(new ThemeStateKeyService(realKeys));
        keysProxy.setProxyTargetClass(true);
        keysProxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(sql.getConfiguration().getEnvironment().getDataSource()),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        keyService=(ThemeStateKeyService)keysProxy.getProxy();
        ReflectionTestUtils.setField(publisher,"cmsTopicMapper",activeTopics);ReflectionTestUtils.setField(publisher,"cmsTopicNodeMapper",realNodes);ReflectionTestUtils.setField(publisher,"cmsTopicChapterMapper",realChapters);com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(publisher, "cmsMemberTemplateMapper",realTemplates);
        wireAuthoringGuards(activeTopics, realChapters, realNodes, realTemplates,
                sql.getMapper(OmsTicketMapper.class), sql.getMapper(MerchantChapterOfferMapper.class));
        ReflectionTestUtils.setField(publisher,"themeStateKeyService",keyService);com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(publisher, "topicStateKeyRefService",new TopicStateKeyRefService(realNodes,realTemplates,realChapters));
        ReflectionTestUtils.setField(route,"topicMapper",activeTopics);ReflectionTestUtils.setField(route,"nodeMapper",realNodes);ReflectionTestUtils.setField(route,"cmsTopicChapterMapper",realChapters);ReflectionTestUtils.setField(route,"templateMapper",realTemplates);ReflectionTestUtils.setField(route,"themeStateKeyMapper",realKeys);
        ReflectionTestUtils.setField(runtime,"runtimeMapper",activeGames);ReflectionTestUtils.setField(runtime,"playTeamMapper",sql.getMapper(PlayTeamMapper.class));
        org.springframework.jdbc.datasource.DataSourceTransactionManager tx=new org.springframework.jdbc.datasource.DataSourceTransactionManager(sql.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource());
        org.springframework.transaction.interceptor.TransactionInterceptor advice=new org.springframework.transaction.interceptor.TransactionInterceptor(tx,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        org.springframework.aop.framework.ProxyFactory authorProxy=new org.springframework.aop.framework.ProxyFactory(publisher);authorProxy.setInterfaces(ICmsTopicService.class);authorProxy.addAdvice(advice);authoring=(ICmsTopicService)authorProxy.getProxy();
        org.springframework.aop.framework.ProxyFactory gameProxy=new org.springframework.aop.framework.ProxyFactory(runtime);gameProxy.setInterfaces(IGameSessionRuntimeService.class);gameProxy.addAdvice(advice);play=(IGameSessionRuntimeService)gameProxy.getProxy();
    }
    private void persistRun() {
        org.springframework.jdbc.core.JdbcTemplate db=new org.springframework.jdbc.core.JdbcTemplate(sql.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource());
        for(String table:Arrays.asList("game_command_receipt","game_story_state","game_station_run","game_session","play_team_member","play_team","cms_registration","cms_activity"))db.update("DELETE FROM "+table);
        db.update("INSERT INTO cms_activity(id,topic_id) VALUES(70,?)",topic.getId());
        db.update("INSERT INTO play_team(id,owner_type,owner_id,leader_member_id,status) VALUES(90,2,70,7,2),(99,2,70,9,2),(101,2,70,11,2)");
        db.update("INSERT INTO play_team_member(team_id,member_id,status) VALUES(90,7,1),(90,8,1),(99,9,1),(101,11,1)");
        for(long member:new long[]{7,8,9,11})db.update("INSERT INTO cms_registration(member_id,owner_type,owner_id,topic_id,payment_status,registration_status) VALUES(?,2,70,?,2,2)",member,topic.getId());
        db.update("INSERT INTO game_session(id,activity_id,topic_id,club_id,module_config_snapshot_json,status,revision,prepared_by,create_time,update_time) VALUES(50,70,?,40,?,'RUNNING',0,7,NOW(),NOW())",topic.getId(),session.getModuleConfigSnapshotJson());
        for(JsonNode node:snapshot.path("routeStations"))db.update("INSERT INTO game_station_run(game_session_id,activity_id,node_id,node_name,station_code,merchant_member_id,source_type,source_id,status,revision,service_start_at,service_end_at,create_time,update_time) VALUES(50,70,?,?,?,30,'CHAPTER_NODE',?,'ACTIVE',0,?,?,NOW(),NOW())",node.path("nodeId").asLong(),node.path("name").asText("任务"),"N"+node.path("nodeId").asLong(),node.path("nodeId").asLong(),new Date(System.currentTimeMillis()-3600000),new Date(System.currentTimeMillis()+3600000));
    }
    static long team(long member) {return member==7||member==8?90:member==9?99:101;}
    private PlayTeam teamRow(long id) {PlayTeam row=new PlayTeam();row.setId(id);row.setOwnerType(2);row.setOwnerId(70L);row.setStatus(2);row.setLeaderMemberId(id==90?7L:id==99?9L:11L);return row;}
    void startRun() throws Exception {
        ObjectNode frozen=snapshot.deepCopy();frozen.put("playMode","ROUTE");
        session=new GameSession();session.setId(50L);session.setActivityId(70L);session.setTopicId(topic.getId());session.setClubId(40L);session.setStatus("RUNNING");session.setRevision(0);session.setModuleConfigSnapshotJson(frozen.toString());
        if(sql!=null)persistRun();
        for(long id:new long[]{90,99,101}) ReflectionTestUtils.invokeMethod(runtime,"initializeRouteOwner",session,"TEAM",id);
        ReflectionTestUtils.invokeMethod(runtime,"initializeRouteOwner",session,"WORLD",50L);
        store("RUN",50,JSON.createObjectNode().put("operationStatus","RUNNING").put("authorityMode","ONLINE").put("authorityEpoch",1).put("eventSeq",0));
    }
    private void store(String type,long id,Object state) throws Exception {
        GameStoryState row=new GameStoryState();row.setId(++sequence);row.setGameSessionId(50L);row.setOwnerType(type);row.setOwnerId(id);row.setRevision(0);row.setStatus("INITIAL");row.setAppliedChoicesJson("[]");row.setCreateTime(new Date());row.setUpdateTime(new Date());row.setVariablesJson(JSON.writeValueAsString(state));if(sql==null)owners.put(type+":"+id,row);else assertEquals(1,activeGames.insertStoryState(row));
    }
    JsonNode state(long member) throws Exception {return JSON.readTree(activeGames.selectStoryState(50L,"TEAM",team(member)).getVariablesJson());}
    JsonNode world() throws Exception {return JSON.readTree(activeGames.selectStoryState(50L,"WORLD",50L).getVariablesJson()).path("themeRuntime").path("cooperation");}
    long node(long original) {for(JsonNode q:snapshot.path("themeConfig").path("gameplayRuntime").path("quests"))if(q.path("questKey").asText().equals("q"+original))return q.path("nodeId").asLong();throw new IllegalArgumentException("未知节点"+original);}
    GameCommandReceiptView command(long member,String action,ObjectNode payload) {
        if(sql!=null)session=activeGames.selectSessionByActivity(70L);
        GameSessionCommand command=new GameSessionCommand();command.setActivityId(70L);command.setExpectedRevision(session.getRevision());command.setAuthorityEpoch(1);command.setRequestId("command-"+(++request));command.setAction(action);command.setPayload(JSON.convertValue(payload,Map.class));return play.command(member==88?GameActorContext.club(member):GameActorContext.player(member),command);
    }
    ObjectNode payload() {return JSON.createObjectNode();}
    void unlock(String act) {command(88,"ACT_UNLOCK",payload().put("actKey",act).put("reason","主持确认到齐"));}
    GameCommandReceiptView resolve(long member,long original,String choice,Integer costOption) {
        ObjectNode p=payload().put("questKey","q"+original);if(costOption!=null)p.put("costOption",costOption);
        command(member,"QUEST_START",p);
        long node=node(original);String instance=play.sharedRouteSnapshot(member,topic.getId(),70L,node).path("runtimeInstance").asText();
        play.recordSharedRouteResult(member==7?8L:member,topic.getId(),70L,node,instance,"answer-"+(++request),true,Collections.emptyList(),1);
        if(choice!=null)p.put("choiceKey",choice);return command(member,"QUEST_RESOLVE",p);
    }
    void scriptedPath(int rescueOption,boolean question) throws Exception {
        startRun();unlock("a1");
        for(long member:new long[]{7,9,11})resolve(member,1001,"aid",null);
        unlock("a2");for(long member:new long[]{7,9,11})resolve(member,2001,null,null);
        for(long member:new long[]{7,9,11}) {
            resolve(member,3001,"aid",null);assertEquals(2,act(member));
            resolve(member,3002,"power",null);assertEquals(3,act(member));
            assertFalse(state(member).path("themeRuntime").path("gameplay").path("extraSlots").has("mobile"));
        }
        resolve(7,3003,"power",null);assertEquals(3,act(7));
        assertEquals("q3003",state(7).path("themeRuntime").path("gameplay").path("extraSlots").path("mobile").asText());
        assertThrows(GameSessionException.class,()->command(7,"QUEST_START",payload().put("questKey","q4001")));
        int aid=stock(7,"medical"),food=stock(7,"ration");
        GameCommandReceiptView rescue=resolve(7,4001,null,rescueOption);
        assertEquals(rescueOption,((Number)rescue.getResult().get("costOption")).intValue());
        assertEquals(aid-(rescueOption==0?1:0),stock(7,"medical"));assertEquals(food-(rescueOption==1?2:0),stock(7,"ration"));
        String before=state(7).toString();
        assertThrows(GameSessionException.class,()->command(7,"QUEST_RESOLVE",payload().put("questKey","q4001").put("costOption",1-rescueOption)));
        assertEquals(before,state(7).toString());
        command(7,"QUEST_RESOLVE",payload().put("questKey","q4001").put("costOption",rescueOption));assertEquals(before,state(7).toString());
        assertThrows(GameSessionException.class,()->command(7,"QUEST_START",payload().put("questKey","q4002")));
        int otherAid=stock(9,"medical"),otherHp=hp(9);
        GameCommandReceiptView lost=command(9,"QUEST_START",payload().put("questKey","q4001").put("costOption",0));
        assertEquals(Boolean.TRUE,lost.getResult().get("competitionLost"));assertEquals(otherAid,stock(9,"medical"));assertEquals(otherHp,hp(9));
        resolve(9,4002,null,null);resolve(11,4003,null,null);
        assertEquals(1,world().path("facilities").path("tpl_node_"+node(4001)).path("count").asInt());
        unlock("a5");resolve(7,5001,null,null);assertEquals(0,hp(7));challenge(question);
        String facilityItem=variant.path("facilityItem").asText();
        int[] temperatures={hp(7),hp(9),hp(11)};int material=stock(9,facilityItem);
        command(9,"FACILITY_USE",payload().put("facilityKey","heating"));
        assertEquals(material-variant.path("facilityCost").asInt(),stock(9,facilityItem));
        int index=0;for(long member:new long[]{7,9,11})assertEquals(Math.min(variant.path("maxHp").asInt(),temperatures[index++]+variant.path("facilityDelta").asInt()),hp(member));
        before=state(7).toString();command(9,"FACILITY_USE",payload().put("facilityKey","heating"));assertEquals(before,state(7).toString());
        assertEquals(1,world().path("facilities").path("public_recovery").path("count").asInt());
        resolve(9,5001,null,null);resolve(11,5001,null,null);
        command(9,"ITEM_USE",payload().put("contentId","ration"));
        int battery=stock(9,"power"),otherBattery=stock(7,"power");
        command(9,"FACILITY_USE",payload().put("facilityKey","verify"));command(7,"FACILITY_USE",payload().put("facilityKey","verify"));
        assertEquals(battery-1,stock(9,"power"));assertEquals(otherBattery,stock(7,"power"));
        assertEquals(1,world().path("facilities").path("verification").path("count").asInt());
        for(long member:new long[]{7,9,11})resolve(member,6001,"parts",null);
        assertThrows(GameSessionException.class,()->command(7,"QUEST_START",payload().put("questKey","q6002")));
        assertEquals("DISCOVERED_LOCKED",state(7).path("nodeStates").path(String.valueOf(node(6002))).asText());
        assertEquals(variant.path("actionLimit").asInt(),state(7).path("variables").path("sys.actions").asInt());
        command(7,"PLAN_PROPOSE",payload().put("proposalKey","operation").put("endingKey","end03"));
        for(long member:new long[]{7,9}) {
            ObjectNode p=payload().put("proposalKey","operation").put("version",1);p.putArray("amounts").addObject().put("itemId","power").put("quantity",1);p.withArray("amounts").addObject().put("itemId","parts").put("quantity",1);command(member,"PLAN_INTENT",p);
        }
        command(7,"VOTE_OPEN",payload());int round=world().path("round").asInt();
        for(long member:new long[]{7,9,11})command(member,"VOTE_CAST",payload().put("round",round).put("proposalKey","operation"));
        assertEquals("SELECTED",world().path("stage").asText());
        for(long member:new long[]{7,9})command(member,"PLAN_CONFIRM",payload().put("round",round).put("proposalKey","operation"));
        int[] stocks={stock(7,"power"),stock(7,"parts"),stock(9,"power"),stock(9,"parts")};
        assertThrows(GameSessionException.class,()->command(7,"PLAN_EXECUTE",payload().put("round",round).put("proposalKey","operation")));
        command(7,"OPERATION_START",payload().put("operationKind","PLAN").put("operationKey","end03"));
        long operation=node(7001);String instance=play.sharedRouteSnapshot(8L,topic.getId(),70L,operation).path("runtimeInstance").asText();
        play.recordSharedRouteResult(8L,topic.getId(),70L,operation,instance,"final-operation",true,Collections.emptyList(),1);
        command(7,"PLAN_EXECUTE",payload().put("round",round).put("proposalKey","operation"));
        assertEquals("end03",world().path("result").path("endingKey").asText());
        assertEquals(stocks[0]-1,stock(7,"power"));assertEquals(stocks[1]-1,stock(7,"parts"));assertEquals(stocks[2]-1,stock(9,"power"));assertEquals(stocks[3]-1,stock(9,"parts"));
        assertThrows(GameSessionException.class,()->command(7,"PLAN_EXECUTE",payload().put("round",round).put("proposalKey","operation")));
        assertEquals(stocks[0]-1,stock(7,"power"));assertEquals(stocks[2]-1,stock(9,"power"));
    }
    JsonNode challenge(boolean alternative) throws Exception {
        JsonNode policy=snapshot.path("themeConfig").path("gameplayRuntime").path("survival").path("zeroChallenge");
        long node=alternative?policy.path("alternativeNodeIds").path(0).asLong():policy.path("challengeNodeId").asLong();
        String instance=play.sharedRouteSnapshot(7L,topic.getId(),70L,node).path("runtimeInstance").asText();
        int actions=state(7).path("variables").path("sys.actions").asInt();
        JsonNode settled;
        if(alternative)settled=play.recordSharedZeroChallengeResult(8L,topic.getId(),70L,node,instance,"challenge-question",true,null,false,1);
        else {
            JsonNode first=play.recordSharedZeroChallengeResult(7L,topic.getId(),70L,node,instance,"challenge-hits",false,6L,false,1);
            assertFalse(first.path("success").asBoolean());assertEquals(0,hp(7));
            settled=play.recordSharedZeroChallengeResult(8L,topic.getId(),70L,node,instance,"challenge-hits",false,6L,false,1);
            assertEquals(JSON.readTree(first.toString()),JSON.readTree(play.recordSharedZeroChallengeResult(7L,topic.getId(),70L,node,instance,"challenge-hits",false,6L,false,1).toString()));
            assertEquals(JSON.readTree(settled.toString()),JSON.readTree(play.recordSharedZeroChallengeResult(8L,topic.getId(),70L,node,instance,"challenge-hits",false,6L,false,1).toString()));
        }
        assertTrue(settled.path("success").asBoolean());assertEquals(variant.path("restoreTo").asInt(),hp(7));
        assertEquals(actions,state(7).path("variables").path("sys.actions").asInt());
        assertEquals(1,state(7).path("themeRuntime").path("zeroChallenges").path("used").asInt());
        for(JsonNode completed:state(7).path("completedNodeIds"))assertNotEquals(node,completed.asLong());
        return settled;
    }
    int hp(long member) throws Exception {return state(member).path("variables").path("sys.hp").asInt();}
    int stock(long member,String item) throws Exception {return state(member).path("themeRuntime").path("inventory").path(item).asInt();}
    int act(long member) throws Exception {return state(member).path("themeRuntime").path("gameplay").path("actIndex").asInt();}
}

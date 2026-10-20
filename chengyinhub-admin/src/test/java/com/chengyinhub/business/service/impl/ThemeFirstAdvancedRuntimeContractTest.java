package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mapper mocks prove namespace and call ordering only, never database locking/concurrency. */
class ThemeFirstAdvancedRuntimeContractTest {
    private final ObjectMapper json=new ObjectMapper();
    private final Map<Long,TopicRouteSession> actors=new LinkedHashMap<>();
    private final Map<String,GameRuntimeSession> games=new LinkedHashMap<>();
    private TopicRouteSessionMapper routes;
    private ThemeTeamRunMapper runs;
    private PlayTeamMapper teams;
    private GameRuntimeSessionMapper gameMapper;
    private CmsTopicMapper topics;
    private CmsTopicNodeMapper nodes;
    private CmsActivityMapper activities;
    private PlayAttemptLogMapper attempts;
    private TopicRouteRuntimeServiceImpl runtime;
    private AdvancedGameRuntimeServiceImpl advanced;
    private PlayTeam team;
    private List<PlayTeamMember> members;
    private static final String FIRST="theme-first:10:5";
    private static final String MULTI="{\"schemaVersion\":1,\"multiplayer\":{\"enabled\":true,\"mode\":\"ROLE_BASED\",\"minPlayers\":2,\"maxPlayers\":4,\"assignment\":\"AUTO\",\"requiredTurns\":2,\"roles\":[{\"id\":\"pacer\",\"label\":\"领跑\",\"min\":1,\"max\":2},{\"id\":\"closer\",\"label\":\"收尾\",\"min\":1,\"max\":2}],\"turnOrder\":[\"pacer\",\"closer\"]}}";

    @BeforeEach void setup() throws Exception {
        actors.clear();games.clear();
        routes=mock(TopicRouteSessionMapper.class);runs=mock(ThemeTeamRunMapper.class);teams=mock(PlayTeamMapper.class);
        topics=mock(CmsTopicMapper.class);nodes=mock(CmsTopicNodeMapper.class);activities=mock(CmsActivityMapper.class);
        runtime=spy(new TopicRouteRuntimeServiceImpl(routes,topics,nodes,mock(PlayerNodeProgressMapper.class),activities,mock(CmsRegistrationMapper.class)));
        ThemeRuntimeFeaturePolicy policy=new ThemeRuntimeFeaturePolicy();ReflectionTestUtils.setField(policy,"enabled",true);
        ReflectionTestUtils.setField(runtime,"themeRuntimePolicy",policy);ReflectionTestUtils.setField(runtime,"themeTeamRunMapper",runs);
        ReflectionTestUtils.setField(runtime,"themeTeamCoordinator",new ThemeTeamRunCoordinator(teams,runs));
        team=new PlayTeam();team.setId(33L);team.setOwnerType(2);team.setOwnerId(5L);team.setStatus(0);team.setLeaderMemberId(7L);
        members=new ArrayList<>(Arrays.asList(member(7),member(8)));
        when(teams.selectMyTeams(anyLong())).thenReturn(Collections.singletonList(team));
        when(teams.selectPlayTeamById(33L)).thenReturn(team);when(teams.selectPlayTeamByIdForUpdate(33L)).thenReturn(team);
        when(teams.selectActiveMembers(33L)).thenAnswer(i->new ArrayList<>(members));
        when(teams.selectActiveMembersForUpdate(33L)).thenAnswer(i->new ArrayList<>(members));
        when(teams.selectActiveMember(eq(33L),anyLong())).thenAnswer(i->members.stream().filter(m->m.getMemberId().equals(i.getArgument(1))).findFirst().orElse(null));
        when(teams.hasPaidRegistration(anyLong(),eq(2),eq(5L))).thenReturn(1);
        when(routes.selectCurrentByOwner(eq(5L),eq(10L),anyLong())).thenAnswer(i->actors.get(i.getArgument(2)));
        when(routes.selectCurrentByOwnerForUpdate(eq(5L),eq(10L),anyLong())).thenAnswer(i->actors.get(i.getArgument(2)));
        when(routes.selectByOwner(eq(5L),eq(10L),anyLong(),anyString())).thenAnswer(i->owned(i.getArgument(2),i.getArgument(3)));
        when(routes.selectByOwnerForUpdate(eq(5L),eq(10L),anyLong(),anyString())).thenAnswer(i->owned(i.getArgument(2),i.getArgument(3)));
        CmsTopic topic=new CmsTopic();topic.setId(10L);topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1}");when(topics.selectCmsTopicById(10L)).thenReturn(topic);
        CmsTopicNode node=new CmsTopicNode();node.setId(11L);node.setTopicId(10L);node.setTemplateId(99L);when(nodes.selectCmsTopicNodeById(11L)).thenReturn(node);
        CmsActivity activity=new CmsActivity();activity.setId(5L);activity.setTopicId(10L);activity.setProductType(1);when(activities.selectCmsActivityById(5L)).thenReturn(activity);
        actors.put(7L,first(7,MULTI));actors.put(8L,first(8,MULTI));
        doNothing().when(runtime).requireOpeningAccess(anyLong(),anyLong(),anyLong(),anyLong());
        doNothing().when(runtime).requireOpeningAccessReadOnly(anyLong(),anyLong(),anyLong(),anyLong());
        doReturn(true).when(runtime).canPlay(anyLong(),anyLong(),anyLong(),anyLong());
        doReturn(Collections.emptyMap()).when(runtime).vitals(anyLong(),anyLong(),anyLong());
        doAnswer(i->json.readTree(actors.get(i.getArgument(0)).getConfigSnapshotJson()).path("nodeConfigs").path("11"))
                .when(runtime).nodeRuntimeConfig(anyLong(),eq(10L),eq(11L),eq(5L));
        doReturn(99L).when(runtime).nodeRuntimeTemplateId(anyLong(),eq(10L),eq(11L),eq(5L));
        doNothing().when(runtime).recordNodeResult(anyLong(),anyLong(),anyLong(),anyLong(),anyString(),anyString(),any(),anyBoolean(),anyInt(),anyList());
        gameMapper=mock(GameRuntimeSessionMapper.class);attempts=mock(PlayAttemptLogMapper.class);advanced=new AdvancedGameRuntimeServiceImpl();
        ReflectionTestUtils.setField(advanced,"sessionMapper",gameMapper);ReflectionTestUtils.setField(advanced,"nodeMapper",nodes);
        ReflectionTestUtils.setField(advanced,"templateMapper",mock(CmsMemberTemplateMapper.class));ReflectionTestUtils.setField(advanced,"activityMapper",activities);
        ReflectionTestUtils.setField(advanced,"teamMapper",teams);ReflectionTestUtils.setField(advanced,"attemptMapper",attempts);
        ReflectionTestUtils.setField(advanced,"topicRouteRuntimeService",runtime);ReflectionTestUtils.setField(advanced,"random",(AdvancedGameRandom)bound->0);
        when(gameMapper.insert(any())).thenAnswer(i->{GameRuntimeSession g=i.getArgument(0);g.setId(100L+games.size());games.put(key(g),g);return 1;});
        when(gameMapper.selectByOwnerForInstance(eq(5L),eq(10L),eq(11L),anyInt(),anyLong(),anyString()))
                .thenAnswer(i->games.get(i.getArgument(3)+":"+i.getArgument(4)+":"+i.getArgument(5)));
        when(gameMapper.selectTeamByMemberForInstance(eq(5L),eq(10L),eq(11L),anyLong(),eq(33L),anyString()))
                .thenAnswer(i->games.get("2:33:"+i.getArgument(5)));
        when(gameMapper.selectPlayerByOwnerForInstance(eq(5L),eq(10L),eq(11L),anyLong(),anyString()))
                .thenAnswer(i->games.get("1:"+i.getArgument(3)+":"+i.getArgument(4)));
        when(gameMapper.selectAccessibleById(anyLong(),anyLong())).thenAnswer(i->games.values().stream().filter(g->g.getId().equals(i.getArgument(0))).findFirst().orElse(null));
        when(gameMapper.selectAccessibleByIdForUpdate(anyLong(),anyLong())).thenAnswer(i->games.values().stream().filter(g->g.getId().equals(i.getArgument(0))).findFirst().orElse(null));
        when(gameMapper.updateStateCas(any())).thenReturn(1);when(gameMapper.restartTerminalCas(any())).thenReturn(1);
        Map<String,PlayAttemptLog> receipts=new LinkedHashMap<>();
        when(attempts.selectByIdempotencyKey(anyString())).thenAnswer(i->receipts.get(i.getArgument(0)));
        when(attempts.insertPlayAttemptLog(any())).thenAnswer(i->{PlayAttemptLog log=i.getArgument(0);receipts.put(log.getIdempotencyKey(),log);return 1;});
    }
    private PlayTeamMember member(long id){PlayTeamMember m=new PlayTeamMember();m.setTeamId(33L);m.setMemberId(id);m.setStatus(PlayTeamMember.STATUS_JOINED);return m;}
    private TopicRouteSession first(long owner,String config)throws Exception {
        TopicRouteSession s=new TopicRouteSession();s.setMemberId(owner);s.setTopicId(10L);s.setActivityId(5L);s.setVersion(0);s.setStatus(0);s.setStateJson("{}");
        ObjectNode root=json.createObjectNode();root.putObject("themeConfig");root.putObject("nodeConfigs").set("11",json.readTree(config));root.putObject("nodeTemplateIds").put("11",99L);
        root.putObject("nodeJourney").putObject("11");root.putObject("nodeAnswers").putObject("11").put("validationMethod",1).put("questionAnswer","frozen-answer");
        s.setConfigSnapshotJson(root.toString());ThemeFirstIdentity.stampNew(s);s.setId(1000L+owner);return s;
    }
    private TopicRouteSession owned(Long member,String instance){TopicRouteSession s=actors.get(member);return s!=null&&instance.equals(s.getPlayInstanceId())?s:null;}
    private String key(GameRuntimeSession g){return g.getOwnerType()+":"+g.getOwnerId()+":"+g.getPlayInstanceId();}
    private ITopicRouteRuntimeService.TeamGameScope scope(Long member,Long node,Long team,boolean write){return runtime.resolveTeamGameScope(member,10L,5L,node,team,write);}
    private GameRuntimeSession shared(){return games.get("2:33:"+FIRST);}

    @Test void firstActorsShareTeamNamespaceWithoutCreatingTeamRun() {
        ITopicRouteRuntimeService.TeamGameScope a=scope(7L,11L,null,true),b=scope(8L,11L,null,true);
        assertNotNull(a);assertNotNull(b);assertEquals(FIRST,a.getPlayInstanceId());assertEquals(FIRST,b.getActorPlayInstanceId());assertEquals(33L,a.getTeamId());assertNull(a.getTeamRunId());
        verify(runs,never()).insertRun(any());verify(routes,never()).insertSession(any());
    }
    @Test void storyScopeWithoutNodeStillUsesFirstTeamNamespace(){ITopicRouteRuntimeService.TeamGameScope s=scope(7L,null,null,false);assertNotNull(s);assertEquals(FIRST,s.getPlayInstanceId());verify(teams,never()).selectPlayTeamByIdForUpdate(any());}
    @Test void firstTeamLocksPrecedePersonalRoute(){assertNotNull(scope(7L,11L,33L,true));org.mockito.InOrder order=inOrder(teams,routes);order.verify(teams).selectPlayTeamByIdForUpdate(33L);order.verify(teams).selectActiveMembersForUpdate(33L);order.verify(routes).selectCurrentByOwnerForUpdate(5L,10L,7L);}
    @Test void implicitMultipleLiveTeamsAreAmbiguous(){PlayTeam other=new PlayTeam();other.setId(44L);other.setOwnerType(2);other.setOwnerId(5L);other.setStatus(0);PlayTeamMember otherMember=member(7);otherMember.setTeamId(44L);when(teams.selectMyTeams(7L)).thenReturn(Arrays.asList(team,other));when(teams.selectActiveMember(44L,7L)).thenReturn(otherMember);assertThrows(ServiceException.class,()->scope(7L,11L,null,true));verify(routes,never()).selectCurrentByOwnerForUpdate(any(),any(),any());}
    @Test void legacyActorCannotBorrowFirstTeamScope(){actors.get(7L).setPlayInstanceId("default");assertNull(scope(7L,11L,33L,true));}
    @Test void untrustedOriginIsRejected(){ObjectNode root=(ObjectNode)json.valueToTree(Collections.singletonMap("themeConfig",Collections.emptyMap()));actors.get(7L).setConfigSnapshotJson(root.toString());assertThrows(ServiceException.class,()->scope(7L,11L,33L,true));}
    @Test void twoFirstMembersResumeOneExactSharedGame(){assertEquals(advanced.start(7L,5L,10L,11L,null).get("sessionId"),advanced.start(8L,5L,10L,11L,null).get("sessionId"));assertNotNull(shared());assertEquals(FIRST,advanced.start(7L,5L,10L,11L,null).get("playInstanceId"));assertEquals(1,games.size());verify(gameMapper,times(1)).insert(any());verify(gameMapper,never()).selectByOwner(any(),any(),any(),any(),any());}
    @Test void openingCreationMustResolveAgainInsteadOfStartingDefaultTeam(){actors.remove(7L);doAnswer(i->{actors.put(7L,first(7,MULTI));return null;}).when(runtime).requireOpeningAccess(7L,10L,5L,11L);advanced.start(7L,5L,10L,11L,null);assertNotNull(shared());assertFalse(games.containsKey("2:33:default"));}
    @Test void missingFirstTeamScopeCannotUseLegacyTeamFallback(){ReflectionTestUtils.setField(runtime,"themeTeamCoordinator",null);assertThrows(ServiceException.class,()->advanced.start(7L,5L,10L,11L,33L));verify(gameMapper,never()).insert(any());}
    @Test void blankRuntimeInstanceCannotBecomeLegacyDefault(){doReturn(" ").when(runtime).playInstance(anyLong(),anyLong(),anyLong());assertThrows(ServiceException.class,()->advanced.start(7L,5L,10L,11L,null));verify(gameMapper,never()).insert(any());}

    @Test void firstGameUsesServerFingerprintAndEquivalentObjectOrderMatches()throws Exception {
        ObjectNode author=(ObjectNode)json.readTree(MULTI);author.put(AdvancedGameRuntimeServiceImpl.FIRST_TEAM_CONFIG_FINGERPRINT,"author-forgery");actors.put(7L,first(7,author.toString()));
        Map<String,Object> view=advanced.start(7L,5L,10L,11L,null);
        assertFalse(json.valueToTree(view.get("config")).has(AdvancedGameRuntimeServiceImpl.FIRST_TEAM_CONFIG_FINGERPRINT));
        String digest=json.readTree(shared().getConfigSnapshotJson()).path(AdvancedGameRuntimeServiceImpl.FIRST_TEAM_CONFIG_FINGERPRINT).asText();
        assertEquals(AdvancedGameRuntimeServiceImpl.firstConfigFingerprint(99L,json.readTree(MULTI)),digest);assertTrue(digest.matches("[0-9a-f]{64}"));
        ObjectNode reversed=json.createObjectNode();reversed.set("multiplayer",author.path("multiplayer"));reversed.put("schemaVersion",1);actors.put(8L,first(8,reversed.toString()));
        assertEquals(shared().getId(),advanced.start(8L,5L,10L,11L,null).get("sessionId"));assertEquals(1,games.size());
    }
    @Test void differentFrozenVersionRejectsStartStateActCompleteAndRestart()throws Exception {
        advanced.start(7L,5L,10L,11L,null);GameRuntimeSession game=shared();String original=game.getConfigSnapshotJson();
        actors.put(8L,first(8,MULTI.replace("\"requiredTurns\":2","\"requiredTurns\":3")));clearInvocations(gameMapper,attempts);
        assertThrows(ServiceException.class,()->advanced.start(8L,5L,10L,11L,null));
        assertThrows(ServiceException.class,()->advanced.state(8L,game.getId()));
        assertThrows(ServiceException.class,()->advanced.act(8L,game.getId(),0,"different-version","COMPLETE_UNIT",Collections.singletonMap("unitId","u")));
        assertThrows(ServiceException.class,()->advanced.completeGate(8L,5L,10L,11L));
        game.setStatus(GameRuntimeSession.STATUS_FAILED);assertThrows(ServiceException.class,()->advanced.start(8L,5L,10L,11L,null));
        assertEquals(original,game.getConfigSnapshotJson());verify(gameMapper,never()).insert(any());verify(gameMapper,never()).updateStateCas(any());verify(gameMapper,never()).restartTerminalCas(any());verify(attempts,never()).selectByIdempotencyKey(any());
    }
    @Test void missingOrNonTextFingerprintIsRejectedRatherThanAdopted()throws Exception {
        advanced.start(7L,5L,10L,11L,null);ObjectNode config=(ObjectNode)json.readTree(shared().getConfigSnapshotJson());config.remove(AdvancedGameRuntimeServiceImpl.FIRST_TEAM_CONFIG_FINGERPRINT);shared().setConfigSnapshotJson(config.toString());
        assertThrows(ServiceException.class,()->advanced.state(7L,shared().getId()));
        config.put(AdvancedGameRuntimeServiceImpl.FIRST_TEAM_CONFIG_FINGERPRINT,1);shared().setConfigSnapshotJson(config.toString());assertThrows(ServiceException.class,()->advanced.start(7L,5L,10L,11L,null));verify(gameMapper,never()).updateStateCas(any());
    }
    @Test void fingerprintVerifierAlsoBindsOwnerInstanceTemplateAndScope() {
        advanced.start(7L,5L,10L,11L,null);GameRuntimeSession game=shared();ITopicRouteRuntimeService.TeamGameScope s=scope(7L,11L,33L,false);
        AdvancedGameRuntimeServiceImpl.requireFirstTeamConfig(s,game);
        game.setOwnerId(44L);assertThrows(ServiceException.class,()->AdvancedGameRuntimeServiceImpl.requireFirstTeamConfig(s,game));game.setOwnerId(33L);
        game.setOwnerType(1);assertThrows(ServiceException.class,()->AdvancedGameRuntimeServiceImpl.requireFirstTeamConfig(s,game));game.setOwnerType(2);
        game.setTemplateId(100L);assertThrows(ServiceException.class,()->AdvancedGameRuntimeServiceImpl.requireFirstTeamConfig(s,game));game.setTemplateId(99L);
        game.setActivityId(6L);assertThrows(ServiceException.class,()->AdvancedGameRuntimeServiceImpl.requireFirstTeamConfig(s,game));game.setActivityId(5L);
        game.setPlayInstanceId("default");assertThrows(ServiceException.class,()->AdvancedGameRuntimeServiceImpl.requireFirstTeamConfig(s,game));
    }
    @Test void firstTeamCanActCompleteAndReuseActorScopedReceiptKeys() {
        advanced.start(7L,5L,10L,11L,null);Long id=shared().getId();
        Map<String,Object> a=advanced.act(7L,id,0,"same-key","COMPLETE_UNIT",Collections.singletonMap("unitId","one"));
        Map<String,Object> b=advanced.act(8L,id,1,"same-key","COMPLETE_UNIT",Collections.singletonMap("unitId","two"));
        assertEquals(1,a.get("version"));assertEquals(2,b.get("version"));assertEquals(json.valueToTree(a).toString(),json.valueToTree(advanced.act(7L,id,0,"same-key","COMPLETE_UNIT",Collections.singletonMap("unitId","one"))).toString());
        advanced.completeGate(7L,5L,10L,11L);assertEquals(GameRuntimeSession.STATUS_COMPLETED,shared().getStatus());
        verify(attempts,times(2)).insertPlayAttemptLog(any());
        verify(runtime).recordNodeResult(eq(7L),eq(10L),eq(5L),eq(11L),eq(FIRST),anyString(),any(),eq(false),eq(0),anyList());
        verify(runtime).recordNodeResult(eq(8L),eq(10L),eq(5L),eq(11L),eq(FIRST),anyString(),any(),eq(false),eq(0),anyList());
    }
    @Test void currentFirstCanExpireAndRestartTheSameGame() {
        advanced.start(7L,5L,10L,11L,null);GameRuntimeSession game=shared();Long id=game.getId();game.setDeadlineAt(new Date(1));
        assertEquals("EXPIRED",advanced.state(8L,id).get("status"));
        assertEquals(id,advanced.start(8L,5L,10L,11L,null).get("sessionId"));assertEquals(FIRST,game.getPlayInstanceId());assertEquals(1,games.size());verify(gameMapper).restartTerminalCas(game);
    }
    @Test void afterPersonalReplayOldFirstCannotActExpireOrRestart()throws Exception {
        advanced.start(7L,5L,10L,11L,null);GameRuntimeSession game=shared();game.setDeadlineAt(new Date(1));actors.get(7L).setPlayInstanceId("replay:1007");
        clearInvocations(gameMapper,attempts);
        assertThrows(ServiceException.class,()->advanced.state(7L,game.getId()));
        assertThrows(ServiceException.class,()->advanced.act(7L,game.getId(),0,"old-first","COMPLETE_UNIT",Collections.singletonMap("unitId","u")));
        assertThrows(ServiceException.class,()->advanced.completeGate(7L,5L,10L,11L));
        verify(gameMapper,never()).updateStateCas(any());verify(gameMapper,never()).restartTerminalCas(any());verify(attempts,never()).selectByIdempotencyKey(any());
    }
    @Test void departedMemberAndOtherActivityCannotReadOrMutateFirstGame() {
        advanced.start(7L,5L,10L,11L,null);members=Collections.singletonList(member(8));
        assertThrows(ServiceException.class,()->advanced.state(7L,shared().getId()));
        assertThrows(ServiceException.class,()->scope(7L,11L,33L,true));members=Arrays.asList(member(7),member(8));team.setOwnerId(6L);
        assertThrows(ServiceException.class,()->advanced.state(7L,shared().getId()));verify(gameMapper,never()).updateStateCas(any());
    }
    @Test void leaderAssignmentUsesLockedCurrentMembership()throws Exception {
        String leader=MULTI.replace("\"AUTO\"","\"LEADER\"");actors.put(7L,first(7,leader));actors.put(8L,first(8,leader));advanced.start(7L,5L,10L,11L,null);
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("memberId",7L);payload.put("roleId","pacer");advanced.act(7L,shared().getId(),0,"role","ASSIGN_ROLE",payload);
        assertEquals("pacer",json.readTree(shared().getStateJson()).path("multiplayer").path("roles").path("7").asText());
        payload.put("memberId",9L);assertThrows(IllegalStateException.class,()->advanced.act(7L,shared().getId(),1,"departed-target","ASSIGN_ROLE",payload));
        payload.put("memberId",8L);assertThrows(IllegalStateException.class,()->advanced.act(8L,shared().getId(),1,"not-leader","ASSIGN_ROLE",payload));verify(gameMapper,times(1)).updateStateCas(any());
    }
    @Test void firstPersonalNeverResumesCompletedDefaultPlayerOrTeam()throws Exception {
        String random="{\"schemaVersion\":1,\"random\":{\"enabled\":true,\"drawCount\":1,\"items\":[{\"id\":\"a\",\"label\":\"A\",\"weight\":1}]}}";actors.put(7L,first(7,random));
        GameRuntimeSession old=new GameRuntimeSession();old.setId(90L);old.setOwnerType(1);old.setOwnerId(7L);old.setPlayInstanceId("default");old.setStateJson("{\"old\":true}");old.setStatus(1);games.put(key(old),old);
        Map<String,Object> view=advanced.start(7L,5L,10L,11L,null);GameRuntimeSession current=games.get("1:7:"+FIRST);assertNotNull(current);assertNotEquals(90L,view.get("sessionId"));
        assertThrows(ServiceException.class,()->advanced.completeGate(7L,5L,10L,11L));advanced.act(7L,current.getId(),0,"draw-first","DRAW",Collections.emptyMap());advanced.completeGate(7L,5L,10L,11L);
        assertEquals(GameRuntimeSession.STATUS_COMPLETED,current.getStatus());assertEquals("{\"old\":true}",old.getStateJson());verify(gameMapper,never()).selectPlayerByOwner(any(),any(),any(),any());verify(gameMapper,never()).selectTeamByMember(any(),any(),any(),any());
    }
    @Test void openingCannotAcquireNewTeamAfterCreatingRouteInSameTransaction()throws Exception {
        actors.remove(7L);when(teams.selectMyTeams(7L)).thenReturn(Collections.emptyList());
        doAnswer(i->{actors.put(7L,first(7,MULTI));when(teams.selectMyTeams(7L)).thenReturn(Collections.singletonList(team));return null;}).when(runtime).requireOpeningAccess(7L,10L,5L,11L);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(ServiceException.class,()->advanced.start(7L,5L,10L,11L,null));verify(teams,never()).selectPlayTeamByIdForUpdate(any());verify(gameMapper,never()).insert(any()); }
        finally { org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }
    }
    @Test void teamSelectionPinCleansAfterCommitRollbackAndNestedSuspension() {
        org.springframework.jdbc.datasource.DriverManagerDataSource ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:selection_pin_"+System.nanoTime(),"sa","");
        org.springframework.jdbc.datasource.DataSourceTransactionManager manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
        org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        org.springframework.transaction.support.TransactionTemplate nested=new org.springframework.transaction.support.TransactionTemplate(manager);nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ThemeTeamRunCoordinator coordinator=new ThemeTeamRunCoordinator(teams,runs);
        tx.execute(status->{coordinator.guardSelection(7L,5L,10L,null);assertThrows(ServiceException.class,()->coordinator.guardSelection(7L,5L,10L,33L));nested.execute(inner->{coordinator.guardSelection(7L,5L,10L,44L);return null;});coordinator.guardSelection(7L,5L,10L,null);return null;});
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive());
        assertThrows(IllegalStateException.class,()->tx.execute(status->{coordinator.guardSelection(7L,5L,10L,33L);throw new IllegalStateException("rollback");}));
        tx.execute(status->{coordinator.guardSelection(7L,5L,10L,44L);return null;});
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive());
    }
    @Test void firstAndLegacyTeamGamesAreMutuallyInaccessible() {
        advanced.start(7L,5L,10L,11L,null);GameRuntimeSession game=shared();Long id=game.getId();
        actors.get(7L).setPlayInstanceId("default");assertThrows(ServiceException.class,()->advanced.state(7L,id));
        actors.get(7L).setPlayInstanceId(FIRST);game.setPlayInstanceId("default");assertThrows(ServiceException.class,()->advanced.state(7L,id));
        assertThrows(ServiceException.class,()->advanced.act(7L,id,0,"old-default","COMPLETE_UNIT",Collections.singletonMap("unitId","u")));verify(gameMapper,never()).updateStateCas(any());verify(attempts,never()).selectByIdempotencyKey(any());
    }
    @Test void firstHistoryCannotMutateAfterATeamGenerationExists() {
        advanced.start(7L,5L,10L,11L,null);GameRuntimeSession game=shared();
        ThemeTeamRun run=new ThemeTeamRun();run.setId(501L);run.setGeneration(1);run.setTeamId(33L);run.setActivityId(5L);run.setTopicId(10L);run.setPlayInstanceId("team-replay:501");run.setConfigSnapshotJson(actors.get(7L).getConfigSnapshotJson());
        when(runs.selectCurrentByTeam(33L,5L,10L)).thenReturn(run);when(runs.selectCurrentByTeamForUpdate(33L,5L,10L)).thenReturn(run);
        assertThrows(ServiceException.class,()->advanced.state(7L,game.getId()));
        assertThrows(ServiceException.class,()->advanced.act(7L,game.getId(),0,"old-first-group","COMPLETE_UNIT",Collections.singletonMap("unitId","u")));verify(gameMapper,never()).updateStateCas(any());
    }
    @Test void unstartedThemeWithoutOpeningCannotCreateGameOrRoute() {
        actors.remove(7L);assertThrows(ServiceException.class,()->advanced.start(7L,5L,10L,11L,null));verify(gameMapper,never()).insert(any());verify(routes,never()).insertSession(any());
    }
    @Test void teamSelectionCannotSwitchFromAToBAfterOpening() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertNotNull(scope(7L,11L,33L,true));assertThrows(ServiceException.class,()->scope(7L,11L,44L,true));verify(teams,never()).selectPlayTeamByIdForUpdate(44L); }
        finally { org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }
    }
    @Test void duplicateInsertReadsOnlyTheExactFirstWinner() {
        doAnswer(i->{GameRuntimeSession winner=i.getArgument(0);winner.setId(700L);games.put(key(winner),winner);throw new org.springframework.dao.DuplicateKeyException("concurrent winner");}).when(gameMapper).insert(any());
        assertEquals(700L,advanced.start(7L,5L,10L,11L,null).get("sessionId"));assertEquals(FIRST,shared().getPlayInstanceId());
        verify(gameMapper,times(2)).selectByOwnerForInstance(5L,10L,11L,2,33L,FIRST);verify(gameMapper,never()).selectByOwner(any(),any(),any(),any(),any());
    }
    @Test void duplicateWinnerWithDifferentFrozenVersionCannotBeAdopted() {
        doAnswer(i->{GameRuntimeSession winner=i.getArgument(0);winner.setId(700L);ObjectNode snapshot=(ObjectNode)json.readTree(winner.getConfigSnapshotJson());snapshot.put(AdvancedGameRuntimeServiceImpl.FIRST_TEAM_CONFIG_FINGERPRINT,String.join("",Collections.nCopies(64,"f")));winner.setConfigSnapshotJson(snapshot.toString());games.put(key(winner),winner);throw new org.springframework.dao.DuplicateKeyException("different winner");}).when(gameMapper).insert(any());
        assertThrows(ServiceException.class,()->advanced.start(7L,5L,10L,11L,null));verify(gameMapper,never()).updateStateCas(any());verify(gameMapper,never()).restartTerminalCas(any());
    }
    @Test void firstTeamNativeRequestStillRejectsBeforeScopeOrReceipt() {
        advanced.start(7L,5L,10L,11L,null);clearInvocations(runtime,attempts,gameMapper);
        Map<String,Object> nativePayload=new LinkedHashMap<>();nativePayload.put("provider",NativeStepProtocol.PROVIDER);nativePayload.put("deviceKeyId","device-key");
        ServiceException rejected=assertThrows(ServiceException.class,()->advanced.act(7L,shared().getId(),0,"native-team",NativeStepProtocol.ISSUE,nativePayload));
        assertEquals("NATIVE_STEPS_UNSUPPORTED_OWNER",((Map<?,?>)rejected.getData()).get("reasonCode"));verify(runtime,never()).resolveTeamGameScope(any(),any(),any(),any(),any(),anyBoolean());verify(attempts,never()).selectByIdempotencyKey(any());verify(gameMapper,never()).updateStateCas(any());
    }
    @Test void readOnlyRecoveryChecksTheSameFingerprintWithoutOpeningOrWriteLocks()throws Exception {
        advanced.start(7L,5L,10L,11L,null);clearInvocations(runtime,teams,gameMapper);
        advanced.timeWindow(8L,5L,10L,11L);verify(runtime,never()).requireOpeningAccess(any(),any(),any(),any());verify(teams,never()).selectPlayTeamByIdForUpdate(any());verify(gameMapper,never()).insert(any());
        actors.put(8L,first(8,MULTI.replace("\"requiredTurns\":2","\"requiredTurns\":3")));assertThrows(ServiceException.class,()->advanced.timeWindow(8L,5L,10L,11L));
    }
    private com.chengyinhub.web.controller.api.ApiPlayProgressController nodesController(long member) {
        com.chengyinhub.web.controller.api.ApiPlayProgressController api=spy(new com.chengyinhub.web.controller.api.ApiPlayProgressController());doReturn(member).when(api).getAppUserId();
        com.chengyinhub.business.service.ICmsActivityService activityService=mock(com.chengyinhub.business.service.ICmsActivityService.class);CmsActivity currentActivity=activities.selectCmsActivityById(5L);when(activityService.selectCmsActivityById(5L)).thenReturn(currentActivity);
        com.chengyinhub.business.service.ICmsRegistrationService registrations=mock(com.chengyinhub.business.service.ICmsRegistrationService.class);CmsRegistration paid=new CmsRegistration();paid.setMemberId(member);paid.setOwnerType(2L);paid.setOwnerId(5L);paid.setRegistrationStatus(2);when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(paid));
        com.chengyinhub.business.service.ICmsTopicService topicService=mock(com.chengyinhub.business.service.ICmsTopicService.class);CmsTopic topic=new CmsTopic();topic.setId(10L);topic.setProductType(1);when(topicService.selectCmsTopicById(10L)).thenReturn(topic);
        com.chengyinhub.business.service.ICmsTopicNodeService nodeService=mock(com.chengyinhub.business.service.ICmsTopicNodeService.class);when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        ITopicRouteRuntimeService read=mock(ITopicRouteRuntimeService.class);when(read.playInstance(member,10L,5L)).thenReturn(FIRST);when(read.assertCurrentPlayInstance(member,10L,5L,FIRST)).thenReturn(true);
        when(read.currentSessionFacts(anyList())).thenReturn(Collections.singletonMap(ITopicRouteRuntimeService.sessionKey(member,10L,5L),new ITopicRouteRuntimeService.CurrentSessionFact(0,FIRST,true)));
        when(read.resolveTeamGameScope(eq(member),eq(10L),eq(5L),any(),any(),eq(false))).thenAnswer(i->runtime.resolveTeamGameScope(member,10L,5L,i.getArgument(3),i.getArgument(4),false));
        when(gameMapper.selectSessionsForMemberAndTeam(5L,10L,member,FIRST,33L,FIRST)).thenAnswer(i->Collections.singletonList(shared()));
        ReflectionTestUtils.setField(api,"cmsActivityService",activityService);ReflectionTestUtils.setField(api,"cmsRegistrationService",registrations);ReflectionTestUtils.setField(api,"cmsTopicService",topicService);ReflectionTestUtils.setField(api,"cmsTopicNodeService",nodeService);
        ReflectionTestUtils.setField(api,"playerNodeProgressService",mock(com.chengyinhub.business.service.IPlayerNodeProgressService.class));ReflectionTestUtils.setField(api,"cmsPointsSettingService",mock(com.chengyinhub.business.service.ICmsPointsSettingService.class));ReflectionTestUtils.setField(api,"cmsMemberTemplateService",mock(com.chengyinhub.business.service.ICmsMemberTemplateService.class));
        ReflectionTestUtils.setField(api,"cmsTopicChapterService",mock(com.chengyinhub.business.service.ICmsTopicChapterService.class));ReflectionTestUtils.setField(api,"puzzlePlayService",mock(com.chengyinhub.business.service.IPuzzlePlayService.class));ReflectionTestUtils.setField(api,"omsTicketService",mock(com.chengyinhub.business.service.IOmsTicketService.class));
        ReflectionTestUtils.setField(api,"topicRouteRuntimeService",read);ReflectionTestUtils.setField(api,"gameRuntimeSessionMapper",gameMapper);return api;
    }
    @Test void nodesOnlyProjectsSharedFirstStoryWithMatchingFrozenVersion() {
        advanced.start(7L,5L,10L,11L,null);shared().setStateJson("{\"profile\":{\"answers\":{\"name\":\"current-first-team\"}}}");
        com.chengyinhub.common.core.domain.AjaxResult result=nodesController(8L).nodes(5L,null);assertEquals(200,result.get("code"));
        assertEquals("current-first-team",((Map<?,?>)((Map<?,?>)result.get("data")).get("vars")).get("name"));
        verify(gameMapper,never()).selectStateJsonsByTopic(any(),any(),any());verify(gameMapper,never()).selectStateJsonsByTopicForInstance(any(),any(),any(),any());
    }
    @Test void nodesRejectsDifferentVersionBeforeBestEffortStoryProjection()throws Exception {
        advanced.start(7L,5L,10L,11L,null);shared().setStateJson("{\"profile\":{\"answers\":{\"name\":\"must-not-leak\"}}}");actors.put(8L,first(8,MULTI.replace("\"requiredTurns\":2","\"requiredTurns\":3")));
        assertThrows(ServiceException.class,()->nodesController(8L).nodes(5L,null));
    }
    private void inWriteTransaction(Runnable action) {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        try { action.run(); } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }
    }
    private int firstInvocation(Object mock,String method) {
        return mockingDetails(mock).getInvocations().stream().filter(i->method.equals(i.getMethod().getName()))
                .mapToInt(org.mockito.invocation.Invocation::getSequenceNumber).min().orElse(Integer.MAX_VALUE);
    }
    private void assertTeamBeforeFirstRouteLock() {
        int teamLock=firstInvocation(teams,"selectPlayTeamByIdForUpdate"),memberLock=firstInvocation(teams,"selectActiveMembersForUpdate"),routeLock=firstInvocation(routes,"selectCurrentByOwnerForUpdate");
        assertTrue(teamLock<memberLock && memberLock<routeLock,"The earliest route lock must follow team and membership, even before Advanced/award preparation");
    }
    @Test void frozenAnswerEntryTakesFirstTeamBarrierBeforeAnyRouteLock() {
        inWriteTransaction(()->{assertEquals("frozen-answer",runtime.nodeAnswers(7L,10L,5L,11L).path("questionAnswer").asText());assertNotNull(scope(7L,11L,null,true));assertTeamBeforeFirstRouteLock();});
    }
    @Test void canPlayEntryTakesFirstTeamBarrierBeforeAnyRouteLock() {
        CmsActivity activity=activities.selectCmsActivityById(5L);activity.setStatus(1L);activity.setDelFlag(0);
        CmsTopic topic=topics.selectCmsTopicById(10L);topic.setPublishedConfigVersion(1L);topic.setRouteMode("LINEAR");
        CmsRegistrationMapper registrationMapper=(CmsRegistrationMapper)ReflectionTestUtils.getField(runtime,"registrationMapper");when(registrationMapper.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(new CmsRegistration()));
        CmsTopicNode node=nodes.selectCmsTopicNodeById(11L);node.setStatus(1L);node.setSortId(1L);when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        actors.get(7L).setStateJson("{\"status\":\"ACTIVE\",\"currentNodeId\":11,\"recommendedNodeId\":11,\"nodeStates\":{\"11\":\"PLAYABLE\"}}");
        inWriteTransaction(()->{assertTrue(runtime.canPlay(7L,10L,5L,11L,null));assertNotNull(scope(7L,11L,null,true));assertTeamBeforeFirstRouteLock();});
    }
    @Test void earlyFrozenAnswerPinsNoTeamAndCannotAcquireOneLater() {
        when(teams.selectMyTeams(7L)).thenReturn(Collections.emptyList());
        inWriteTransaction(()->{runtime.nodeAnswers(7L,10L,5L,11L);when(teams.selectMyTeams(7L)).thenReturn(Collections.singletonList(team));
            assertThrows(ServiceException.class,()->scope(7L,11L,null,true));verify(teams,never()).selectPlayTeamByIdForUpdate(any());});
    }
    @Test void newlyObservedFirstAfterRouteLockFailsWithoutChasingATeam() {
        topics.selectCmsTopicById(10L).setPublishedThemeRuntimeConfigJson(null);when(routes.selectCurrentByOwner(5L,10L,7L)).thenReturn(null);
        inWriteTransaction(()->{assertThrows(ServiceException.class,()->runtime.nodeAnswers(7L,10L,5L,11L));verify(teams,never()).selectPlayTeamByIdForUpdate(any());});
    }
    @Test void aNewOtherTeamRunCannotEscapeTheEarlierFirstTeamPin() {
        inWriteTransaction(()->{assertNotNull(scope(7L,11L,33L,true));ThemeTeamRun other=new ThemeTeamRun();other.setId(600L);other.setTeamId(44L);
            when(runs.selectByCurrentMember(7L,5L,10L)).thenReturn(other);assertThrows(ServiceException.class,()->runtime.nodeAnswers(7L,10L,5L,11L));verify(teams,never()).selectPlayTeamByIdForUpdate(44L);});
    }
    @Test void legacyDefaultPreferenceMustTakeTeamAndGameBeforeMember() throws Exception {
        List<String> lockEvents=new ArrayList<>();actors.clear();topics.selectCmsTopicById(10L).setPublishedThemeRuntimeConfigJson(null);
        PlayerNodeProgressMapper progress=(PlayerNodeProgressMapper)ReflectionTestUtils.getField(runtime,"progressMapper");
        when(progress.selectOriginalNodeAwardEvidence(7L,5L,10L,11L)).thenReturn(new LinkedHashMap<String,Object>(){{put("legacyCompleted",false);put("dropConsumed",false);put("couponConsumed",false);}});
        when(progress.selectOriginalInstanceAwardSessions(7L,5L,11L)).thenReturn(Collections.emptyList());
        when(routes.selectCurrentCandidatesByOwners(anyList())).thenReturn(Collections.emptyList());
        UmsMemberMapper memberRows=mock(UmsMemberMapper.class);when(memberRows.lockMemberById(7L)).thenAnswer(call->{lockEvents.add("member");return 7L;});ReflectionTestUtils.setField(runtime,"originalAwardMemberMapper",memberRows);
        when(teams.selectPlayTeamByIdForUpdate(33L)).thenAnswer(call->{lockEvents.add("team");return team;});
        GameRuntimeSession game=new GameRuntimeSession();game.setId(99L);game.setActivityId(5L);game.setTopicId(10L);game.setNodeId(11L);game.setTemplateId(99L);game.setOwnerType(2);game.setOwnerId(33L);game.setPlayInstanceId("default");game.setStatus(0);game.setVersion(0);game.setConfigSnapshotJson(MULTI);game.setStateJson("{\"multiplayer\":{\"members\":[7,8],\"roles\":{\"7\":\"pacer\",\"8\":\"closer\"},\"completedUnitIds\":[\"a\",\"b\"]}}");
        when(gameMapper.selectTeamByMember(5L,10L,11L,7L)).thenReturn(game);
        when(gameMapper.updateStateCas(any())).thenAnswer(call->{lockEvents.add("game");return 1;});
        com.chengyinhub.web.service.PreferencePlayService preference=new com.chengyinhub.web.service.PreferencePlayService();
        ReflectionTestUtils.setField(preference,"routeRuntimeService",runtime);ReflectionTestUtils.setField(preference,"attemptMapper",attempts);ReflectionTestUtils.setField(preference,"tagService",mock(com.chengyinhub.business.service.IPlayerTopicTagService.class));
        CmsMemberTemplate template=new CmsMemberTemplate();template.setPreferenceJson("{\"steps\":[{\"key\":\"q\",\"type\":\"single\",\"title\":\"Choose\",\"options\":[{\"key\":\"A\",\"text\":\"Small\",\"scores\":{\"compact\":2}},{\"key\":\"B\",\"text\":\"Big\",\"scores\":{\"compact\":1}}]}],\"dimensions\":[\"compact\"],\"results\":{\"compact\":{\"title\":\"Compact\",\"body\":\"because {{choices}}\",\"nextStep\":\"Clear\",\"nextStepDays\":7}}}");
        com.chengyinhub.business.domain.dto.PreferenceSubmitRequest request=new com.chengyinhub.business.domain.dto.PreferenceSubmitRequest();request.setChoices(Collections.singletonMap("q","A"));
        org.apache.ibatis.datasource.pooled.PooledDataSource ds=new org.apache.ibatis.datasource.pooled.PooledDataSource("org.h2.Driver","jdbc:h2:mem:legacy_preference_order_"+UUID.randomUUID(),"sa","");
        org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));tx.setIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        Map<String,Object> result=tx.execute(status->preference.submit(7L,10L,5L,11L,template,request,new com.chengyinhub.web.service.PreferencePlayService.CompletionFunction() {
            public ITopicRouteRuntimeService.NodeAwardPermit prepare() {
                advanced.completeGate(7L,5L,10L,11L);
                return runtime.beginOriginalNodeAward(7L,10L,5L,11L,"default");
            }
            public Map<String,Object> apply(String code) { return Collections.singletonMap("completed",true); }
        }));
        assertEquals(Boolean.TRUE,result.get("firstTime"));assertEquals(GameRuntimeSession.STATUS_COMPLETED,game.getStatus());
        System.out.println("INDEPENDENT_LEGACY_DEFAULT_PREFERENCE_ORDER="+lockEvents);
        assertTrue(lockEvents.contains("team") && lockEvents.contains("game"));
        assertTrue(lockEvents.indexOf("team")<lockEvents.indexOf("member") && lockEvents.indexOf("game")<lockEvents.indexOf("member"),"Legacy TEAM and game must precede member: "+lockEvents);
    }
}

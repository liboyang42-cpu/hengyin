package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AiThemeDraftIntentRow;
import com.chengyinhub.business.domain.dto.ai.*;
import com.chengyinhub.business.domain.vo.ai.*;
import com.chengyinhub.business.mapper.AiThemeDraftIntentMapper;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.ai.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit boundary doubles only; real commit/CAS/Lua/RR evidence belongs to the companion IT. */
@Timeout(20)
class AiThemeDraftIntentServiceTest {
    private static final long MEMBER=501L;
    private static final String PREPARE_ID="7c9e6679-7425-40de-944b-e07fc1f90ae7";
    private final Map<String,AiThemeDraftIntentRow> rows=new ConcurrentHashMap<>();
    private final MutableClock clock=new MutableClock();
    private final MemoryBodies bodies=new MemoryBodies();
    private AiThemeDraftRequestService service;
    private IAiCreatorService creator;
    private AiThemeDraftIntentMapper mapper;
    private PlatformTransactionManager manager;
    private final AtomicLong ids=new AtomicLong();

    @BeforeEach void setup() {
        service=new AiThemeDraftRequestService(); creator=mock(IAiCreatorService.class);
        mapper=mock(AiThemeDraftIntentMapper.class); manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(c->new SimpleTransactionStatus());
        ReflectionTestUtils.setField(service,"aiCreatorService",creator);
        ReflectionTestUtils.setField(service,"intentMapper",mapper);
        ReflectionTestUtils.setField(service,"transactionManager",manager);
        ReflectionTestUtils.setField(service,"redisTemplate",bodies);
        ReflectionTestUtils.setField(service,"intentClock",clock);
        ReflectionTestUtils.setField(service,"intentEnabled",true);
        when(creator.planThemeDraft(eq(MEMBER),anyString())).thenAnswer(c->plan(c.getArgument(1)));
        when(creator.themeDraftPrepared(any(),eq(MEMBER),any())).thenAnswer(c->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return draft(); });
        when(mapper.selectOwned(anyLong(),anyString())).thenAnswer(c->{
            AiThemeDraftIntentRow r=rows.get(c.getArgument(1));
            return r!=null && Objects.equals(r.getMemberId(),c.getArgument(0))?copy(r):null; });
        when(mapper.selectByPrepareKey(anyLong(),anyString())).thenAnswer(c->find(c.getArgument(0),c.getArgument(1)));
        when(mapper.selectByPrepareKeyForUpdate(anyLong(),anyString())).thenAnswer(c->find(c.getArgument(0),c.getArgument(1)));
        when(mapper.selectRecovery(anyLong(),nullable(Long.class))).thenAnswer(c->rows.values().stream()
                .filter(r->Objects.equals(r.getMemberId(),c.getArgument(0))&&(c.getArgument(1)==null||r.getId()<(Long)c.getArgument(1)))
                .sorted(Comparator.comparing(AiThemeDraftIntentRow::getId).reversed()).limit(21).map(AiThemeDraftIntentServiceTest::copy).toList());
        when(mapper.insertPrepared(any())).thenAnswer(c->{
            AiThemeDraftIntentRow r=c.getArgument(0); r.setId(ids.incrementAndGet()); rows.put(r.getRequestId(),copy(r)); return 1; });
        when(mapper.claim(anyLong(),anyLong(),anyString(),anyString(),anyString(),any(Instant.class))).thenAnswer(c->{ synchronized(rows) {
            AiThemeDraftIntentRow r=byId(c.getArgument(0)); if(!"PREPARED".equals(r.getState()))return 0;
            r.setState("PROCESSING"); r.setClaimToken(c.getArgument(4)); r.setClaimedAt(clock.instant()); return 1; }});
        when(mapper.complete(any())).thenAnswer(c->{ synchronized(rows) {
            AiThemeDraftIntentRow supplied=c.getArgument(0),r=byId(supplied.getId());
            if(!"PROCESSING".equals(r.getState())||!Objects.equals(r.getClaimToken(),supplied.getClaimToken()))return 0;
            AiThemeDraftIntentRow saved=copy(supplied); saved.setClaimedAt(r.getClaimedAt()); saved.setCompletedAt(clock.instant());
            rows.put(saved.getRequestId(),saved); return 1; }});
        when(mapper.rejectPrepared(any())).thenAnswer(c->{ synchronized(rows) {
            AiThemeDraftIntentRow supplied=c.getArgument(0),r=byId(supplied.getId());
            if(!"PREPARED".equals(r.getState()))return 0;
            AiThemeDraftIntentRow saved=copy(supplied); saved.setCompletedAt(clock.instant()); rows.put(saved.getRequestId(),saved);return 1; }});
        when(mapper.selectExpiredContexts(anyInt(),any(Instant.class))).thenAnswer(c->rows.values().stream()
                .filter(r->r.getContextPurgedAt()==null&&!r.getContextExpiresAt().isAfter(clock.instant()))
                .limit((Integer)c.getArgument(0)).map(AiThemeDraftIntentServiceTest::copy).toList());
        when(mapper.selectExpiredResults(anyInt(),any(Instant.class))).thenAnswer(c->rows.values().stream()
                .filter(r->r.getResultPurgedAt()==null&&r.getResultExpiresAt()!=null&&!r.getResultExpiresAt().isAfter(clock.instant()))
                .limit((Integer)c.getArgument(0)).map(AiThemeDraftIntentServiceTest::copy).toList());
        when(mapper.markContextPurged(anyLong(),anyString(),any(Instant.class),any(Instant.class))).thenAnswer(c->{ byId(c.getArgument(0)).setContextPurgedAt(clock.instant());return 1; });
        when(mapper.markResultPurged(anyLong(),anyString(),any(Instant.class),any(Instant.class))).thenAnswer(c->{ byId(c.getArgument(0)).setResultPurgedAt(clock.instant());return 1; });
    }

    @Test void samePrepareKeyKeepsOriginalPlanAndFirstResult() {
        var first=prepare(); AiThemeDraftIntentRow before=copy(rows.get(first.requestId())); int writes=bodies.writes;
        assertEquals(first.requestId(),prepare().requestId()); assertEquals(writes,bodies.writes);
        var result=execute(first); assertEquals("原草稿",result.result().getDraft().getTitle());
        assertEquals(first.requestId(),prepare().requestId()); assertEquals("原草稿",execute(first).result().getDraft().getTitle());
        assertEquals(before.getNonce(),rows.get(first.requestId()).getNonce());
        verify(creator,times(1)).planThemeDraft(eq(MEMBER),anyString());
        verify(creator,times(1)).themeDraftPrepared(any(),eq(MEMBER),any());
    }
    @Test void nonOwnerCannotReadClaimOrDiscoverIntent() {
        var v=prepare(); int accesses=bodies.reads;
        var read=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,()->service.readIntent(MEMBER+1,v.requestId()));
        var absent=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,()->service.readIntent(MEMBER+1,"td2_"+"f".repeat(32)));
        assertEquals(read.getCode(),absent.getCode()); assertEquals(read.getErrorCode(),absent.getErrorCode());
        assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,()->service.execute(MEMBER+1,new AiThemeDraftExecuteReq(v.requestId())));
        assertEquals(accesses,bodies.reads); verify(creator,never()).themeDraftPrepared(any(),anyLong(),any());
    }
    @Test void knownFailureRemainsTerminalUnderOriginalIdentity() {
        doThrow(new AiServiceException("known failure")).when(creator).themeDraftPrepared(any(),anyLong(),any());
        var v=prepare(); assertEquals(AiThemeDraftIntentState.FAILED,execute(v).state());
        assertEquals(AiThemeDraftIntentState.FAILED,execute(v).state());
        assertEquals(v.requestId(),prepare().requestId()); verify(creator,times(1)).themeDraftPrepared(any(),anyLong(),any());
    }
    @Test void concurrentLoserOnlyReadsAndExpiredClaimNeverReexecutes() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(creator.themeDraftPrepared(any(),anyLong(),any())).thenAnswer(c->{entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS));return draft();});
        var v=prepare(); ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<AiThemeDraftIntentView> winner=pool.submit(()->execute(v)); assertTrue(entered.await(5,TimeUnit.SECONDS));
            refusal(v,"AI_DRAFT_IN_PROGRESS");
            clock.at.set(clock.instant().plusMillis(AiThemeDraftRequestService.LEASE_MILLIS+1));
            assertEquals(AiThemeDraftIntentState.UNKNOWN,service.readIntent(MEMBER,v.requestId()).state());
            refusal(v,"AI_DRAFT_OUTCOME_UNKNOWN"); release.countDown();
            assertEquals(AiThemeDraftIntentState.SUCCEEDED,winner.get(5,TimeUnit.SECONDS).state());
        } finally {release.countDown();pool.shutdownNow();}
        verify(creator,times(1)).themeDraftPrepared(any(),anyLong(),any());
    }
    @Test void prepareStorageFailureNeverExecutes() {
        bodies.failWrites=true;
        assertThrows(AiThemeDraftRequestService.StateUnavailableException.class,this::prepare);
        String id=rows.values().iterator().next().getRequestId(); bodies.failWrites=false;
        assertEquals(id,prepare().requestId()); assertEquals(AiThemeDraftIntentState.FAILED,service.readPrepared(MEMBER,PREPARE_ID).state());
        verify(creator,never()).themeDraftPrepared(any(),anyLong(),any());
    }
    @Test void resultBodyLossKeepsKnownSuccessWithoutRegeneration() {
        var v=prepare(); execute(v); bodies.values.remove(bodyKey("result",v.requestId()));
        var read=service.readIntent(MEMBER,v.requestId()); assertEquals(AiThemeDraftIntentState.SUCCEEDED,read.state());
        assertFalse(read.resultAvailable()); assertEquals("AI_DRAFT_RESULT_UNAVAILABLE",read.reasonCode());
        refusal(v,"AI_DRAFT_RESULT_UNAVAILABLE"); assertNotNull(rows.get(v.requestId()).getResultHash());
        verify(creator,times(1)).themeDraftPrepared(any(),anyLong(),any());
    }
    @Test void failedTerminalCommitKeepsOriginalClaimWithoutResend() {
        var v=prepare();String nonce=rows.get(v.requestId()).getNonce();
        doThrow(new IllegalStateException("controlled terminal database failure")).when(mapper).complete(any());
        assertThrows(AiThemeDraftRequestService.StateUnavailableException.class,()->execute(v));
        assertEquals(AiThemeDraftIntentState.PROCESSING,service.readIntent(MEMBER,v.requestId()).state());
        clock.at.set(clock.instant().plusMillis(AiThemeDraftRequestService.LEASE_MILLIS+1));
        assertEquals(AiThemeDraftIntentState.UNKNOWN,service.readIntent(MEMBER,v.requestId()).state());refusal(v,"AI_DRAFT_OUTCOME_UNKNOWN");
        assertEquals(nonce,rows.get(v.requestId()).getNonce());verify(creator,times(1)).themeDraftPrepared(any(),anyLong(),any());
    }
    @Test void legacyMissingAndForgedIdsCannotExecute() {
        for(String id:new String[]{null,PREPARE_ID,"td2_bad","td2_"+"f".repeat(32)})
            assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,()->service.execute(MEMBER,new AiThemeDraftExecuteReq(id)));
        verifyNoInteractions(creator); assertTrue(rows.isEmpty()); assertEquals(0,bodies.writes);
    }
    @Test void typedProviderUnknownKeepsOriginalIdentity() {
        doThrow(new AiProviderOutcomeUnknownException("unknown",null)).when(creator).themeDraftPrepared(any(),anyLong(),any());
        var v=prepare(); refusal(v,"AI_DRAFT_OUTCOME_UNKNOWN"); String nonce=rows.get(v.requestId()).getNonce();
        assertEquals(AiThemeDraftIntentState.UNKNOWN,service.readIntent(MEMBER,v.requestId()).state());
        clock.at.set(clock.instant().plus(Duration.ofDays(2))); refusal(v,"AI_DRAFT_OUTCOME_UNKNOWN");
        assertEquals(v.requestId(),prepare().requestId()); assertEquals(nonce,rows.get(v.requestId()).getNonce());
        verify(creator,times(1)).themeDraftPrepared(any(),anyLong(),any());
    }
    @Test void genericInputUnknownRemainsFailed() {
        doThrow(new AiServiceException(AiServiceException.Reason.UNKNOWN,"input unavailable")).when(creator).themeDraftPrepared(any(),anyLong(),any());
        var v=prepare(); assertEquals(AiThemeDraftIntentState.FAILED,execute(v).state()); assertNull(rows.get(v.requestId()).getProviderState());
        assertEquals(AiThemeDraftIntentState.FAILED,execute(v).state()); verify(creator,times(1)).themeDraftPrepared(any(),anyLong(),any());
    }
    @Test void wrappedUnknownDoesNotExpandTypedRule() {
        List<RuntimeException> failures=List.of(new AiServiceException("ordinary",new AiProviderOutcomeUnknownException("nested",null)),
                new RuntimeException(new AiProviderOutcomeUnknownException("nested",null)),new AiServiceException("UNKNOWN AiProviderOutcomeUnknownException"));
        for(RuntimeException failure:failures) {
            doThrow(failure).when(creator).themeDraftPrepared(any(),anyLong(),any());
            var v=service.prepare(MEMBER,req(UUID.randomUUID().toString(),"idea",null));
            assertEquals(AiThemeDraftIntentState.FAILED,execute(v).state()); assertEquals(AiThemeDraftIntentState.FAILED,execute(v).state());
        }
        verify(creator,times(failures.size())).themeDraftPrepared(any(),anyLong(),any());
    }
    @Test void invalidDraftKeepsFailedIdentity() {
        doThrow(new AiServiceException("invalid draft")).when(creator).themeDraftPrepared(any(),anyLong(),any());
        var v=prepare(); assertEquals(AiThemeDraftIntentState.FAILED,execute(v).state());
        assertEquals(AiThemeDraftIntentState.FAILED,service.readPrepared(MEMBER,PREPARE_ID).state());
        assertEquals(AiThemeDraftIntentState.FAILED,execute(v).state()); verify(creator,times(1)).themeDraftPrepared(any(),anyLong(),any());
    }
    @Test void disabledAdmissionDoesNotDisableOriginalReadback() {
        var v=prepare(); ReflectionTestUtils.setField(service,"intentEnabled",false);
        assertEquals(v.requestId(),service.readPrepared(MEMBER,PREPARE_ID).requestId());
        assertEquals(AiThemeDraftIntentState.PREPARED,service.readIntent(MEMBER,v.requestId()).state());
        assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,this::prepare);
        refusal(v,"AI_DRAFT_DISABLED"); verify(creator,never()).themeDraftPrepared(any(),anyLong(),any());
    }
    @Test void payloadFingerprintPreservesNullOrderAndWhitespace() {
        var v=prepare(); String nonce=rows.get(v.requestId()).getNonce();
        for(AiThemeDraftPrepareReq changed:List.of(req(PREPARE_ID," idea",null),req(PREPARE_ID,"idea",List.of()),req(PREPARE_ID,"idea",List.of("a","b")),
                new AiThemeDraftPrepareReq(PREPARE_ID,"idea",1,null))) {
            var e=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,()->service.prepare(MEMBER,changed));
            assertEquals("AI_DRAFT_REQUEST_CONFLICT",e.getErrorCode());
        }
        assertEquals(nonce,rows.get(v.requestId()).getNonce()); verify(creator,times(1)).planThemeDraft(anyLong(),anyString());
        verify(creator,never()).themeDraftPrepared(any(),anyLong(),any());
        String otherKey=UUID.randomUUID().toString();List<String> original=new ArrayList<>(Arrays.asList("a",null));
        var command=req(otherKey,"idea",original);original.set(0,"mutated after DTO construction");
        var ordered=service.prepare(MEMBER,command);
        assertEquals(ordered.requestId(),service.prepare(MEMBER,req(otherKey,"idea",Arrays.asList("a",null))).requestId());
        assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,
                ()->service.prepare(MEMBER,req(otherKey,"idea",Arrays.asList(null,"a"))));
    }
    @Test void planAuthorityAndConfigRejectionsKeepOriginalNonce() {
        for(var code:List.of(AiThemeDraftPlanRejectedException.Code.AUTHORITY_CHANGED,AiThemeDraftPlanRejectedException.Code.CONFIG_CHANGED)) {
            doThrow(new AiThemeDraftPlanRejectedException(code)).when(creator).themeDraftPrepared(any(),anyLong(),any());
            var v=service.prepare(MEMBER,req(UUID.randomUUID().toString(),"idea",null)); String nonce=rows.get(v.requestId()).getNonce();
            refusal(v,"AI_DRAFT_"+code.name()); refusal(v,"AI_DRAFT_"+code.name());
            assertEquals("NOT_SENT",rows.get(v.requestId()).getProviderState()); assertEquals(nonce,rows.get(v.requestId()).getNonce());
        }
        verify(creator,times(2)).themeDraftPrepared(any(),anyLong(),any());
    }
    @Test void originalPlanExpiryIsKnownNotSent() {
        var v=prepare(); clock.at.set(v.executeBefore()); refusal(v,"AI_DRAFT_PLAN_EXPIRED");
        assertEquals("NOT_SENT",rows.get(v.requestId()).getProviderState());
        assertEquals(v.requestId(),prepare().requestId()); verify(creator,never()).themeDraftPrepared(any(),anyLong(),any());
    }
    @Test void bodiesHaveFixedDeadlineAndSuccessSurvivesExpiry() {
        var v=prepare(); Instant contextExpiry=rows.get(v.requestId()).getContextExpiresAt();
        assertEquals(clock.instant().plus(Duration.ofHours(24)),contextExpiry); execute(v);
        Instant resultExpiry=rows.get(v.requestId()).getResultExpiresAt(); int writes=bodies.writes;
        clock.at.set(resultExpiry); var expired=service.readIntent(MEMBER,v.requestId());
        assertEquals(AiThemeDraftIntentState.SUCCEEDED,expired.state()); assertFalse(expired.resultAvailable());
        assertEquals("AI_DRAFT_RESULT_EXPIRED",expired.reasonCode()); refusal(v,"AI_DRAFT_RESULT_EXPIRED"); prepare();
        assertEquals(writes,bodies.writes); assertEquals(resultExpiry,rows.get(v.requestId()).getResultExpiresAt());
        verify(creator,times(1)).themeDraftPrepared(any(),anyLong(),any());
    }
    @Test void dbFailureAndAmbiguousClaimCommitNeverStartCreator() {
        var v=prepare(); AtomicBoolean armed=new AtomicBoolean(true);
        doAnswer(c->{ if(rows.values().stream().anyMatch(r->"PROCESSING".equals(r.getState()))&&armed.compareAndSet(true,false))
            throw new IllegalStateException("commit acknowledgement unavailable"); return null; }).when(manager).commit(any());
        assertThrows(AiThemeDraftRequestService.StateUnavailableException.class,()->execute(v));
        refusal(v,"AI_DRAFT_IN_PROGRESS"); verify(creator,never()).themeDraftPrepared(any(),anyLong(),any());
        when(mapper.selectOwned(anyLong(),anyString())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(AiThemeDraftRequestService.StateUnavailableException.class,()->service.readIntent(MEMBER,v.requestId()));
    }
    @Test void missingPrepareResponseReadRecoversOriginalPlanAcrossDisable() {
        assertEquals("AI_DRAFT_PREPARE_UNCONFIRMED",service.readPrepared(MEMBER,PREPARE_ID).reasonCode()); verifyNoInteractions(creator);
        var lost=prepare(); String nonce=rows.get(lost.requestId()).getNonce();
        ReflectionTestUtils.setField(service,"intentEnabled",false);
        assertEquals(lost.requestId(),service.readPrepared(MEMBER,PREPARE_ID).requestId()); assertEquals(nonce,rows.get(lost.requestId()).getNonce());
        verify(creator,times(1)).planThemeDraft(anyLong(),anyString()); verify(creator,never()).themeDraftPrepared(any(),anyLong(),any());
    }
    @Test void contextTamperIsRefusedBeforeClaim() {
        var v=prepare(); bodies.values.put(bodyKey("context",v.requestId()),"{bad}");
        assertEquals(AiThemeDraftIntentState.FAILED,execute(v).state()); assertEquals("NOT_SENT",rows.get(v.requestId()).getProviderState());
        verify(mapper,never()).claim(anyLong(),anyLong(),anyString(),anyString(),anyString(),any(Instant.class));
        verify(creator,never()).themeDraftPrepared(any(),anyLong(),any());
    }
    @Test void purgeUsesTotalKeyBudgetAndPreservesMetadataAndUnrelatedKeys() {
        var v=prepare(); execute(v); bodies.values.put("unrelated:sentinel","keep");
        clock.at.set(rows.get(v.requestId()).getResultExpiresAt().plusMillis(1));
        ReflectionTestUtils.setField(service,"intentEnabled",false);
        assertEquals(1,service.purgeExpiredBodies(1)); assertEquals(1,service.purgeExpiredBodies(1));
        assertEquals("keep",bodies.values.get("unrelated:sentinel")); assertEquals(1,rows.size());
        assertNotNull(rows.get(v.requestId()).getResultHash()); assertThrows(IllegalArgumentException.class,()->service.purgeExpiredBodies(101));
    }
    @Test void readRecoveryHasFixedOwnerCursorAndNoRedisOrCreatorEffects() {
        var original=prepare();AiThemeDraftIntentRow snapshot=copy(rows.get(original.requestId()));
        int reads=bodies.reads,writes=bodies.writes;clearInvocations(creator);
        ReflectionTestUtils.setField(service,"intentEnabled",false);
        var list=service.readRecovery(MEMBER,null);assertEquals(1,list.size());var item=list.get(0);
        assertEquals(original.requestId(),item.requestId());assertEquals(PREPARE_ID,item.prepareKey());assertEquals(snapshot.getId(),item.cursor());
        assertEquals(reads,bodies.reads);assertEquals(writes,bodies.writes);verifyNoInteractions(creator);
        assertEquals(snapshot.getState(),rows.get(original.requestId()).getState());
        assertEquals(snapshot.getExecuteBefore(),rows.get(original.requestId()).getExecuteBefore());
        assertEquals(snapshot.getContextExpiresAt(),rows.get(original.requestId()).getContextExpiresAt());
    }
    @Test void recoveryPaginationKeepsDescendingLookaheadAndOwnerIsolation() {
        var seed=prepare();AiThemeDraftIntentRow template=copy(rows.get(seed.requestId()));rows.clear();
        for(long i=1;i<=42;i++) {
            AiThemeDraftIntentRow r=copy(template);r.setId(i);r.setRequestId("td2_"+String.format("%032x",i));
            r.setPrepareKey(UUID.randomUUID().toString());r.setState(List.of("PREPARED","PROCESSING","SUCCEEDED","FAILED","UNKNOWN").get((int)i%5));
            r.setReasonCode("AI_DRAFT_RECOVERY");r.setResultExpiresAt("SUCCEEDED".equals(r.getState())?clock.instant().minusSeconds(1):null);r.setExecuteBefore(clock.instant().minusSeconds(1));rows.put(r.getRequestId(),r);
        }
        AiThemeDraftIntentRow other=copy(template);other.setId(100L);other.setMemberId(MEMBER+1);other.setRequestId("td2_"+"f".repeat(32));rows.put(other.getRequestId(),other);
        List<Long> shown=new ArrayList<>();Long cursor=null;
        for(int page=0;page<3;page++) {
            var list=service.readRecovery(MEMBER,cursor);assertTrue(list.size()<=21);
            var display=list.subList(0,Math.min(20,list.size()));display.forEach(i->shown.add(i.cursor()));
            if(!display.isEmpty())cursor=display.get(display.size()-1).cursor();
            for(var item:list)assertTrue(item.executeBefore().isBefore(clock.instant()),"expired PREPARED facts remain visible");
        }
        assertEquals(java.util.stream.LongStream.rangeClosed(1,42).boxed().sorted(Comparator.reverseOrder()).toList(),shown);
        assertEquals(1,service.readRecovery(MEMBER+1,null).size());assertTrue(service.readRecovery(MEMBER+2,null).isEmpty());
        var encoded=com.alibaba.fastjson2.JSON.parseObject(com.alibaba.fastjson2.JSON.toJSONString(service.readRecovery(MEMBER,null).get(0),com.alibaba.fastjson2.JSONWriter.Feature.WriteNulls));
        assertEquals(Set.of("cursor","prepareKey","requestId","state","reasonCode","createdAt","executeBefore","resultExpiresAt","productType"),encoded.keySet());
    }
    @Test void recoveryNonPositiveCursorAndDatabaseFailureNeverBecomeEmptySuccess() {
        for(Long cursor:List.of(0L,-1L)) {
            var bad=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,()->service.readRecovery(MEMBER,cursor));
            assertEquals(Integer.valueOf(400),bad.getCode());
        }verify(mapper,never()).selectRecovery(anyLong(),nullable(Long.class));
        when(mapper.selectRecovery(anyLong(),nullable(Long.class))).thenThrow(new IllegalStateException("controlled database outage"));
        assertThrows(AiThemeDraftRequestService.StateUnavailableException.class,()->service.readRecovery(MEMBER,null));
        assertEquals(0,bodies.reads);assertEquals(0,bodies.writes);verifyNoInteractions(creator);
    }

    private AiThemeDraftIntentView prepare(){return service.prepare(MEMBER,req(PREPARE_ID,"idea",null));}
    private AiThemeDraftIntentView execute(AiThemeDraftIntentView v){return service.execute(MEMBER,new AiThemeDraftExecuteReq(v.requestId()));}
    private void refusal(AiThemeDraftIntentView v,String code){
        var e=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,()->execute(v));
        assertEquals(code,e.getErrorCode()); if(e.getIntent()!=null)assertEquals(v.requestId(),e.getIntent().requestId()); }
    private static AiThemeDraftPrepareReq req(String key,String idea,List<String> names){return new AiThemeDraftPrepareReq(key,idea,null,names);}
    private static AiThemeDraftResp draft(){AiThemePlan p=new AiThemePlan();p.setTitle("原草稿");AiThemeDraftResp r=new AiThemeDraftResp();r.setDraft(p);return r;}
    private AiThemeDraftPlan plan(String nonce){
        LocalDate day=LocalDate.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai"));String d=day.format(DateTimeFormatter.BASIC_ISO_DATE);
        String counter="ai:theme:draft:player:"+MEMBER+":"+d;
        return new AiThemeDraftPlan(MEMBER,"player","player-draft",d,counter,nonce,
                "ai:player:theme:draft:reservation:"+counter+":"+nonce,counter+"|"+nonce,"a".repeat(64),"b".repeat(64),
                day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()); }
    private AiThemeDraftIntentRow find(Long member,String key){return rows.values().stream().filter(r->member.equals(r.getMemberId())&&key.equals(r.getPrepareKey()))
            .findFirst().map(AiThemeDraftIntentServiceTest::copy).orElse(null);}
    private AiThemeDraftIntentRow byId(Long id){return rows.values().stream().filter(r->id.equals(r.getId())).findFirst().orElseThrow();}
    private static AiThemeDraftIntentRow copy(AiThemeDraftIntentRow r){AiThemeDraftIntentRow c=new AiThemeDraftIntentRow();BeanUtils.copyProperties(r,c);return c;}
    private static String bodyKey(String kind,String id){return "ai:theme:draft:v2:"+kind+":"+MEMBER+":"+id;}
    static final class MutableClock extends Clock {
        final AtomicReference<Instant> at=new AtomicReference<>(Instant.parse("2026-10-23T01:00:00Z"));
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return at.get();}
    }
    final class MemoryBodies extends RedisTemplate<Object,Object> {
        final Map<String,String> values=new ConcurrentHashMap<>(); int reads,writes; boolean failWrites;
        @SuppressWarnings("unchecked") @Override public <T>T execute(RedisScript<T> script,RedisSerializer<?> argsSerializer,
                RedisSerializer<T> resultSerializer,List<Object> keys,Object...args){
            String key=String.valueOf(keys.get(0));
            if(script.getResultType()==String.class){reads++;long deadline=Long.parseLong(String.valueOf(args[0]));
                if(clock.instant().toEpochMilli()>=deadline){values.remove(key);return null;}return (T)values.get(key);}
            if(args.length==2){writes++;if(failWrites)throw new IllegalStateException("controlled body write outage");
                if(clock.instant().toEpochMilli()>=Long.parseLong(String.valueOf(args[1])))return (T)Long.valueOf(0);
                String old=values.putIfAbsent(key,String.valueOf(args[0]));return (T)Long.valueOf(old==null||old.equals(args[0])?1:-1);}
            if(clock.instant().toEpochMilli()<Long.parseLong(String.valueOf(args[0])))return (T)Long.valueOf(0);
            values.remove(key);return (T)Long.valueOf(1);
        }
    }

    // Input-boundary delta: original 24 scenarios and assertion semantics are retained.
    @Test void prepareRejectsNullIdeaBeforeAnyDependencyAccess() {
        for (boolean enabled : new boolean[]{true,false}) {
            ReflectionTestUtils.setField(service,"intentEnabled",enabled);
            var refusal=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,
                    ()->service.prepare(MEMBER,req(PREPARE_ID,null,null)));
            assertEquals(Integer.valueOf(400),refusal.getCode());
            assertEquals("AI_DRAFT_INVALID_REQUEST",refusal.getErrorCode());
        }
        verifyNoInteractions(mapper,manager,creator);
        assertTrue(rows.isEmpty());assertEquals(0,bodies.reads);assertEquals(0,bodies.writes);
    }
    @Test void prepareRejectsEmptyOrWhitespaceIdeaBeforeAnyDependencyAccess() {
        for (String idea : List.of("", " ", " \t\r\n ")) {
            var refusal=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,
                    ()->service.prepare(MEMBER,req(PREPARE_ID,idea,null)));
            assertEquals(Integer.valueOf(400),refusal.getCode());
            assertEquals("AI_DRAFT_INVALID_REQUEST",refusal.getErrorCode());
        }
        verifyNoInteractions(mapper,manager,creator);
        assertTrue(rows.isEmpty());assertEquals(0,bodies.reads);assertEquals(0,bodies.writes);
    }
    @Test void prepareRejectsRawIdeaOver300EvenIfTrimmedLengthWouldFit() {
        for (String idea : List.of("a".repeat(301), " "+"a".repeat(299)+" ")) {
            assertEquals(301,idea.length());
            var refusal=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,
                    ()->service.prepare(MEMBER,req(PREPARE_ID,idea,null)));
            assertEquals(Integer.valueOf(400),refusal.getCode());
            assertEquals("AI_DRAFT_INVALID_REQUEST",refusal.getErrorCode());
        }
        verifyNoInteractions(mapper,manager,creator);
        assertTrue(rows.isEmpty());assertEquals(0,bodies.reads);assertEquals(0,bodies.writes);
    }
    @Test void prepareAcceptsExactly300AndPreservesOriginalWhitespacePayloadAndHash() {
        String original=" "+"a".repeat(298)+" ";assertEquals(300,original.length());
        var prepared=service.prepare(MEMBER,req(PREPARE_ID,original,null));
        assertEquals(AiThemeDraftIntentState.PREPARED,prepared.state());assertTrue(prepared.contextAvailable());
        String envelope=bodies.values.get(bodyKey("context",prepared.requestId()));
        var payload=com.alibaba.fastjson2.JSON.parseArray(com.alibaba.fastjson2.JSON.parseObject(envelope).getString("payload"));
        assertEquals(original,payload.getString(1));assertNull(payload.get(2));assertNull(payload.get(3));
        assertEquals(prepared.requestId(),service.prepare(MEMBER,req(PREPARE_ID,original,null)).requestId());
        var changed=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,
                ()->service.prepare(MEMBER,req(PREPARE_ID,original.trim(),null)));
        assertEquals("AI_DRAFT_REQUEST_CONFLICT",changed.getErrorCode());
        verify(creator,times(1)).planThemeDraft(eq(MEMBER),anyString());
        verify(creator,never()).themeDraftPrepared(any(),anyLong(),any());
    }

    @Test void prepareRejectsUnknownProductTypesBeforeAnyDependencyAccess() {
        for (Integer invalid : List.of(-1,0,3,Integer.MAX_VALUE)) {
            var rejected=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,
                    ()->service.prepare(MEMBER,new AiThemeDraftPrepareReq(PREPARE_ID,"idea",invalid,null)));
            assertEquals(Integer.valueOf(400),rejected.getCode());assertEquals("AI_DRAFT_INVALID_REQUEST",rejected.getErrorCode());
        }
        verifyNoInteractions(mapper,manager,creator);assertTrue(rows.isEmpty());assertEquals(0,bodies.reads);assertEquals(0,bodies.writes);
    }
    @SuppressWarnings("unchecked")
    @Test void originalProductTypeSurvivesAllViewsAndNullIsNeverDefaulted() {
        for (Integer mode : Arrays.asList(null,1,2)) {
            String id=UUID.randomUUID().toString();var request=new AiThemeDraftPrepareReq(id,"idea",mode,null);
            var prepared=service.prepare(MEMBER,request);assertEquals(mode,prepared.productType());
            assertEquals(mode,service.readPrepared(MEMBER,id).productType());assertEquals(mode,service.readIntent(MEMBER,prepared.requestId()).productType());
            Integer different=mode==null?Integer.valueOf(1):mode==1?Integer.valueOf(2):null;
            var conflict=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,
                    ()->service.prepare(MEMBER,new AiThemeDraftPrepareReq(id,"idea",different,null)));
            assertEquals("AI_DRAFT_REQUEST_CONFLICT",conflict.getErrorCode());
            var completed=execute(prepared);assertEquals(mode,completed.productType());
            assertEquals(mode,service.readRecovery(MEMBER,null).stream().filter(v->v.requestId().equals(prepared.requestId())).findFirst().orElseThrow().productType());
            bodies.values.remove(bodyKey("result",prepared.requestId()));
            var unavailable=service.readIntent(MEMBER,prepared.requestId());assertEquals(AiThemeDraftIntentState.SUCCEEDED,unavailable.state());
            assertFalse(unavailable.resultAvailable());assertEquals(mode,unavailable.productType());
        }
        assertNull(service.readPrepared(MEMBER,UUID.randomUUID().toString()).productType());
        AiThemeDraftRequestService legacy=new AiThemeDraftRequestService();
        RedisTemplate<Object,Object> redis=mock(RedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<Object,Object> values=mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        com.alibaba.fastjson2.JSONObject record=new com.alibaba.fastjson2.JSONObject();record.put("state","SUCCEEDED");record.put("result",draft());
        when(values.get(any())).thenReturn(record.toJSONString());ReflectionTestUtils.setField(legacy,"redisTemplate",redis);
        var old=legacy.readIntent(MEMBER,PREPARE_ID);assertEquals(AiThemeDraftIntentState.SUCCEEDED,old.state());assertTrue(old.resultAvailable());
        assertNull(old.productType(),"legacy result text never supplies a guessed mode");
        verify(creator,times(3)).themeDraftPrepared(any(),eq(MEMBER),any());
    }
    @Test void mismatchedStoredProductTypeCannotReinterpretOriginalContext() {
        var prepared=service.prepare(MEMBER,new AiThemeDraftPrepareReq(PREPARE_ID,"idea",1,null));
        rows.get(prepared.requestId()).setProductType(2); // controlled corrupt metadata, not an application writer
        assertThrows(AiThemeDraftRequestService.StateUnavailableException.class,()->execute(prepared));
        verify(mapper,never()).claim(anyLong(),anyLong(),anyString(),anyString(),anyString(),any(Instant.class));
        verify(creator,never()).themeDraftPrepared(any(),anyLong(),any());
    }
}

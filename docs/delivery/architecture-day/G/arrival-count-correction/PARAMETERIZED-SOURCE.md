# 原六个参数测试源码与24个输入（未修改/未执行）

源：`4cf548c3e8b05c6d23f3252c7e5d063691ef0eaa`，路径：`chengyinhub-admin/src/test/java/com/chengyinhub/business/it/ThemeRuntimeMySqlIT.java`。六个注解+方法正文与acc/29c逐字相同。以下为原源码摘录，不是新增Java文件或测试拆分。

## r5PredictActualRoundSettlementWaitsForRevealAndUsesOriginalAttempt

原行 227—260；原顺序输入 ["PASS", "FAIL", "VOID", "CLOSED", "TIMEOUT", "CLOSED_TIMEOUT", "CONCURRENT"]；预期展开 7。

```java
@org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"PASS","FAIL","VOID","CLOSED","TIMEOUT","CLOSED_TIMEOUT","CONCURRENT"})
    void r5PredictActualRoundSettlementWaitsForRevealAndUsesOriginalAttempt(String result) throws Exception {
        long betTime=java.time.ZonedDateTime.of(2026,10,1,12,0,0,0,java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();advancedNow.set(betTime);
        Map<String,Object> arrived=personalTask(false,null,"BLOCK_HP_COST",false,result.endsWith("TIMEOUT"),false,"predict");long routeId=((Number)arrived.get("sessionId")).longValue();
        runtime.gameplayAction(7L,routeId,((Number)arrived.get("version")).intValue(),"predict-start","QUEST_START","north",null);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=advancedService();Map<String,Object> opened=advanced.start(7L,0L,10L,11L,null);long id=((Number)opened.get("sessionId")).longValue();
        advanced.act(7L,id,0,"bet","SUBMIT_PREDICT",Collections.singletonMap("optionKey","rain"));
        com.chengyinhub.business.mapper.PlayPredictMapper predictions=sql.getMapper(PlayPredictMapper.class);java.sql.Date day=java.sql.Date.valueOf("2026-10-01");PlayPredictBet bet=predictions.selectBet(11L,day,7L);assertNotNull(bet.getId());assertEquals(0,bet.getRewardStatus());
        assertEquals(6,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson()).path("variables").path("sys.hp").asInt());
        if("CLOSED_TIMEOUT".equals(result))try(Connection c=ds.getConnection()){exec(c,"UPDATE topic_route_session SET status=2 WHERE id="+routeId);}
        if(result.endsWith("TIMEOUT")){
            advancedNow.set(betTime+31000);JsonNode settled=JSON.valueToTree(advanced.state(7L,id));
            if("TIMEOUT".equals(result)){
                String routeBefore=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson();
                assertEquals(settled,JSON.valueToTree(advanced.start(7L,0L,10L,11L,null)));
                assertEquals(routeBefore,sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson());
            }
        }
        com.chengyinhub.business.service.impl.PlayPredictServiceImpl merchantTarget=new com.chengyinhub.business.service.impl.PlayPredictServiceImpl();ReflectionTestUtils.setField(merchantTarget,"predictMapper",predictions);ReflectionTestUtils.setField(merchantTarget,"nodeMapper",sql.getMapper(CmsTopicNodeMapper.class));ReflectionTestUtils.setField(merchantTarget,"templateMapper",sql.getMapper(CmsMemberTemplateMapper.class));
        ProxyFactory merchantProxy=new ProxyFactory(merchantTarget);merchantProxy.setInterfaces(com.chengyinhub.business.service.IPlayPredictService.class);merchantProxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));com.chengyinhub.business.service.IPlayPredictService merchant=(com.chengyinhub.business.service.IPlayPredictService)merchantProxy.getProxy();
        if("VOID".equals(result))merchant.voidExpiredRounds(24);else merchant.settle(11L,day,"PASS".equals(result)||"CONCURRENT".equals(result)?"rain":"sun",30L);
        JsonNode early=JSON.valueToTree(advanced.state(7L,id));assertEquals(0,early.path("playKit").path("predict").path("settleStatus").asInt());assertFalse(early.path("playKit").path("predict").has("settledOption"));
        if("CLOSED".equals(result))try(Connection c=ds.getConnection()){exec(c,"UPDATE topic_route_session SET status=2 WHERE id="+routeId);}
        advancedNow.set(betTime+13*3600000);
        if("CONCURRENT".equals(result)){ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<?> a=pool.submit(()->advanced.state(7L,id));Future<?> b=pool.submit(()->advanced.state(7L,id));a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);}finally{pool.shutdownNow();}}
        JsonNode revealed=JSON.valueToTree(advanced.state(7L,id));JsonNode owner=JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson());JsonNode advancedState=JSON.readTree(sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(id,7L).getStateJson());
        assertTrue(advancedState.path("predict").path("revealRecorded").asBoolean());assertEquals(bet.getId().longValue(),advancedState.path("predict").path("betId").asLong());
        if(Arrays.asList("PASS","CONCURRENT").contains(result)){assertEquals("PASS",advancedState.path("gameplayDecision").path("outcome").asText());assertEquals(6,owner.path("variables").path("sys.hp").asInt());runtime.gameplayAction(7L,routeId,sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getVersion(),"predict-confirm","QUEST_RESOLVE","north",null);assertEquals(4,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson()).path("variables").path("sys.hp").asInt());}
        else if("FAIL".equals(result)){assertEquals(4,owner.path("variables").path("sys.hp").asInt());assertEquals("FAIL",advancedState.path("gameplayDecision").path("outcome").asText());}
        else if(result.endsWith("TIMEOUT")){assertEquals("TIMEOUT",advancedState.path("gameplayDecision").path("reason").asText());assertEquals("CLOSED_TIMEOUT".equals(result)?6:3,owner.path("variables").path("sys.hp").asInt());}
        else {assertEquals(6,owner.path("variables").path("sys.hp").asInt());assertFalse(advancedState.has("gameplayDecision"));assertFalse(owner.path("themeRuntime").has("outcomeAttempts"));}
        int version=sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(id,7L).getVersion();advanced.state(7L,id);assertEquals(version,sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(id,7L).getVersion());
    }
```

## r5TypeInActualFrozenTimerOverridesLegacySeconds

原行 261—266；原顺序输入 [false, true]；预期展开 2。

```java
@org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void r5TypeInActualFrozenTimerOverridesLegacySeconds(boolean timer) throws Exception {
        long started=System.currentTimeMillis();advancedNow.set(started);Map<String,Object> arrived=personalTask(false,null,"BLOCK_HP_COST",false,timer,false,"typeIn");long routeId=((Number)arrived.get("sessionId")).longValue();runtime.gameplayAction(7L,routeId,((Number)arrived.get("version")).intValue(),"typein-start","QUEST_START","north",null);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=advancedService();Map<String,Object> opened=advanced.start(7L,0L,10L,11L,null);long id=((Number)opened.get("sessionId")).longValue();Map<String,Object> begun=advanced.act(7L,id,0,"typein-clock","START_CHALLENGE",Collections.singletonMap("game","typeIn"));advancedNow.set(started+20000);Map<String,Object> answer=new LinkedHashMap<>();answer.put("text","hello");answer.put("elapsedMs",20000);assertEquals("COMPLETED",advanced.act(7L,id,((Number)begun.get("version")).intValue(),"typein-submit","SUBMIT_TYPE_IN",answer).get("status"));assertEquals(6,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson()).path("variables").path("sys.hp").asInt());
    }
```

## r5ActualCompletionSaveUsesOnePendingConfirmationAndReplaysNoSecondFee

原行 315—328；原顺序输入 ["profile", "note", "dailySign"]；预期展开 3。

```java
@org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"profile","note","dailySign"})
    void r5ActualCompletionSaveUsesOnePendingConfirmationAndReplaysNoSecondFee(String kit) throws Exception {
        try(Connection c=ds.getConnection()){exec(c,"DELETE FROM play_note");}
        Map<String,Object> initial=personalTask(false,null,"BLOCK_HP_COST",false,false,false,kit);long id=((Number)initial.get("sessionId")).longValue();runtime.gameplayAction(7L,id,((Number)initial.get("version")).intValue(),"start","QUEST_START","north",null);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=advancedService();Map<String,Object> opened=advanced.start(7L,0L,10L,11L,null);long advancedId=((Number)opened.get("sessionId")).longValue();String action="profile".equals(kit)?"SUBMIT_PROFILE":"note".equals(kit)?"SUBMIT_NOTE":"CLAIM_DAILY_SIGN";
        Map<String,Object> payload=new java.util.LinkedHashMap<>();if("profile".equals(kit))payload.put("answers",Collections.singletonMap("name","小满"));if("note".equals(kit))payload.put("text","留下一句话");
        assertEquals(true,advanced.act(7L,advancedId,((Number)opened.get("version")).intValue(),"complete",action,payload).get("readyForBase"));
        assertEquals(6,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson()).path("variables").path("sys.hp").asInt());
        advanced.act(7L,advancedId,1,"complete-replay",action,payload);
        TopicRouteSession row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);runtime.gameplayAction(7L,id,row.getVersion(),"confirm","QUEST_RESOLVE","north",null);
        assertEquals(5,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson()).path("variables").path("sys.hp").asInt());
        if("note".equals(kit))try(Connection c=ds.getConnection();Statement st=c.createStatement();ResultSet result=st.executeQuery("SELECT count(*) FROM play_note WHERE member_id=7 AND node_id=11")){result.next();assertEquals(1,result.getInt(1));}
    }
```

## r5PhotoAlbumAndStepsPublishFreezeAndConfirmTheirRealSave

原行 329—341；原顺序输入 ["steps", "qaShot", "photoCheck", "objectCard", "album"]；预期展开 5。

```java
@org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"steps","qaShot","photoCheck","objectCard","album"})
    void r5PhotoAlbumAndStepsPublishFreezeAndConfirmTheirRealSave(String kit) throws Exception {
        Map<String,Object> initial=personalTask(false,null,"BLOCK_HP_COST",false,false,false,kit);long id=((Number)initial.get("sessionId")).longValue();runtime.gameplayAction(7L,id,((Number)initial.get("version")).intValue(),"start","QUEST_START","north",null);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=advancedService();Object advancedTarget=((org.springframework.aop.framework.Advised)advanced).getTargetSource().getTarget();
        ReflectionTestUtils.setField(advancedTarget,"verifiedWeRunService",R5WeRunFixture.service());ReflectionTestUtils.setField(advancedTarget,"photoEvidenceVerifier",(java.util.function.BiConsumer<String,Long>)(url,member)->{if(!url.startsWith("fixture-owned-"+member))throw new IllegalArgumentException("foreign upload");});
        AdvancedPhotoMySqlFixture.install((com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl)advancedTarget, sql, ds, com.chengyinhub.business.service.ai.PhotoCheckVisionResult.concluded(true,99,"passed"));
        Map<String,Object> opened=advanced.start(7L,0L,10L,11L,null);long advancedId=((Number)opened.get("sessionId")).longValue();Map<String,Object> payload=new java.util.LinkedHashMap<>();String action;
        if("steps".equals(kit)){payload=JSON.convertValue(R5WeRunFixture.today(advancedNow.get(),7,"",100),Map.class);payload.remove("episodeId");action="SUBMIT_STEPS";}
        else if("album".equals(kit)){payload.put("imageUrls",Arrays.asList("fixture-owned-7-a","fixture-owned-7-b"));action="SUBMIT_ALBUM";}
        else{payload.put("imageUrl","fixture-owned-7-a");action="qaShot".equals(kit)?"SUBMIT_QA":"SUBMIT_PHOTO_CHECK";}
        assertEquals(true,advanced.act(7L,advancedId,((Number)opened.get("version")).intValue(),"complete",action,payload).get("readyForBase"));assertEquals(6,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson()).path("variables").path("sys.hp").asInt());
        TopicRouteSession row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);runtime.gameplayAction(7L,id,row.getVersion(),"confirm","QUEST_RESOLVE","north",null);assertEquals(5,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson()).path("variables").path("sys.hp").asInt());
    }
```

## photoOriginalResultSurvivesLocalRollbackAndSameCommandAppliesOnce

原行 342—436；原顺序输入 ["CAS", "RECEIPT", "APPLIED", "CONCURRENT"]；预期展开 4。

```java
@org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"CAS","RECEIPT","APPLIED","CONCURRENT"})
    void photoOriginalResultSurvivesLocalRollbackAndSameCommandAppliesOnce(String fault) throws Exception {
        Map<String,Object> initial=personalTask(false,null,"BLOCK_HP_COST",false,false,false,"photoCheck");
        long routeId=((Number)initial.get("sessionId")).longValue();
        runtime.gameplayAction(7L,routeId,((Number)initial.get("version")).intValue(),"photo-route-start","QUEST_START","north",null);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=advancedService();
        com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl advancedTarget=
                (com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl)((org.springframework.aop.framework.Advised)advanced).getTargetSource().getTarget();
        com.chengyinhub.business.service.ai.PhotoCheckVisionService vision=AdvancedPhotoMySqlFixture.install(advancedTarget,sql,ds,
                com.chengyinhub.business.service.ai.PhotoCheckVisionResult.concluded(true,99,"first saved result"));
        ReflectionTestUtils.setField(advancedTarget,"photoEvidenceVerifier",(java.util.function.BiConsumer<String,Long>)(url,member)->
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()));
        Map<String,Object> opened=advanced.start(7L,0L,10L,11L,null);
        long sessionId=((Number)opened.get("sessionId")).longValue();int version=((Number)opened.get("version")).intValue();
        GameRuntimeSessionMapper realSessions=sql.getMapper(GameRuntimeSessionMapper.class);
        PlayAttemptLogMapper realAttempts=sql.getMapper(PlayAttemptLogMapper.class);
        GameRuntimeSession before=realSessions.selectAccessibleById(sessionId,7L);
        String routeBefore=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson();
        com.chengyinhub.business.service.ai.PhotoCheckIntentService actualIntents =
                (com.chengyinhub.business.service.ai.PhotoCheckIntentService)
                        ReflectionTestUtils.getField(advancedTarget, "photoCheckIntentService");
        PhotoCheckIntentMapper realPhotoRows = sql.getMapper(PhotoCheckIntentMapper.class);
        if("CAS".equals(fault)) {
            GameRuntimeSessionMapper failing=mock(GameRuntimeSessionMapper.class,org.mockito.AdditionalAnswers.delegatesTo(realSessions));
            doReturn(0).when(failing).updateStateCas(any());ReflectionTestUtils.setField(advancedTarget,"sessionMapper",failing);
        } else if ("RECEIPT".equals(fault)) {
            PlayAttemptLogMapper failing=mock(PlayAttemptLogMapper.class,org.mockito.AdditionalAnswers.delegatesTo(realAttempts));
            doThrow(new IllegalStateException("fixture receipt insert failed")).when(failing).insertPlayAttemptLog(any());
            ReflectionTestUtils.setField(advancedTarget,"attemptMapper",failing);
        }
        if ("APPLIED".equals(fault)) {
            PhotoCheckIntentMapper failing = mock(PhotoCheckIntentMapper.class,
                    org.mockito.AdditionalAnswers.delegatesTo(realPhotoRows));
            doAnswer(i -> {
                assertEquals(1, realPhotoRows.markApplied(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
                throw new IllegalStateException("fixture failure after actual APPLIED update");
            }).when(failing).markApplied(anyLong(), anyString(), anyLong());
            ReflectionTestUtils.setField(actualIntents, "mapper", failing);
        }
        Map<String,Object> payload=Collections.singletonMap("imageUrl","media-asset:701");
        if ("CONCURRENT".equals(fault)) {
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            com.chengyinhub.business.service.ai.PhotoCheckVisionService raceVision=AdvancedPhotoMySqlFixture.install(advancedTarget,sql,ds,
                    com.chengyinhub.business.service.ai.PhotoCheckVisionResult.concluded(true,99,"first saved result"),()->{
                        entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}
                        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                    });
            ExecutorService pool=Executors.newFixedThreadPool(2);
            try {
                Future<Map<String,Object>> first=pool.submit(()->advanced.act(7L,sessionId,version,"photo-fault","SUBMIT_PHOTO_CHECK",payload));
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                Future<Throwable> second=pool.submit(()->{
                    try{advanced.act(7L,sessionId,version,"photo-fault","SUBMIT_PHOTO_CHECK",payload);return null;}
                    catch(Throwable outcome){return outcome;}
                });
                assertInstanceOf(com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException.class,second.get(5,TimeUnit.SECONDS),
                        "loser must read IN_FLIGHT without waiting on provider-held business locks or inventing fallback");
                sql.clearCache();assertEquals(before.getVersion(),realSessions.selectAccessibleById(sessionId,7L).getVersion());
                assertEquals(JSON.readTree(routeBefore),JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson()));
                String originalKey=ReflectionTestUtils.invokeMethod(advancedTarget,"scopedActionKey",before,7L,"photo-fault");
                assertNull(realAttempts.selectByIdempotencyKey(originalKey));
                PhotoCheckIntentRow inFlight=sql.getMapper(PhotoCheckIntentMapper.class).selectByKey(7L,sessionId,originalKey);
                assertEquals("IN_FLIGHT",inFlight.getState());String originalNonce=inFlight.getNonce();
                release.countDown();Map<String,Object> applied=first.get(10,TimeUnit.SECONDS);
                assertEquals(JSON.valueToTree(applied),JSON.valueToTree(advanced.act(7L,sessionId,version,"photo-fault","SUBMIT_PHOTO_CHECK",payload)));
                sql.clearCache();PhotoCheckIntentRow saved=sql.getMapper(PhotoCheckIntentMapper.class).selectByKey(7L,sessionId,originalKey);
                assertEquals("APPLIED",saved.getState());assertEquals(originalNonce,saved.getNonce());
                assertEquals(realAttempts.selectByIdempotencyKey(originalKey).getId(),saved.getAttemptLogId());
                verify(raceVision,times(1)).reviewPrepared(anyLong(),anyString(),anyString(),anyString(),any());
            } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
            return;
        }
        assertThrows(com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException.class,
                ()->advanced.act(7L,sessionId,version,"photo-fault","SUBMIT_PHOTO_CHECK",payload));
        sql.clearCache();GameRuntimeSession rolledBack=realSessions.selectAccessibleById(sessionId,7L);
        assertEquals(before.getVersion(),rolledBack.getVersion());assertEquals(before.getScore(),rolledBack.getScore());
        assertEquals(JSON.readTree(before.getStateJson()),JSON.readTree(rolledBack.getStateJson()));
        assertEquals(JSON.readTree(routeBefore),JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson()));
        String scopedKey=ReflectionTestUtils.invokeMethod(advancedTarget,"scopedActionKey",before,7L,"photo-fault");
        assertNull(realAttempts.selectByIdempotencyKey(scopedKey));
        PhotoCheckIntentMapper photoRows=sql.getMapper(PhotoCheckIntentMapper.class);
        PhotoCheckIntentRow ready=photoRows.selectByKey(7L,sessionId,scopedKey);
        assertNotNull(ready);assertEquals("RESULT_READY",ready.getState());assertEquals("RECEIVED",ready.getProviderState());
        String originalNonce=ready.getNonce();assertEquals("first saved result",ready.getResultReason());
        ReflectionTestUtils.setField(actualIntents, "mapper", realPhotoRows);
        ReflectionTestUtils.setField(advancedTarget,"sessionMapper",realSessions);ReflectionTestUtils.setField(advancedTarget,"attemptMapper",realAttempts);
        Map<String,Object> applied=advanced.act(7L,sessionId,version,"photo-fault","SUBMIT_PHOTO_CHECK",payload);
        Map<String,Object> replay=advanced.act(7L,sessionId,version,"photo-fault","SUBMIT_PHOTO_CHECK",payload);
        assertEquals(JSON.valueToTree(applied),JSON.valueToTree(replay));sql.clearCache();
        PhotoCheckIntentRow saved=photoRows.selectByKey(7L,sessionId,scopedKey);
        assertEquals("APPLIED",saved.getState());assertEquals(originalNonce,saved.getNonce());
        assertEquals(realAttempts.selectByIdempotencyKey(scopedKey).getId(),saved.getAttemptLogId());
        verify(vision,times(1)).reviewPrepared(anyLong(),anyString(),anyString(),anyString(),any());
    }
```

## r5FeedbackStartsOnlyAfterSettlementAndNeverResettlesTheMainResult

原行 438—475；原顺序输入 ["PASS", "FAIL", "ENDED"]；预期展开 3。

```java
@org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"PASS","FAIL","ENDED"})
    void r5FeedbackStartsOnlyAfterSettlementAndNeverResettlesTheMainResult(String outcome) throws Exception {
        Map<String,Object> initial=personalTask(false,null,"BLOCK_HP_COST",false,false,false,"qaFeedback");long id=((Number)initial.get("sessionId")).longValue();runtime.gameplayAction(7L,id,((Number)initial.get("version")).intValue(),"start","QUEST_START","north",null);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=advancedService();assertNotNull(runtime.publishedR5NodeConfig(7L,10L,11L,0L),"published R5 provenance missing");Map<String,Object> opened=advanced.start(7L,0L,10L,11L,null);long advancedId=((Number)opened.get("sessionId")).longValue();assertEquals("",JSON.valueToTree(opened).path("branch").path("currentStepId").asText());assertThrows(IllegalStateException.class,()->advanced.act(7L,advancedId,0,"early-branch","CHOOSE",Collections.singletonMap("optionId","continue")));
        Map<String,Object> judged=advanced.act(7L,advancedId,0,"judge","SUBMIT_QA",Collections.singletonMap("input","FAIL".equals(outcome)?"wrong":"correct"));if(!"FAIL".equals(outcome)){assertEquals("",JSON.valueToTree(judged).path("branch").path("currentStepId").asText());TopicRouteSession row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);runtime.gameplayAction(7L,id,row.getVersion(),"confirm","QUEST_RESOLVE","north",null);}
        TopicRouteSession row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);String receipt=JSON.readTree(row.getStateJson()).path("themeRuntime").path("lastOutcome").toString();int hp=JSON.readTree(row.getStateJson()).path("variables").path("sys.hp").asInt();
        if("ENDED".equals(outcome)){row.setStatus(TopicRouteSession.STATUS_COMPLETED);sql.getMapper(TopicRouteSessionMapper.class).updateStateCas(row);assertFalse(JSON.valueToTree(advanced.state(7L,advancedId)).path("branch").path("writable").asBoolean());assertThrows(RuntimeException.class,()->advanced.act(7L,advancedId,1,"ended-choice","CHOOSE",Collections.singletonMap("optionId","continue")));}
        else {
            int choiceExpected=((Number)judged.get("version")).intValue();
            Map<String,Object> choicePayload=Collections.singletonMap("optionId","continue");
            GameRuntimeSession originalChoice=sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(advancedId,7L);
            Object advancedTarget=((org.springframework.aop.framework.Advised)advanced).getTargetSource().getTarget();
            String choiceKey=ReflectionTestUtils.invokeMethod(advancedTarget,"scopedActionKey",originalChoice,7L,"feedback-choice");
            String choiceFingerprint=ReflectionTestUtils.invokeMethod(advancedTarget,"commandFingerprint",originalChoice,7L,choiceExpected,"feedback-choice","CHOOSE",choicePayload);
            Map<String,Object> done=advanced.act(7L,advancedId,choiceExpected,"feedback-choice","CHOOSE",choicePayload);
            assertTrue(JSON.valueToTree(done).path("branch").path("ended").asBoolean());
            PlayAttemptLog choiceReceipt=sql.getMapper(PlayAttemptLogMapper.class).selectByIdempotencyKey(choiceKey);
            assertNotNull(choiceReceipt);assertEquals(Integer.valueOf(8),choiceReceipt.getAttemptType());
            assertEquals(choiceFingerprint,choiceReceipt.getRequestFingerprint(),"receipt must bind original CHOOSE, not its feedback effects");
            Map<String,Object> replay=advanced.act(7L,advancedId,choiceExpected,"feedback-choice","CHOOSE",choicePayload);
            assertEquals(JSON.readTree(JSON.writeValueAsString(done)),JSON.readTree(JSON.writeValueAsString(replay)));
            GameRuntimeSession afterChoice=sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(advancedId,7L);
            TopicRouteSession afterFeedback=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);
            assertConflict409(()->advanced.act(7L,advancedId,choiceExpected+1,"feedback-choice","CHOOSE",choicePayload));
            assertConflict409(()->advanced.act(7L,advancedId,choiceExpected,"feedback-choice","CHOOSE",Collections.singletonMap("optionId","different")));
            assertConflict409(()->advanced.act(7L,advancedId,choiceExpected,"feedback-choice","SUBMIT_QA",choicePayload));
            GameRuntimeSession afterRefusals=sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(advancedId,7L);
            assertEquals(afterChoice.getVersion(),afterRefusals.getVersion());assertEquals(afterChoice.getStateJson(),afterRefusals.getStateJson());
            assertEquals(afterChoice.getScore(),afterRefusals.getScore());assertEquals(afterChoice.getStatus(),afterRefusals.getStatus());
            TopicRouteSession routeAfterRefusals=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);
            assertEquals(afterFeedback.getVersion(),routeAfterRefusals.getVersion());assertEquals(afterFeedback.getStateJson(),routeAfterRefusals.getStateJson());
            PlayAttemptLog unchangedReceipt=sql.getMapper(PlayAttemptLogMapper.class).selectByIdempotencyKey(choiceKey);
            assertEquals(choiceReceipt.getId(),unchangedReceipt.getId());assertEquals(choiceReceipt.getRequestFingerprint(),unchangedReceipt.getRequestFingerprint());assertEquals(choiceReceipt.getResultJson(),unchangedReceipt.getResultJson());
            assertEquals(hp+1,JSON.readTree(routeAfterRefusals.getStateJson()).path("variables").path("sys.hp").asInt());
            assertThrows(IllegalStateException.class,()->advanced.act(7L,advancedId,((Number)done.get("version")).intValue(),"new-key-terminal","CHOOSE",choicePayload));
        }
        assertEquals(receipt,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson()).path("themeRuntime").path("lastOutcome").toString());
    }
```

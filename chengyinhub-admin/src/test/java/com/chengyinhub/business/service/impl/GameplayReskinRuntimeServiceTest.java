package com.chengyinhub.business.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameplayReskinRuntimeServiceTest {
    @Test void allThreeConfigurationsSavePublishAndFreezeThroughTheSameServices() throws Exception {
        GameplayReskinFixture fixture=new GameplayReskinFixture();
        java.util.Set<Long> declarationIds=new java.util.HashSet<>();
        java.util.Map<Long,JsonNode> registeredByTopic=new java.util.LinkedHashMap<>();
        java.util.Map<Long,String> publishedByTopic=new java.util.LinkedHashMap<>();
        java.util.Map<com.chengyinhub.business.domain.GameSession,String> originalRuns=new java.util.LinkedHashMap<>();
        JsonNode reference=GameplayReskinFixture.resource("reference.json").path("themeRuntimeConfig");
        assertEquals("mobile",reference.path("chapters").path(2).path("completion").path("overflowSlot").asText());
        for(JsonNode q:reference.path("gameplayRuntime").path("quests"))if("a3".equals(q.path("actKey").asText()))assertFalse(q.has("extraSlot"));
        for(JsonNode skin:GameplayReskinFixture.variants()) {
            fixture.publish(skin);
            Long topicId=fixture.topic.getId();
            java.util.List<com.chengyinhub.business.domain.ThemeStateKey> declarations=fixture.keyMapper.selectByTopicId(topicId);
            assertTrue(declarations.size()>=fixture.keys.size());
            assertEquals(GameplayReskinFixture.JSON.valueToTree(declarations),
                    GameplayReskinFixture.JSON.valueToTree(fixture.keyMapper.selectByTopicIdForUpdate(topicId)));
            long previousId=0;
            for(com.chengyinhub.business.domain.ThemeStateKey row:declarations) {
                assertEquals(topicId,row.getTopicId());assertNotNull(row.getId());
                assertTrue(row.getId()>previousId);previousId=row.getId();
                assertTrue(declarationIds.add(row.getId()),"不同主题声明不得借用同一行身份");
                assertNotNull(row.getCreateTime());assertNotNull(row.getUpdateTime());
                assertEquals(GameplayReskinFixture.JSON.valueToTree(row),GameplayReskinFixture.JSON.valueToTree(
                        fixture.keyMapper.selectByTopicIdAndStateKey(topicId,row.getStateKey())));
                assertEquals(GameplayReskinFixture.JSON.valueToTree(row),GameplayReskinFixture.JSON.valueToTree(
                        fixture.keyMapper.selectByTopicIdAndStateKeyForUpdate(topicId,row.getStateKey())));
            }
            registeredByTopic.put(topicId,GameplayReskinFixture.JSON.valueToTree(declarations));
            publishedByTopic.put(topicId,fixture.topic.getPublishedRouteGraphJson());
            JsonNode theme=fixture.snapshot.path("themeConfig");
            assertEquals(skin.path("hpName").asText(),theme.path("hpName").asText());
            assertEquals(skin.path("actionName").asText(),theme.path("actionName").asText());
            assertEquals("BLOCK_OVERFLOW",theme.path("gameplayRuntime").path("survival").path("actionLimitPolicy").asText());
            assertEquals("CHALLENGE",theme.path("gameplayRuntime").path("survival").path("zeroHpPolicy").asText());
            assertEquals(14,theme.path("capacity").asInt());assertEquals(5,theme.path("items").size());
            assertEquals(2,theme.path("items").path(1).path("slotCost").asInt());
            for(int item=0;item<5;item++)assertEquals(skin.path("itemNames").path(item).asText(),theme.path("items").path(item).path("name").asText());
            assertEquals(skin.path("endingCount").asInt(),theme.path("gameplayRuntime").path("plans").size());
            assertEquals(skin.path("endingCount").asInt()+1,theme.path("endings").size());
            assertEquals(2,theme.path("gameplayRuntime").path("acts").path(2).path("completionRule").path("quantity").asInt());
            assertFalse(theme.path("gameplayRuntime").path("acts").path(2).path("completionRule").has("overflowSlot"));
            for(JsonNode q:theme.path("gameplayRuntime").path("quests"))if("a3".equals(q.path("actKey").asText())){assertEquals("mobile",q.path("extraSlot").asText());assertFalse(q.has("extraOnly"));}
            assertFalse(fixture.snapshot.path("journeyRules").path("checkEnabled").asBoolean());
            assertFalse(fixture.snapshot.path("journeyRules").path("luck").path("enabled").asBoolean());
            fixture.startRun();assertEquals(skin.path("initialHp").asInt(),fixture.hp(7));
            java.util.Map<String,Object> player=fixture.play.view(com.chengyinhub.business.domain.dto.GameActorContext.player(7L),70L).getPlayer();
            assertEquals(skin.path("hpName").asText(),player.get("hpName"));assertEquals(skin.path("actionName").asText(),player.get("actionName"));
            assertEquals(1,fixture.stock(7,"ration"));assertEquals(1,fixture.stock(7,"fuel"));assertEquals(1,fixture.stock(7,"power"));
            originalRuns.put(fixture.session,fixture.session.getModuleConfigSnapshotJson());
            for(java.util.Map.Entry<Long,JsonNode> original:registeredByTopic.entrySet())
                assertEquals(original.getValue(),GameplayReskinFixture.JSON.valueToTree(fixture.keyMapper.selectByTopicId(original.getKey())),"注册后续主题不得重绑旧声明");
        }
        assertEquals(3,registeredByTopic.size());
        Long firstTopic=registeredByTopic.keySet().iterator().next();
        java.util.List<com.chengyinhub.business.domain.ThemeStateKey> originalRows=fixture.keyMapper.selectByTopicId(firstTopic);
        com.chengyinhub.business.domain.ThemeStateKey changed=originalRows.stream()
                .filter(row->"clue.rescue".equals(row.getStateKey())).findFirst().orElseThrow();
        Long originalId=changed.getId();java.util.Date created=changed.getCreateTime();
        changed.setLabel("作者后改声明");changed.setInitialValue(1);changed.setColor("#112233");
        fixture.registerDeclarations(firstTopic,java.util.Collections.singletonList(changed));
        com.chengyinhub.business.domain.ThemeStateKey partial=new com.chengyinhub.business.domain.ThemeStateKey();
        partial.setStateKey(changed.getStateKey());partial.setKeyType(changed.getKeyType());partial.setScope(changed.getScope());
        partial.setLabel("作者再次修改");partial.setInitialValue(0);
        fixture.registerDeclarations(firstTopic,java.util.Collections.singletonList(partial));
        java.util.List<com.chengyinhub.business.domain.ThemeStateKey> updatedRows=fixture.keyMapper.selectByTopicId(firstTopic);
        assertEquals(originalRows.size(),updatedRows.size(),"同主题同键重放只更新原行");
        com.chengyinhub.business.domain.ThemeStateKey updated=updatedRows.stream()
                .filter(row->changed.getStateKey().equals(row.getStateKey())).findFirst().orElseThrow();
        assertEquals(originalId,updated.getId());assertEquals(created,updated.getCreateTime());
        assertEquals("作者再次修改",updated.getLabel());assertEquals(Integer.valueOf(0),updated.getInitialValue());
        assertEquals("#112233",updated.getColor(),"原 upsert 的空扩展字段保留已有定义");
        assertEquals(changed.getKeyType(),updated.getKeyType());assertEquals(changed.getScope(),updated.getScope());
        for(java.util.Map.Entry<Long,JsonNode> original:registeredByTopic.entrySet()) {
            if(!firstTopic.equals(original.getKey()))
                assertEquals(original.getValue(),GameplayReskinFixture.JSON.valueToTree(fixture.keyMapper.selectByTopicId(original.getKey())));
            assertEquals(publishedByTopic.get(original.getKey()),fixture.topics.get(original.getKey()).getPublishedRouteGraphJson());
            assertEquals(GameplayReskinFixture.JSON.readTree(publishedByTopic.get(original.getKey())).path("stateKeys"),
                    GameplayReskinFixture.JSON.readTree(fixture.route.freezePublishedJourney(fixture.topics.get(original.getKey()))).path("stateKeys"),
                    "作者后改声明不能修补原发布冻结定义");
        }
        for(java.util.Map.Entry<com.chengyinhub.business.domain.GameSession,String> original:originalRuns.entrySet())
            assertEquals(original.getValue(),original.getKey().getModuleConfigSnapshotJson(),"后续主题和作者修改不得回填原局");
        Long anotherTopic=registeredByTopic.keySet().stream().filter(id->!firstTopic.equals(id)).findFirst().orElseThrow();
        assertThrows(IllegalStateException.class,()->com.chengyinhub.business.service.support.ThemeStateKeyDeclarationSnapshot.capture(anotherTopic,updatedRows));
        updated.setTopicId(anotherTopic);updated.setLabel("修改读取副本");
        assertEquals(firstTopic,fixture.keyMapper.selectByTopicId(firstTopic).stream()
                .filter(row->originalId.equals(row.getId())).findFirst().orElseThrow().getTopicId());
        for(com.chengyinhub.business.domain.ThemeStateKey prototype:fixture.keys) {
            assertNull(prototype.getId());assertNull(prototype.getTopicId());
        }
    }

    @Test void templateFeesAndRationsUseTheBoundResourceAndBusinessReplayIsFree() throws Exception {
        for(JsonNode skin:GameplayReskinFixture.variants()) {
            GameplayReskinFixture f=new GameplayReskinFixture();f.publish(skin);f.startRun();
            assertThrows(com.chengyinhub.business.service.GameSessionException.class,()->f.resolve(7,1001,"food",null));
            f.unlock("a1");f.resolve(7,1001,"food",null);
            assertEquals(skin.path("initialHp").asInt()-1,f.hp(7));assertEquals(3,f.stock(7,"ration"));
            assertEquals(1,f.act(7));assertTrue(f.state(7).path("themeRuntime").path("gameplay").path("awaitingUnlock").asBoolean());
            int hp=f.hp(7),food=f.stock(7,"ration");
            f.command(7,"QUEST_RESOLVE",f.payload().put("questKey","q1001").put("choiceKey","food"));
            assertEquals(hp,f.hp(7));assertEquals(food,f.stock(7,"ration"));
            assertThrows(com.chengyinhub.business.service.GameSessionException.class,()->f.command(8,"ITEM_USE",f.payload().put("contentId","ration")));
            f.command(7,"ITEM_USE",f.payload().put("contentId","ration"));
            assertEquals(hp+skin.path("rationDelta").asInt(),f.hp(7));assertEquals(2,f.stock(7,"ration"));
            f.command(7,"RECOVER",f.payload().put("recoveryKey","rest"));
            f.command(7,"RECOVER",f.payload().put("recoveryKey","rest"));
            assertEquals(skin.path("maxHp").asInt(),f.hp(7));
            assertThrows(com.chengyinhub.business.service.GameSessionException.class,()->f.command(7,"ITEM_USE",f.payload().put("contentId","ration")));
            assertEquals(2,f.stock(7,"ration"));assertEquals(1,f.state(7).path("variables").path("sys.actions").asInt());
        }
    }

    @Test void zeroHpUsesTheSameTeamChallengeOrQuestionAlternativeAfterReskin() throws Exception {
        for(JsonNode original:GameplayReskinFixture.variants()) for(boolean alternative:new boolean[]{false,true}) {
            com.fasterxml.jackson.databind.node.ObjectNode skin=original.deepCopy();skin.put("initialHp",1);
            GameplayReskinFixture f=new GameplayReskinFixture();f.publish(skin);f.startRun();f.unlock("a1");f.resolve(7,1001,"food",null);
            assertEquals(0,f.hp(7));assertTrue(f.state(7).path("themeRuntime").path("awaitingChallenge").asBoolean());
            f.challenge(alternative);
        }
    }

    @Test void allThreeThemesFollowTheSameScriptToAnExecutedWorldEnding() throws Exception {
        java.util.List<JsonNode> skins=GameplayReskinFixture.variants();
        for(int index=0;index<skins.size();index++) {GameplayReskinFixture f=new GameplayReskinFixture();f.publish(skins.get(index));f.scriptedPath(index%2,index==1);}
    }

    @Test void configuredActionCeilingRejectsTheNextPaidTaskWithoutChangingResources() throws Exception {
        for(JsonNode original:GameplayReskinFixture.variants()) {
            com.fasterxml.jackson.databind.node.ObjectNode skin=original.deepCopy();skin.put("actionLimit",1);
            skin.with("questNumbers").putObject("q2001").put("actionCost",1);
            GameplayReskinFixture f=new GameplayReskinFixture();f.publish(skin);f.startRun();f.unlock("a1");f.resolve(7,1001,"food",null);f.unlock("a2");
            String before=f.state(7).toString();
            assertThrows(com.chengyinhub.business.service.GameSessionException.class,()->f.command(7,"QUEST_START",f.payload().put("questKey","q2001")));
            assertEquals(before,f.state(7).toString());
        }
    }

    @Test void allThreePublishedThemesCanExplicitlyCloseToTheirConfiguredFallbackWithoutSpending() throws Exception {
        for (JsonNode skin : GameplayReskinFixture.variants()) {
            GameplayReskinFixture f = new GameplayReskinFixture(); f.publish(skin); f.startRun();
            assertThrows(com.chengyinhub.business.service.GameSessionException.class, () -> f.command(88, "PLAN_FALLBACK", f.payload().put("reason", "尚未拒绝")));
            for (long member : new long[]{7, 9, 11}) f.command(member, "PLAN_REJECT_ALL", f.payload());
            int hp = f.hp(7), material = f.stock(7, "power");
            f.command(88, "PLAN_FALLBACK", f.payload().put("reason", "全部队伍决定不执行"));
            JsonNode fallback = null;
            for (JsonNode ending : f.snapshot.path("themeConfig").path("endings")) if (ending.path("fallback").asBoolean()) fallback = ending;
            assertNotNull(fallback); assertEquals(fallback.path("id"), f.world().path("result").path("endingKey"));
            assertEquals(fallback.path("title"), f.world().path("result").path("title"));
            assertEquals(hp, f.hp(7)); assertEquals(material, f.stock(7, "power"));
        }
    }

    @Test void aFrozenWorldEndingDisablesAnOutstandingChallengeAndRejectsEveryNewProof() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode skin = GameplayReskinFixture.variants().get(0).deepCopy(); skin.put("initialHp", 1);
        GameplayReskinFixture f = new GameplayReskinFixture(); f.publish(skin); f.startRun(); f.unlock("a1"); f.resolve(7, 1001, "food", null);
        long node = f.snapshot.path("themeConfig").path("gameplayRuntime").path("survival").path("zeroChallenge").path("challengeNodeId").asLong();
        String instance = f.play.sharedRouteSnapshot(7L, f.topic.getId(), 70L, node).path("runtimeInstance").asText();
        for (long member : new long[]{7, 9, 11}) f.command(member, "PLAN_REJECT_ALL", f.payload());
        f.command(88, "PLAN_FALLBACK", f.payload().put("reason", "全部队伍放弃"));
        String before = f.state(7).toString(); int count = f.receipts.size();
        assertThrows(com.chengyinhub.business.service.GameSessionException.class, () -> f.play.assertSharedRouteWritable(7L, f.topic.getId(), 70L, node, 1));
        assertThrows(com.chengyinhub.business.service.GameSessionException.class, () -> f.play.recordSharedZeroChallengeResult(8L, f.topic.getId(), 70L, node, instance, "late-new-proof", true, null, false, 1));
        assertEquals(before, f.state(7).toString()); assertEquals(count, f.receipts.size()); assertEquals(0, f.hp(7));
        JsonNode view = GameplayReskinFixture.JSON.valueToTree(f.play.view(com.chengyinhub.business.domain.dto.GameActorContext.player(7L), 70L).getPlayer());
        assertFalse(view.path("challenge").path("enabled").asBoolean()); assertEquals("SESSION_ENDED", view.path("challenge").path("disabledReason").asText());
    }
}

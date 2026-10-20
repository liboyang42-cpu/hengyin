package com.chengyinhub.business.service.support.qmod;

import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.route.NodeOutcome;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Uses real ce61 domain classes, Jackson, validator and route engine. No service/DB substitute.
 * Fixtures are synthetic existing-shaped sessions. Does not prove authentic owner data, transaction
 * replay, concurrent CAS, completion, rewards, or native UI. Never run if actual dependencies are absent.
 */
public final class QmodExistingBranchProjectionIntegrationTest {
    private static int assertions;
    private static final String HASH = String.join("", Collections.nCopies(64,"a"));
    private static final long LEFT = 9007199254740993L;
    private static final long RIGHT = Long.MAX_VALUE;

    public static void main(String[] args) throws Exception {
        Fixture f = new Fixture();
        QmodChoiceBinding.Context context = f.context();
        QmodChoiceBinding.Access access = f.allow(context);
        QmodChoiceBinding.Candidate candidate = f.analyze(access);
        check(!candidate.isRuntimeEnabled(), "not enabled");
        QmodChoiceBinding.Binding right = candidate.resolve("right", f.context(), access);
        check(right.getTargetNodeId() == RIGHT, "second option maps second target");
        check(candidate.inspectExplicitFallback(f.context(), access).getTargetNodeId() == RIGHT, "same explicit fallback");
        String safe = new ObjectMapper().writeValueAsString(candidate.projectBranch(f.context(), access));
        check(safe.contains("右边") && !safe.contains("RIGHT_END") && !safe.contains("nextStepId")
                && !safe.contains("score") && !safe.contains("edge-right") && !safe.contains("effects"), "safe native branch fragment");

        // Synthetic completed outcomes are used ONLY to test the existing engine's mapping.
        // The candidate never constructs a completion outcome or invokes this engine.
        TopicRouteEngine engine = new TopicRouteEngine();
        TopicRouteEngine.Decision r = engine.advance(f.graph, engine.initial(f.graph), "ADVANCED_RESULT",
                new NodeOutcome(101L, right.getOutcomeCode(), "synthetic-receipt-right"), 1L);
        TopicRouteEngine.Decision l = engine.advance(f.graph, engine.initial(f.graph), "ADVANCED_RESULT",
                new NodeOutcome(101L, "LEFT_END", "synthetic-receipt-left"), 1L);
        check(r.getState().getCurrentNodeId() == RIGHT && l.getState().getCurrentNodeId() == LEFT,
                "actual engine produces different targets for genuine-shaped distinct outcomes");
        check(engine.initial(f.graph).getNodeStates().size() == 3, "complete graph node set retained");

        String originalGame = f.game.getConfigSnapshotJson();
        String originalRoute = f.route.getConfigSnapshotJson();
        f.analyze(access);
        check(originalGame.equals(f.game.getConfigSnapshotJson()) && originalRoute.equals(f.route.getConfigSnapshotJson()),
                "original snapshots unchanged");
        rejected(() -> f.analyze(QmodChoiceBinding.Access.unknown()));
        rejected(() -> candidate.resolve("missing", context, access));

        f.game.setConfigSnapshotJson(originalGame.replace("右边","另一个标签"));
        stale(candidate, f, access); f.game.setConfigSnapshotJson(originalGame);
        f.route.setStateJson(f.route.getStateJson() + " ");
        stale(candidate, f, access); f.resetState();
        f.route.setConfigSnapshotJson(originalRoute + " ");
        stale(candidate, f, access); f.route.setConfigSnapshotJson(originalRoute);
        f.game.setVersion(2); stale(candidate,f,access); f.game.setVersion(1);
        f.route.setVersion(3); stale(candidate,f,access); f.route.setVersion(2);
        f.route.setConfigVersion(4L); stale(candidate,f,access); f.route.setConfigVersion(3L);
        f.route.setPlayInstanceId("different"); stale(candidate,f,access); f.route.setPlayInstanceId("instance");
        f.packageDigest = HASH.replace('a','b'); stale(candidate,f,access); f.packageDigest = HASH;
        f.choice = new QmodChoiceBinding.ChoiceSpec("choice","left",Arrays.asList(
                new QmodChoiceBinding.Option("left","left-end"),new QmodChoiceBinding.Option("right","right-end")));
        stale(candidate,f,access); f.choice = choice();

        for (int owner : new int[] {0,2,3}) {
            f.game.setOwnerType(owner); rejected(f::context);
        }
        f.game.setOwnerType(1); f.game.setOwnerId(8L); rejected(f::context); f.game.setOwnerId(7L);
        f.route.setMemberId(8L); rejected(f::context); f.route.setMemberId(7L);
        f.route.setActivityId(1L); rejected(f::context); f.route.setActivityId(0L);
        f.game.setStatus(1); rejected(f::context); f.game.setStatus(0);
        f.route.setStatus(1); rejected(f::context); f.route.setStatus(0);
        f.game.setDeadlineAt(new Date(1000)); rejected(f::context); f.game.setDeadlineAt(null);
        f.game.setConfigSnapshotJson(null); rejected(f::context); f.game.setConfigSnapshotJson(originalGame);

        f.game.setConfigSnapshotJson(originalGame.replace("\"right-terminal\",\"terminal\":true", "\"right-terminal\",\"terminal\":false"));
        rejected(f::freshAnalyze); f.game.setConfigSnapshotJson(originalGame);
        f.game.setConfigSnapshotJson(originalGame.replace("\"id\":\"right\",\"label\"", "\"id\":\"other\",\"label\""));
        rejected(f::freshAnalyze); f.game.setConfigSnapshotJson(originalGame);
        f.game.setConfigSnapshotJson(originalGame.replace("RIGHT_END","LEFT_END"));
        rejected(f::freshAnalyze); f.game.setConfigSnapshotJson(originalGame);
        f.route.setConfigSnapshotJson(originalRoute.replace("RIGHT_END","LEFT_END"));
        rejected(f::freshAnalyze); f.route.setConfigSnapshotJson(originalRoute);
        f.route.setConfigSnapshotJson(originalRoute.replace("\"conditions\":[]", "\"conditions\":[{\"op\":\"HAS_TAG\",\"value\":\"secret\"}]"));
        rejected(f::freshAnalyze); f.route.setConfigSnapshotJson(originalRoute);
        f.route.setConfigSnapshotJson(originalRoute.replace("\"effects\":[]", "\"effects\":[{\"op\":\"ADD_TAG\",\"value\":\"secret\"}]"));
        rejected(f::freshAnalyze); f.route.setConfigSnapshotJson(originalRoute);
        f.route.setStateJson(f.route.getStateJson().replace("\"edgeVisits\":{}", "\"edgeVisits\":{\"edge-right\":1}"));
        rejected(f::freshAnalyze); f.resetState();
        f.route.setStateJson(f.route.getStateJson().replace("\"completedNodeIds\":[]", "\"completedNodeIds\":["+RIGHT+"]"));
        rejected(f::freshAnalyze); f.resetState();
        System.out.println("PASS QmodExistingBranchProjectionIntegrationTest assertions="+assertions
                +" scope=REAL_VALIDATOR_ENGINE_WITH_SYNTHETIC_SESSIONS_NOT_SERVICE_OR_DB");
    }

    private static void stale(QmodChoiceBinding.Candidate candidate, Fixture f, QmodChoiceBinding.Access old) {
        QmodChoiceBinding.Context now=f.context();
        rejected(() -> candidate.projectBranch(now,old));
        rejected(() -> candidate.resolve("right",now,f.allow(now)));
        rejected(() -> candidate.inspectExplicitFallback(now,f.allow(now)));
    }
    private static QmodChoiceBinding.ChoiceSpec choice() {
        return new QmodChoiceBinding.ChoiceSpec("choice","right",Arrays.asList(
                new QmodChoiceBinding.Option("left","left-end"),new QmodChoiceBinding.Option("right","right-end")));
    }
    private static final class Fixture {
        final QmodExistingBranchProjection bridge = new QmodExistingBranchProjection();
        final GameRuntimeSession game = new GameRuntimeSession();
        final TopicRouteSession route = new TopicRouteSession();
        final Set<String> keys = new LinkedHashSet<>(Arrays.asList("choice","left-end","right-end"));
        final Set<Long> ids = new LinkedHashSet<>(Arrays.asList(101L,LEFT,RIGHT));
        final Map<String,Long> map = new LinkedHashMap<>();
        QmodChoiceBinding.ChoiceSpec choice = choice();
        String packageDigest=HASH;
        final String graph="{\"schemaVersion\":1,\"startNodeId\":101,\"terminalNodeIds\":["+LEFT+","+RIGHT+"],"
                +"\"edges\":[{\"id\":\"edge-left\",\"fromNodeId\":101,\"toNodeId\":"+LEFT+",\"trigger\":{\"type\":\"ADVANCED_RESULT\",\"outcomeCode\":\"LEFT_END\"},\"conditions\":[],\"effects\":[]},"
                +"{\"id\":\"edge-right\",\"fromNodeId\":101,\"toNodeId\":"+RIGHT+",\"trigger\":{\"type\":\"ADVANCED_RESULT\",\"outcomeCode\":\"RIGHT_END\"},\"conditions\":[],\"effects\":[]}],"
                +"\"fallbacks\":[{\"fromNodeId\":101,\"toNodeId\":"+RIGHT+"}]}";
        Fixture() throws Exception {
            map.put("choice",101L);map.put("left-end",LEFT);map.put("right-end",RIGHT);
            game.setId(11L);game.setActivityId(0L);game.setTopicId(9L);game.setNodeId(101L);game.setTemplateId(13L);
            game.setOwnerType(1);game.setOwnerId(7L);game.setVersion(1);game.setStatus(0);
            game.setConfigSnapshotJson("{\"schemaVersion\":1,\"branch\":{\"enabled\":true,\"startStepId\":\"fork\",\"steps\":["
                    +"{\"id\":\"fork\",\"terminal\":false,\"options\":[{\"id\":\"left\",\"label\":\"左边\",\"nextStepId\":\"left-terminal\"},"
                    +"{\"id\":\"right\",\"label\":\"右边\",\"nextStepId\":\"right-terminal\",\"score\":5}]},"
                    +"{\"id\":\"left-terminal\",\"terminal\":true,\"outcomeCode\":\"LEFT_END\",\"options\":[]},"
                    +"{\"id\":\"right-terminal\",\"terminal\":true,\"outcomeCode\":\"RIGHT_END\",\"options\":[]}]}}");
            game.setStateJson("{\"branch\":{\"currentStepId\":\"fork\",\"visitedStepIds\":[\"fork\"]}}");
            route.setId(17L);route.setMemberId(7L);route.setActivityId(0L);route.setTopicId(9L);route.setVersion(2);
            route.setConfigVersion(3L);route.setStatus(0);route.setCurrentNodeId(101L);route.setPlayInstanceId("instance");
            route.setConfigSnapshotJson(new TopicRouteConfigValidator().validateAndNormalize(graph,ids));resetState();
        }
        void resetState() {
            try {route.setStateJson(new ObjectMapper().writeValueAsString(new TopicRouteEngine().initial(graph)));}
            catch(Exception failure){throw new IllegalStateException(failure);}
        }
        QmodChoiceBinding.Context context() {
            return bridge.context(7L,game,route,new Date(1000),QmodChoiceFallbackAdapter.PROFILE,packageDigest,keys,map,ids,choice);
        }
        QmodChoiceBinding.Access allow(QmodChoiceBinding.Context context) {
            return new QmodChoiceBinding.Access(QmodChoiceBinding.Verdict.ALLOWED_BY_EXISTING_OWNER_AND_POLICY,context,ids);
        }
        QmodChoiceBinding.Candidate analyze(QmodChoiceBinding.Access access) {
            return bridge.analyze(7L,game,route,new Date(1000),QmodChoiceFallbackAdapter.PROFILE,packageDigest,keys,map,ids,choice,access);
        }
        void freshAnalyze() {analyze(allow(context()));}
    }
    private static void check(boolean pass,String message){assertions++;if(!pass)throw new AssertionError(message);}
    private static void rejected(Runnable body) {
        try {body.run();throw new AssertionError("expected fail-closed rejection");}
        catch(IllegalArgumentException expected){assertions++;}
    }
}

package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.PlayNodeAccessService;
import com.chengyinhub.web.service.play.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;

/** Test-only assembly of the actual application owners. Explicit dependency replacements rebuild
 * the complete graph before invocation; production never reads a Controller field via callback. */
public final class PlayNodeAccessTestFixtures {
    private PlayNodeAccessTestFixtures() {}
    private static final Map<ApiPlayProgressController, Map<String,Object>> INPUTS = new WeakHashMap<>();
    private static final Map<ApiPlayProgressController, Map<Class<?>,Object>> GRAPHS = new WeakHashMap<>();
    private static final Map<ApiPlayProgressController, org.springframework.transaction.PlatformTransactionManager> TRANSACTIONS = new WeakHashMap<>();
    private static final Class<?>[] OWNERS = {
        PlayNodeContext.class,
        PlayCompletionFacts.class,
        PlayNodeQueryApplication.class,
        PlayEntryApplication.class,
        PlayHistoryQueryApplication.class,
        PlayPlayerQueryApplication.class,
        PlayBasicIntentApplication.class,
        PlayBasicCommandApplication.class,
        PlayPhotoCommandApplication.class,
        PlayPuzzleCommandApplication.class,
        PlayPreferenceApplication.class,
        PlayNodeCompletionApplication.class,
        PlayTaskPresentation.class
    };
    public static synchronized void setField(Object target, String field, Object value) {
        if (!(target instanceof ApiPlayProgressController controller)) {
            ReflectionTestUtils.setField(target,field,value); return;
        }
        controller = target(controller);
        Map<String,Object> input=INPUTS.computeIfAbsent(controller,key -> new LinkedHashMap<>());
        input.put(field,value);
        install(controller);
    }
    public static synchronized Object getField(Object target, String field) {
        if (!(target instanceof ApiPlayProgressController controller)) return ReflectionTestUtils.getField(target,field);
        controller = target(controller);
        installIfAbsent(controller);
        Map<String,Object> input=INPUTS.get(controller);
        if(input.containsKey(field)) return input.get(field);
        for(Object application:GRAPHS.get(controller).values()) {
            if(ReflectionUtils.findField(application.getClass(),field)!=null)
                return ReflectionTestUtils.getField(application,field);
        }
        return ReflectionTestUtils.getField(controller,field);
    }
    public static synchronized Object owner(ApiPlayProgressController controller,Class<?> type) {
        controller = target(controller);
        installIfAbsent(controller);return GRAPHS.get(controller).get(type);
    }
    public static <T> T invokeMethod(Object target,String name,Object... arguments) {
        if(!(target instanceof ApiPlayProgressController controller)) return ReflectionTestUtils.invokeMethod(target,name,arguments);
        Class<?> type=switch(name) {
            case "nodeScope", "playContext", "isDelistedNode", "hasCompletedNodeFact", "findNodeProgressRow", "isPureOnlineNode", "isAppSensorNode", "isPhotoFilterNode", "isPrevDone", "routeAccessError", "unlockGateError", "parseGeoCoordinate", "activityTimeError", "resolveMode", "resolveCtx", "leadGateError", "normalizeAnswer", "frozenAnswer", "frozenTemplateView", "effectiveTemplate" -> PlayNodeContext.class;
            case "isTopicCompleted", "isTopicCompletedFor", "isCompletedCompletionFact", "completedExplorePassOwnedBy", "exploreRegistration", "isRegistered", "activityRegistration", "validSelfPlayPass", "topicHistoryBlocked", "historyReadableNode", "hasSelfPlayRegistration", "isExploreChapterRedeemed" -> PlayCompletionFacts.class;
            case "nodes", "routeState" -> PlayEntryApplication.class;
            case "resolveCoupon", "visibleBranchNodes", "shopDayCrowdedThreshold", "shopDayServiceCapacityPerHour", "shopDayExpectedWaitMinutes", "vitalsOn" -> PlayNodeQueryApplication.class;
            case "ending", "myCompleted" -> PlayHistoryQueryApplication.class;
            case "companionLine", "defaultCompanionLine", "leaderboard", "growth", "achievements" -> PlayPlayerQueryApplication.class;
            case "basicIntentScope", "basicActionContext", "basicActionReceipt", "executeBasicIntent", "basicReplay", "basicJson", "basicHash", "basicRequestId" -> PlayBasicIntentApplication.class;
            case "checkin", "checkinOnce", "recordFirstSuccessfulScan", "scanEntry", "answer", "answerOnce", "arrive", "arriveOnce", "sensorResult" -> PlayBasicCommandApplication.class;
            case "photo", "verifyPhotoUploadFactOrError" -> PlayPhotoCommandApplication.class;
            case "revealPuzzle", "puzzleHint", "unlockHint", "hintUnlockCost" -> PlayPuzzleCommandApplication.class;
            case "preference", "submitPreference", "confirmPreferenceTag", "revokePreferenceTag", "correctPreferenceTag", "newLifeOperatingSystem", "resolvePreferencePlayCtx" -> PlayPreferenceApplication.class;
            case "advanceProgress", "collect", "logAttempt", "claimNodeMedal", "refreshBingo", "grantConditionalReward", "grantTemplateCoupon", "grantCouponById" -> PlayNodeCompletionApplication.class;
            case "shopDayProofData" -> PlayTaskPresentation.class;
            default -> ApiPlayProgressController.class;
        };
        return ReflectionTestUtils.invokeMethod(type==ApiPlayProgressController.class?controller:owner(controller,type),name,arguments);
    }
    private static ApiPlayProgressController target(ApiPlayProgressController controller) {
        try {
            while (controller instanceof org.springframework.aop.framework.Advised advised)
                controller = (ApiPlayProgressController) advised.getTargetSource().getTarget();
            return controller;
        } catch (Exception failed) { throw new IllegalStateException("Cannot resolve play fixture target", failed); }
    }
    /** Explicit storage/transaction fixture wiring; ordinary unit fixtures stay plain objects. */
    public static synchronized void installTransactions(ApiPlayProgressController controller,
            org.springframework.transaction.PlatformTransactionManager transactions) {
        controller = target(controller);
        TRANSACTIONS.put(controller, transactions);
        install(controller);
    }
    private static void installIfAbsent(ApiPlayProgressController controller) {
        if(!GRAPHS.containsKey(controller)) install(controller);
    }
    public static synchronized void install(ApiPlayProgressController controller) {
        controller = target(controller);
        Map<String,Object> input=INPUTS.computeIfAbsent(controller,key -> new LinkedHashMap<>());
        // Explicit ordinary no-lead/unused-ticket fixtures, never production nullable fallbacks.
        if(!input.containsKey("clubLeadMapper")) {
            ClubLeadMapper leads=mock(ClubLeadMapper.class); when(leads.selectByActivity(anyLong())).thenReturn(null);
            input.put("clubLeadMapper",leads);
        }
        input.computeIfAbsent("omsTicketService",key -> mock(IOmsTicketService.class));
        PlayNodeAccessService access=input.containsKey("playNodeAccessService")
                ? (PlayNodeAccessService)input.get("playNodeAccessService") : new PlayNodeAccessService(
                (ICmsActivityService)input.get("cmsActivityService"),
                (CmsActivityMapper)input.get("currentPlayActivityMapper"),
                (CmsRegistrationMapper)input.get("cmsRegistrationMapper"),
                (ICmsTopicNodeService)input.get("cmsTopicNodeService"),
                (IPlayerNodeProgressService)input.get("playerNodeProgressService"),
                (ICmsTopicService)input.get("cmsTopicService"),(IOmsTicketService)input.get("omsTicketService"),
                (ClubLeadMapper)input.get("clubLeadMapper"),(IGameSessionRuntimeService)input.get("gameSessionRuntimeService"),
                (ICmsTopicChapterService)input.get("cmsTopicChapterService"),(ITopicRouteRuntimeService)input.get("topicRouteRuntimeService"));
        Map<Class<?>,Object> targets=new LinkedHashMap<>();
        Map<Class<?>,Object> graph=new LinkedHashMap<>();
        try {
            for(Class<?> type:OWNERS) {
                Object application=type.getDeclaredConstructor().newInstance();
                targets.put(type,application);
                Object entry=application;
                org.springframework.transaction.PlatformTransactionManager transactions=TRANSACTIONS.get(controller);
                if(transactions!=null && java.util.Arrays.stream(type.getMethods()).anyMatch(method ->
                        method.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))) {
                    org.springframework.aop.framework.ProxyFactory proxy=new org.springframework.aop.framework.ProxyFactory(application);
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,
                            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
                    entry=proxy.getProxy();
                }
                graph.put(type,entry);
            }
        } catch(ReflectiveOperationException failed) { throw new IllegalStateException("Cannot assemble play applications",failed); }
        for(Object application:targets.values()) {
            for(Map.Entry<String,Object> dependency:input.entrySet()) {
                if(ReflectionUtils.findField(application.getClass(),dependency.getKey())!=null)
                    ReflectionTestUtils.setField(application,dependency.getKey(),dependency.getValue());
            }
            for(Field field:application.getClass().getDeclaredFields()) {
                if(graph.containsKey(field.getType())) ReflectionTestUtils.setField(application,field.getName(),graph.get(field.getType()));
            }
            if(ReflectionUtils.findField(application.getClass(),"playNodeAccessService")!=null)
                ReflectionTestUtils.setField(application,"playNodeAccessService",access);
        }
        for(Field field:ApiPlayProgressController.class.getDeclaredFields()) {
            if(graph.containsKey(field.getType())) ReflectionTestUtils.setField(controller,field.getName(),graph.get(field.getType()));
        }
        Object puzzle=input.get("puzzlePlayService");
        if(puzzle instanceof org.springframework.aop.framework.Advised advised) {
            try { puzzle=advised.getTargetSource().getTarget(); }
            catch(Exception failed) { throw new IllegalStateException("Cannot rewire hint application fixture",failed); }
        }
        if(puzzle instanceof com.chengyinhub.business.service.impl.PuzzlePlayServiceImpl) {
            ReflectionTestUtils.setField(puzzle,"accessService",access);
            ReflectionTestUtils.setField(puzzle,"topicRouteRuntimeService",input.get("topicRouteRuntimeService"));
        }
        GRAPHS.put(controller,graph);
    }
}

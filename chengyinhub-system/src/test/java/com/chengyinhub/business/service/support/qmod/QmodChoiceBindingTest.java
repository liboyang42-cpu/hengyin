package com.chengyinhub.business.service.support.qmod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Standalone pure tests. Synthetic facts are not real runtime/authorization evidence. */
public final class QmodChoiceBindingTest {
    private static int assertions;
    private static final String HASH = String.join("", Collections.nCopies(64, "a"));
    private static final long LEFT = 9007199254740993L;
    private static final long RIGHT = Long.MAX_VALUE;

    public static void main(String[] args) {
        positiveAndProjection(); negatives(); contextAndRevocation(); preservation();
        System.out.println("PASS QmodChoiceBindingTest assertions=" + assertions
                + " scope=READ_ONLY_SYNTHETIC_EXISTING_BRANCH_SUBSET");
    }

    @SuppressWarnings("unchecked")
    private static void positiveAndProjection() {
        H h = new H(); QmodChoiceBinding.Candidate c = h.bind();
        check(!c.isRuntimeEnabled(), "default disabled");
        check(c.getStatus().contains("SUBSET_NOT_ENABLED"), "bounded status");
        check(c.resolve("left", h.context, h.access).getTargetNodeId() == LEFT, "left target exact Long");
        QmodChoiceBinding.Binding right = c.resolve("right", h.context, h.access);
        check(right.getTargetNodeId() == RIGHT, "second option different target exact Long.MAX");
        check(right.getOutcomeCode().equals("RIGHT_END"), "existing terminal code preserved");
        check(right.getTerminalStepId().equals("right-terminal"), "terminal step preserved");
        check(right.getEdgeId().equals("edge-right"), "real edge identity preserved");
        check(right.getOptionId().equals("right"), "option identity preserved");
        check(c.inspectExplicitFallback(h.context, h.access) == right, "explicit second fallback same binding/context");
        Map<String, Object> fragment = c.projectBranch(h.context, h.access);
        check(fragment.keySet().equals(set("currentStepId", "currentStep")), "existing branch fragment fields only");
        Map<String, Object> step = (Map<String, Object>) fragment.get("currentStep");
        check(step.keySet().equals(set("id", "terminal", "options")), "step fields only");
        List<Map<String, Object>> options = (List<Map<String, Object>>) step.get("options");
        check(options.size() == 2, "all options preserved");
        check(options.get(0).keySet().equals(set("id", "label")), "safe option allowlist");
        check(options.get(1).get("label").equals("右边"), "label from frozen branch");
        check(!fragment.toString().contains("RIGHT_END") && !fragment.toString().contains("edge-right")
                && !fragment.toString().contains("nextStepId") && !fragment.toString().contains("fallback"),
                "no target outcome edge or fallback policy leaks");
        unsupported(() -> fragment.put("next", "secret"));
        unsupported(() -> step.put("terminal", true));
        unsupported(() -> options.add(new LinkedHashMap<String, Object>()));
        unsupported(() -> options.get(0).put("label", "changed"));
        reject("UNKNOWN_OPTION", () -> c.resolve("other", h.context, h.access));
        for (int i = 0; i <= 32; i++) {
            final String malformed = "right" + (char) i;
            reject("AUTHOR_KEY_NON_CANONICAL", () -> c.resolve(malformed, h.context, h.access));
        }
        reject("AUTHOR_KEY_NON_CANONICAL", () -> c.resolve("ｒight", h.context, h.access));
        reject("AUTHOR_KEY_NON_CANONICAL", () -> c.resolve(null, h.context, h.access));
    }

    private static void negatives() {
        H h = new H(); h.access = QmodChoiceBinding.Access.unknown(); rejected(h);
        h = new H(); h.access = new QmodChoiceBinding.Access(QmodChoiceBinding.Verdict.DENIED, h.context, h.ids); rejected(h);
        h = new H(); h.access = new QmodChoiceBinding.Access(null, h.context, h.ids); rejected(h);
        h = new H(); h.context = null; rejected(h);
        h = new H(); h.profile = "legacy-v1"; rejected(h);
        h = new H(); h.fallback = null; rejected(h);
        h = new H(); h.fallback = LEFT; rejected(h);
        h = new H(); h.frozen = null; rejected(h);
        h = new H(); h.frozen.remove(1); rejected(h);
        h = new H(); h.frozen.add(option("third", "第三", "third-terminal", true, "THIRD")); rejected(h);
        h = new H(); h.frozen.set(1, option("left", "重复", "right-terminal", true, "RIGHT_END")); rejected(h);
        h = new H(); h.frozen.set(1, option("else", "不同", "right-terminal", true, "RIGHT_END")); rejected(h);
        h = new H(); h.frozen.set(1, option("right", " ", "right-terminal", true, "RIGHT_END")); rejected(h);
        h = new H(); h.frozen.set(1, option("right", "标签", "right-terminal", false, "RIGHT_END")); rejected(h);
        h = new H(); h.frozen.set(1, option("right", "标签", "right-terminal", true, null)); rejected(h);
        h = new H(); h.frozen.set(1, option("right", "标签", "right-terminal", true, "LEFT_END")); rejected(h);
        h = new H(); h.frozen.set(1, option("right", "标签", "right-terminal", true, "RIGHT_END ")); rejected(h);
        h = new H(); h.edges = null; rejected(h);
        h = new H(); h.edges.remove(1); rejected(h);
        h = new H(); h.edges.add(edge("another", 101, RIGHT, "ADVANCED_RESULT", "RIGHT_END", true, true, false)); rejected(h);
        h = new H(); h.edges.set(1, edge("edge-left", 101, RIGHT, "ADVANCED_RESULT", "RIGHT_END", true, true, false)); rejected(h);
        h = new H(); h.edges.set(1, edge("edge-right", 102, RIGHT, "ADVANCED_RESULT", "RIGHT_END", true, true, false)); rejected(h);
        h = new H(); h.edges.set(1, edge("edge-right", 101, RIGHT, "CHOICE", "COMPLETED", true, true, false)); rejected(h);
        h = new H(); h.edges.set(1, edge("edge-right", 101, RIGHT, "PREFERENCE_RESULT", "RIGHT_END", true, true, false)); rejected(h);
        h = new H(); h.edges.set(1, edge("edge-right", 101, RIGHT, "ADVANCED_RESULT", "LEFT_END", true, true, false)); rejected(h);
        h = new H(); h.edges.set(1, edge("edge-right", 101, RIGHT, "ADVANCED_RESULT", "MISSING", true, true, false)); rejected(h);
        h = new H(); h.edges.set(1, edge("edge-right", 101, RIGHT, "ADVANCED_RESULT", "RIGHT_END", false, true, false)); rejected(h);
        h = new H(); h.edges.set(1, edge("edge-right", 101, RIGHT, "ADVANCED_RESULT", "RIGHT_END", true, false, false)); rejected(h);
        h = new H(); h.edges.set(1, edge("edge-right", 101, RIGHT, "ADVANCED_RESULT", "RIGHT_END", true, true, true)); rejected(h);
        h = new H(); h.edges.set(1, edge("edge-right", 101, 101, "ADVANCED_RESULT", "RIGHT_END", true, true, false)); rejected(h);
        h = new H(); h.edges.set(1, edge("edge-right", 101, LEFT, "ADVANCED_RESULT", "RIGHT_END", true, true, false)); rejected(h);
        h = new H(); h.keys.remove("right-end"); rejected(h);
        h = new H(); h.map.put("right-end", LEFT); rejected(h);
        h = new H(); h.ids.remove(RIGHT); rejected(h);
        h = new H(); h.choice = new QmodChoiceBinding.ChoiceSpec("choice", "unknown", specs()); rejected(h);
        h = new H(); h.choice = new QmodChoiceBinding.ChoiceSpec("choice", null, specs()); rejected(h);
        h = new H(); h.choice = new QmodChoiceBinding.ChoiceSpec("left-end", "right", specs()); rejected(h);
        h = new H(); h.access = new QmodChoiceBinding.Access(QmodChoiceBinding.Verdict.ALLOWED_BY_EXISTING_OWNER_AND_POLICY,
                h.context, set(101L, LEFT)); rejected(h);
        reject("OWNER_UNKNOWN_TEAM_OR_MISMATCH", () -> ctxOwner(0, 1, 7, 7));
        reject("OWNER_UNKNOWN_TEAM_OR_MISMATCH", () -> ctxOwner(7, 2, 7, 7));
        reject("OWNER_UNKNOWN_TEAM_OR_MISMATCH", () -> ctxOwner(7, 0, 7, 7));
        reject("OWNER_UNKNOWN_TEAM_OR_MISMATCH", () -> ctxOwner(7, 1, 8, 7));
        reject("OWNER_UNKNOWN_TEAM_OR_MISMATCH", () -> ctxOwner(7, 1, 7, 8));
        reject("FROZEN_DIGEST_REQUIRED", () -> new QmodChoiceBinding.Context(7,1,7,7,9,0,11,13,1,17,2,3,101,"fork", "hash"));
    }

    private static void contextAndRevocation() {
        H h = new H(); QmodChoiceBinding.Candidate c = h.bind();
        for (int field = 0; field < 12; field++) {
            QmodChoiceBinding.Context current = changedContext(field);
            QmodChoiceBinding.Access access = new QmodChoiceBinding.Access(
                    QmodChoiceBinding.Verdict.ALLOWED_BY_EXISTING_OWNER_AND_POLICY, current, h.ids);
            reject("BINDING_CONTEXT_STALE", () -> c.resolve("right", current, access));
            reject("BINDING_CONTEXT_STALE", () -> c.projectBranch(current, access));
            reject("BINDING_CONTEXT_STALE", () -> c.inspectExplicitFallback(current, access));
        }
        QmodChoiceBinding.Access stale = new QmodChoiceBinding.Access(
                QmodChoiceBinding.Verdict.ALLOWED_BY_EXISTING_OWNER_AND_POLICY, changedContext(1), h.ids);
        reject("ACCESS_CONTEXT_STALE", () -> c.resolve("right", h.context, stale));
        reject("LIVE_OWNER_POLICY_UNKNOWN_OR_DENIED", () -> c.resolve("right", h.context, QmodChoiceBinding.Access.unknown()));
        QmodChoiceBinding.Access removed = new QmodChoiceBinding.Access(
                QmodChoiceBinding.Verdict.ALLOWED_BY_EXISTING_OWNER_AND_POLICY, h.context, set(101L, LEFT));
        reject("TARGET_ELIGIBILITY_UNKNOWN_OR_DENIED", () -> c.resolve("left", h.context, removed));
        reject("TARGET_ELIGIBILITY_UNKNOWN_OR_DENIED", () -> c.inspectExplicitFallback(h.context, removed));
        reject("TARGET_ELIGIBILITY_UNKNOWN_OR_DENIED", () -> c.projectBranch(h.context, removed));
    }

    private static void preservation() {
        H h = new H(); List<QmodChoiceBinding.Option> specs = specs();
        h.choice = new QmodChoiceBinding.ChoiceSpec("choice", "right", specs);
        specs.clear(); QmodChoiceBinding.Candidate c = h.bind();
        h.frozen.clear(); h.edges.clear(); h.map.clear(); h.keys.clear(); h.ids.clear();
        check(c.resolve("right", h.context, h.access).getTargetNodeId() == RIGHT, "defensive input snapshots");
        check(c.inspectExplicitFallback(h.context, h.access).getOptionId().equals("right"), "fallback unchanged");
        QmodChoiceBinding.Context equal = ctxOwner(7,1,7,7);
        check(h.context.equals(equal) && h.context.hashCode() == equal.hashCode(), "context value equality");
    }

    private static final class H {
        String profile = QmodChoiceFallbackAdapter.PROFILE;
        Set<String> keys = set("choice", "left-end", "right-end");
        Map<String, Long> map = new LinkedHashMap<>();
        Set<Long> ids = set(101L, LEFT, RIGHT);
        QmodChoiceBinding.ChoiceSpec choice = new QmodChoiceBinding.ChoiceSpec("choice", "right", specs());
        QmodChoiceBinding.Context context = ctxOwner(7,1,7,7);
        QmodChoiceBinding.Access access = new QmodChoiceBinding.Access(
                QmodChoiceBinding.Verdict.ALLOWED_BY_EXISTING_OWNER_AND_POLICY, context, ids);
        List<QmodChoiceBinding.FrozenOption> frozen = new ArrayList<>(Arrays.asList(
                option("left", "左边", "left-terminal", true, "LEFT_END"),
                option("right", "右边", "right-terminal", true, "RIGHT_END")));
        List<QmodChoiceBinding.FrozenEdge> edges = new ArrayList<>(Arrays.asList(
                edge("edge-left",101,LEFT,"ADVANCED_RESULT","LEFT_END",true,true,false),
                edge("edge-right",101,RIGHT,"ADVANCED_RESULT","RIGHT_END",true,true,false)));
        Long fallback = RIGHT;
        H() { map.put("choice",101L); map.put("left-end",LEFT); map.put("right-end",RIGHT); }
        QmodChoiceBinding.Candidate bind() {
            return new QmodChoiceBinding().bind(profile, keys, map, ids, choice, context, access, frozen, edges, fallback);
        }
    }
    private static List<QmodChoiceBinding.Option> specs() {
        return new ArrayList<>(Arrays.asList(new QmodChoiceBinding.Option("left","left-end"),
                new QmodChoiceBinding.Option("right","right-end")));
    }
    private static QmodChoiceBinding.FrozenOption option(String id,String label,String next,boolean terminal,String outcome) {
        return new QmodChoiceBinding.FrozenOption(id,label,next,terminal,outcome);
    }
    private static QmodChoiceBinding.FrozenEdge edge(String id,long from,long to,String type,String outcome,
                                                    boolean conditions,boolean unvisited,boolean loop) {
        return new QmodChoiceBinding.FrozenEdge(id,from,to,type,outcome,conditions,unvisited,loop);
    }
    private static QmodChoiceBinding.Context ctxOwner(long member,int type,long owner,long routeMember) {
        return new QmodChoiceBinding.Context(member,type,owner,routeMember,9,0,11,13,1,17,2,3,101,"fork",HASH);
    }
    private static QmodChoiceBinding.Context changedContext(int n) {
        return new QmodChoiceBinding.Context(n==0?8:7,1,n==0?8:7,n==0?8:7,
                n==1?10:9,n==2?1:0,n==3?12:11,n==4?14:13,n==5?2:1,
                n==6?18:17,n==7?3:2,n==8?4:3,n==9?102:101,n==10?"other":"fork",n==11?HASH.replace('a','b'):HASH);
    }
    @SafeVarargs private static <T> Set<T> set(T... values) {
        Set<T> out = new LinkedHashSet<>(); for (T value : values) out.add(value); return out;
    }
    private static void check(boolean pass,String message) { assertions++; if(!pass)throw new AssertionError(message); }
    private static void rejected(H h) { reject(null, h::bind); }
    private static void reject(String code,Runnable body) {
        try { body.run(); throw new AssertionError("expected rejection: " + code); }
        catch(IllegalArgumentException expected) { check(code==null||code.equals(expected.getMessage()), "wrong rejection: "+expected); }
    }
    private static void unsupported(Runnable body) {
        try { body.run(); throw new AssertionError("mutable projection"); }
        catch(UnsupportedOperationException expected) { assertions++; }
    }
}

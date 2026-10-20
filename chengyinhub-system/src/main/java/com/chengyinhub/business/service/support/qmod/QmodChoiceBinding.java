package com.chengyinhub.business.service.support.qmod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Unregistered, read-only candidate for the EXISTING immediate-terminal branch subset.
 * These input values are projections of trusted server sources, never proof of authority.
 * No method creates a NodeOutcome, a command, a completion receipt or a route transition.
 */
public final class QmodChoiceBinding {
    private static final Pattern OUTCOME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    public Candidate bind(String trustedProfile, Set<String> completeNodeKeys,
                          Map<String, Long> serverNodeIds, Set<Long> topicNodeIds,
                          ChoiceSpec choice, Context context, Access access,
                          List<FrozenOption> frozenOptions, List<FrozenEdge> outgoingEdges,
                          Long frozenFallbackTarget) {
        need(choice != null && context != null, "FROZEN_CONTEXT_REQUIRED");
        access(context, access);
        List<QmodChoiceFallbackAdapter.Option> fallbackOptions = new ArrayList<>();
        need(choice.options != null, "OPTIONS_REQUIRED");
        for (Option option : choice.options) {
            need(option != null, "OPTION_REQUIRED");
            fallbackOptions.add(new QmodChoiceFallbackAdapter.Option(option.id, option.next));
        }
        // Reuse the reviewed fallback checks without modifying or replacing that implementation.
        QmodChoiceFallbackAdapter.Plan fallback = new QmodChoiceFallbackAdapter().adapt(
                trustedProfile, completeNodeKeys,
                Collections.singletonList(new QmodChoiceFallbackAdapter.Choice(
                        choice.nodeKey, choice.fallbackChoiceId, fallbackOptions)), serverNodeIds, topicNodeIds);
        need(context.nodeId == fallback.nodeIdMap().get(choice.nodeKey), "CURRENT_NODE_MISMATCH");
        need(frozenOptions != null && frozenOptions.size() == choice.options.size(), "OPTION_SET_MISMATCH");
        Map<String, FrozenOption> frozen = new LinkedHashMap<>();
        Set<String> outcomes = new LinkedHashSet<>();
        for (FrozenOption option : frozenOptions) {
            need(option != null, "FROZEN_OPTION_REQUIRED");
            QmodChoiceFallbackAdapter.requireKey(option.id);
            need(frozen.put(option.id, option) == null, "FROZEN_OPTION_DUPLICATE");
            need(option.label != null && !option.label.trim().isEmpty() && option.label.length() <= 80,
                    "FROZEN_LABEL_INVALID");
            QmodChoiceFallbackAdapter.requireKey(option.nextStepId);
            need(option.directTerminal, "MULTISTEP_OR_NONTERMINAL_UNSUPPORTED");
            need(option.outcomeCode != null && OUTCOME.matcher(option.outcomeCode).matches(),
                    "EXISTING_TERMINAL_OUTCOME_REQUIRED");
            need(outcomes.add(option.outcomeCode), "OUTCOME_AMBIGUOUS");
        }
        need(outgoingEdges != null && outgoingEdges.size() == choice.options.size(), "ROUTE_EDGE_SET_MISMATCH");
        Map<String, FrozenEdge> byOutcome = new LinkedHashMap<>();
        Set<String> edgeIds = new LinkedHashSet<>();
        for (FrozenEdge edge : outgoingEdges) {
            need(edge != null && edge.fromNodeId == context.nodeId, "ROUTE_EDGE_SCOPE_MISMATCH");
            need(edge.id != null && !edge.id.trim().isEmpty() && edge.id.length() <= 64
                    && edge.id.equals(edge.id.trim()), "EDGE_ID_INVALID");
            need(edgeIds.add(edge.id), "EDGE_ID_DUPLICATE");
            need("ADVANCED_RESULT".equals(edge.triggerType), "EXISTING_BRANCH_TRIGGER_REQUIRED");
            need(edge.unconditional && edge.unvisited && !edge.loop, "CONDITIONAL_OR_USED_ROUTE_UNSUPPORTED");
            need(outcomes.contains(edge.outcomeCode), "UNBOUND_ROUTE_OUTCOME");
            need(byOutcome.put(edge.outcomeCode, edge) == null, "RANDOM_OR_AMBIGUOUS_ROUTE_UNSUPPORTED");
            need(edge.toNodeId > 0 && edge.toNodeId != context.nodeId, "ROUTE_TARGET_INVALID");
            need(access.liveEligibleNodeIds.contains(edge.toNodeId), "TARGET_ELIGIBILITY_UNKNOWN_OR_DENIED");
        }
        Map<String, Binding> bindings = new LinkedHashMap<>();
        List<Map<String, Object>> options = new ArrayList<>();
        for (Option option : choice.options) {
            FrozenOption existing = frozen.get(option.id);
            need(existing != null, "OPTION_ID_NOT_IN_FROZEN_STEP");
            FrozenEdge edge = byOutcome.get(existing.outcomeCode);
            need(edge != null, "ROUTE_OUTCOME_UNBOUND");
            need(fallback.nodeIdMap().get(option.next).longValue() == edge.toNodeId, "OPTION_TARGET_MISMATCH");
            bindings.put(option.id, new Binding(option.id, existing.nextStepId, existing.outcomeCode,
                    edge.id, edge.toNodeId));
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("id", option.id); safe.put("label", existing.label);
            options.add(Collections.unmodifiableMap(safe));
        }
        Binding fallbackBinding = bindings.get(choice.fallbackChoiceId);
        need(frozenFallbackTarget != null && frozenFallbackTarget.longValue() == fallbackBinding.targetNodeId,
                "FROZEN_FALLBACK_MISMATCH");
        need(access.liveEligibleNodeIds.contains(frozenFallbackTarget), "FALLBACK_ELIGIBILITY_UNKNOWN_OR_DENIED");
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", context.stepId); step.put("terminal", Boolean.FALSE);
        step.put("options", Collections.unmodifiableList(options));
        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("currentStepId", context.stepId);
        branch.put("currentStep", Collections.unmodifiableMap(step));
        return new Candidate(context, bindings, fallbackBinding, Collections.unmodifiableMap(branch));
    }

    private static void access(Context context, Access access) {
        need(access != null && access.verdict == Verdict.ALLOWED_BY_EXISTING_OWNER_AND_POLICY,
                "LIVE_OWNER_POLICY_UNKNOWN_OR_DENIED");
        need(context.equals(access.context), "ACCESS_CONTEXT_STALE");
        need(access.liveEligibleNodeIds.contains(context.nodeId), "CURRENT_NODE_UNAVAILABLE");
    }

    static void need(boolean condition, String code) {
        if (!condition) throw new IllegalArgumentException(code);
    }

    public enum Verdict { UNKNOWN, DENIED, ALLOWED_BY_EXISTING_OWNER_AND_POLICY }

    /** Caller must obtain this verdict from existing authorization/policy, never an upload/request. */
    public static final class Access {
        private final Verdict verdict;
        private final Context context;
        private final Set<Long> liveEligibleNodeIds;
        public Access(Verdict verdict, Context context, Set<Long> liveEligibleNodeIds) {
            this.verdict = verdict; this.context = context;
            this.liveEligibleNodeIds = liveEligibleNodeIds == null ? Collections.<Long>emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(liveEligibleNodeIds));
        }
        public static Access unknown() { return new Access(Verdict.UNKNOWN, null, null); }
    }

    /** Exact immutable context; digest is over all frozen author, runtime and route inputs. */
    public static final class Context {
        private final long nodeId;
        private final String stepId;
        private final List<Object> identity;
        public Context(long memberId, int ownerType, long ownerId, long routeMemberId,
                       long topicId, long activityId, long gameSessionId, long templateId, int gameVersion,
                       long routeSessionId, int routeVersion, long routeConfigVersion,
                       long nodeId, String stepId, String frozenInputDigest) {
            need(memberId > 0 && ownerType == 1 && ownerId == memberId && routeMemberId == memberId,
                    "OWNER_UNKNOWN_TEAM_OR_MISMATCH");
            need(topicId > 0 && activityId >= 0 && gameSessionId > 0 && templateId > 0 && gameVersion >= 0
                    && routeSessionId > 0 && routeVersion >= 0 && routeConfigVersion > 0 && nodeId > 0,
                    "CONTEXT_ID_OR_VERSION_INVALID");
            QmodChoiceFallbackAdapter.requireKey(stepId);
            need(frozenInputDigest != null && DIGEST.matcher(frozenInputDigest).matches(), "FROZEN_DIGEST_REQUIRED");
            this.nodeId = nodeId; this.stepId = stepId;
            this.identity = Collections.unmodifiableList(Arrays.<Object>asList(memberId, ownerType,
                    ownerId, routeMemberId, topicId, activityId, gameSessionId, templateId, gameVersion,
                    routeSessionId, routeVersion, routeConfigVersion, nodeId, stepId, frozenInputDigest));
        }
        @Override public boolean equals(Object other) {
            return other instanceof Context && identity.equals(((Context) other).identity);
        }
        @Override public int hashCode() { return Objects.hash(identity); }
    }

    /** A validated current CHOICE projection, not a second package or persistence model. */
    public static final class ChoiceSpec {
        final String nodeKey;
        final String fallbackChoiceId;
        final List<Option> options;
        public ChoiceSpec(String nodeKey, String fallbackChoiceId, List<Option> options) {
            this.nodeKey = nodeKey; this.fallbackChoiceId = fallbackChoiceId;
            this.options = options == null ? null : Collections.unmodifiableList(new ArrayList<>(options));
        }
    }
    public static final class Option {
        final String id; final String next;
        public Option(String id, String next) { this.id = id; this.next = next; }
    }
    public static final class FrozenOption {
        final String id; final String label; final String nextStepId; final String outcomeCode;
        final boolean directTerminal;
        public FrozenOption(String id, String label, String nextStepId, boolean directTerminal, String outcomeCode) {
            this.id = id; this.label = label; this.nextStepId = nextStepId;
            this.directTerminal = directTerminal; this.outcomeCode = outcomeCode;
        }
    }
    public static final class FrozenEdge {
        final String id; final long fromNodeId; final long toNodeId; final String triggerType;
        final String outcomeCode; final boolean unconditional; final boolean unvisited; final boolean loop;
        public FrozenEdge(String id, long fromNodeId, long toNodeId, String triggerType, String outcomeCode,
                          boolean unconditional, boolean unvisited, boolean loop) {
            this.id = id; this.fromNodeId = fromNodeId; this.toNodeId = toNodeId;
            this.triggerType = triggerType; this.outcomeCode = outcomeCode; this.unconditional = unconditional;
            this.unvisited = unvisited; this.loop = loop;
        }
    }

    /** Server-only expected binding. It is NOT evidence that the player obtained this outcome. */
    public static final class Binding {
        private final String optionId; private final String terminalStepId; private final String outcomeCode;
        private final String edgeId; private final long targetNodeId;
        private Binding(String optionId, String terminalStepId, String outcomeCode, String edgeId, long targetNodeId) {
            this.optionId = optionId; this.terminalStepId = terminalStepId; this.outcomeCode = outcomeCode;
            this.edgeId = edgeId; this.targetNodeId = targetNodeId;
        }
        public String getOptionId() { return optionId; }
        public String getTerminalStepId() { return terminalStepId; }
        public String getOutcomeCode() { return outcomeCode; }
        public String getEdgeId() { return edgeId; }
        public long getTargetNodeId() { return targetNodeId; }
    }

    public static final class Candidate {
        private final Context context;
        private final Map<String, Binding> bindings;
        private final Binding fallback;
        private final Map<String, Object> branch;
        private Candidate(Context context, Map<String, Binding> bindings, Binding fallback, Map<String, Object> branch) {
            this.context = context; this.bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
            this.fallback = fallback; this.branch = branch;
        }
        public String getStatus() { return "READ_ONLY_EXISTING_BRANCH_SUBSET_NOT_ENABLED"; }
        public boolean isRuntimeEnabled() { return false; }
        private void recheck(Context current, Access currentAccess) {
            need(context.equals(current), "BINDING_CONTEXT_STALE");
            access(current, currentAccess);
            for (Binding binding : bindings.values()) {
                need(currentAccess.liveEligibleNodeIds.contains(binding.targetNodeId), "TARGET_ELIGIBILITY_UNKNOWN_OR_DENIED");
            }
        }
        public Binding resolve(String optionId, Context current, Access currentAccess) {
            recheck(current, currentAccess);
            QmodChoiceFallbackAdapter.requireKey(optionId);
            Binding binding = bindings.get(optionId);
            need(binding != null, "UNKNOWN_OPTION");
            return binding;
        }
        public Binding inspectExplicitFallback(Context current, Access currentAccess) {
            recheck(current, currentAccess); return fallback;
        }
        /** Only the existing publicBranch fragment; not a complete native session response. */
        public Map<String, Object> projectBranch(Context current, Access currentAccess) {
            recheck(current, currentAccess); return branch;
        }
    }
}

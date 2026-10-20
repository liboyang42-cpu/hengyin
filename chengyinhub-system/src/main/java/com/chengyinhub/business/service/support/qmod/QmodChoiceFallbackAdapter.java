package com.chengyinhub.business.service.support.qmod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Local r2 candidate only: project explicitly selected CHOICE fallbacks.
 * Input is a complete CHOICE projection AFTER the separate r2 contract check.
 * The caller supplies the complete node key set and server-owned persistence map.
 * This is neither a qmod reader, a complete route, nor an outcome declaration.
 * It does not normalize author keys, infer a selection, or parse keys as IDs.
 */
public final class QmodChoiceFallbackAdapter {
    public static final String PROFILE = "qmod-local-contract-r2";
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9._-]{0,63}");

    public Plan adapt(String trustedProfileId, Set<String> completeNodeKeys,
                      List<Choice> choices, Map<String, Long> serverNodeIdMap,
                      Set<Long> topicNodeIds) {
        require(PROFILE.equals(trustedProfileId), "PROFILE_NOT_SUPPORTED");
        require(completeNodeKeys != null && !completeNodeKeys.isEmpty()
                && completeNodeKeys.size() <= 256, "NODE_KEYS_REQUIRED");
        Set<String> keys = new LinkedHashSet<String>(completeNodeKeys);
        for (String key : keys) requireKey(key);
        require(serverNodeIdMap != null, "SERVER_NODE_MAP_REQUIRED");
        Map<String, Long> ids = new LinkedHashMap<String, Long>(serverNodeIdMap);
        require(ids.keySet().equals(keys), "SERVER_NODE_MAP_KEY_SET_MISMATCH");
        require(topicNodeIds != null, "TOPIC_NODE_IDS_REQUIRED");
        Set<Long> topicIds = new LinkedHashSet<Long>(topicNodeIds);
        Set<Long> mappedIds = new LinkedHashSet<Long>();
        for (Long id : ids.values()) {
            require(id != null && id.longValue() > 0L, "SERVER_NODE_ID_INVALID");
            require(mappedIds.add(id), "SERVER_NODE_ID_DUPLICATE");
        }
        require(mappedIds.equals(topicIds), "TOPIC_NODE_ID_SET_MISMATCH");
        require(choices != null && !choices.isEmpty() && choices.size() <= keys.size(),
                "CHOICE_PROJECTION_REQUIRED");
        Set<String> fromKeys = new LinkedHashSet<String>();
        List<Fallback> fallbacks = new ArrayList<Fallback>();
        Map<String, Set<String>> choiceTargets = new LinkedHashMap<String, Set<String>>();
        for (Choice choice : new ArrayList<Choice>(choices)) {
            require(choice != null, "CHOICE_REQUIRED");
            requireKey(choice.id);
            require(keys.contains(choice.id), "CHOICE_NODE_UNKNOWN");
            require(fromKeys.add(choice.id), "CHOICE_NODE_DUPLICATE");
            require(choice.fallbackChoiceId != null, "CHOICE_ADAPTATION_REQUIRED");
            requireKey(choice.fallbackChoiceId);
            require(choice.options != null && choice.options.size() >= 2
                    && choice.options.size() <= 8, "CHOICE_OPTIONS_INVALID");
            Set<String> optionIds = new LinkedHashSet<String>();
            Set<String> targets = new LinkedHashSet<String>();
            String selectedNext = null;
            for (Option option : choice.options) {
                require(option != null, "CHOICE_OPTION_REQUIRED");
                requireKey(option.id);
                requireKey(option.next);
                require(optionIds.add(option.id), "CHOICE_OPTION_DUPLICATE");
                require(keys.contains(option.next), "CHOICE_TARGET_UNKNOWN");
                targets.add(option.next);
                if (choice.fallbackChoiceId.equals(option.id)) selectedNext = option.next;
            }
            require(selectedNext != null, "CHOICE_FALLBACK_UNKNOWN");
            require(!choice.id.equals(selectedNext), "CHOICE_FALLBACK_SELF_CYCLE");
            fallbacks.add(new Fallback(choice.id, selectedNext));
            choiceTargets.put(choice.id, targets);
        }
        return new Plan(fallbacks, ids, topicIds, choiceTargets);
    }

    static void requireKey(String key) {
        require(key != null && KEY.matcher(key).matches(), "AUTHOR_KEY_NON_CANONICAL");
    }

    static void require(boolean condition, String code) {
        if (!condition) throw new IllegalArgumentException(code);
    }

    /** Input projection, not a new route/business entity. */
    public static final class Choice {
        private final String id;
        private final String fallbackChoiceId;
        private final List<Option> options;

        public Choice(String id, String fallbackChoiceId, List<Option> options) {
            this.id = id;
            this.fallbackChoiceId = fallbackChoiceId;
            this.options = options == null ? null
                    : Collections.unmodifiableList(new ArrayList<Option>(options));
        }
    }

    /** Author option ID is never used as a persisted ID or runtime outcome. */
    public static final class Option {
        private final String id;
        private final String next;

        public Option(String id, String next) {
            this.id = id;
            this.next = next;
        }
    }

    public static final class Fallback {
        private final String fromNodeKey;
        private final String toNodeKey;

        private Fallback(String fromNodeKey, String toNodeKey) {
            this.fromNodeKey = fromNodeKey;
            this.toNodeKey = toNodeKey;
        }

        public String getFromNodeKey() { return fromNodeKey; }
        public String getToNodeKey() { return toNodeKey; }
    }

    public static final class Plan {
        private final List<Fallback> fallbacks;
        private final Map<String, Long> nodeIdMap;
        private final Set<Long> topicNodeIds;
        private final Map<String, Set<String>> choiceTargets;

        private Plan(List<Fallback> fallbacks, Map<String, Long> nodeIdMap,
                     Set<Long> topicNodeIds, Map<String, Set<String>> choiceTargets) {
            this.fallbacks = Collections.unmodifiableList(new ArrayList<Fallback>(fallbacks));
            this.nodeIdMap = Collections.unmodifiableMap(new LinkedHashMap<String, Long>(nodeIdMap));
            this.topicNodeIds = Collections.unmodifiableSet(new LinkedHashSet<Long>(topicNodeIds));
            Map<String, Set<String>> targets = new LinkedHashMap<String, Set<String>>();
            for (Map.Entry<String, Set<String>> entry : choiceTargets.entrySet()) {
                targets.put(entry.getKey(), Collections.unmodifiableSet(
                        new LinkedHashSet<String>(entry.getValue())));
            }
            this.choiceTargets = Collections.unmodifiableMap(targets);
        }

        public String getStatus() { return "NOT_A_COMPLETE_ROUTE"; }
        public List<Fallback> getFallbacks() { return fallbacks; }
        Map<String, Long> nodeIdMap() { return nodeIdMap; }
        Set<Long> topicNodeIds() { return topicNodeIds; }
        Map<String, Set<String>> choiceTargets() { return choiceTargets; }
    }
}

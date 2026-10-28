package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ThemeAttributeMark;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.ThemeAttributeMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 文档二 L8 单测用的内存注册表:真 ThemeStateKeyService 跑在它上面,引用收集可预置。 */
final class Doc2KeyRegistryFakes {

    private Doc2KeyRegistryFakes() { }

    static ThemeStateKey copyWithId(ThemeStateKey source) {
        ThemeStateKey copy = ThemeKeyNamingService.copy(source);
        copy.setId(source.getId());
        copy.setTopicId(source.getTopicId());
        return copy;
    }

    static final class Registry implements ThemeStateKeyMapper, ThemeAttributeMapper {
        final Long topicId = 7L; // The existing World owns exactly this topic.
        final Map<String, ThemeStateKey> rows = new LinkedHashMap<String, ThemeStateKey>();
        final Map<String, String> marks = new LinkedHashMap<String, String>();
        final List<String> calls = new ArrayList<String>();
        long version = 1L;
        long nextId = 1L;
        private boolean parentLocked;

        // Both production statements fence the same cms_topic row. This is an
        // ordered-call double, not a database lock or transaction implementation.
        private Long lockParent(Long requested, String call) {
            if (!topicId.equals(requested)) return null;
            parentLocked = true;
            calls.add(call);
            return topicId;
        }

        private void requireParent(Long requested, String call) {
            if (!parentLocked || !topicId.equals(requested))
                throw new AssertionError("owned topic parent fence required: " + call);
            calls.add(call);
        }

        @Override public Long lockTopic(Long topicId) { return lockParent(topicId, "lockTopic"); }
        @Override public Long lockTopicForStateKeyWrite(Long topicId) {
            return lockParent(topicId, "lockTopicForStateKeyWrite");
        }
        @Override public int ensureRegistryVersion(Long topicId) {
            requireParent(topicId, "ensureRegistryVersion"); return 1;
        }
        @Override public Long lockRegistryVersion(Long topicId) {
            requireParent(topicId, "lockRegistryVersion"); return version;
        }
        @Override public int advanceRegistryVersion(Long topicId) {
            requireParent(topicId, "advanceRegistryVersion"); version++; return 1;
        }
        @Override public List<ThemeStateKey> selectByTopicIdForUpdate(Long topicId) {
            requireParent(topicId, "selectByTopicIdForUpdate"); return selectByTopicId(topicId);
        }
        @Override public ThemeStateKey selectByTopicIdAndStateKeyForUpdate(Long topicId, String stateKey) {
            requireParent(topicId, "selectByTopicIdAndStateKeyForUpdate");
            return selectByTopicIdAndStateKey(topicId, stateKey);
        }
        @Override public List<ThemeStateKey> selectByTopicId(Long topicId) {
            List<ThemeStateKey> copy = new ArrayList<ThemeStateKey>();
            if (!this.topicId.equals(topicId)) return copy;
            for (ThemeStateKey row : rows.values()) copy.add(copyWithId(row));
            return copy;
        }
        @Override public int upsertThemeStateKey(ThemeStateKey key) {
            requireParent(key.getTopicId(), "upsertThemeStateKey");
            return store(key);
        }
        private int store(ThemeStateKey key) {
            ThemeStateKey existing = rows.get(key.getStateKey());
            ThemeStateKey stored = copyWithId(key);
            stored.setId(existing == null ? Long.valueOf(nextId++) : existing.getId());
            rows.put(key.getStateKey(), stored);
            return 1;
        }
        @Override public ThemeStateKey selectByTopicIdAndStateKey(Long topicId, String stateKey) {
            if (!this.topicId.equals(topicId)) return null;
            ThemeStateKey row = rows.get(stateKey);
            return row == null ? null : copyWithId(row);
        }
        @Override public int updateDefinition(ThemeStateKey key) {
            requireParent(key.getTopicId(), "updateDefinition");
            ThemeStateKey existing = rows.get(key.getStateKey());
            if (existing == null) return 0;
            ThemeStateKey stored = copyWithId(key);
            stored.setId(existing.getId());
            rows.put(key.getStateKey(), stored);
            return 1;
        }
        @Override public int deleteByTopicIdAndStateKey(Long topicId, String stateKey) {
            requireParent(topicId, "deleteByTopicIdAndStateKey");
            marks.remove(stateKey);
            return rows.remove(stateKey) == null ? 0 : 1;
        }
        @Override public List<ThemeAttributeMark> selectAttributeMarks(Long topicId) {
            List<ThemeAttributeMark> list = new ArrayList<ThemeAttributeMark>();
            if (!this.topicId.equals(topicId)) return list;
            for (Map.Entry<String, String> entry : marks.entrySet()) {
                ThemeAttributeMark mark = new ThemeAttributeMark();
                mark.setStateKey(entry.getKey());
                mark.setPreset(entry.getValue());
                list.add(mark);
            }
            return list;
        }
        @Override public int markAttribute(Long topicId, String stateKey, String preset) {
            requireParent(topicId, "markAttribute");
            if (!rows.containsKey(stateKey)) return 0;
            marks.put(stateKey, preset);
            return 1;
        }

        void add(String key, String type, String label, int initial) {
            ThemeStateKey row = new ThemeStateKey();
            row.setTopicId(topicId);
            row.setStateKey(key);
            row.setKeyType(type);
            row.setScope("run");
            row.setLabel(label);
            row.setInitialValue(Integer.valueOf(initial));
            store(row); // Test setup only; service mutations use the fenced mapper methods.
        }
    }

    static final class Refs extends TopicStateKeyRefService {
        final TopicStateKeyRefService.RefCollection collection = new TopicStateKeyRefService.RefCollection();

        Refs() {
            super(null, null, null);
        }

        @Override
        public TopicStateKeyRefService.RefCollection collect(CmsTopic topic) {
            return collection;
        }

        void usedAtNode(long nodeId, String nodeName, String key) {
            collection.getNodeLabels().put(Long.valueOf(nodeId), nodeName);
            collection.getRefsByNode().computeIfAbsent(Long.valueOf(nodeId), id -> new java.util.LinkedHashSet<String>()).add(key);
        }
    }

    static final class World {
        final Registry registry = new Registry();
        final Refs refs = new Refs();
        final ThemeStateKeyService keys = new ThemeStateKeyService(registry);
        final ThemeKeyNamingService naming = new ThemeKeyNamingService(registry, registry, keys, refs);
        final ThemeAttributeService attributes = new ThemeAttributeService(registry, registry, keys, naming);
        final CmsTopic topic = new CmsTopic();

        World() {
            topic.setId(registry.topicId);
        }
    }
}

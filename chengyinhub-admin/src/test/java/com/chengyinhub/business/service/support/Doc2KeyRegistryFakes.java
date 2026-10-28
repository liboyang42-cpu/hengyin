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
        return copy;
    }

    static final class Registry implements ThemeStateKeyMapper, ThemeAttributeMapper {
        final Map<String, ThemeStateKey> rows = new LinkedHashMap<String, ThemeStateKey>();
        final Map<String, String> marks = new LinkedHashMap<String, String>();
        long version = 1L;
        long nextId = 1L;

        @Override public Long lockTopic(Long topicId) { return topicId; }
        @Override public int ensureRegistryVersion(Long topicId) { return 1; }
        @Override public Long lockRegistryVersion(Long topicId) { return version; }
        @Override public int advanceRegistryVersion(Long topicId) { version++; return 1; }
        @Override public List<ThemeStateKey> selectByTopicIdForUpdate(Long topicId) { return selectByTopicId(topicId); }
        @Override public ThemeStateKey selectByTopicIdAndStateKeyForUpdate(Long topicId, String stateKey) {
            return selectByTopicIdAndStateKey(topicId, stateKey);
        }
        @Override public List<ThemeStateKey> selectByTopicId(Long topicId) {
            List<ThemeStateKey> copy = new ArrayList<ThemeStateKey>();
            for (ThemeStateKey row : rows.values()) copy.add(copyWithId(row));
            return copy;
        }
        @Override public int upsertThemeStateKey(ThemeStateKey key) {
            ThemeStateKey existing = rows.get(key.getStateKey());
            ThemeStateKey stored = copyWithId(key);
            stored.setId(existing == null ? Long.valueOf(nextId++) : existing.getId());
            rows.put(key.getStateKey(), stored);
            return 1;
        }
        @Override public ThemeStateKey selectByTopicIdAndStateKey(Long topicId, String stateKey) {
            ThemeStateKey row = rows.get(stateKey);
            return row == null ? null : copyWithId(row);
        }
        @Override public int updateDefinition(ThemeStateKey key) {
            ThemeStateKey existing = rows.get(key.getStateKey());
            if (existing == null) return 0;
            ThemeStateKey stored = copyWithId(key);
            stored.setId(existing.getId());
            rows.put(key.getStateKey(), stored);
            return 1;
        }
        @Override public int deleteByTopicIdAndStateKey(Long topicId, String stateKey) {
            marks.remove(stateKey);
            return rows.remove(stateKey) == null ? 0 : 1;
        }
        @Override public List<ThemeAttributeMark> selectAttributeMarks(Long topicId) {
            List<ThemeAttributeMark> list = new ArrayList<ThemeAttributeMark>();
            for (Map.Entry<String, String> entry : marks.entrySet()) {
                ThemeAttributeMark mark = new ThemeAttributeMark();
                mark.setStateKey(entry.getKey());
                mark.setPreset(entry.getValue());
                list.add(mark);
            }
            return list;
        }
        @Override public int markAttribute(Long topicId, String stateKey, String preset) {
            if (!rows.containsKey(stateKey)) return 0;
            marks.put(stateKey, preset);
            return 1;
        }

        void add(String key, String type, String label, int initial) {
            ThemeStateKey row = new ThemeStateKey();
            row.setStateKey(key);
            row.setKeyType(type);
            row.setScope("run");
            row.setLabel(label);
            row.setInitialValue(Integer.valueOf(initial));
            upsertThemeStateKey(row);
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
            topic.setId(7L);
        }
    }
}

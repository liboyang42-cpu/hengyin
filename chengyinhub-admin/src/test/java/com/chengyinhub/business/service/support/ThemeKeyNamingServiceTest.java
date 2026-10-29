package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文档二 L8:键名系统生成、同名检查、用在、被用到不能删、开局带着。 */
class ThemeKeyNamingServiceTest {

    private static ServiceException fail(Runnable action) {
        return assertThrows(ServiceException.class, action::run);
    }

    @Test
    void createGeneratesKeysPerKindAndNeverAsksForOne() {
        Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
        assertEquals("clue.k1", w.naming.create(w.topic, "clue", " 半张货单 ").get("key"));
        assertEquals("clue.k2", w.naming.create(w.topic, "clue", "巡夜人的怀表").get("key"));
        assertEquals("tag.k1", w.naming.create(w.topic, "tag", "新来的").get("key"));
        Map<String, Object> value = w.naming.create(w.topic, "counter", "电量");
        assertEquals("counter.v1", value.get("key"));
        assertEquals("counter", value.get("kind"));
        assertEquals("电量", value.get("label"));
        ThemeStateKey stored = w.registry.rows.get("clue.k1");
        assertEquals("flag", stored.getKeyType());
        assertEquals("半张货单", stored.getLabel());
        w.registry.add("clue.k9", "flag", "旧的", 0);
        assertEquals("clue.k10", w.naming.create(w.topic, "clue", "另半张货单").get("key"));
    }

    @Test
    void duplicateNamesAreRefusedPerKindWithContractMessages() {
        Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
        w.naming.create(w.topic, "clue", "半张货单");
        w.naming.create(w.topic, "tag", "新来的");
        w.naming.create(w.topic, "counter", "电量");
        assertEquals("已有同名的线索。", fail(() -> w.naming.create(w.topic, "clue", " 半张货单")).getMessage());
        assertEquals("已有同名的标签。", fail(() -> w.naming.create(w.topic, "tag", "新来的")).getMessage());
        assertEquals("已有同名的数值。", fail(() -> w.naming.create(w.topic, "counter", "电量")).getMessage());
        // 不同种类可以同名
        assertEquals("tag.k2", w.naming.create(w.topic, "tag", "半张货单").get("key"));
        assertEquals("请填写名称。", fail(() -> w.naming.create(w.topic, "clue", "  ")).getMessage());
        assertEquals("名称最多 20 字。", fail(() -> w.naming.create(w.topic, "clue", "一二三四五六七八九十一二三四五六七八九十一")).getMessage());
        ServiceException kind = fail(() -> w.naming.create(w.topic, "relation", "朋友"));
        assertEquals("请选择种类。", kind.getMessage());
        assertEquals(Integer.valueOf(400), kind.getCode());
    }

    @Test
    void renameKeepsKeyAndChecksDuplicates() {
        Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
        w.naming.create(w.topic, "clue", "半张货单");
        w.naming.create(w.topic, "clue", "怀表");
        Map<String, Object> renamed = w.naming.rename(w.topic, "clue.k1", "半张旧货单");
        assertEquals("clue.k1", renamed.get("key"));
        assertEquals("半张旧货单", w.registry.rows.get("clue.k1").getLabel());
        assertEquals("半张旧货单", w.naming.rename(w.topic, "clue.k1", "半张旧货单").get("label"));
        assertEquals("已有同名的线索。", fail(() -> w.naming.rename(w.topic, "clue.k1", "怀表")).getMessage());
        assertEquals(Integer.valueOf(404), fail(() -> w.naming.rename(w.topic, "clue.k99", "x")).getCode());

        Doc2KeyRegistryFakes.World attributes = new Doc2KeyRegistryFakes.World();
        attributes.attributes.add(attributes.topic, "custom", "原属性");
        attributes.attributes.add(attributes.topic, "custom", "另一属性");
        String twelve = "一二三四五六七八九十一二";
        assertEquals(twelve, attributes.naming.rename(attributes.topic, "counter.attr_1", twelve).get("label"));
        long version = attributes.registry.version;
        assertEquals("名称最多 12 字。", fail(() -> attributes.naming.rename(
                attributes.topic, "counter.attr_1", twelve + "三")).getMessage());
        assertEquals("已有同名的属性。", fail(() -> attributes.naming.rename(
                attributes.topic, "counter.attr_1", "另一属性")).getMessage());
        assertEquals(twelve, attributes.registry.rows.get("counter.attr_1").getLabel());
        assertEquals(version, attributes.registry.version);
        String twenty = "一二三四五六七八九十一二三四五六七八九十";
        assertEquals(twenty, attributes.naming.create(attributes.topic, "counter", twenty).get("label"));
    }

    @Test
    void usageListsPositionsAndExcludesAttributesAndClocks() {
        Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
        w.naming.create(w.topic, "clue", "半张货单");
        w.naming.create(w.topic, "tag", "新来的");
        w.registry.add("counter.observe", "counter", "观察", 2);
        w.registry.marks.put("counter.observe", "observe");
        w.registry.add("counter.alarm", "counter", "巡夜人警觉", 0);
        w.topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,\"rpg\":{\"clocks\":[{\"var\":\"counter.alarm\",\"label\":\"巡夜人警觉\",\"segments\":6}]}}");
        w.refs.usedAtNode(11L, "仓库侧门", "clue.k1");
        w.naming.setStartWith(w.topic, Collections.singletonList("tag.k1"));
        List<Map<String, Object>> items = w.naming.usage(w.topic);
        assertEquals(2, items.size());
        assertEquals("clue.k1", items.get(0).get("key"));
        assertEquals(Collections.singletonList("节点「仓库侧门」"), items.get(0).get("usedIn"));
        assertEquals(Boolean.FALSE, items.get(0).get("startWith"));
        assertEquals("tag.k1", items.get(1).get("key"));
        assertEquals(Collections.singletonList("初始状态"), items.get(1).get("usedIn"));
        assertEquals(Boolean.TRUE, items.get(1).get("startWith"));
        assertEquals(Collections.singletonList("进度钟"), w.naming.usedIn(w.topic, "counter.alarm"));
    }

    @Test
    void deleteRefusedWhenUsedWithNameAndPositions() {
        Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
        w.naming.create(w.topic, "clue", "半张货单");
        w.naming.create(w.topic, "clue", "怀表");
        w.refs.usedAtNode(11L, "仓库侧门", "clue.k1");
        ServiceException used = fail(() -> w.naming.delete(w.topic, "clue.k1"));
        assertEquals("不能删除「半张货单」", used.getMessage());
        assertEquals(Integer.valueOf(409), used.getCode());
        assertEquals(ThemeKeyNamingService.ERR_IN_USE, used.getDetailMessage());
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) used.getData();
        assertEquals(Collections.singletonList("节点「仓库侧门」"), data.get("usedIn"));
        assertTrue(w.registry.rows.containsKey("clue.k1"));
        w.naming.delete(w.topic, "clue.k2");
        assertFalse(w.registry.rows.containsKey("clue.k2"));
        assertEquals(Integer.valueOf(404), fail(() -> w.naming.delete(w.topic, "clue.k2")).getCode());
    }

    @Test
    void unreadableConfigFailsClosed() {
        Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
        w.naming.create(w.topic, "clue", "半张货单");
        w.refs.collection.getParseErrors().add(new TopicStateKeyRefService.ParseError(1L, "x", "block", "坏了"));
        assertEquals("主题里有无法读取的配置，暂时不能查看用在哪里。", fail(() -> w.naming.usage(w.topic)).getMessage());
        assertEquals("主题里有无法读取的配置，暂时不能查看用在哪里。", fail(() -> w.naming.delete(w.topic, "clue.k1")).getMessage());
        assertTrue(w.registry.rows.containsKey("clue.k1"));
    }

    @Test
    void startWithSetsInitialOnlyForCluesAndTags() {
        Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
        w.naming.create(w.topic, "clue", "半张货单");
        w.naming.create(w.topic, "tag", "新来的");
        w.naming.create(w.topic, "counter", "电量");
        w.naming.setStartWith(w.topic, Arrays.asList("clue.k1", "tag.k1"));
        assertEquals(Integer.valueOf(1), w.registry.rows.get("clue.k1").getInitialValue());
        assertEquals(Integer.valueOf(1), w.registry.rows.get("tag.k1").getInitialValue());
        w.naming.setStartWith(w.topic, Collections.singletonList("tag.k1"));
        assertEquals(Integer.valueOf(0), w.registry.rows.get("clue.k1").getInitialValue());
        assertEquals(Integer.valueOf(404), fail(() -> w.naming.setStartWith(w.topic, Collections.singletonList("counter.v1"))).getCode());
        assertEquals(Boolean.FALSE, w.naming.usage(w.topic).get(2).get("startWith"));
    }
}

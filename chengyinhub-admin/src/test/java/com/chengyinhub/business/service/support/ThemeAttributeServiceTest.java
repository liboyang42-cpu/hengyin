package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文档二 L8 d20 属性:预设(A3)、是属性标记(A2)、最多 6 项、范围、排序、删除、旧值自动迁移(A1)、保存绑定。 */
class ThemeAttributeServiceTest {

    private static ServiceException fail(Runnable action) {
        return assertThrows(ServiceException.class, action::run);
    }

    @Test
    void presetAddsDefinitionFromTypeAndMarksAttribute() {
        Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
        Map<String, Object> row = w.attributes.add(w.topic, "observe", "随便写的名字");
        assertEquals("counter.observe", row.get("key"));
        assertEquals("观察", row.get("label"));
        assertEquals(0, row.get("min"));
        assertEquals(5, row.get("max"));
        assertEquals(2, row.get("initial"));
        assertEquals("stars", row.get("displayType"));
        assertEquals("attr-observe", row.get("icon"));
        assertEquals(1, row.get("sortOrder"));
        assertEquals("observe", w.registry.marks.get("counter.observe"));
        Map<String, Object> nerve = w.attributes.add(w.topic, "nerve", null);
        assertEquals("bar", nerve.get("displayType"));
        assertEquals(10, nerve.get("max"));
        assertEquals(6, nerve.get("initial"));
        assertEquals(2, nerve.get("sortOrder"));
        ThemeStateKey stored = w.registry.rows.get("counter.nerve");
        assertEquals("run", stored.getPersistScope());
        assertEquals(Boolean.TRUE, stored.getVisible());
    }

    @Test
    void presetKeyAvoidsTakenNamesAndCustomGetsSequence() {
        Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
        w.registry.add("counter.observe", "counter", "旧计数", 0);
        assertEquals("counter.observe_2", w.attributes.add(w.topic, "observe", null).get("key"));
        assertEquals("counter.attr_1", w.attributes.add(w.topic, "custom", "耐力").get("key"));
        assertEquals("counter.attr_2", w.attributes.add(w.topic, "custom", "专注").get("key"));
    }

    @Test
    void duplicatesFullAndBadInputUseContractMessages() {
        Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
        w.attributes.add(w.topic, "observe", null);
        assertEquals("已有同名的属性。", fail(() -> w.attributes.add(w.topic, "observe", null)).getMessage());
        assertEquals("已有同名的属性。", fail(() -> w.attributes.add(w.topic, "custom", " 观察 ")).getMessage());
        assertEquals("请填写名称。", fail(() -> w.attributes.add(w.topic, "custom", " ")).getMessage());
        assertEquals("名称最多 12 字。", fail(() -> w.attributes.add(w.topic, "custom", "一二三四五六七八九十一二三")).getMessage());
        assertEquals("请选择属性。", fail(() -> w.attributes.add(w.topic, "wisdom", null)).getMessage());
        for (String preset : Arrays.asList("nerve", "body", "insight", "agility", "charm")) w.attributes.add(w.topic, preset, null);
        assertEquals("最多 6 项属性。", fail(() -> w.attributes.add(w.topic, "fortune", null)).getMessage());
        assertTrue(((List<?>) w.attributes.view(w.topic, false).get("presetsLeft")).isEmpty());
    }

    @Test
    void rangeEditsAreGuarded() {
        Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
        w.attributes.add(w.topic, "observe", null);
        Map<String, Object> row = w.attributes.updateRange(w.topic, "counter.observe", 1, 7, 2);
        assertEquals(1, row.get("min"));
        assertEquals(7, row.get("max"));
        assertEquals(2, row.get("initial"));
        assertEquals("stars", row.get("displayType"));
        assertEquals("最小值要小于最大值。", fail(() -> w.attributes.updateRange(w.topic, "counter.observe", 3, 3, 3)).getMessage());
        assertEquals("开局值要在最小值和最大值之间。", fail(() -> w.attributes.updateRange(w.topic, "counter.observe", 1, 5, 6)).getMessage());
        assertEquals("范围只能在 0 到 20 之间。", fail(() -> w.attributes.updateRange(w.topic, "counter.observe", 0, 21, 1)).getMessage());
        assertEquals("范围只能在 0 到 20 之间。", fail(() -> w.attributes.updateRange(w.topic, "counter.observe", -1, 5, 1)).getMessage());
        assertEquals(Integer.valueOf(404), fail(() -> w.attributes.updateRange(w.topic, "counter.none", 0, 5, 1)).getCode());
    }

    @Test
    void reorderRequiresExactSetAndWritesSortOrder() {
        Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
        w.attributes.add(w.topic, "observe", null);
        w.attributes.add(w.topic, "agility", null);
        List<Map<String, Object>> rows = w.attributes.reorder(w.topic, Arrays.asList("counter.agility", "counter.observe"));
        assertEquals("counter.agility", rows.get(0).get("key"));
        assertEquals(1, w.registry.rows.get("counter.agility").getSortOrder());
        assertEquals(2, w.registry.rows.get("counter.observe").getSortOrder());
        ServiceException conflict = fail(() -> w.attributes.reorder(w.topic, Arrays.asList("counter.agility")));
        assertEquals(Integer.valueOf(409), conflict.getCode());
        assertEquals("属性已变化，请重新读取。", conflict.getMessage());
        @SuppressWarnings("unchecked") List<Map<String, Object>> listed =
                (List<Map<String, Object>>) w.attributes.view(w.topic, false).get("attributes");
        assertEquals("counter.agility", listed.get(0).get("key"));
    }

    @Test
    void deleteRefusedWhenUsed() {
        Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
        w.attributes.add(w.topic, "observe", null);
        w.attributes.add(w.topic, "charm", null);
        w.refs.usedAtNode(3L, "钟楼", "counter.observe");
        ServiceException used = fail(() -> w.attributes.delete(w.topic, "counter.observe"));
        assertEquals("不能删除「观察」", used.getMessage());
        assertEquals(Integer.valueOf(409), used.getCode());
        w.attributes.delete(w.topic, "counter.charm");
        assertFalse(w.registry.rows.containsKey("counter.charm"));
        assertFalse(w.registry.marks.containsKey("counter.charm"));
        // 普通线索不能经属性接口删
        w.naming.create(w.topic, "clue", "货单");
        assertEquals(Integer.valueOf(404), fail(() -> w.attributes.delete(w.topic, "clue.k1")).getCode());
    }

    @Test
    void legacyCustomIntegerValuesAreAdoptedOnceAndClocksSkipped() {
        Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
        w.registry.add("counter.focus", "counter", "已注册的专注", 1);
        w.topic.setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":true,\"checkEnabled\":true,"
                + "\"attributes\":[{\"key\":\"counter.focus\",\"label\":\"专注\",\"initial\":2,\"min\":0,\"max\":12,\"visible\":true},"
                + "{\"key\":\"counter.stamina\",\"label\":\"体力\",\"initial\":3,\"min\":0,\"max\":9,\"visible\":false},"
                + "{\"key\":\"counter.alarm\",\"label\":\"警觉\",\"initial\":0,\"min\":0,\"max\":6},"
                + "{\"key\":\"tag.newcomer\",\"label\":\"新来的\",\"initial\":1,\"min\":0,\"max\":1}],"
                + "\"rpg\":{\"clocks\":[{\"var\":\"counter.alarm\",\"label\":\"警觉\",\"segments\":6}]}}");
        Map<String, Object> view = w.attributes.view(w.topic, true);
        assertEquals("custom", w.registry.marks.get("counter.focus"));
        assertEquals("custom", w.registry.marks.get("counter.stamina"));
        assertFalse(w.registry.marks.containsKey("counter.alarm"));
        assertFalse(w.registry.marks.containsKey("tag.newcomer"));
        // 已注册的定义不被旧值覆盖
        assertEquals("已注册的专注", w.registry.rows.get("counter.focus").getLabel());
        ThemeStateKey stamina = w.registry.rows.get("counter.stamina");
        assertEquals(Integer.valueOf(9), stamina.getMaxValue());
        assertEquals(Boolean.FALSE, stamina.getVisible());
        @SuppressWarnings("unchecked") List<Map<String, Object>> attributes = (List<Map<String, Object>>) view.get("attributes");
        assertEquals(2, attributes.size());
        long version = w.registry.version;
        w.attributes.view(w.topic, true);
        assertEquals(version, w.registry.version);
        // 冻结主题只读:不补登记
        Doc2KeyRegistryFakes.World frozen = new Doc2KeyRegistryFakes.World();
        frozen.topic.setJourneyRulesJson(w.topic.getJourneyRulesJson());
        frozen.attributes.view(frozen.topic, false);
        assertTrue(frozen.registry.rows.isEmpty());
    }

    @Test
    void bindingAndProjectionMatchRegistryForSave() {
        Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
        w.attributes.add(w.topic, "observe", null);
        w.naming.create(w.topic, "counter", "电量");
        w.naming.create(w.topic, "clue", "半张货单");
        w.naming.create(w.topic, "tag", "新来的");
        w.naming.setStartWith(w.topic, Arrays.asList("tag.k1"));
        w.topic.setJourneyRulesJson("{\"schemaVersion\":1,\"difficulty\":\"hard\"}");
        Map<String, Object> view = w.attributes.view(w.topic, true);
        assertEquals("hard", view.get("difficulty"));
        JsonNode binding = (JsonNode) view.get("binding");
        assertEquals(String.valueOf(w.registry.version), binding.path("registryVersion").asText());
        assertEquals("[\"counter.observe\",\"counter.v1\",\"tag.k1\"]", binding.path("enabledKeys").toString());
        @SuppressWarnings("unchecked") List<Map<String, Object>> projection = (List<Map<String, Object>>) view.get("projection");
        assertEquals(3, projection.size());
        assertEquals("观察", projection.get(0).get("label"));
        assertEquals(2, projection.get(0).get("initial"));
        assertEquals(5, projection.get(0).get("max"));
        assertEquals(Boolean.TRUE, projection.get(0).get("visible"));
        assertEquals(100, projection.get(1).get("max"));
        assertEquals(1, projection.get(2).get("initial"));
        assertEquals(1, projection.get(2).get("max"));
    }
}

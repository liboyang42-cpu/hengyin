package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct contract controls for the single-topic ordered-call double, not MySQL locking evidence. */
class Doc2KeyRegistryFakesTest {

    private static ThemeStateKey key(Long topicId) {
        ThemeStateKey key = new ThemeStateKey();
        key.setTopicId(topicId);
        key.setStateKey("clue.note");
        key.setKeyType("flag");
        key.setScope("run");
        key.setLabel("原线索");
        key.setInitialValue(0);
        return key;
    }

    @Test
    void realRegistryWriterUsesParentBeforeVersionAndCurrentDefinition() {
        Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
        w.keys.register(7L, Collections.singletonList(key(null)));
        assertEquals(Arrays.asList("lockTopicForStateKeyWrite", "ensureRegistryVersion",
                "lockRegistryVersion", "upsertThemeStateKey", "advanceRegistryVersion"), w.registry.calls);
        assertEquals(7L, w.registry.rows.get("clue.note").getTopicId().longValue());
        assertEquals(2L, w.registry.version);

        int before = w.registry.calls.size();
        ThemeStateKey patch = Doc2KeyRegistryFakes.copyWithId(w.registry.rows.get("clue.note"));
        patch.setLabel("新展示名");
        w.keys.updateDefinition(7L, "clue.note", patch);
        assertEquals(Arrays.asList("lockTopicForStateKeyWrite", "ensureRegistryVersion",
                "lockRegistryVersion", "selectByTopicIdAndStateKeyForUpdate",
                "updateDefinition", "advanceRegistryVersion"),
                w.registry.calls.subList(before, w.registry.calls.size()));
        assertEquals("新展示名", w.registry.rows.get("clue.note").getLabel());
        assertEquals(3L, w.registry.version);
    }

    @Test
    void missingParentFenceRejectsCurrentReadsAndMutationsWithoutEffects() {
        Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
        for (Runnable operation : Arrays.<Runnable>asList(
                () -> w.registry.ensureRegistryVersion(7L),
                () -> w.registry.lockRegistryVersion(7L),
                () -> w.registry.advanceRegistryVersion(7L),
                () -> w.registry.selectByTopicIdForUpdate(7L),
                () -> w.registry.selectByTopicIdAndStateKeyForUpdate(7L, "clue.note"),
                () -> w.registry.upsertThemeStateKey(key(7L)),
                () -> w.registry.updateDefinition(key(7L)),
                () -> w.registry.deleteByTopicIdAndStateKey(7L, "clue.note"),
                () -> w.registry.markAttribute(7L, "clue.note", "custom"))) {
            assertThrows(AssertionError.class, operation::run);
        }
        assertTrue(w.registry.rows.isEmpty());
        assertTrue(w.registry.marks.isEmpty());
        assertTrue(w.registry.calls.isEmpty());
        assertEquals(1L, w.registry.version);
        assertEquals(1L, w.registry.nextId);
    }

    @Test
    void bothParentPortsShareOnlyTheOwnedTopicAndForeignWriterCannotMutateIt() {
        for (boolean declarationPort : Arrays.asList(false, true)) {
            Doc2KeyRegistryFakes.World w = new Doc2KeyRegistryFakes.World();
            w.registry.add("clue.note", "flag", "原线索", 0);
            assertThrows(AssertionError.class, () -> w.registry.selectByTopicIdForUpdate(7L));
            assertEquals(7L, (declarationPort ? w.registry.lockTopicForStateKeyWrite(7L)
                    : w.registry.lockTopic(7L)).longValue());
            assertEquals(7L, w.registry.selectByTopicIdForUpdate(7L).get(0).getTopicId().longValue());
            ThemeStateKey copy = w.registry.selectByTopicIdAndStateKeyForUpdate(7L, "clue.note");
            copy.setLabel("仅改返回副本");
            assertEquals("原线索", w.registry.rows.get("clue.note").getLabel());

            assertNull(w.registry.lockTopicForStateKeyWrite(null));
            assertNull(w.registry.lockTopicForStateKeyWrite(8L));
            assertNull(w.registry.lockTopic(8L));
            assertThrows(AssertionError.class, () -> w.registry.lockRegistryVersion(8L));
            assertThrows(AssertionError.class, () -> w.registry.selectByTopicIdForUpdate(8L));
            assertThrows(AssertionError.class, () -> w.registry.upsertThemeStateKey(key(8L)));
            assertThrows(AssertionError.class, () -> w.registry.updateDefinition(key(8L)));
            assertThrows(AssertionError.class, () -> w.registry.markAttribute(8L, "clue.note", "custom"));
            assertTrue(w.registry.selectByTopicId(8L).isEmpty());
            assertNull(w.registry.selectByTopicIdAndStateKey(8L, "clue.note"));
            assertTrue(w.registry.selectAttributeMarks(8L).isEmpty());
            assertThrows(ServiceException.class, () -> w.keys.register(8L, Collections.singletonList(key(8L))));
            assertEquals(1, w.registry.rows.size());
            assertEquals("原线索", w.registry.rows.get("clue.note").getLabel());
            assertEquals(1L, w.registry.version);
        }
    }
}

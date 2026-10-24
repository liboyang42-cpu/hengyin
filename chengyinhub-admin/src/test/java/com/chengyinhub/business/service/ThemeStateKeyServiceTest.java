package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B1:主题状态键注册校验 + 重复注册走 upsert 的书面契约。 */
class ThemeStateKeyServiceTest {

    private ThemeStateKeyMapper mapper;
    private ThemeStateKeyService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ThemeStateKeyMapper.class);
        service = new ThemeStateKeyService(mapper);
    }

    @Test
    void validRegistrationWritesDefaultInitialValueAndTimestamps() {
        ThemeStateKey key = key("clue.ledger_page", "flag", "run");

        service.register(7L, Arrays.asList(key));

        ArgumentCaptor<ThemeStateKey> captured = ArgumentCaptor.forClass(ThemeStateKey.class);
        verify(mapper).upsertThemeStateKey(captured.capture());
        assertEquals(Long.valueOf(7L), captured.getValue().getTopicId());
        assertEquals(Integer.valueOf(0), captured.getValue().getInitialValue(), "未给 initial_value 时必须补 0");
        assertNotNull(captured.getValue().getCreateTime());
        assertNotNull(captured.getValue().getUpdateTime());
    }

    @Test
    void rejectsIllegalKeyName() {
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.register(7L, Arrays.asList(key("BadKey", "flag", "run"))));
        assertTrue(error.getMessage().contains("状态键名不合法"), error.getMessage());
        verify(mapper, never()).upsertThemeStateKey(any(ThemeStateKey.class));
    }

    @Test
    void rejectsIllegalKeyType() {
        assertThrows(ServiceException.class,
                () -> service.register(7L, Arrays.asList(key("tag.hurried", "banana", "run"))));
        verify(mapper, never()).upsertThemeStateKey(any(ThemeStateKey.class));
    }

    @Test
    void rejectsIllegalScope() {
        assertThrows(ServiceException.class,
                () -> service.register(7L, Arrays.asList(key("tag.hurried", "tag", "global"))));
        verify(mapper, never()).upsertThemeStateKey(any(ThemeStateKey.class));
    }

    @Test
    void cluePrefixMustBeFlag() {
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.register(7L, Arrays.asList(key("clue.ledger_page", "counter", "run"))));
        assertTrue(error.getMessage().contains("clue 前缀的状态键类型必须是 flag"), error.getMessage());
        verify(mapper, never()).upsertThemeStateKey(any(ThemeStateKey.class));
    }

    /** F3:命名空间与类型一一对应,四个前缀里另外三个同样不许交叉。 */
    @Test
    void everyOtherNamespaceOnlyAcceptsItsOwnKeyType() {
        ServiceException counterError = assertThrows(ServiceException.class,
                () -> service.register(7L, Arrays.asList(key("counter.coins", "flag", "run"))));
        assertTrue(counterError.getMessage().contains("counter 前缀的状态键类型必须是 counter"),
                counterError.getMessage());

        ServiceException relationError = assertThrows(ServiceException.class,
                () -> service.register(7L, Arrays.asList(key("relation.owner", "tag", "run"))));
        assertTrue(relationError.getMessage().contains("relation 前缀的状态键类型必须是 relation"),
                relationError.getMessage());

        ServiceException tagError = assertThrows(ServiceException.class,
                () -> service.register(7L, Arrays.asList(key("tag.hurried", "counter", "run"))));
        assertTrue(tagError.getMessage().contains("tag 前缀的状态键类型必须是 tag"),
                tagError.getMessage());

        verify(mapper, never()).upsertThemeStateKey(any(ThemeStateKey.class));
    }

    /** F3 正控:四组「前缀 = 类型」的合法组合照旧写入。 */
    @Test
    void everyNamespaceAcceptsItsOwnKeyType() {
        service.register(7L, Arrays.asList(
                key("clue.ledger_page", "flag", "run"),
                key("counter.coins", "counter", "run"),
                key("relation.owner", "relation", "run"),
                key("tag.hurried", "tag", "run")));

        verify(mapper, times(4)).upsertThemeStateKey(any(ThemeStateKey.class));
    }

    /** P2-025:party/deployment 运行期未实现,注册口必须拒绝,不能静默按 run 跑。 */
    @Test
    void unimplementedScopesAreRejectedAtRegistration() {
        for (String scope : Arrays.asList("party", "deployment")) {
            ServiceException error = assertThrows(ServiceException.class,
                    () -> service.register(7L, Arrays.asList(key("relation.owner", "relation", scope))));
            assertTrue(error.getMessage().contains("仅支持 run"), error.getMessage());
            assertFalse(service.check(Arrays.asList(key("relation.owner", "relation", scope))).isEmpty(), scope);
        }
        verify(mapper, never()).upsertThemeStateKey(any(ThemeStateKey.class));
    }

    @Test
    void oneBadKeyBlocksTheWholeBatch() {
        assertThrows(ServiceException.class, () -> service.register(7L, Arrays.asList(
                key("tag.hurried", "tag", "run"), key("nope", "tag", "run"))));
        verify(mapper, never()).upsertThemeStateKey(any(ThemeStateKey.class));
    }

    @Test
    void listByTopicReturnsMapperRows() {
        ThemeStateKey row = key("tag.hurried", "tag", "run");
        when(mapper.selectByTopicId(7L)).thenReturn(Arrays.asList(row));
        assertEquals(1, service.listByTopic(7L).size());
        assertTrue(service.listByTopic(null).isEmpty());
    }

    /** R8 创作者端删除:行数 > 0 即删掉,键本就不在返回 false(不报错)。 */
    @Test
    void deleteDelegatesToMapper() {
        when(mapper.deleteByTopicIdAndStateKey(7L, "tag.hurried")).thenReturn(1);
        assertTrue(service.delete(7L, "tag.hurried"));

        when(mapper.deleteByTopicIdAndStateKey(7L, "tag.gone")).thenReturn(0);
        assertFalse(service.delete(7L, "tag.gone"));
    }

    @Test
    void deleteRejectsBadArguments() {
        assertThrows(ServiceException.class, () -> service.delete(null, "tag.hurried"));
        assertThrows(ServiceException.class, () -> service.delete(7L, " "));
        verify(mapper, never()).deleteByTopicIdAndStateKey(any(), anyString());
    }

    /**
     * 真库 upsert 语义本地没有 MySQL 可跑(见任务不确定清单),这里钉住书面契约:
     * 同 topic 重复注册必须走 on duplicate key update,且只覆盖 label/initial_value。
     */
    @Test
    void mapperXmlUpdatesInsteadOfDuplicatingOnSameKey() throws Exception {
        InputStream in = getClass().getResourceAsStream("/mapper/business/ThemeStateKeyMapper.xml");
        assertNotNull(in, "ThemeStateKeyMapper.xml 必须随 chengyinhub-system 交付");
        Scanner scanner = new Scanner(in, "UTF-8").useDelimiter("\\A");
        String xml = scanner.hasNext() ? scanner.next() : "";
        scanner.close();
        assertTrue(xml.contains("on duplicate key update"), "重复注册不允许走纯 insert");
        assertTrue(xml.contains("label = values(label)"));
        assertTrue(xml.contains("initial_value = values(initial_value)"));
    }

    private static ThemeStateKey key(String stateKey, String type, String scope) {
        ThemeStateKey key = new ThemeStateKey();
        key.setStateKey(stateKey);
        key.setKeyType(type);
        key.setScope(scope);
        return key;
    }

    /** F2 T01:改 label 不改键名;类型 / 作用域取库里的,body 覆盖不了。 */
    @Test
    void updateDefinitionKeepsTheStateKeyAndWritesTheNewMetadata() {
        ThemeStateKey existing = key("counter.str", "counter", "run");
        existing.setId(5L);
        when(mapper.selectByTopicIdAndStateKey(7L, "counter.str")).thenReturn(existing);
        ThemeStateKey patch = new ThemeStateKey();
        patch.setLabel("膂力");
        patch.setInitialValue(8);
        patch.setMinValue(0);
        patch.setMaxValue(20);
        patch.setDisplayType("bar");
        patch.setColor("#AA3300");
        patch.setIcon("muscle");
        patch.setSortOrder(1);
        patch.setVisible(Boolean.TRUE);
        patch.setPersistScope("topic");
        patch.setRulesJson("{\"levels\":[{\"at\":5,\"label\":\"壮\"}]}");

        ThemeStateKey saved = service.updateDefinition(7L, "counter.str", patch);

        ArgumentCaptor<ThemeStateKey> captured = ArgumentCaptor.forClass(ThemeStateKey.class);
        verify(mapper).updateDefinition(captured.capture());
        assertEquals("counter.str", captured.getValue().getStateKey());
        assertEquals("counter", captured.getValue().getKeyType());
        assertEquals("膂力", captured.getValue().getLabel());
        assertEquals("topic", captured.getValue().getPersistScope());
        assertEquals(Integer.valueOf(20), captured.getValue().getMaxValue());
        assertEquals(Long.valueOf(5L), saved.getId());
    }

    @Test
    void updateDefinitionRejectsRenameAndUnknownKey() {
        ThemeStateKey renamed = new ThemeStateKey();
        renamed.setStateKey("counter.power");
        ServiceException rename = assertThrows(ServiceException.class,
                () -> service.updateDefinition(7L, "counter.str", renamed));
        assertEquals(ThemeStateKeyService.ERR_RENAME, rename.getDetailMessage());
        assertEquals(Integer.valueOf(400), rename.getCode());

        ServiceException missing = assertThrows(ServiceException.class,
                () -> service.updateDefinition(7L, "counter.ghost", new ThemeStateKey()));
        assertEquals(Integer.valueOf(404), missing.getCode());
        verify(mapper, never()).updateDefinition(any(ThemeStateKey.class));
    }

    /** 老数据没有范围 = 0..100;定义接口里越界初值 → 400 STATE_KEY_RANGE。 */
    @Test
    void updateDefinitionRejectsInitialOutsideDefaultRange() {
        when(mapper.selectByTopicIdAndStateKey(7L, "counter.str")).thenReturn(key("counter.str", "counter", "run"));
        ThemeStateKey patch = new ThemeStateKey();
        patch.setInitialValue(120);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.updateDefinition(7L, "counter.str", patch));

        assertEquals(ThemeStateKeyService.ERR_RANGE, error.getDetailMessage());
        assertTrue(error.getMessage().contains("0..100"), error.getMessage());
    }

    /** 老注册路径(发布期同步 journeyRules 属性)不带范围,不因 0..100 默认被拦。 */
    @Test
    void legacyRegistrationWithoutRangeKeepsAcceptingAnyInitialValue() {
        ThemeStateKey legacy = key("counter.gold", "counter", "run");
        legacy.setInitialValue(500);

        service.register(7L, Arrays.asList(legacy));

        verify(mapper).upsertThemeStateKey(legacy);
        ThemeStateKey ranged = key("counter.hp", "counter", "run");
        ranged.setInitialValue(30);
        ranged.setMaxValue(20);
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.register(7L, Arrays.asList(ranged)));
        assertEquals(ThemeStateKeyService.ERR_RANGE, error.getDetailMessage());
    }

    @Test
    void checkListsEveryProblemWithoutWriting() {
        ThemeStateKey badColor = key("counter.a", "counter", "run");
        badColor.setColor("red");
        ThemeStateKey topicTag = key("tag.b", "tag", "run");
        topicTag.setPersistScope("topic");
        ThemeStateKey badRules = key("counter.c", "counter", "run");
        badRules.setRulesJson("{\"formula\":\"x*2\"}");
        ThemeStateKey fine = key("counter.d", "counter", "run");
        fine.setInitialValue(100);

        java.util.List<String> problems = service.check(Arrays.asList(badColor, topicTag, badRules, fine));

        assertEquals(3, problems.size(), problems.toString());
        verify(mapper, never()).upsertThemeStateKey(any(ThemeStateKey.class));
        verify(mapper, never()).updateDefinition(any(ThemeStateKey.class));
    }
}

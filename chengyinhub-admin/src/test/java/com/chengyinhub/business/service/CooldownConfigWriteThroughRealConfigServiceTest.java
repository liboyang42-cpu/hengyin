package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.PlayerWithdrawalCooldownMapper;
import com.chengyinhub.business.service.impl.PlayerWithdrawalCooldownServiceImpl;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.system.domain.SysConfig;
import com.chengyinhub.system.mapper.SysConfigMapper;
import com.chengyinhub.system.service.ISysConfigService;
import com.chengyinhub.system.service.impl.SysConfigServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A3 冷静期改写的<b>跨模块调用契约</b>：{@code ISysConfigService} 跑真的实现，不 mock。
 *
 * <p>为什么必须有这一层：{@code PlayerWithdrawalCooldownServiceTest} 把整个
 * {@code ISysConfigService} 替身掉，断言是 {@code verify(configService).updateConfig(any())} ——
 * 那是<b>回执</b>（我调过它了），不是<b>观测</b>（对面那一行真的变了吗）。
 * {@code any()} 连「传进去的 SysConfig 有没有 configId」都不看，而 RuoYi 的
 * {@code SysConfigServiceImpl.updateConfig} 第一句就拿 configId 反查再解引用 ——
 * 少一个主键，端点 100% NPE，配置一个字都改不动，审计行还会被一并回滚。
 *
 * <p>这里唯一被替身的是<b>系统边界</b>：sys_config 这张表（用一张按主键工作的内存表模拟
 * {@code SysConfigMapper.xml} 的 {@code sqlwhereSearch} 与
 * {@code where config_id = #{configId}}）与 Redis。判断逻辑全跑真的。
 * 真 SQL 的行为另由 {@code PlayerWithdrawalCooldownMybatisIntegrationTest} 在真 MySQL 上回读。
 */
class CooldownConfigWriteThroughRealConfigServiceTest {

    private static final String KEY = IPlayerWithdrawalCooldownService.CONFIG_KEY;

    private InMemorySysConfigTable table;
    private ISysConfigService configService;
    private PlayerWithdrawalCooldownMapper cooldownMapper;
    private PlayerWithdrawalCooldownServiceImpl cooldownService;

    @BeforeEach
    void setUp() {
        table = new InMemorySysConfigTable();
        table.seed(101L, KEY, "48");
        SysConfigServiceImpl impl = new SysConfigServiceImpl();
        ReflectionTestUtils.setField(impl, "configMapper", table);
        ReflectionTestUtils.setField(impl, "redisCache", mock(RedisCache.class));
        configService = impl;

        cooldownMapper = mock(PlayerWithdrawalCooldownMapper.class);
        when(cooldownMapper.insertConfigChangeAudit(anyInt(), anyInt(), anyString(), anyLong())).thenReturn(1);
        cooldownService = new PlayerWithdrawalCooldownServiceImpl(cooldownMapper, configService);
    }

    /** 观测而不是回执：改完之后<b>从 sys_config 那一行读回来</b>，必须真的是新值。 */
    @Test
    void rewritingTheCooldownActuallyChangesTheStoredConfigValue() {
        assertEquals(48, cooldownService.currentCooldownHours());

        assertEquals(48, cooldownService.rewriteCooldownHours(24, "投诉争议期按法务意见收窄", 71L));

        assertEquals("24", table.valueOf(KEY), "sys_config 那一行必须真的变成 24");
        assertEquals(24, cooldownService.currentCooldownHours());
    }

    /** 命中 0 行必须点名报错。丢掉返回值 = 「审计说改了、配置没动」的静默假成功。 */
    @Test
    void anUpdateThatMatchesNoRowIsRefusedInsteadOfSilentlySucceeding() {
        table.breakUpdateSoItMatchesNothing();

        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> cooldownService.rewriteCooldownHours(24, "理由", 71L));

        // 点名到键与「命中 0 行」，否则一个不相干的 NPE 也能让这条断言绿。
        assertTrue(String.valueOf(refused.getMessage()).contains(KEY)
                        && String.valueOf(refused.getMessage()).contains("0 行"),
                "必须点名是哪个键没写进去，实际报的是：" + refused.getMessage());
        assertEquals("48", table.valueOf(KEY), "写不进去就必须保持原值，不许假装改过");
    }

    /** 键根本不存在时点名拒绝，不许当成「改成功了」。 */
    @Test
    void rewritingAKeyThatWasNeverSeededIsRefused() {
        table.clear();

        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> cooldownService.rewriteCooldownHours(24, "理由", 71L));

        assertTrue(String.valueOf(refused.getMessage()).contains("不存在"),
                "键不存在要说人话，实际报的是：" + refused.getMessage());
    }

    // ── 通用参数页那条无审计旁路必须关掉（审计 2：冷却配置「不是普通编辑接口」）──────────

    /**
     * RuoYi 通用参数页只写新值、不留前值、不采集理由。它和专用端点改的是同一个键 ——
     * 并排新增一条合规的路，不等于关掉了不合规的那条。
     */
    @Test
    void theGenericConfigEditRouteCannotTouchAFundsPolicyKey() {
        SysConfig edited = new SysConfig();
        edited.setConfigId(101L);
        edited.setConfigKey(KEY);
        edited.setConfigValue("1");

        assertThrows(RuntimeException.class, () -> configService.updateConfig(edited));

        assertEquals("48", table.valueOf(KEY), "通用编辑必须一个字都改不动它");
        assertEquals(48, cooldownService.currentCooldownHours());
    }

    /** 通用页改别的键照常可用：这道闸只收窄资金政策键，不是把参数管理整个关掉。 */
    @Test
    void theGenericConfigEditRouteStillWorksForOrdinaryKeys() {
        table.seed(202L, "sys.index.skinName", "skin-blue");
        SysConfig edited = new SysConfig();
        edited.setConfigId(202L);
        edited.setConfigKey("sys.index.skinName");
        edited.setConfigValue("skin-green");

        assertEquals(1, configService.updateConfig(edited));
        assertEquals("skin-green", table.valueOf("sys.index.skinName"));
    }

    /**
     * 改名绕道也要堵：先把这一行改名成别的键，再从通用页新建一个同名键写 1，
     * 就绕开了专用入口。两头都拒。
     */
    @Test
    void aFundsPolicyKeyCanBeNeitherRenamedAwayNorRecreatedFromTheGenericPage() {
        SysConfig renamed = new SysConfig();
        renamed.setConfigId(101L);
        renamed.setConfigKey("withdrawal.legacy-parked-key");
        renamed.setConfigValue("48");
        assertThrows(RuntimeException.class, () -> configService.updateConfig(renamed));

        SysConfig recreated = new SysConfig();
        recreated.setConfigKey(KEY);
        recreated.setConfigValue("1");
        assertThrows(RuntimeException.class, () -> configService.insertConfig(recreated));

        assertEquals("48", table.valueOf(KEY));
    }

    @Test
    void theGenericConfigDeleteRouteCannotRemoveAFundsPolicyKey() {
        assertThrows(RuntimeException.class, () -> configService.deleteConfigByIds(new Long[]{101L}));

        assertEquals("48", table.valueOf(KEY), "资金政策键不得被通用删除路径移除");
        assertEquals(48, cooldownService.currentCooldownHours());
    }

    @Test
    void theGenericConfigDeleteRouteStillRemovesOrdinaryKeys() {
        table.seed(202L, "sys.index.skinName", "skin-blue");

        configService.deleteConfigByIds(new Long[]{202L});

        assertNull(table.valueOf("sys.index.skinName"));
    }

    /**
     * 黑名单与消费方的键常量必须是同一个串。两处各写一遍字面量，改一处漏一处
     * 就是「闸还在、但它守的是另一个键」—— 静默失效，零告警。
     */
    @Test
    void theBlockedKeySetCoversTheCooldownConfigKeyItself() {
        assertTrue(ISysConfigService.FUNDS_POLICY_CONFIG_KEYS.contains(KEY),
                "冷静期时长键必须在资金政策键黑名单里，否则通用参数页那条无审计旁路还开着");
    }

    /** 专用入口不会把 update_by 丢掉：谁改的这一行必须落在行上。 */
    @Test
    void theDedicatedWriteKeepsTheOperatorOnTheRow() {
        cooldownService.rewriteCooldownHours(12, "理由", 71L);

        assertEquals("71", table.rowOf(KEY).getUpdateBy());
        assertNotEquals("48", table.valueOf(KEY));
    }

    /**
     * 按主键工作的 sys_config 内存表。
     *
     * <p>逐条对应真 SQL：{@code selectConfig} = {@code sqlwhereSearch}（configId / configKey 都是
     * 精确等值，给了几个就都要满足）；{@code updateConfig} = {@code where config_id = #{configId}}，
     * 主键为 null 时命中 0 行、返回 0（MySQL 三值逻辑下 {@code = NULL} 恒不成立）。
     */
    private static final class InMemorySysConfigTable implements SysConfigMapper {
        private final List<SysConfig> rows = new ArrayList<SysConfig>();
        private boolean updateMatchesNothing;

        void seed(Long configId, String key, String value) {
            SysConfig row = new SysConfig();
            row.setConfigId(configId);
            row.setConfigKey(key);
            row.setConfigValue(value);
            row.setConfigType("N");
            rows.add(row);
        }

        void clear() {
            rows.clear();
        }

        void breakUpdateSoItMatchesNothing() {
            updateMatchesNothing = true;
        }

        String valueOf(String key) {
            SysConfig row = rowOf(key);
            return row == null ? null : row.getConfigValue();
        }

        SysConfig rowOf(String key) {
            for (SysConfig row : rows) {
                if (row.getConfigKey().equals(key)) return row;
            }
            return null;
        }

        @Override
        public SysConfig selectConfig(SysConfig probe) {
            for (SysConfig row : rows) {
                if (probe.getConfigId() != null && !probe.getConfigId().equals(row.getConfigId())) continue;
                if (probe.getConfigKey() != null && !probe.getConfigKey().isEmpty()
                        && !probe.getConfigKey().equals(row.getConfigKey())) continue;
                if (probe.getConfigId() == null
                        && (probe.getConfigKey() == null || probe.getConfigKey().isEmpty())) continue;
                return copyOf(row);
            }
            return null;
        }

        @Override
        public SysConfig selectConfigById(Long configId) {
            SysConfig probe = new SysConfig();
            probe.setConfigId(configId);
            return selectConfig(probe);
        }

        @Override
        public SysConfig selectFundsPolicyConfigForUpdate(String configKey) {
            SysConfig probe = new SysConfig();
            probe.setConfigKey(configKey);
            return selectConfig(probe);
        }

        @Override
        public List<SysConfig> selectConfigList(SysConfig probe) {
            List<SysConfig> matched = new ArrayList<SysConfig>();
            for (SysConfig row : rows) {
                if (probe.getConfigKey() == null || row.getConfigKey().contains(probe.getConfigKey())) {
                    matched.add(copyOf(row));
                }
            }
            return matched;
        }

        @Override
        public SysConfig checkConfigKeyUnique(String configKey) {
            for (SysConfig row : rows) {
                if (row.getConfigKey().equals(configKey)) return copyOf(row);
            }
            return null;
        }

        @Override
        public int insertConfig(SysConfig config) {
            seed(rows.size() + 900L, config.getConfigKey(), config.getConfigValue());
            return 1;
        }

        @Override
        public int updateConfig(SysConfig config) {
            if (updateMatchesNothing || config.getConfigId() == null) return 0;
            for (SysConfig row : rows) {
                if (config.getConfigId().equals(row.getConfigId())) {
                    if (config.getConfigKey() != null) row.setConfigKey(config.getConfigKey());
                    if (config.getConfigValue() != null) row.setConfigValue(config.getConfigValue());
                    if (config.getUpdateBy() != null) row.setUpdateBy(config.getUpdateBy());
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int deleteConfigById(Long configId) {
            for (int i = 0; i < rows.size(); i++) {
                if (configId.equals(rows.get(i).getConfigId())) {
                    rows.remove(i);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int deleteConfigByIds(Long[] configIds) {
            return 0;
        }

        private static SysConfig copyOf(SysConfig row) {
            SysConfig copy = new SysConfig();
            copy.setConfigId(row.getConfigId());
            copy.setConfigKey(row.getConfigKey());
            copy.setConfigValue(row.getConfigValue());
            copy.setConfigType(row.getConfigType());
            copy.setUpdateBy(row.getUpdateBy());
            copy.setRemark(row.getRemark());
            return copy;
        }
    }
}

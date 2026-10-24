package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.ThemeCharacter;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.ThemeCharacterMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 MyBatis + 本地 MySQL:F2 RPG P0 的两段 SQL 契约,mock 证明不了 ——
 * <ol>
 *   <li>theme_state_key 老注册 upsert 不带扩展列时 {@code coalesce} 保留作者在定义接口设过的值;
 *       定义接口整体覆盖时键名 / 类型 / 作用域不在 SET 里,改 label 不改键名;</li>
 *   <li>theme_character 台账只在来源 (sessionId, version) 严格更新时覆盖,同源重放与旧源都是 0 行;
 *       唯一键冲突的插入不改已有行。</li>
 * </ol>
 * 本套连不上 = 红,不 skip。schema 走真实迁移(新列迁移与新表迁移都在里面)。
 */
class ThemeCharacterMapperMyBatisIT {

    private SqlSession open() {
        try {
            Configuration cfg = new Configuration();
            cfg.getTypeAliasRegistry().registerAlias("ThemeStateKey", ThemeStateKey.class);
            cfg.getTypeAliasRegistry().registerAlias("ThemeCharacter", ThemeCharacter.class);
            PooledDataSource ds = new PooledDataSource("com.mysql.cj.jdbc.Driver",
                    MybatisItDatabase.url("theme_character"), MybatisItDatabase.user(), MybatisItDatabase.password());
            cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
            for (String xml : new String[]{"mapper/business/ThemeStateKeyMapper.xml", "mapper/business/ThemeCharacterMapper.xml"}) {
                try (InputStream in = Resources.getResourceAsStream(xml)) {
                    new XMLMapperBuilder(in, cfg, xml, cfg.getSqlFragments()).parse();
                }
            }
            SqlSession s = new SqlSessionFactoryBuilder().build(cfg).openSession(true);
            s.getConnection().isValid(2);
            ItSchema.applyMigrations(s.getConnection(),
                    "sql/migration_theme_state_key_20260916.sql",
                    "sql/migration_20261022_theme_state_key_attribute_def.sql",
                    "sql/migration_20261022_theme_character.sql");
            return s;
        } catch (Throwable t) {
            throw new IllegalStateException("IT 前置不满足(库不可达 / Mapper XML / schema 配置有误),本套必须真跑不得跳过:"
                    + t.getMessage(), t);
        }
    }

    @Test
    void legacyUpsertKeepsDefinitionColumnsAndUpdateNeverRenames() {
        try (SqlSession s = open()) {
            ThemeStateKeyMapper mapper = s.getMapper(ThemeStateKeyMapper.class);
            ThemeStateKey key = key("counter.str", "力量", 3);
            mapper.upsertThemeStateKey(key);
            ThemeStateKey stored = mapper.selectByTopicIdAndStateKey(7L, "counter.str");
            assertNull(stored.getMaxValue(), "老数据扩展列全为 NULL(= 0..100 老口径)");
            assertEquals(100, stored.effectiveMax());

            ThemeStateKey definition = key("counter.str", "膂力", 8);
            definition.setMinValue(0);
            definition.setMaxValue(20);
            definition.setPersistScope("topic");
            definition.setVisible(Boolean.FALSE);
            definition.setSortOrder(2);
            assertEquals(1, mapper.updateDefinition(definition));

            mapper.upsertThemeStateKey(key("counter.str", "力量", 4));
            stored = mapper.selectByTopicIdAndStateKey(7L, "counter.str");
            assertEquals("counter.str", stored.getStateKey());
            assertEquals("力量", stored.getLabel());
            assertEquals(Integer.valueOf(4), stored.getInitialValue());
            assertEquals(Integer.valueOf(20), stored.getMaxValue(), "老注册路径不带扩展列 = 保留定义接口设的值");
            assertEquals("topic", stored.getPersistScope());
            assertEquals(Boolean.FALSE, stored.getVisible());
            assertEquals(1, mapper.selectByTopicId(7L).size());
        }
    }

    @Test
    void ledgerOnlyMovesForwardOnStrictlyNewerSource() {
        try (SqlSession s = open()) {
            ThemeCharacterMapper mapper = s.getMapper(ThemeCharacterMapper.class);
            assertNull(mapper.selectByMemberAndTopic(11L, 7L));
            assertEquals(0, mapper.updateIfNewer(row("{\"counter.str\":5}", 40L, 3)));
            assertEquals(1, mapper.insertIfAbsent(row("{\"counter.str\":5}", 40L, 3)));
            mapper.insertIfAbsent(row("{\"counter.str\":1}", 99L, 9));
            assertEquals("{\"counter.str\":5}", mapper.selectByMemberAndTopic(11L, 7L).getValuesJson(),
                    "唯一键冲突的插入不改已有行");

            assertEquals(0, mapper.updateIfNewer(row("{\"counter.str\":2}", 40L, 3)), "同源重放 = 0 行");
            assertEquals(0, mapper.updateIfNewer(row("{\"counter.str\":2}", 39L, 99)), "旧局不覆盖");
            assertEquals(1, mapper.updateIfNewer(row("{\"counter.str\":6}", 40L, 4)));
            assertEquals(1, mapper.updateIfNewer(row("{\"counter.str\":9}", 41L, 0)));
            ThemeCharacter stored = mapper.selectByMemberAndTopic(11L, 7L);
            assertEquals("{\"counter.str\":9}", stored.getValuesJson());
            assertEquals(Long.valueOf(41L), stored.getSourceSessionId());
            assertEquals(Integer.valueOf(0), stored.getSourceVersion());
        }
    }

    private static ThemeStateKey key(String name, String label, int initial) {
        ThemeStateKey key = new ThemeStateKey();
        key.setTopicId(7L);
        key.setStateKey(name);
        key.setKeyType(ThemeStateKey.TYPE_COUNTER);
        key.setScope(ThemeStateKey.SCOPE_RUN);
        key.setLabel(label);
        key.setInitialValue(initial);
        Date now = new Date();
        key.setCreateTime(now);
        key.setUpdateTime(now);
        return key;
    }

    private static ThemeCharacter row(String values, long sourceSession, int sourceVersion) {
        ThemeCharacter row = new ThemeCharacter();
        row.setMemberId(11L);
        row.setTopicId(7L);
        row.setValuesJson(values);
        row.setSourceSessionId(sourceSession);
        row.setSourceVersion(sourceVersion);
        Date now = new Date();
        row.setCreateTime(now);
        row.setUpdateTime(now);
        return row;
    }
}

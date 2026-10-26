package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.ThemeCharacter;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.ThemeCharacterMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.support.ThemeCharacterService;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.JourneyInitialState;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                    "sql/migration_20261022_theme_character.sql",
                    "sql/migration_20261026_theme_attribute_registry_version.sql");
            try (java.sql.Statement statement = s.getConnection().createStatement()) {
                statement.execute("create table if not exists cms_topic (id bigint primary key)");
                statement.execute("insert ignore into cms_topic(id) values(7)");
            }
            return s;
        } catch (Throwable t) {
            throw new IllegalStateException("IT 前置不满足(库不可达 / Mapper XML / schema 配置有误),本套必须真跑不得跳过:"
                    + t.getMessage(), t);
        }
    }

    @Test
    void registryLockSerializesPublicationAndWriterAndRollbackRestoresVersion() throws Exception {
        try (SqlSession publishing = open(); SqlSession writing = open()) {
            publishing.getConnection().setAutoCommit(false);
            writing.getConnection().setAutoCommit(false);
            ThemeStateKeyService publisher = new ThemeStateKeyService(publishing.getMapper(ThemeStateKeyMapper.class));
            ThemeStateKeyService writer = new ThemeStateKeyService(writing.getMapper(ThemeStateKeyMapper.class));
            String before = publisher.readBinding(7L, java.util.Collections.emptyList()).path("registryVersion").asText();
            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
            try {
                java.util.concurrent.Future<?> pending = executor.submit(() -> {
                    started.countDown();
                    writer.register(7L, java.util.Collections.singletonList(key("counter.version", "版本", 1)));
                    writing.commit(true);
                });
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> pending.get(200, java.util.concurrent.TimeUnit.MILLISECONDS), "发布事务必须阻挡注册写入");
                publishing.commit(true);
                pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
                String after = publisher.readBinding(7L, java.util.Collections.emptyList()).path("registryVersion").asText();
                assertEquals(Long.parseLong(before) + 1, Long.parseLong(after));
                com.chengyinhub.common.exception.ServiceException stale = assertThrows(
                        com.chengyinhub.common.exception.ServiceException.class,
                        () -> publisher.freezeDefinition(7L, 3L, before, new JourneyRules()));
                assertEquals("ATTRIBUTE_REGISTRY_VERSION_CONFLICT", stale.getDetailMessage());
                publishing.rollback(true);
                writer.register(7L, java.util.Collections.singletonList(key("counter.version", "未提交", 2)));
                writing.rollback(true);
                assertEquals(after, publisher.readBinding(7L, java.util.Collections.emptyList()).path("registryVersion").asText());
                assertEquals("版本", publishing.getMapper(ThemeStateKeyMapper.class)
                        .selectByTopicIdAndStateKeyForUpdate(7L, "counter.version").getLabel());
                publishing.rollback(true);
            } finally {
                publishing.rollback(true); writing.rollback(true); executor.shutdownNow();
            }
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

    @Test
    void servicePromotesNewerSourceAfterCompetingFirstInsert() {
        assertCompetingFirstInsert(40L, 41L, "{\"counter.str\":9}");
    }

    /** 注册/角色服务和两张表走真实SQL；冻结会话由夹具提供，不冒充04建局事务测试。 */
    @Test
    void stableDefinitionLifecycleReadsTemplateThenCarriesOnlyTopicValues() throws Exception {
        try (SqlSession s = open()) {
            ThemeStateKeyMapper definitions = s.getMapper(ThemeStateKeyMapper.class);
            ThemeCharacterMapper ledger = s.getMapper(ThemeCharacterMapper.class);
            ThemeStateKey strength = key("counter.str", "力量", 3);
            strength.setMinValue(0); strength.setMaxValue(20); strength.setPersistScope("topic");
            ThemeStateKey focus = key("counter.focus", "专注", 5);
            focus.setMinValue(0); focus.setMaxValue(20); focus.setPersistScope("run");
            new ThemeStateKeyService(definitions).register(7L, java.util.Arrays.asList(strength, focus));
            String raw = "{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":["
                    + "{\"key\":\"counter.str\",\"label\":\"力量\",\"initial\":3,\"min\":0,\"max\":20,\"visible\":true},"
                    + "{\"key\":\"counter.focus\",\"label\":\"专注\",\"initial\":5,\"min\":0,\"max\":20,\"visible\":true}]}";
            JourneyRules rules = JourneyRules.parse(raw);
            String frozen = "{\"journeyRules\":" + raw + ",\"attributeDefinition\":"
                    + new ThemeStateKeyService(definitions).freezeDefinition(7L, 3L, new ThemeStateKeyService(definitions)
                            .readBinding(7L, java.util.Collections.emptyList()).path("registryVersion").asText(), rules) + "}";
            CmsTopic topic = new CmsTopic();
            topic.setId(7L); topic.setMemberId(99L); topic.setPublishedConfigVersion(3L); topic.setJourneyRulesJson(raw);
            CmsTopicMapper topics = mock(CmsTopicMapper.class);
            when(topics.selectCmsTopicById(7L)).thenReturn(topic);
            TopicRouteSessionMapper sessions = mock(TopicRouteSessionMapper.class);
            ThemeCharacterService service = new ThemeCharacterService(definitions, ledger, sessions, topics);
            ObjectMapper json = new ObjectMapper();

            JsonNode template = json.valueToTree(service.card(11L, 7L));
            assertFalse(template.path("created").asBoolean());
            assertNull(ledger.selectByMemberAndTopic(11L, 7L));
            assertEquals(3, attributeValue(template, "counter.str"));
            assertEquals(5, attributeValue(template, "counter.focus"));

            TopicRouteEngine.State first = new TopicRouteEngine.State();
            JourneyInitialState.initialize(rules, first);
            service.seedRun(11L, 7L, rules, first, frozen);
            assertNull(ledger.selectByMemberAndTopic(11L, 7L), "无上一局不创建虚假的结转来源");
            first.getVariables().put("counter.str", 9);
            first.getVariables().put("counter.focus", 1);
            TopicRouteSession previous = new TopicRouteSession();
            previous.setId(41L); previous.setVersion(6); previous.setConfigVersion(3L);
            previous.setStatus(TopicRouteSession.STATUS_RUNNING);
            previous.setConfigSnapshotJson(frozen);
            previous.setStateJson(json.writeValueAsString(first));
            when(sessions.selectHistoryPage(11L, 7L, null, 1))
                    .thenReturn(java.util.Collections.singletonList(previous));
            JsonNode running = json.valueToTree(service.card(11L, 7L));
            assertTrue(running.path("created").asBoolean());
            assertEquals(9, attributeValue(running, "counter.str"));
            assertEquals(1, attributeValue(running, "counter.focus"));
            assertEquals(running, json.valueToTree(service.card(11L, 7L)), "同局只读不会重置状态");
            assertNull(ledger.selectByMemberAndTopic(11L, 7L), "读取角色卡不写结转台账");

            previous.setStatus(TopicRouteSession.STATUS_COMPLETED);
            TopicRouteEngine.State second = new TopicRouteEngine.State();
            JourneyInitialState.initialize(rules, second);
            service.seedRun(11L, 7L, rules, second, frozen);
            assertEquals(9, second.getVariables().get("counter.str"));
            assertEquals(5, second.getVariables().get("counter.focus"));
            ThemeCharacter carried = ledger.selectByMemberAndTopic(11L, 7L);
            assertEquals(41L, carried.getSourceSessionId().longValue());
            assertEquals(6, carried.getSourceVersion().intValue());
            assertEquals("{\"counter.str\":9}", carried.getValuesJson());

            previous.setId(42L); previous.setVersion(0); previous.setStatus(TopicRouteSession.STATUS_RUNNING);
            previous.setStateJson(json.writeValueAsString(second));
            JsonNode nextCard = json.valueToTree(service.card(11L, 7L));
            assertEquals(9, attributeValue(nextCard, "counter.str"));
            assertEquals(5, attributeValue(nextCard, "counter.focus"));
            assertEquals(41L, ledger.selectByMemberAndTopic(11L, 7L).getSourceSessionId().longValue());
            strength.setPersistScope("run"); focus.setPersistScope("topic");
            definitions.updateDefinition(strength); definitions.updateDefinition(focus);
            String changed = "{\"attributeDefinition\":" + new ThemeStateKeyService(definitions)
                    .freezeDefinition(7L, 4L, new ThemeStateKeyService(definitions)
                            .readBinding(7L, java.util.Collections.emptyList()).path("registryVersion").asText(), rules) + "}";
            previous.setStateJson("{\"variables\":{\"counter.str\":9,\"counter.focus\":1}}");
            TopicRouteEngine.State changedScopeRun = new TopicRouteEngine.State();
            JourneyInitialState.initialize(rules, changedScopeRun);
            service.seedRun(11L, 7L, rules, changedScopeRun, changed);
            assertEquals(3, changedScopeRun.getVariables().get("counter.str"), "topic转run从新冻结初值开始");
            assertEquals(5, changedScopeRun.getVariables().get("counter.focus"), "run转topic不继承旧run值");
            assertEquals(41L, ledger.selectByMemberAndTopic(11L, 7L).getSourceSessionId().longValue(), "无持续topic值不伪造结转");
            JsonNode anotherMember = json.valueToTree(service.card(12L, 7L));
            assertFalse(anotherMember.path("created").asBoolean());
            assertEquals(3, attributeValue(anotherMember, "counter.str"));
            assertNull(ledger.selectByMemberAndTopic(12L, 7L), "不串账号或凭读取创建角色台账");
        }
    }

    private static int attributeValue(JsonNode card, String key) {
        for (JsonNode row : card.path("attributes")) {
            if (key.equals(row.path("stateKey").asText())) return row.path("value").asInt();
        }
        throw new AssertionError("missing attribute: " + key);
    }

    @Test
    void serviceDoesNotOverwriteNewerCompetingFirstInsert() {
        assertCompetingFirstInsert(42L, 42L, "{\"counter.str\":5}");
    }

    /**
     * 确定性重现 update 未命中与 insert 之间的已提交竞争写入。
     * 台账与注册表全部走真实 MyBatis/MySQL；仅历史局查询使用固定来源夹具。
     * 不把它当成线程压力测试，也不以 mocked affected rows 代替唯一键/CAS。
     */
    private void assertCompetingFirstInsert(long competingSource, long expectedSource, String expectedValues) {
        try (SqlSession s = open()) {
            ThemeCharacterMapper ledger = s.getMapper(ThemeCharacterMapper.class);
            ThemeStateKeyMapper definitions = s.getMapper(ThemeStateKeyMapper.class);
            ThemeStateKey definition = key("counter.str", "力量", 3);
            definition.setPersistScope("topic");
            definitions.upsertThemeStateKey(definition);
            assertNull(ledger.selectByMemberAndTopic(11L, 7L));
            ThemeCharacterMapper interleaved = new ThemeCharacterMapper() {
                @Override
                public ThemeCharacter selectByMemberAndTopic(Long memberId, Long topicId) {
                    return ledger.selectByMemberAndTopic(memberId, topicId);
                }
                @Override
                public int updateIfNewer(ThemeCharacter value) {
                    return ledger.updateIfNewer(value);
                }
                @Override
                public int insertIfAbsent(ThemeCharacter value) {
                    // openSession(true): this competing insert commits before the service insert.
                    assertEquals(1, ledger.insertIfAbsent(row("{\"counter.str\":5}", competingSource, 0)));
                    return ledger.insertIfAbsent(value);
                }
            };
            TopicRouteSession latest = new TopicRouteSession();
            latest.setId(41L);
            latest.setVersion(1);
            latest.setStateJson("{\"variables\":{\"counter.str\":9}}");
            TopicRouteSessionMapper sessions = mock(TopicRouteSessionMapper.class);
            when(sessions.selectHistoryPage(11L, 7L, null, 1))
                    .thenReturn(java.util.Collections.singletonList(latest));
            ThemeCharacterService service = new ThemeCharacterService(definitions, interleaved, sessions, null);
            TopicRouteEngine.State state = new TopicRouteEngine.State();
            state.getVariables().put("counter.str", 3);

            JourneyRules rules = JourneyRules.parse("{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":["
                    + "{\"key\":\"counter.str\",\"label\":\"力量\",\"initial\":3,\"min\":0,\"max\":100}]}");
            String frozen = "{\"attributeDefinition\":" + new ThemeStateKeyService(definitions)
                    .freezeDefinition(7L, 1L, new ThemeStateKeyService(definitions)
                            .readBinding(7L, java.util.Collections.emptyList()).path("registryVersion").asText(), rules) + "}";
            latest.setConfigVersion(1L); latest.setConfigSnapshotJson(frozen);
            service.seedRun(11L, 7L, rules, state, frozen);

            ThemeCharacter stored = ledger.selectByMemberAndTopic(11L, 7L);
            assertEquals(Long.valueOf(expectedSource), stored.getSourceSessionId());
            assertEquals(expectedValues, stored.getValuesJson());
            assertEquals(Integer.valueOf(expectedSource == 41L ? 1 : 0), stored.getSourceVersion());
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

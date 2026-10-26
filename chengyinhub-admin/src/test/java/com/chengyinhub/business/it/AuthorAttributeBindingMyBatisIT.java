package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.support.AuthorAttributeBinding;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.sql.PreparedStatement;
import java.util.Collections;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real registry Mapper + author freeze helper. No availability skips; does not replace HTTP/whole-save acceptance. */
class AuthorAttributeBindingMyBatisIT {
    private SqlSession open() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("ThemeStateKey", ThemeStateKey.class);
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MybatisItDatabase.url("author_attribute_binding"),
                MybatisItDatabase.user(), MybatisItDatabase.password())));
        String xml = "mapper/business/ThemeStateKeyMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(xml)) {
            new XMLMapperBuilder(in, cfg, xml, cfg.getSqlFragments()).parse();
        }
        SqlSession session = new SqlSessionFactoryBuilder().build(cfg).openSession(true);
        ItSchema.applyMigrations(session.getConnection(), "sql/migration_theme_state_key_20260916.sql",
                "sql/migration_20261022_theme_state_key_attribute_def.sql",
                "sql/migration_20261026_theme_attribute_registry_version.sql");
        try (var statement = session.getConnection().createStatement()) {
            statement.execute("create table if not exists cms_topic(id bigint primary key, config_version bigint not null, journey_rules_json longtext)");
            statement.execute("insert ignore into cms_topic(id,config_version) values(7,3)");
        }
        return session;
    }
    private ThemeStateKey key() {
        ThemeStateKey row = new ThemeStateKey(); row.setStateKey("counter.hp"); row.setKeyType("counter");
        row.setScope("run"); row.setLabel("体力"); row.setInitialValue(3); row.setMinValue(0); row.setMaxValue(20);
        row.setVisible(true); row.setDisplayType("bar"); row.setPersistScope("topic"); return row;
    }
    private String rules(String version) throws Exception {
        ObjectNode root = (ObjectNode) new ObjectMapper().readTree("{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":[{\"key\":\"counter.hp\",\"label\":\"体力\",\"initial\":3,\"min\":0,\"max\":20,\"visible\":true}]}");
        ObjectNode binding = root.putObject("attributeBinding"); binding.put("schemaVersion",1);
        binding.put("registryVersion",version); binding.putArray("enabledKeys").add("counter.hp"); return root.toString();
    }
    @Test void frozenSaveLocksOutRegistryWriterAndRollbackLeavesOriginalContent() throws Exception {
        try (SqlSession saving = open(); SqlSession writing = open()) {
            ThemeStateKeyService registry = new ThemeStateKeyService(saving.getMapper(ThemeStateKeyMapper.class));
            registry.register(7L, Collections.singletonList(key()));
            String version = registry.readBinding(7L,Collections.emptyList()).path("registryVersion").asText();
            saving.getConnection().setAutoCommit(false);
            String frozen = AuthorAttributeBinding.freeze(registry,7L,3L,3L,4L,rules(version),true);
            try (PreparedStatement update = saving.getConnection().prepareStatement(
                    "update cms_topic set config_version=4,journey_rules_json=? where id=7 and config_version=3")) {
                update.setString(1,frozen); assertEquals(1,update.executeUpdate());
            }
            ExecutorService pool = Executors.newSingleThreadExecutor(); CountDownLatch started = new CountDownLatch(1);
            try {
                Future<?> writer = pool.submit(() -> {
                    started.countDown(); new ThemeStateKeyService(writing.getMapper(ThemeStateKeyMapper.class))
                            .register(7L,Collections.singletonList(key()));
                });
                assertTrue(started.await(5,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->writer.get(200,TimeUnit.MILLISECONDS));
                saving.rollback(true); writer.get(5,TimeUnit.SECONDS);
                try (var select = saving.getConnection().createStatement(); var rows = select.executeQuery(
                        "select config_version,journey_rules_json from cms_topic where id=7")) {
                    assertTrue(rows.next()); assertEquals(3L,rows.getLong(1)); assertNull(rows.getString(2));
                }
                ServiceException stale = assertThrows(ServiceException.class,
                        ()->AuthorAttributeBinding.freeze(registry,7L,3L,3L,4L,rules(version),true));
                assertEquals("ATTRIBUTE_REGISTRY_VERSION_CONFLICT",stale.getDetailMessage());
                saving.rollback(true);
            } finally { saving.rollback(true); pool.shutdownNow(); }
        }
    }
}

package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TemplateClearFieldsMySqlIT {
    private static PooledDataSource source;
    private static JdbcTemplate db;
    private static CmsMemberTemplateMapper mapper;
    private static final Map<String, String> COLUMNS = new LinkedHashMap<>();
    static {
        COLUMNS.put("rewardRulesJson", "reward_rules_json"); COLUMNS.put("merchantGuide", "merchant_guide");
        COLUMNS.put("sensorType", "sensor_type"); COLUMNS.put("sensorConfig", "sensor_config"); COLUMNS.put("storyImg", "story_img");
    }

    @BeforeAll static void start() throws Exception {
        assertEquals("127.0.0.1", MybatisItDatabase.host());
        source = new PooledDataSource("com.mysql.cj.jdbc.Driver", MybatisItDatabase.url("template_clear_fields"), MybatisItDatabase.user(), MybatisItDatabase.password());
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE cms_member_template (id BIGINT PRIMARY KEY, title VARCHAR(255), validation_method INT, reward_rules_json TEXT, merchant_guide TEXT, sensor_type VARCHAR(32), sensor_config TEXT, story_img TEXT)");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain,com.chengyinhub.business.domain.vo");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsMemberTemplateMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(CmsMemberTemplateMapper.class);
    }
    @AfterAll static void close() { if (source != null) source.forceCloseAll(); }
    private void seed() {
        db.update("DELETE FROM cms_member_template");
        db.update("INSERT INTO cms_member_template VALUES (1,'original',7,'[]','guide','still','{}','image')");
    }
    private Map<String, Object> read() { return db.queryForMap("SELECT * FROM cms_member_template WHERE id=1"); }
    private CmsMemberTemplate patch() { CmsMemberTemplate patch = new CmsMemberTemplate(); patch.setId(1L); patch.setTitle("updated"); return patch; }

    @Test void eachClearFlagClearsOnlyItsWhitelistedColumn() {
        for (Map.Entry<String, String> field : COLUMNS.entrySet()) {
            seed(); Map<String, Object> original = read(); CmsMemberTemplate patch = patch();
            patch.setClearFields(Collections.singleton(field.getKey()));
            assertEquals(1, mapper.updateCmsMemberTemplate(patch));
            Map<String, Object> saved = read(); assertNull(saved.get(field.getValue()));
            for (String column : COLUMNS.values()) if (!column.equals(field.getValue())) assertEquals(original.get(column), saved.get(column));
        }
    }
    @Test void absentClearFlagsKeepStoredValuesAndAllFlagsClearTogether() {
        seed(); Map<String, Object> original = read(); CmsMemberTemplate patch = patch();
        assertEquals(1, mapper.updateCmsMemberTemplate(patch));
        for (String column : COLUMNS.values()) assertEquals(original.get(column), read().get(column));
        patch.setClearFields(new HashSet<>(COLUMNS.keySet())); assertEquals(1, mapper.updateCmsMemberTemplate(patch));
        for (String column : COLUMNS.values()) assertNull(read().get(column));
    }
    @Test void entityAndParamsInjectionCannotSetInternalClearFlags() throws Exception {
        seed(); Map<String, Object> original = read();
        CmsMemberTemplate patch = new ObjectMapper().readValue("{\"id\":1,\"title\":\"updated\",\"clearFields\":[\"sensorConfig\"],\"params\":{\"clearFields\":[\"sensorConfig\"]}}", CmsMemberTemplate.class);
        assertNull(patch.getClearFields()); assertEquals(1, mapper.updateCmsMemberTemplate(patch));
        for (String column : COLUMNS.values()) assertEquals(original.get(column), read().get(column));
    }
}

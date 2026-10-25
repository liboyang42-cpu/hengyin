package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.it.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.InputStream;
import java.sql.Connection;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离 MySQL：作者保存、节点重映射、发布冻结和队伍账本持久化。 */
class GameplayReskinMySqlIT {
    private static PooledDataSource source;
    private static boolean scratchCreated;
    private static SqlSessionTemplate sql;
    private static JdbcTemplate db;
    @BeforeAll static void database() throws Exception {
        assertEquals("127.0.0.1",MybatisItDatabase.host());
        assertTrue(MybatisItDatabase.databaseName("gameplay_reskin").matches("chengyin_mybatis_gameplay_reskin[a-zA-Z0-9_]*"));
        String scratchUrl = MybatisItDatabase.url("gameplay_reskin");
        scratchCreated = true;
        source = new PooledDataSource("com.mysql.cj.jdbc.Driver", scratchUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
        try(Connection connection=source.getConnection()) {
            ItSchema.apply(connection,"it-schema/generic-route-runtime-it-schema.sql");
            ItSchema.apply(connection,"it-schema/cms-topic-it-schema.sql");
            ItSchema.apply(connection,"it-schema/journey-full-flow-it-schema.sql");
            ItSchema.apply(connection,"it-schema/gameplay-reskin-commercial-it-schema.sql");
            // 完整保留真实 community_post DDL 及其外键前置，再执行审核列迁移。
            // 不运行平台迁移中与本 IT 无关的历史回填，也不手写 review_flag。
            String platform = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "sql/migration_community_post_platform_20260909.sql"));
            for (String table : Arrays.asList("community_guideline_version", "community_post")) {
                String start = "CREATE TABLE IF NOT EXISTS " + table + " (";
                int offset = platform.indexOf(start);
                assertTrue(offset >= 0, "missing canonical CREATE TABLE: " + table);
                assertEquals(offset, platform.lastIndexOf(start), "ambiguous canonical CREATE TABLE: " + table);
                int end = platform.indexOf(";\n", offset);
                assertTrue(end > offset, "missing canonical DDL terminator: " + table);
                try (java.sql.Statement ddl = connection.createStatement()) {
                    ddl.execute(platform.substring(offset, end));
                }
            }
            ItSchema.applyMigrations(connection,"sql/migration_theme_state_key_20260916.sql", "sql/migration_20261022_theme_state_key_attribute_def.sql","sql/migration_20261003_merchant_offer_withdrawal_columns.sql",
                    "sql/migration_club_direct_merchant_receivable_20261004.sql",
                    "sql/migration_20261012_app_media_asset.sql",
                    "sql/migration_20261012_app_media_asset_namespaces.sql",
                    "sql/migration_audit_level_20261020.sql");
        }
        Configuration configuration=new Configuration();configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for(String name:Arrays.asList("CmsTopicMapper","CmsTopicChapterMapper","CmsTopicNodeMapper","CmsMemberTemplateMapper","OmsTicketMapper","MerchantChapterOfferMapper","AppMediaAssetMapper","ThemeStateKeyMapper","GameSessionRuntimeMapper","PlayTeamMapper")) {
            String resource="mapper/business/"+name+".xml";
            try(InputStream input=GameplayReskinMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {new XMLMapperBuilder(input,configuration,resource,configuration.getSqlFragments()).parse();}
        }
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(configuration);sql=new SqlSessionTemplate(factory.getObject());db=new JdbcTemplate(source);
    }
    @AfterAll static void close() throws Exception {
        try { if (source != null) source.forceCloseAll(); }
        finally { dropScratch(); }
    }
    @Test void threeThemesPublishWithTrustedBindingsAndPersistRenamedResources() throws Exception {
        GameplayReskinFixture fixture=new GameplayReskinFixture();fixture.useSql(sql);
        var references = new com.chengyinhub.business.service.support.TopicStateKeyRefService(
                sql.getMapper(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class),
                sql.getMapper(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class),
                sql.getMapper(com.chengyinhub.business.mapper.CmsTopicChapterMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(references, "cmsTopicMapper",
                sql.getMapper(com.chengyinhub.business.mapper.CmsTopicMapper.class));
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(
                fixture.publisher, "topicStateKeyRefService", references);
        com.chengyinhub.business.mapper.AppMediaAssetMapper mediaMapper =
                sql.getMapper(com.chengyinhub.business.mapper.AppMediaAssetMapper.class);
        com.chengyinhub.business.service.impl.AppMediaAssetService mediaAssets =
                new com.chengyinhub.business.service.impl.AppMediaAssetService(mediaMapper,
                        org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));
        // Metadata fixture for an already-reviewed image, not an official moderation or OSS proof.
        java.util.Map<String, Object> approved = mediaAssets.stageApprovedImage(7L,
                "upload/0123456789abcdef0123456789abcdef.png");
        assertEquals(1L, ((Number) approved.get("assetId")).longValue());
        java.util.Map<String, Object> stored = mediaMapper.select(1L);
        assertEquals(7L, ((Number) stored.get("memberId")).longValue());
        assertEquals("APP", stored.get("ownerType"));
        assertEquals("OSS", stored.get("storageType"));
        assertEquals("IMAGE", stored.get("mediaType"));
        assertEquals("APPROVED", stored.get("reviewState"));
        assertEquals(0, ((Number) stored.get("version")).intValue());
        for(com.chengyinhub.business.domain.CmsMemberTemplate template:fixture.templates.values())
            db.update("INSERT INTO cms_member_template(id,member_id,title,status,draft_status,validation_method,advanced_config_json,question_answer) VALUES(?,?,?,1,1,?,?,?)",template.getId(),7,template.getTitle(),template.getValidationMethod(),template.getAdvancedConfigJson(),template.getQuestionAnswer());
        for(JsonNode skin:GameplayReskinFixture.variants()) {
            fixture.publish(skin);fixture.scriptedPath(1,false);
            assertEquals("end03",fixture.world().path("result").path("endingKey").asText());
            fixture.startRun();fixture.unlock("a1");
            fixture.resolve(7,1001,"aid",null);
            assertEquals(skin.path("initialHp").asInt()-1,fixture.hp(7));
            assertEquals(1,fixture.stock(7,"parts"));assertEquals(1,fixture.stock(7,"medical"));
            assertEquals(1,fixture.state(7).path("variables").path("sys.actions").asInt());
            assertEquals(skin.path("hpName").asText(),fixture.snapshot.path("themeConfig").path("hpName").asText());
        }
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM cms_topic WHERE published_config_version>0",Integer.class));
        com.chengyinhub.business.service.support.AppMediaPublicationGuard publication =
                (com.chengyinhub.business.service.support.AppMediaPublicationGuard)
                        org.springframework.test.util.ReflectionTestUtils.getField(fixture.publisher, "mediaPublicationGuard");
        String reviewedItem = "{\"items\":[{\"image\":\"media-asset:1\"}]}";
        try {
            db.update("UPDATE app_media_asset SET review_state='HOLD' WHERE id=1");
            assertEquals(Integer.valueOf(422), assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                    () -> publication.requireJson(7L, reviewedItem, true)).getCode());
            db.update("UPDATE app_media_asset SET member_id=8 WHERE id=1");
            assertEquals(Integer.valueOf(403), assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                    () -> publication.requireJson(7L, reviewedItem, true)).getCode());
            assertEquals(Integer.valueOf(422), assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                    () -> publication.requireJson(7L, reviewedItem.replace("media-asset:1", "media-asset:2"), true)).getCode());
        } finally {
            db.update("UPDATE app_media_asset SET member_id=7,review_state='APPROVED' WHERE id=1");
        }
        assertEquals(reviewedItem, publication.requireJson(7L, reviewedItem, true));
        assertEquals("APPROVED", mediaMapper.select(1L).get("reviewState"));
    }

    private static void dropScratch() throws Exception {
        if (!scratchCreated) return;
        String database = MybatisItDatabase.databaseName("gameplay_reskin");
        if (!"127.0.0.1".equals(MybatisItDatabase.host()) || !database.matches("chengyin_mybatis_[A-Za-z0-9_]+")) {
            throw new IllegalStateException("Refusing cleanup outside owned loopback scratch");
        }
        try (java.sql.Connection admin = java.sql.DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:" + MybatisItDatabase.port() + "/?useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password());
             java.sql.Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE `" + database + "`");
            try (java.sql.PreparedStatement check = admin.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
                check.setString(1, database);
                try (java.sql.ResultSet row = check.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals(0, row.getInt(1), "owned scratch must be absent");
                    assertFalse(row.next());
                }
            }
            scratchCreated = false;
        }
    }
}

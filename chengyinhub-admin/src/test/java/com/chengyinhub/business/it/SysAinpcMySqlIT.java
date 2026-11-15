package com.chengyinhub.business.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.dto.ai.NpcDialogueReq;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.ai.AiGatewayResult;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.impl.NpcDialogueServiceImpl;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.chengyinhub.business.service.support.ThemeNpcAdminService;
import com.chengyinhub.common.exception.ServiceException;

/**
 * Real MySQL IT for SYS-AINPC.
 */
class SysAinpcMySqlIT {
    private static JdbcTemplate jdbc;
    private static NpcProfileMapper npcProfileMapper;
    private static CmsTopicMapper cmsTopicMapper;
    private static ThemeStateKeyMapper themeStateKeyMapper;
    private static TopicRouteSessionMapper routeMapper;
    private static TopicRouteRuntimeServiceImpl routeRuntime;
    private static ThemeNpcAdminService adminService;

    @BeforeAll
    static void setupSchema() throws Exception {
        assumeTrue("1".equals(System.getenv("CHENGYIN_SYS_AINPC_MYSQL_IT")),
                "set CHENGYIN_SYS_AINPC_MYSQL_IT=1 to run against MySQL");
        String url = MybatisItDatabase.url("sys_ainpc") + "&allowMultiQueries=true";
        DriverManagerDataSource ds = new DriverManagerDataSource(url, MybatisItDatabase.user(), MybatisItDatabase.password());
        jdbc = new JdbcTemplate(ds);

        String baseDdl = ""
            + "CREATE TABLE cms_topic ("
            + "  id bigint NOT NULL AUTO_INCREMENT,"
            + "  member_id bigint DEFAULT NULL,"
            + "  topic_type int DEFAULT NULL,"
            + "  product_type tinyint DEFAULT NULL,"
            + "  roam_referral_enabled tinyint(1) NOT NULL DEFAULT 0,"
            + "  circle_theme_code varchar(24) DEFAULT NULL,"
            + "  source_template_topic_id bigint DEFAULT NULL,"
            + "  circle_reviewed_at datetime DEFAULT NULL,"
            + "  complete_rule_json json DEFAULT NULL,"
            + "  route_mode varchar(20) NOT NULL DEFAULT 'LINEAR',"
            + "  route_graph_json json DEFAULT NULL,"
            + "  config_version bigint NOT NULL DEFAULT 1,"
            + "  published_route_graph_json json DEFAULT NULL,"
            + "  published_config_version bigint NOT NULL DEFAULT 0,"
            + "  topic_id bigint DEFAULT NULL,"
            + "  source_topic_id bigint DEFAULT NULL,"
            + "  name varchar(255) DEFAULT NULL,"
            + "  subtitle varchar(100) DEFAULT '',"
            + "  description varchar(8000) DEFAULT NULL,"
            + "  address_name varchar(200) DEFAULT '',"
            + "  total_time int DEFAULT 0,"
            + "  total_mileage decimal(10,2) DEFAULT NULL,"
            + "  location_count int DEFAULT 0,"
            + "  template_count int DEFAULT 0,"
            + "  start_date datetime DEFAULT NULL,"
            + "  end_date datetime DEFAULT NULL,"
            + "  img_url varchar(255) DEFAULT NULL,"
            + "  img_arr varchar(2000) DEFAULT NULL,"
            + "  category_ids varchar(255) DEFAULT '0',"
            + "  status tinyint(1) DEFAULT 0,"
            + "  reason varchar(500) DEFAULT NULL,"
            + "  user_status tinyint(1) DEFAULT 0,"
            + "  merchant_status tinyint(1) DEFAULT NULL,"
            + "  open_club_pool tinyint(1) NOT NULL DEFAULT 0,"
            + "  merchant_sign_up_start_date datetime DEFAULT NULL,"
            + "  merchant_sign_up_end_date datetime DEFAULT NULL,"
            + "  recruit_deadline datetime DEFAULT NULL,"
            + "  sale_end_at datetime DEFAULT NULL,"
            + "  selling_started_at datetime DEFAULT NULL,"
            + "  recruit_extend_count tinyint(1) NOT NULL DEFAULT 0,"
            + "  lifecycle tinyint(1) NOT NULL DEFAULT 0,"
            + "  del_flag tinyint(1) DEFAULT 0,"
            + "  is_sync tinyint(1) DEFAULT 0,"
            + "  is_recommend tinyint DEFAULT 0,"
            + "  check_status tinyint DEFAULT 0,"
            + "  is_settlement tinyint DEFAULT 0,"
            + "  create_by varchar(64) DEFAULT NULL,"
            + "  create_time datetime DEFAULT NULL,"
            + "  update_by varchar(64) DEFAULT NULL,"
            + "  update_time datetime DEFAULT NULL,"
            + "  publish_mode varchar(16) DEFAULT 'pro',"
            + "  club_id bigint DEFAULT NULL,"
            + "  self_play tinyint DEFAULT 0,"
            + "  self_play_price decimal(10,2) DEFAULT NULL,"
            + "  self_play_quota int DEFAULT NULL,"
            + "  xp_budget int DEFAULT 3000,"
            + "  finish_medal_name varchar(40) DEFAULT NULL,"
            + "  finish_medal_img varchar(255) DEFAULT NULL,"
            + "  complete_reward_coupon_id bigint DEFAULT NULL,"
            + "  audio_url varchar(500) DEFAULT NULL,"
            + "  audio_duration int DEFAULT NULL,"
            + "  is_draft tinyint(1) NOT NULL DEFAULT 0,"
            + "  is_template tinyint(1) NOT NULL DEFAULT 0,"
            + "  beta_flag tinyint NOT NULL DEFAULT 0,"
            + "  fee_platform_rate decimal(10,4) DEFAULT NULL,"
            + "  fee_guarantee_base_rate decimal(10,4) DEFAULT NULL,"
            + "  fee_guarantee_step_rate decimal(10,4) DEFAULT NULL,"
            + "  fee_guarantee_max_merchant tinyint DEFAULT NULL,"
            + "  team_mode tinyint NOT NULL DEFAULT 0,"
            + "  team_max_members int NOT NULL DEFAULT 4,"
            + "  game_module_config_json json DEFAULT NULL,"
            + "  cancellation_epoch bigint NOT NULL DEFAULT 0,"
            + "  club_coop_open tinyint(1) NOT NULL DEFAULT 1,"
            + "  club_pinned tinyint(1) NOT NULL DEFAULT 0,"
            + "  club_member_only tinyint(1) NOT NULL DEFAULT 0,"
            + "  content_safety_hold tinyint NOT NULL DEFAULT 0,"
            + "  city_code varchar(32) DEFAULT NULL,"
            + "  review_flag tinyint NOT NULL DEFAULT 0,"
            + "  theme_runtime_config_json mediumtext,"
            + "  published_theme_runtime_config_json mediumtext,"
            + "  look_json varchar(1024) DEFAULT NULL,"
            + "  journey_rules_json json DEFAULT NULL,"
            + "  journey_story_json json DEFAULT NULL,"
            + "  content_contract_json json DEFAULT NULL,"
            + "  PRIMARY KEY (id)"
            + ");"
            + "CREATE TABLE mms_merchant ("
            + "  id bigint NOT NULL AUTO_INCREMENT,"
            + "  name varchar(128) DEFAULT NULL,"
            + "  PRIMARY KEY (id)"
            + ");"
            + "CREATE TABLE npc_profile ("
            + "  id bigint NOT NULL AUTO_INCREMENT,"
            + "  scope_type tinyint NOT NULL,"
            + "  scope_id bigint NOT NULL DEFAULT 0,"
            + "  name varchar(32) NOT NULL,"
            + "  avatar varchar(255) DEFAULT NULL,"
            + "  persona text NOT NULL,"
            + "  greeting varchar(255) DEFAULT NULL,"
            + "  sort int DEFAULT 0,"
            + "  audit_status tinyint DEFAULT 0,"
            + "  enabled tinyint DEFAULT 1,"
            + "  del_flag char(1) DEFAULT '0',"
            + "  create_time datetime DEFAULT NULL,"
            + "  update_time datetime DEFAULT NULL,"
            + "  active_slot tinyint DEFAULT NULL,"
            + "  voice_version bigint NOT NULL DEFAULT 0,"
            + "  voice_id varchar(96) DEFAULT NULL,"
            + "  voice_status tinyint NOT NULL DEFAULT 0,"
            + "  voice_sample varchar(255) DEFAULT NULL,"
            + "  knowledge text,"
            + "  audit_reason varchar(255) DEFAULT NULL,"
            + "  PRIMARY KEY (id),"
            + "  UNIQUE KEY uk_active_scope (scope_type,scope_id,active_slot),"
            + "  KEY idx_scope (scope_type,scope_id,sort)"
            + ");"
            + "CREATE TABLE merchant_npc_avatar_job ("
            + "  id bigint NOT NULL AUTO_INCREMENT,"
            + "  merchant_id bigint NOT NULL,"
            + "  member_id bigint NOT NULL,"
            + "  image_url varchar(1024) NOT NULL,"
            + "  style varchar(32) NOT NULL,"
            + "  provider varchar(64) NOT NULL,"
            + "  status varchar(16) NOT NULL,"
            + "  model_url varchar(1024) DEFAULT NULL,"
            + "  thumb_url varchar(1024) DEFAULT NULL,"
            + "  fail_reason varchar(64) DEFAULT NULL,"
            + "  create_time datetime(3) NOT NULL,"
            + "  update_time datetime(3) NOT NULL,"
            + "  PRIMARY KEY (id)"
            + ");"
            + "CREATE TABLE npc_voice_enrollment_intent ("
            + "  id bigint NOT NULL AUTO_INCREMENT,"
            + "  npc_id bigint NOT NULL,"
            + "  scope_type int NOT NULL,"
            + "  scope_id bigint NOT NULL,"
            + "  actor_member_id bigint NOT NULL,"
            + "  sample_sha256 char(64) NOT NULL,"
            + "  sample_reference varchar(64) NOT NULL,"
            + "  state varchar(16) NOT NULL,"
            + "  voice_id varchar(96) DEFAULT NULL,"
            + "  reason_code varchar(64) DEFAULT NULL,"
            + "  profile_version bigint NOT NULL,"
            + "  created_at datetime(6) NOT NULL,"
            + "  updated_at datetime(6) NOT NULL,"
            + "  request_id varchar(96) NOT NULL,"
            + "  previous_intent_id bigint DEFAULT NULL,"
            + "  PRIMARY KEY (id)"
            + ");"
            + "CREATE TABLE theme_state_key ("
            + "  id bigint NOT NULL AUTO_INCREMENT,"
            + "  topic_id bigint NOT NULL,"
            + "  state_key varchar(64) NOT NULL,"
            + "  key_type varchar(16) NOT NULL,"
            + "  scope varchar(16) NOT NULL,"
            + "  label varchar(128) DEFAULT NULL,"
            + "  initial_value int NOT NULL DEFAULT 0,"
            + "  create_time datetime(3) NOT NULL,"
            + "  update_time datetime(3) NOT NULL,"
            + "  min_value int DEFAULT NULL,"
            + "  max_value int DEFAULT NULL,"
            + "  display_type varchar(16) DEFAULT NULL,"
            + "  color varchar(16) DEFAULT NULL,"
            + "  icon varchar(32) DEFAULT NULL,"
            + "  sort_order int DEFAULT NULL,"
            + "  visible tinyint(1) DEFAULT NULL,"
            + "  persist_scope varchar(16) DEFAULT NULL,"
            + "  rules_json varchar(1024) DEFAULT NULL,"
            + "  is_attribute tinyint(1) NOT NULL DEFAULT 0,"
            + "  attribute_preset varchar(16) DEFAULT NULL,"
            + "  PRIMARY KEY (id),"
            + "  UNIQUE KEY uk_theme_state_key (topic_id,state_key)"
            + ");"
            + "CREATE TABLE topic_route_session ("
            + "  id bigint NOT NULL AUTO_INCREMENT,"
            + "  activity_id bigint NOT NULL DEFAULT 0,"
            + "  topic_id bigint NOT NULL,"
            + "  member_id bigint NOT NULL,"
            + "  play_instance_id varchar(64) NOT NULL DEFAULT 'default',"
            + "  config_version bigint NOT NULL,"
            + "  config_snapshot_json json NOT NULL,"
            + "  state_json json NOT NULL,"
            + "  status tinyint NOT NULL DEFAULT 0,"
            + "  phase varchar(16) DEFAULT NULL,"
            + "  current_node_id bigint DEFAULT NULL,"
            + "  recommended_node_id bigint DEFAULT NULL,"
            + "  seed bigint NOT NULL,"
            + "  version int NOT NULL DEFAULT 0,"
            + "  completed_at datetime(3) DEFAULT NULL,"
            + "  create_time datetime(3) NOT NULL,"
            + "  update_time datetime(3) NOT NULL,"
            + "  PRIMARY KEY (id),"
            + "  UNIQUE KEY uk_topic_route_owner (activity_id,topic_id,member_id,play_instance_id)"
            + ");"
            + "CREATE TABLE topic_route_action ("
            + "  id bigint NOT NULL AUTO_INCREMENT,"
            + "  session_id bigint NOT NULL,"
            + "  member_id bigint NOT NULL,"
            + "  node_id bigint NOT NULL,"
            + "  action_id varchar(96) NOT NULL,"
            + "  receipt_id varchar(96) NOT NULL,"
            + "  outcome_code varchar(64) NOT NULL,"
            + "  response_json json NOT NULL,"
            + "  create_time datetime(3) NOT NULL,"
            + "  PRIMARY KEY (id),"
            + "  UNIQUE KEY uk_topic_route_action (session_id,member_id,action_id),"
            + "  UNIQUE KEY uk_topic_route_receipt (session_id,receipt_id)"
            + ");"
            + "CREATE TABLE sys_menu ("
            + "  menu_id bigint NOT NULL AUTO_INCREMENT,"
            + "  menu_name varchar(50) NOT NULL,"
            + "  parent_id bigint DEFAULT 0,"
            + "  order_num int DEFAULT 0,"
            + "  path varchar(200) DEFAULT '',"
            + "  component varchar(255) DEFAULT NULL,"
            + "  is_frame int DEFAULT 1,"
            + "  is_cache int DEFAULT 0,"
            + "  menu_type char(1) DEFAULT '',"
            + "  visible char(1) DEFAULT '0',"
            + "  status char(1) DEFAULT '0',"
            + "  perms varchar(100) DEFAULT NULL,"
            + "  icon varchar(100) DEFAULT '#',"
            + "  create_by varchar(64) DEFAULT '',"
            + "  create_time datetime DEFAULT NULL,"
            + "  remark varchar(500) DEFAULT '',"
            + "  PRIMARY KEY (menu_id)"
            + ");"
            + "CREATE TABLE sys_role_menu (role_id bigint NOT NULL, menu_id bigint NOT NULL, PRIMARY KEY (role_id, menu_id));"
            + "INSERT INTO sys_menu (menu_id, menu_name, menu_type) VALUES (2209, '供给与发布', 'M');";

        Path migration = Path.of("sql/migration_20261115_sys_ainpc.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("chengyinhub-admin/sql/migration_20261115_sys_ainpc.sql");
        }
        String migrationSql = Files.readString(migration);

        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(baseDdl);
            s.execute(migrationSql);
            s.execute(migrationSql);
        }

        Configuration cfg = new Configuration(new Environment("it", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(ds);
        factoryBean.setConfiguration(cfg);
        factoryBean.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factoryBean.setMapperLocations(
                new ClassPathResource("mapper/business/NpcProfileMapper.xml"),
                new ClassPathResource("mapper/business/CmsTopicMapper.xml"),
                new ClassPathResource("mapper/business/ThemeStateKeyMapper.xml"),
                new ClassPathResource("mapper/business/TopicRouteSessionMapper.xml")
        );
        SqlSessionFactory factory = factoryBean.getObject();
        SqlSessionTemplate session = new SqlSessionTemplate(factory);
        npcProfileMapper = session.getMapper(NpcProfileMapper.class);
        cmsTopicMapper = session.getMapper(CmsTopicMapper.class);
        themeStateKeyMapper = session.getMapper(ThemeStateKeyMapper.class);
        routeMapper = session.getMapper(TopicRouteSessionMapper.class);

        routeRuntime = new TopicRouteRuntimeServiceImpl(
                routeMapper, cmsTopicMapper, null, null, null, null, null, null,
                null, new ObjectProvider<RouteReachabilityProvider>() {
                    @Override public RouteReachabilityProvider getObject(Object... args) { return null; }
                    @Override public RouteReachabilityProvider getIfAvailable() { return null; }
                    @Override public RouteReachabilityProvider getIfUnique() { return null; }
                    @Override public RouteReachabilityProvider getObject() { return null; }
                    @Override public java.util.stream.Stream<RouteReachabilityProvider> stream() { return java.util.stream.Stream.empty(); }
                },
                themeStateKeyMapper);
        adminService = new ThemeNpcAdminService(npcProfileMapper, cmsTopicMapper, themeStateKeyMapper, jdbc);
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.execute("DELETE FROM topic_route_action");
        jdbc.execute("DELETE FROM topic_route_session");
        jdbc.execute("DELETE FROM theme_state_key");
        jdbc.execute("DELETE FROM npc_voice_enrollment_intent");
        jdbc.execute("DELETE FROM merchant_npc_avatar_job");
        jdbc.execute("DELETE FROM npc_profile");
        jdbc.execute("DELETE FROM mms_merchant");
        jdbc.execute("DELETE FROM cms_topic");

        jdbc.update("INSERT INTO cms_topic (id, name, status, user_status, del_flag) VALUES (101, '雨巷疑云', 1, 1, 0)");
        jdbc.update("INSERT INTO mms_merchant (id, name) VALUES (201, '老茶馆')");
        jdbc.update("INSERT INTO theme_state_key (id, topic_id, state_key, key_type, scope, label, initial_value, min_value, max_value, create_time, update_time) "
                + "VALUES (1, 101, 'relation.shen', 'relation', 'run', '沈老板', 1, -10, 10, NOW(3), NOW(3))");
        jdbc.update("INSERT INTO theme_state_key (id, topic_id, state_key, key_type, scope, label, initial_value, min_value, max_value, create_time, update_time) "
                + "VALUES (2, 101, 'tag.trusted', 'tag', 'run', '受信任', 0, 0, 1, NOW(3), NOW(3))");
        jdbc.update("INSERT INTO theme_state_key (id, topic_id, state_key, key_type, scope, label, initial_value, min_value, max_value, create_time, update_time) "
                + "VALUES (3, 101, 'clue.hidden_letter', 'flag', 'run', '秘信', 0, 0, 1, NOW(3), NOW(3))");
    }

    @Test
    void migrationCreatesSchemaAndMenuOnceAndAdminCrudIsolatesScope1() {
        Integer menuCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE component = 'business/themenpc/index' AND parent_id = 2209",
                Integer.class);
        assertEquals(1, menuCount);

        jdbc.update("INSERT INTO npc_profile (id, scope_type, scope_id, name, persona, enabled, del_flag, voice_version, voice_status) "
                + "VALUES (88, 2, 201, '门店小二', '热情', 1, '0', 0, 0)");

        NpcProfile created = new NpcProfile();
        created.setScopeId(101L);
        created.setName("沈老板");
        created.setPersona("茶馆掌柜，沉稳敏锐。");
        created.setGreeting("客官里边请。");
        created.setRelationKey("relation.shen");
        created.setInitialRelation(1);
        created.setSort(10);
        assertEquals(1, adminService.addThemeNpc(created));

        List<NpcProfile> list = adminService.listThemeNpcs(101L, null);
        assertEquals(1, list.size());
        assertEquals("沈老板", list.get(0).getName());
        assertEquals("雨巷疑云", list.get(0).getTopicTitle());
        assertEquals(1, list.get(0).getInitialRelation());

        assertEquals(1, adminService.deleteThemeNpc(list.get(0).getId()));
        assertTrue(adminService.listThemeNpcs(101L, null).isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM npc_profile WHERE id = 88 AND del_flag = '0'", Integer.class));
    }

    @Test
    void dialogueValidatesEffectsAgainstMySqlRegistryClampsAndReplaysIdempotently() throws Exception {
        jdbc.update("INSERT INTO npc_profile (id, scope_type, scope_id, name, persona, relation_key, audit_status, enabled, del_flag) "
                + "VALUES (12, 1, 101, '沈老板', '茶馆掌柜，知道雨夜暗门线索', 'relation.shen', 1, 1, '0')");
        jdbc.update("INSERT INTO topic_route_session (id, activity_id, topic_id, member_id, play_instance_id, config_version, config_snapshot_json, state_json, status, seed, version, create_time, update_time) "
                + "VALUES (7001, 0, 101, 5001, 'default', 1, '{}', '{\"variables\":{\"relation.shen\":1},\"tags\":[]}', 0, 42, 2, NOW(3), NOW(3))");

        AiGatewayService gateway = mock(AiGatewayService.class);
        when(gateway.generate(eq(5001L), eq("player"), eq("npc_dialogue"), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace-it-1",
                        "{\"reply\":\"茶凉了，我记住你的诚意了。\","
                      + "\"effects\":["
                      + "  {\"op\":\"INC\",\"key\":\"relation.shen\",\"delta\":99},"
                      + "  {\"op\":\"ADD_TAG\",\"key\":\"tag.trusted\"},"
                      + "  {\"op\":\"INC\",\"key\":\"reward.coupon\",\"delta\":1},"
                      + "  {\"op\":\"ADD_TAG\",\"key\":\"clue.hidden_letter\"},"
                      + "  {\"op\":\"INC\",\"key\":\"relation.unknown\",\"delta\":2}"
                      + "]}",
                        "deepseek-chat", 0));

        NpcDialogueServiceImpl dialogueService = new NpcDialogueServiceImpl(npcProfileMapper, routeRuntime, gateway);
        NpcDialogueReq req = new NpcDialogueReq();
        req.setRunId(7001L);
        req.setNpcId(12L);
        req.setUtterance("掌柜的，我昨晚见过那位撑油纸伞的客人。");
        req.setRouteActionId("dlg-mysql-001");

        Map<String, Object> first = dialogueService.dialogue(5001L, req);
        assertEquals(3, ((Number) first.get("stateVersion")).intValue());
        assertEquals("茶凉了，我记住你的诚意了。", first.get("reply"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> effects = (List<Map<String, Object>>) first.get("effects");
        assertEquals(2, effects.size());
        assertEquals("relation.shen", effects.get(0).get("key"));
        assertEquals(2, ((Number) effects.get(0).get("delta")).intValue());
        assertEquals(3, ((Number) effects.get(0).get("value")).intValue());
        assertEquals("tag.trusted", effects.get(1).get("key"));

        @SuppressWarnings("unchecked")
        Map<String, Object> relation = (Map<String, Object>) first.get("relation");
        assertEquals("ally", relation.get("state"));
        assertEquals(3, ((Number) relation.get("value")).intValue());

        Map<String, Object> replay = dialogueService.dialogue(5001L, req);
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(om.readTree(om.writeValueAsString(first)), om.readTree(om.writeValueAsString(replay)));
        verify(gateway, times(1)).generate(eq(5001L), eq("player"), eq("npc_dialogue"), anyString(), anyString());

        Integer dbVersion = jdbc.queryForObject("SELECT version FROM topic_route_session WHERE id = 7001", Integer.class);
        assertEquals(3, dbVersion);

        jdbc.update("UPDATE topic_route_session SET status = 1 WHERE id = 7001");
        NpcDialogueReq req2 = new NpcDialogueReq();
        req2.setRunId(7001L);
        req2.setNpcId(12L);
        req2.setUtterance("再问一句");
        req2.setRouteActionId("dlg-mysql-002");
        ServiceException conflict = assertThrows(ServiceException.class, () -> dialogueService.dialogue(5001L, req2));
        assertEquals(409, conflict.getCode());
    }

    @Test
    void avatarJobReviewAndVoiceConsentCasRevokeWorkOnRealMysql() {
        jdbc.update("INSERT INTO merchant_npc_avatar_job (id, merchant_id, member_id, image_url, style, provider, status, model_url, create_time, update_time) "
                + "VALUES (301, 201, 9001, 'https://img/a.png', 'clay', 'tripo', 'SUCCEEDED', 'https://cdn/a.glb', NOW(3), NOW(3))");
        jdbc.update("INSERT INTO npc_profile (id, scope_type, scope_id, name, persona, voice_version, voice_id, voice_status, voice_sample, del_flag) "
                + "VALUES (401, 2, 201, '茶室掌柜', '热情', 3, 'v_123', 2, 'https://audio/sample.mp3', '0')");

        List<Map<String, Object>> jobs = adminService.listAvatarJobs(null, "PENDING", null);
        assertEquals(1, jobs.size());
        assertEquals("老茶馆", jobs.get(0).get("merchant_name"));

        Map<String, Object> reviewed = adminService.reviewAvatarJob(301L, "APPROVED", "外观清晰");
        assertEquals("APPROVED", reviewed.get("review_status"));

        List<Map<String, Object>> audits = adminService.listVoiceAudits(2, 201L);
        assertEquals(1, audits.size());

        ServiceException stale = assertThrows(ServiceException.class, () -> adminService.revokeVoiceConsent(401L, 2L));
        assertEquals(409, stale.getCode());

        Map<String, Object> revoked = adminService.revokeVoiceConsent(401L, 3L);
        assertEquals(true, revoked.get("revoked"));
        assertEquals(4L, ((Number) revoked.get("voiceVersion")).longValue());
        assertNull(jdbc.queryForObject("SELECT voice_id FROM npc_profile WHERE id = 401", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT voice_status FROM npc_profile WHERE id = 401", Integer.class));
        assertFalse(adminService.providerKeyStatuses().isEmpty());
    }
}

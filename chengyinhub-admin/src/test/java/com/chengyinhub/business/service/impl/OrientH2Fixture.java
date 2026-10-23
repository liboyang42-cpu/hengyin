package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.OrientMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 城市定向 F1d 的 H2(MySQL 模式)夹具:真 OrientMapper.xml + SqlSessionTemplate(Spring 异常翻译,
 * 唯一键冲突是 DuplicateKeyException,与生产同一条分支)。DDL 与 migration_orient_geo_ar_team_20261022.sql 同列同键。
 */
final class OrientH2Fixture {

    final PooledDataSource dataSource;
    final OrientMapper mapper;

    OrientH2Fixture(String dbName) throws Exception {
        dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), dataSource));
        String resource = "mapper/business/OrientMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        mapper = new SqlSessionTemplate(factory).getMapper(OrientMapper.class);
        exec("drop all objects");
        exec("create table orient_node_geo (node_id bigint primary key, topic_id bigint not null,"
                + " source_coord_sys varchar(8) not null, source_lng varchar(255), source_lat varchar(255),"
                + " source_fingerprint char(64) not null, wgs84_lng decimal(10,7), wgs84_lat decimal(10,7),"
                + " geo_status tinyint not null default 0, dirty_reason varchar(32),"
                + " create_time datetime not null, update_time datetime not null)");
        exec("create table orient_task_alternative (id bigint auto_increment primary key, topic_id bigint not null,"
                + " task_id bigint not null, alternative_task_type varchar(8) not null, prompt_text varchar(500),"
                + " answer_hash char(64), enabled tinyint not null default 1, create_by bigint not null,"
                + " create_time datetime not null, update_time datetime not null,"
                + " constraint uk_orient_task_alt unique (task_id, alternative_task_type))");
        exec("create table orient_task_attempt (id bigint auto_increment primary key, member_id bigint not null,"
                + " activity_id bigint not null default 0, topic_id bigint not null,"
                + " play_instance_id varchar(64) not null default 'default', task_id bigint not null,"
                + " alternative_task_type varchar(8) not null, idempotency_key varchar(64) not null,"
                + " request_fingerprint char(64) not null, pic_url varchar(500), report_coord_sys varchar(8),"
                + " wgs84_lng decimal(10,7), wgs84_lat decimal(10,7), distance_m int,"
                + " node_geo_dirty tinyint not null default 0, status tinyint not null, review_by bigint,"
                + " review_time datetime, create_time datetime not null, update_time datetime not null,"
                + " constraint uk_orient_attempt_idem unique (member_id, idempotency_key))");
        exec("create table orient_task_completion (id bigint auto_increment primary key, member_id bigint not null,"
                + " activity_id bigint not null default 0, topic_id bigint not null,"
                + " play_instance_id varchar(64) not null default 'default', task_id bigint not null,"
                + " completed_via varchar(8) not null, source_attempt_id bigint, completed_time datetime not null,"
                + " constraint uk_orient_completion unique (member_id, activity_id, topic_id, play_instance_id, task_id))");
        exec("create table orient_safety_rule (topic_id bigint primary key, insurance_required tinyint not null default 0,"
                + " night_start_minute int not null default 1200, night_end_minute int not null default 360,"
                + " weather_block tinyint not null default 0, weather_reason varchar(200), weather_block_until datetime,"
                + " update_by bigint not null, create_time datetime not null, update_time datetime not null)");
        exec("create table player_node_progress (id bigint auto_increment primary key, member_id bigint,"
                + " activity_id bigint, topic_id bigint, node_id bigint, status int, checkin_time timestamp,"
                + " del_flag int default 0, play_instance_id varchar(64) default 'default')");
        exec("create table ums_member (id bigint primary key, nickname varchar(50), avatar varchar(255))");
        exec("create table play_team (id bigint primary key, owner_type tinyint, owner_id bigint,"
                + " leader_member_id bigint, invite_code varchar(32), title varchar(60), join_mode tinyint,"
                + " max_members int, joined_count int, status tinyint, expire_time datetime, create_time datetime,"
                + " update_time datetime)");
        exec("create table play_team_member (id bigint auto_increment primary key, team_id bigint, member_id bigint,"
                + " role tinyint, status tinyint, apply_message varchar(200), create_time datetime, update_time datetime)");
    }

    void exec(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    long count(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}

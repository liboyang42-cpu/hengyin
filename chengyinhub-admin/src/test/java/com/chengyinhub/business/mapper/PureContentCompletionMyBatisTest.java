package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PureContentCompletionMyBatisTest {
    @Test
    void actualSqlUsesRegistrationSchemaAndPaidRunScopedRequiredGameFacts() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(),
                new PooledDataSource("org.h2.Driver",
                        "jdbc:h2:mem:pure_content_completion;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        String source;
        try (InputStream in = Resources.getResourceAsStream("mapper/business/CmsRegistrationMapper.xml")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = in.read(buffer)) != -1) bytes.write(buffer, 0, length);
            source = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        int start = source.indexOf("<select id=\"selectPureContentExploreCompletion\"");
        String select = source.substring(start, source.indexOf("</select>", start) + 9);
        String mapper = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">"
                + "<mapper namespace=\"completion\">" + select + "</mapper>";
        new XMLMapperBuilder(new ByteArrayInputStream(mapper.getBytes(StandardCharsets.UTF_8)),
                configuration, "actual-completion-sql", configuration.getSqlFragments()).parse();
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            // cms_registration 实际没有 del_flag；这个结构必须能执行生产 mapper 的查询。
            statement.execute("create table cms_registration(id bigint, member_id bigint, owner_type int, owner_id bigint, payment_status int, registration_status int, purchase_kind int)");
            statement.execute("create table cms_activity(id bigint, topic_id bigint)");
            statement.execute("create table cms_topic_chapter(id bigint, topic_id bigint, required int)");
            statement.execute("create table cms_topic_node(id bigint, chapter_id bigint, topic_id bigint, status int, template_id bigint)");
            statement.execute("create table player_node_progress(member_id bigint, activity_id bigint, topic_id bigint, node_id bigint, status int, del_flag int)");
            statement.execute("insert into cms_activity values(20,10),(21,10)");
            statement.execute("insert into cms_topic_chapter values(30,10,1),(31,10,0)");
            statement.execute("insert into cms_topic_node values(40,30,10,1,50),(41,30,10,1,51),(42,31,10,1,52)");
            statement.execute("insert into cms_registration values(1,101,2,20,2,2,3),(2,102,2,20,2,2,3),(3,103,2,20,2,3,3),(4,104,2,20,1,2,3)");
            statement.execute("insert into player_node_progress values(101,20,10,40,1,0),(101,20,10,41,1,0),(102,20,10,40,1,0),(102,21,10,41,1,0),(102,20,10,41,1,1),(103,20,10,40,1,0),(103,20,10,41,1,0),(104,20,10,40,1,0),(104,20,10,41,1,0)");
            List<Map<String, Object>> rows = session.selectList("completion.selectPureContentExploreCompletion",
                    Collections.singletonMap("topicId", 10L));
            assertEquals(2, rows.size(), "取消和未付款的报名不能成为通关统计事实");
            Map<Long, Integer> completed = new java.util.HashMap<>();
            for (Map<String, Object> row : rows) {
                completed.put(((Number) row.get("MEMBERID")).longValue(),
                        ((Number) row.get("COMPLETED")).intValue());
            }
            assertEquals(Integer.valueOf(1), completed.get(101L), "可选章不能阻塞通关");
            assertEquals(Integer.valueOf(0), completed.get(102L), "别场进度和已删进度不能补齐必选玩法");
        }
    }
}

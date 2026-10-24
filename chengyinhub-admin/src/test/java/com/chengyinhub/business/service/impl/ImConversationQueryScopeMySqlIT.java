package com.chengyinhub.business.service.impl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 真实 IM SQL 的范围/statement 数；club/会员谓词改用真表，路线守卫仍用显式 mock。
 * 不声称全部跨域真实存储、SQL O(1) 工作量或性能。只能受控端显式点名运行。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class ImConversationQueryScopeMySqlIT
{
    private final ImConversationBatchReadMySqlIT fixture = new ImConversationBatchReadMySqlIT();
    private JdbcTemplate db;
    private ImServiceImpl service;
    private ImConversationMemberMapper members;
    private AtomicInteger statements;

    @BeforeAll
    void setup() throws Exception
    {
        fixture.setup(); // 同一显式开关/loopback/独占 UUID 库，无凭据默认值或 assume 跳过。
        db = (JdbcTemplate) ReflectionTestUtils.getField(fixture, "db");
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/im-club-conversation-batch.sql"))
                .execute(db.getDataSource());
        service = (ImServiceImpl) ReflectionTestUtils.getField(fixture, "service");
        members = (ImConversationMemberMapper) ReflectionTestUtils.getField(fixture, "members");
        Object counter = ReflectionTestUtils.getField(fixture, "statements");
        statements = (AtomicInteger) ReflectionTestUtils.getField(counter, "count");
    }

    @BeforeEach
    void seed()
    {
        fixture.seed();
        db.update("delete from club_member");db.update("delete from club");
    }

    @AfterAll
    void cleanup()
    {
        fixture.cleanup();
    }

    @Test
    void conversationIdSetsAreExactlyTheCurrentReadersActiveMemberships()
    {
        Map<Long, Set<Long>> expected = Map.of(
                1L, Set.of(101L, 102L, 103L, 104L, 105L),
                2L, Set.of(101L), 3L, Set.of(104L), 4L, Set.of(105L), 999L, Set.of());
        for (Map.Entry<Long, Set<Long>> entry : expected.entrySet())
        {
            statements.set(0);
            assertEquals(entry.getValue(), ids(service.listConversations(entry.getKey())));
            assertEquals(1, statements.get(), "真实 IM SQL statement 数与返回集合大小无关");
        }
        db.update("update im_conversation_member set deleted=1 where conversation_id=104 and member_id=1");
        db.update("update im_conversation set status=1 where id=105");
        statements.set(0);
        assertEquals(Set.of(101L, 102L, 103L), ids(service.listConversations(1L)));
        assertEquals(1, statements.get());
    }

    @Test
    void pendingImagesAreVisibleOnlyToTheirSenderAndNeverGrantConversationMembership()
    {
        image(31, 1, "14:00:00");
        image(32, 2, "15:00:00");
        image(33, 9, "16:00:00"); // 非成员即使是 sender，也不能得到该会话摘要。
        assertLatest(1L, 31L);
        assertLatest(2L, 32L);
        statements.set(0);
        assertTrue(members.selectMyConversationSummaries(9L).isEmpty());
        assertEquals(1, statements.get());
        db.update("update im_message set check_status=1,visible_at='2026-10-23 17:00:00' where id=31");
        assertLatest(1L, 31L);
        assertLatest(2L, 31L);
    }

    @Test
    void inboxPredicateExcludesMutedGroupsDeletedClosedOrphanAndForeignRowsWithoutWrites()
    {
        conversation(201, 4, "club_42", 50, null);
        conversation(202, 4, "club_43", 60, null);
        conversation(203, 1, "closed", 70, null);
        conversation(204, 1, "deleted", 80, null);
        conversation(205, 5, "other-non-group-type", 7, null);
        db.update("update im_conversation set status=1 where id=203");
        db.update("update im_conversation_member set deleted=1 where conversation_id=204 and member_id=1");
        db.update("insert into im_conversation_member(conversation_id,member_id,unread_count) values(999,1,90),(101,9,100)");
        List<Map<String, Object>> before = memberState();
        statements.set(0);
        assertEquals(17, service.unreadTotal(1L)); // 基础 10 + 非群 type=5 的 7；muted=1 的 4 不计。
        assertEquals(1, statements.get());
        assertEquals(before, memberState(), "红点读取不推进已读位、不改未读数或资格");
    }

    @Test
    void routeCardReaderSetsStillDependOnCurrentDomainGateAndKeepStoredContent()
    {
        db.update("insert into im_message(id,conversation_id,sender_id,msg_type,status,check_status,content,extra_json,request_hash,create_time,visible_at) "
                + "values(50,101,1,3,0,1,'[路线] historical secret','{\"cardType\":\"route\",\"topicId\":17}','fixture-digest','2026-10-23 18:00:00','2026-10-23 18:00:00')");
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopic publicTopic = new CmsTopic();
        publicTopic.setStatus(1); publicTopic.setUserStatus(1); publicTopic.setDelFlag(0);
        publicTopic.setClubMemberOnly(0); publicTopic.setName("current public name");
        when(topics.selectCmsTopicDetailById(17L, 1L)).thenReturn(publicTopic);
        when(topics.selectCmsTopicDetailById(17L, 2L)).thenReturn(null);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        Map<String, Object> before = db.queryForMap("select content,extra_json,request_hash from im_message where id=50");

        statements.set(0);
        assertEquals("[路线] current public name", conversationRow(service.listConversations(1L), 101L).get("lastMsgText"));
        assertEquals(1, statements.get(), "只计真实 IM SQL；topic mock 不冒充数据库查询");
        statements.set(0);
        assertEquals("路线已不可用", conversationRow(service.listConversations(2L), 101L).get("lastMsgText"));
        assertEquals(1, statements.get());
        verify(topics).selectCmsTopicDetailById(17L, 1L);
        verify(topics).selectCmsTopicDetailById(17L, 2L);
        verifyNoMoreInteractions(topics);
        assertEquals(before, db.queryForMap("select content,extra_json,request_hash from im_message where id=50"));
    }

    @Test
    void groupReaderSetsUseOriginalGuardAndTheirAdditionalLookupsAreExplicit()
    {
        conversation(201, 4, "club_42", 50, 2L);
        conversation(202, 4, "club_43", 60, 2L);
        for (long id : new long[] {42, 43})
        {
            db.update("insert into club(id,member_id,status,del_flag,name) values(?,99,1,0,?)",id,"club-"+id);
        }
        db.update("insert into club_member(club_id,member_id,status,del_flag) values(42,1,1,0)");

        statements.set(0);
        assertEquals(Set.of(101L, 102L, 103L, 104L, 105L, 201L), ids(service.listConversations(1L)));
        assertEquals(2, statements.get(), "真实IM摘要一次 + 当前club/会员批量一次；不再把mock当SQL证据");
        statements.set(0);
        assertEquals(Set.of(101L), ids(service.listConversations(2L)));
        assertEquals(2, statements.get());
        statements.set(0);
        assertEquals(10, service.unreadTotal(1L));
        assertEquals(1, statements.get());
    }

    @Test
    void directSystemAndMerchantStatementBudgetsHoldAtZeroOneSixtyFourAndTwoHundredFiftySeven()
    {
        for (int size : new int[] {0, 1, 64, 257})
        {
            db.update("delete from im_message");
            db.update("delete from im_conversation_member");
            db.update("delete from im_conversation");
            Set<Long> expected = new java.util.HashSet<>();
            for (int i = 0; i < size; i++)
            {
                long id = 1000L + i;
                int type = i % 3 + 1;
                conversation(id, type, "scale-" + id, 1, type == 1 ? 2L : null);
                expected.add(id);
            }
            statements.set(0);
            assertEquals(expected, ids(service.listConversations(1L)));
            assertEquals(1, statements.get());
            statements.set(0);
            assertEquals(size, service.unreadTotal(1L));
            assertEquals(1, statements.get());
        }
    }

    @Test
    void explainPlansAreRecordedWithActualSourceIdentityWithoutInventedCostThresholds() throws Exception
    {
        String source = System.getenv("VALIDATED_SOURCE_SHA");
        assertTrue(source != null && source.matches("[0-9a-f]{40}"), "需要本次受控 checkout 的 VALIDATED_SOURCE_SHA");
        Process checkout = new ProcessBuilder("git", "rev-parse", "HEAD").start();
        assertTrue(checkout.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, checkout.exitValue());
        assertEquals(source, new String(checkout.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(db.getDataSource());
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"));
        Configuration configuration = factory.getObject().getConfiguration();
        Map<String, Object> params = Map.of("memberId", 1L);
        Map<String, Object> plans = new HashMap<>();
        for (String method : Arrays.asList("selectMyConversationSummaries", "sumInboxUnread"))
        {
            BoundSql bound = configuration.getMappedStatement(
                    "com.chengyinhub.business.mapper.ImConversationMemberMapper." + method).getBoundSql(params);
            List<Object> values = new ArrayList<>();
            for (ParameterMapping mapping : bound.getParameterMappings()) values.add(params.get(mapping.getProperty()));
            String plan = db.queryForObject("EXPLAIN FORMAT=JSON " + bound.getSql(), String.class, values.toArray());
            plans.put(method, JSONObject.parseObject(plan));
        }
        Map<String, Object> receipt = new HashMap<>();
        receipt.put("source_sha", source);
        receipt.put("mysql_version", db.queryForObject("select version()", String.class));
        receipt.put("layer", "REAL_IM_SQL_PLAN_OBSERVATION_ONLY");
        receipt.put("plans", plans);
        Path output = Path.of("target/surefire-reports/ImConversationQueryScopeMySqlIT-explain.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.toJSONString(receipt) + "\n");
        assertTrue(Files.size(output) > 0);
    }

    private Set<Long> ids(List<Map<String, Object>> rows)
    {
        Set<Long> ids = rows.stream().map(row -> (Long) row.get("conversationId")).collect(Collectors.toSet());
        assertEquals(rows.size(), ids.size(), "批量 JOIN 不得扇出重复会话");
        return ids;
    }

    private Map<String, Object> conversationRow(List<Map<String, Object>> rows, long id)
    {
        return rows.stream().filter(row -> Long.valueOf(id).equals(row.get("conversationId"))).findFirst().orElseThrow();
    }

    private void assertLatest(long viewer, long expected)
    {
        statements.set(0);
        Long actual = members.selectMyConversationSummaries(viewer).stream()
                .filter(row -> Long.valueOf(101).equals(row.getConversationId()))
                .findFirst().orElseThrow().getLatest().getId();
        assertEquals(expected, actual);
        assertEquals(1, statements.get());
    }

    private void image(long id, long sender, String createdAt)
    {
        db.update("insert into im_message(id,conversation_id,sender_id,msg_type,status,check_status,content,create_time,visible_at) "
                + "values(?,101,?,2,0,0,'synthetic pending image',?,null)", id, sender, "2026-10-23 " + createdAt);
    }

    private void conversation(long id, int type, String bizKey, int unread, Long other)
    {
        db.update("insert into im_conversation(id,type,biz_key,status,last_msg_at) values(?,?,?,0,'2026-10-23 08:00:00')", id, type, bizKey);
        db.update("insert into im_conversation_member(conversation_id,member_id,unread_count) values(?,1,?)", id, unread);
        if (other != null) db.update("insert into im_conversation_member(conversation_id,member_id) values(?,?)", id, other);
    }

    private List<Map<String, Object>> memberState()
    {
        return db.queryForList("select conversation_id,member_id,unread_count,last_read_msg_id,muted,deleted from im_conversation_member order by id");
    }
}

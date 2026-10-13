package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.vo.ActivityShellTicketInventoryVO;
import com.chengyinhub.business.domain.vo.PublicTicketVO;
import com.chengyinhub.business.service.impl.OmsTicketServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 活动壳余席按显式 source_ticket_id 配对，并把活动壳 id 交给 service 判歧义。 */
class OmsTicketActivityShellSourcePairingMyBatisTest
{
    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() throws Exception
    {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("OmsTicket", OmsTicket.class);
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:activity_shell_source_pairing;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/OmsTicketMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table cms_activity (id bigint primary key, topic_id bigint, " +
                    "del_flag int, status int, publish_status int)");
            statement.execute("create table oms_ticket (id bigint primary key, owner_type bigint, owner_id bigint, " +
                    "source_ticket_id bigint, name varchar(80), price decimal(10,2), start_time timestamp, " +
                    "end_time timestamp, total_inventory bigint, remaining_inventory bigint, del_flag int)");
        }
    }

    @Test
    void sourceKeySurvivesCopiedFieldEditsAndExposesEveryActiveShell() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("delete from oms_ticket");
            statement.execute("delete from cms_activity");
            statement.execute("insert into oms_ticket(id,owner_type,owner_id,name,price,del_flag) " +
                    "values(41,1,9,'原票名',69.00,0)");
            statement.execute("insert into cms_activity values(101,9,0,1,1)");
            statement.execute("insert into oms_ticket(id,owner_type,owner_id,source_ticket_id,name,price,remaining_inventory,del_flag) " +
                    "values(201,2,101,41,'后台改过的票名',79.00,6,0)");

            List<ActivityShellTicketInventoryVO> one = session.getMapper(OmsTicketMapper.class)
                    .selectActivityShellTicketRemainingByTopic(9L);
            assertEquals(1, one.size());
            assertEquals(Long.valueOf(101L), one.get(0).getActivityId());
            assertEquals(Long.valueOf(41L), one.get(0).getTopicTicketId());
            assertEquals(Long.valueOf(6L), one.get(0).getRemainingInventory());

            statement.execute("insert into cms_activity values(102,9,0,1,1)");
            statement.execute("insert into oms_ticket(id,owner_type,owner_id,source_ticket_id,name,price,remaining_inventory,del_flag) " +
                    "values(202,2,102,41,'第二壳',88.00,5,0)");
            session.clearCache();
            List<ActivityShellTicketInventoryVO> multiple = session.getMapper(OmsTicketMapper.class)
                    .selectActivityShellTicketRemainingByTopic(9L);
            assertEquals(2, multiple.size(), "多壳事实必须全部交给 service 判歧义，SQL 不得静默返回空");
            assertEquals(2L, multiple.stream().map(ActivityShellTicketInventoryVO::getActivityId).distinct().count());
        }
    }

    /** 活动壳存在与否由 cms_activity 决定；壳有没有配对票不能决定壳可不可见。 */
    @Test
    void activeShellWithoutAnyTicketStillReachesService() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            reset(statement);
            seedTopicTicketAndNormalShell(statement);
            statement.execute("insert into cms_activity values(102,9,0,1,1)");

            List<ActivityShellTicketInventoryVO> rows = session.getMapper(OmsTicketMapper.class)
                    .selectActivityShellTicketRemainingByTopic(9L);

            assertEquals(2L, rows.stream().map(ActivityShellTicketInventoryVO::getActivityId).distinct().count(),
                    "无票活动壳也是 active 壳，SQL 不得把它 join 掉");
            ActivityShellTicketInventoryVO empty = shellRow(rows, 102L);
            assertNull(empty.getTopicTicketId(), "无票壳没有配对事实，topicTicketId 必须为 null");
            assertNull(empty.getRemainingInventory(), "无票壳没有余席事实，不得冒出 0");
        }
    }

    /** 壳里全是未配对票（source_ticket_id IS NULL）时，壳依然存在，必须交给 service。 */
    @Test
    void activeShellWithOnlyUnpairedTicketsStillReachesService() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            reset(statement);
            seedTopicTicketAndNormalShell(statement);
            statement.execute("insert into cms_activity values(103,9,0,1,1)");
            statement.execute("insert into oms_ticket(id,owner_type,owner_id,name,price,remaining_inventory,del_flag) " +
                    "values(203,2,103,'全未配对壳票',88.00,5,0)");

            List<ActivityShellTicketInventoryVO> rows = session.getMapper(OmsTicketMapper.class)
                    .selectActivityShellTicketRemainingByTopic(9L);

            assertEquals(2L, rows.stream().map(ActivityShellTicketInventoryVO::getActivityId).distinct().count(),
                    "全未配对壳也是 active 壳，SQL 不得把它 join 掉");
            assertNull(shellRow(rows, 103L).getTopicTicketId(), "未配对壳不得伪装成有配对事实");
        }
    }

    /** 真 SQL 直接喂真 service：壳存在事实必须走完整条链，四种壳形状都不能凭空长出确定余席。 */
    @Test
    void realSqlDrivesServiceUnknownForEveryIncompleteShellShape() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            OmsTicketServiceImpl service = new OmsTicketServiceImpl();
            ReflectionTestUtils.setField(service, "omsTicketMapper", session.getMapper(OmsTicketMapper.class));

            reset(statement);
            seedTopicTicketAndNormalShell(statement);
            assertEquals(Long.valueOf(6L), remainingAfterProjection(service, session),
                    "单个正常壳有稳定配对事实，必须给出确定余席");

            statement.execute("insert into cms_activity values(102,9,0,1,1)");
            assertNull(remainingAfterProjection(service, session),
                    "正常壳 + 无票壳：第二个 active 壳存在即歧义，整个 topic 必须 unknown");

            reset(statement);
            seedTopicTicketAndNormalShell(statement);
            statement.execute("insert into cms_activity values(103,9,0,1,1)");
            statement.execute("insert into oms_ticket(id,owner_type,owner_id,name,price,remaining_inventory,del_flag) " +
                    "values(203,2,103,'全未配对壳票',88.00,5,0)");
            assertNull(remainingAfterProjection(service, session),
                    "正常壳 + 全未配对壳：整个 topic 必须 unknown");

            reset(statement);
            seedTopicTicketAndNormalShell(statement);
            statement.execute("insert into cms_activity values(104,9,0,1,1)");
            statement.execute("insert into oms_ticket(id,owner_type,owner_id,source_ticket_id,name,price,remaining_inventory,del_flag) " +
                    "values(204,2,104,41,'第二正常壳票',88.00,5,0)");
            assertNull(remainingAfterProjection(service, session),
                    "多个正常壳同样不得挑一个库存冒充真值");
        }
    }

    /**
     * 同一个壳里既有配对票又有未配对票：未配对那张是 R6 回填留下的歧义行
     * （迁移注释写明「歧义行保持 NULL，读侧 fail-closed 并告警」），它可能本来就是 41 的另一份拷贝。
     * 此时 41 的余席不可判，不能拿配对上的那张冒充真值。
     */
    @Test
    void shellHoldingAnUnpairedTicketAlongsideAPairedOneIsUnknown() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            OmsTicketServiceImpl service = new OmsTicketServiceImpl();
            ReflectionTestUtils.setField(service, "omsTicketMapper", session.getMapper(OmsTicketMapper.class));

            reset(statement);
            seedTopicTicketAndNormalShell(statement);
            statement.execute("insert into oms_ticket(id,owner_type,owner_id,name,price,remaining_inventory,del_flag) " +
                    "values(202,2,101,'歧义未配对票',79.00,7,0)");

            assertNull(remainingAfterProjection(service, session),
                    "壳里有未配对票 ⇒ 该壳存在不可归属的库存，41 的余席不可判，不得挑配对上的那张冒充真值");
        }
    }

    private static void seedTopicTicketAndNormalShell(Statement statement) throws Exception
    {
        statement.execute("insert into oms_ticket(id,owner_type,owner_id,name,price,del_flag) " +
                "values(41,1,9,'原票名',69.00,0)");
        statement.execute("insert into cms_activity values(101,9,0,1,1)");
        statement.execute("insert into oms_ticket(id,owner_type,owner_id,source_ticket_id,name,price,remaining_inventory,del_flag) " +
                "values(201,2,101,41,'正常壳票',79.00,6,0)");
    }

    private static Long remainingAfterProjection(OmsTicketServiceImpl service, SqlSession session)
    {
        session.clearCache();
        PublicTicketVO ticket = new PublicTicketVO();
        ticket.setId(41L);
        ticket.setRemainingInventory(30L);
        service.applyActivityShellRemaining(9L, java.util.Collections.singletonList(ticket));
        return ticket.getRemainingInventory();
    }

    private static void reset(Statement statement) throws Exception
    {
        statement.execute("delete from oms_ticket");
        statement.execute("delete from cms_activity");
    }

    private static ActivityShellTicketInventoryVO shellRow(List<ActivityShellTicketInventoryVO> rows, long activityId)
    {
        return rows.stream().filter(row -> Long.valueOf(activityId).equals(row.getActivityId()))
                .findFirst().orElseThrow(() -> new AssertionError("活动壳 " + activityId + " 没有出现在投影里"));
    }

    @Test
    void orphanDeleteIsAtomicAndOnlyAcceptsConsistentUnsoldInventory() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("delete from oms_ticket");
            statement.execute("delete from cms_activity");
            statement.execute("insert into cms_activity values(101,9,0,1,1)");
            statement.execute("insert into oms_ticket(id,owner_type,owner_id,source_ticket_id,total_inventory,remaining_inventory,del_flag) " +
                    "values(201,2,101,41,30,5,0)");
            OmsTicketMapper mapper = session.getMapper(OmsTicketMapper.class);

            assertEquals(0, mapper.deleteUnsoldActivityOrphanTicket(201L, 101L, 41L),
                    "已售库存不得被预读后无条件删除");
            statement.execute("update oms_ticket set remaining_inventory=30 where id=201");
            assertEquals(1, mapper.deleteUnsoldActivityOrphanTicket(201L, 101L, 41L));
        }
    }
}

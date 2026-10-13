package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.OmsTicket;
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

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主题编辑整体重建时的主题票原地更新，跑<b>真 SQL</b>（H2 MODE=MySQL）。
 *
 * <p>为什么必须有这一层：这条语句的三条承重都在 SQL 里，Java 侧看不见 ——
 * ①可清空的编辑字段覆盖、可选确认要求省略时保留；②where 里的 owner 归属判定；③库存守恒条件。
 * 只靠 service 层的 mock/假 mapper 断言，等于用我自己写的假设去证明我自己写的 SQL。
 */
class OmsTicketTopicRebuildUpdateMyBatisTest
{
    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() throws Exception
    {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("OmsTicket", OmsTicket.class);
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:topic_ticket_rebuild_update;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/OmsTicketMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table oms_ticket (id bigint primary key, member_id bigint, "
                    + "owner_type bigint, owner_id bigint, source_ticket_id bigint, name varchar(80), "
                    + "price decimal(10,2), start_time timestamp, end_time timestamp, "
                    + "sale_start_time timestamp, sale_end_time timestamp, "
                    + "total_inventory bigint, remaining_inventory bigint, mode int, sub_type varchar(32), "
                    + "meeting_point varchar(120), meeting_point_address varchar(255), team_size int, "
                    + "min_people int, max_people int, "
                    + "signup_deadline timestamp, gather_lng decimal(12,6), gather_lat decimal(12,6), "
                    + "team_status int, refund_rule varchar(120), refund_supported tinyint, "
                    + "sync_with_theme tinyint, need_audit int not null default 0, del_flag int, "
                    + "create_by varchar(32), create_time timestamp, update_by varchar(32), update_time timestamp)");
        }
    }

    @Test
    void omittedTeamSizePreservesDefaultAndExplicitZeroStillClearsTeamMode() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            reset(statement);
            seedTopicTicket(statement, 41L, 9L, 30L, 5L);
            statement.execute("update oms_ticket set team_size = 3 where id = 41");
            OmsTicket patch = rebuildPatch(41L, 9L, "草稿提交", 30L, 5L);
            assertEquals(1, session.getMapper(OmsTicketMapper.class).updateTopicTicketOnRebuild(patch));
            try (ResultSet rs = statement.executeQuery("select team_size from oms_ticket where id = 41")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
                assertTrue(!rs.wasNull());
            }
            patch.setTeamSize(0);
            assertEquals(1, session.getMapper(OmsTicketMapper.class).updateTopicTicketOnRebuild(patch));
            try (ResultSet rs = statement.executeQuery("select team_size from oms_ticket where id = 41")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void omittedAuditSettingPreservesTheExistingNonNullValue() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            reset(statement);
            seedTopicTicket(statement, 41L, 9L, 30L, 5L);
            statement.execute("update oms_ticket set need_audit = 1 where id = 41");
            assertEquals(1, session.getMapper(OmsTicketMapper.class)
                    .updateTopicTicketOnRebuild(rebuildPatch(41L, 9L, "草稿发布", 30L, 5L)));
            try (ResultSet rs = statement.executeQuery("select need_audit from oms_ticket where id = 41")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertTrue(!rs.wasNull());
            }
            OmsTicket patch = rebuildPatch(41L, 9L, "明确取消审核", 30L, 5L);
            patch.setNeedAudit(0);
            assertEquals(1, session.getMapper(OmsTicketMapper.class).updateTopicTicketOnRebuild(patch));
            try (ResultSet rs = statement.executeQuery("select need_audit from oms_ticket where id = 41")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void keepsTheRowIdAndReallyClearsWhatTheEditorEmptied() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            reset(statement);
            // 总量 30、余席 5 ⇒ 已售 25
            seedTopicTicket(statement, 41L, 9L, 30L, 5L);

            assertEquals(1, session.getMapper(OmsTicketMapper.class)
                    .updateTopicTicketOnRebuild(rebuildPatch(41L, 9L, "改过的票名", 40L, 15L)));

            try (ResultSet rs = statement.executeQuery(
                    "select name, total_inventory, remaining_inventory, meeting_point, refund_rule, "
                            + "source_ticket_id, create_by from oms_ticket where id = 41")) {
                assertTrue(rs.next(), "行必须还在，id 不能被换掉");
                assertEquals("改过的票名", rs.getString(1));
                assertEquals(40L, rs.getLong(2));
                assertEquals(15L, rs.getLong(3), "已售 25 张，总量 30→40 后余席只能是 15");
                assertNull(rs.getString(4), "发布者清空的集合地点必须真清空，<if> 语义会把它留成旧值");
                assertNull(rs.getString(5), "发布者清空的退款规则必须真清空");
                rs.getLong(6);
                assertTrue(rs.wasNull(), "主题票不带来源键，这条语句不得写它");
                assertEquals("原始发布者", rs.getString(7), "create_by 不属于本次编辑，不能被覆盖");
            }
        }
    }

    @Test
    void omittedApprovalRequirementKeepsItsValueAndExplicitChangesStillApply() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            reset(statement);
            seedTopicTicket(statement, 41L, 9L, 30L, 5L);
            OmsTicket patch = rebuildPatch(41L, 9L, "原票名", 30L, 5L);
            OmsTicketMapper mapper = session.getMapper(OmsTicketMapper.class);

            assertEquals(1, mapper.updateTopicTicketOnRebuild(patch));
            assertEquals(0, needAuditOf(statement, 41L));
            patch.setNeedAudit(1);
            assertEquals(1, mapper.updateTopicTicketOnRebuild(patch));
            assertEquals(1, needAuditOf(statement, 41L));
            patch.setNeedAudit(null);
            assertEquals(1, mapper.updateTopicTicketOnRebuild(patch));
            assertEquals(1, needAuditOf(statement, 41L));
            patch.setNeedAudit(0);
            assertEquals(1, mapper.updateTopicTicketOnRebuild(patch));
            assertEquals(0, needAuditOf(statement, 41L));
        }
    }

    @Test
    void refusesRowsOutsideTheEditedTopic() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            reset(statement);
            seedTopicTicket(statement, 41L, 9L, 30L, 5L);

            assertEquals(0, session.getMapper(OmsTicketMapper.class)
                            .updateTopicTicketOnRebuild(rebuildPatch(41L, 10L, "别的主题偷改", 40L, 15L)),
                    "归属判定在 where 里：拿别的 topicId 来更新必须 0 行，不能靠 Java 侧自觉");
            assertEquals("原票名", nameOf(statement, 41L));
        }
    }

    @Test
    void refusesActivityShellRowsEvenWhenTheIdMatches() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            reset(statement);
            statement.execute("insert into oms_ticket(id,owner_type,owner_id,source_ticket_id,name,"
                    + "total_inventory,remaining_inventory,del_flag) values(201,2,101,41,'壳票',30,5,0)");

            assertEquals(0, session.getMapper(OmsTicketMapper.class)
                            .updateTopicTicketOnRebuild(rebuildPatch(201L, 101L, "被主题重建改到的壳票", 40L, 15L)),
                    "这条语句只准改主题票(owner_type=1)，改到壳票就等于绕开同步直接动库存");
            assertEquals("壳票", nameOf(statement, 201L));
        }
    }

    @Test
    void refusesSoftDeletedRows() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            reset(statement);
            seedTopicTicket(statement, 41L, 9L, 30L, 5L);
            statement.execute("update oms_ticket set del_flag = 2 where id = 41");

            assertEquals(0, session.getMapper(OmsTicketMapper.class)
                    .updateTopicTicketOnRebuild(rebuildPatch(41L, 9L, "复活已删票", 40L, 15L)));
            assertEquals("原票名", nameOf(statement, 41L));
        }
    }

    @Test
    void refusesInventoryArithmeticThatWouldReviveSoldSeats() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            reset(statement);
            seedTopicTicket(statement, 41L, 9L, 30L, 5L);

            // 已售 25 张却想把余席直接写成 40：SQL 必须自己再算一次并拒绝
            assertEquals(0, session.getMapper(OmsTicketMapper.class)
                    .updateTopicTicketOnRebuild(rebuildPatch(41L, 9L, "复活库存", 40L, 40L)));
            assertEquals(5L, remainingOf(statement, 41L));
        }
    }

    @Test
    void refusesLoweringTotalBelowOccupiedSeats() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            reset(statement);
            seedTopicTicket(statement, 41L, 9L, 30L, 5L);

            // 已售 25，总量想降到 20 ⇒ 折算余席为 -5
            assertEquals(0, session.getMapper(OmsTicketMapper.class)
                    .updateTopicTicketOnRebuild(rebuildPatch(41L, 9L, "总量降到已售之下", 20L, -5L)));
            assertEquals(30L, totalOf(statement, 41L));
        }
    }

    private static void reset(Statement statement) throws Exception
    {
        statement.execute("delete from oms_ticket");
    }

    private static void seedTopicTicket(Statement statement, long id, long topicId, long total, long remaining)
            throws Exception
    {
        statement.execute("insert into oms_ticket(id,member_id,owner_type,owner_id,name,price,"
                + "total_inventory,remaining_inventory,meeting_point,refund_rule,del_flag,create_by) values("
                + id + ",88,1," + topicId + ",'原票名',69.00," + total + "," + remaining
                + ",'人民广场','开场前24小时可退',0,'原始发布者')");
    }

    private static OmsTicket rebuildPatch(long id, long ownerId, String name, Long total, Long remaining)
    {
        OmsTicket patch = new OmsTicket();
        patch.setId(id);
        patch.setOwnerId(ownerId);
        patch.setName(name);
        patch.setPrice(new java.math.BigDecimal("79.00"));
        patch.setTotalInventory(total);
        patch.setRemainingInventory(remaining);
        patch.setMode(2);
        patch.setUpdateBy("me");
        patch.setUpdateTime(new java.util.Date());
        return patch;
    }

    private static String nameOf(Statement statement, long id) throws Exception
    {
        try (ResultSet rs = statement.executeQuery("select name from oms_ticket where id = " + id)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private static int needAuditOf(Statement statement, long id) throws Exception
    {
        try (ResultSet rs = statement.executeQuery("select need_audit from oms_ticket where id = " + id)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static long remainingOf(Statement statement, long id) throws Exception
    {
        try (ResultSet rs = statement.executeQuery("select remaining_inventory from oms_ticket where id = " + id)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static long totalOf(Statement statement, long id) throws Exception
    {
        try (ResultSet rs = statement.executeQuery("select total_inventory from oms_ticket where id = " + id)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }
}

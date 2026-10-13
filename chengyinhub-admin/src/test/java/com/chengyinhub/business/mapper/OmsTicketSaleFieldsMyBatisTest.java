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
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-P2-26:「售票开始/结束」「支持随时退款」「与主题日期同步」「集合点地址」五个字段必须真落库、真回读。
 *
 * <p>病:小程序发布器 {@code pages/publish/fabu/index.js:4458-4481} 一直在上送这五个字段,
 * {@code ActivityTicketRequest} 没有它们,{@code saveTickets} 的
 * {@code BeanUtils.copyProperties} 静默丢弃 ⇒ 发布者填了等于没填,编辑回读恒为空。
 *
 * <p>为什么必须真 SQL(H2 MODE=MySQL)而不是 mock:判据是「值有没有进库、读得回来吗」。
 * mock 只能证明 service 调了 mapper 且对象上带着值 —— 而丢值恰好发生在这一步之后
 * (insert/update 少一列接口照样不报错)。本文件把 insert、整体重建 update、动态 update
 * 三条写路径和 select 回读都压在同一张真表上。
 */
class OmsTicketSaleFieldsMyBatisTest
{
    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() throws Exception
    {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("OmsTicket", OmsTicket.class);
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:oms_ticket_sale_fields;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/OmsTicketMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table cms_topic(id bigint, member_id bigint, del_flag int)");
            statement.execute("create table cms_activity(id bigint, topic_id bigint, member_id bigint, club_id bigint, del_flag int)");
            statement.execute("create table oms_ticket (id bigint auto_increment primary key, member_id bigint, "
                    + "owner_type bigint, owner_id bigint, source_ticket_id bigint, name varchar(80), "
                    + "price decimal(10,2), start_time timestamp, end_time timestamp, "
                    + "sale_start_time timestamp, sale_end_time timestamp, "
                    + "total_inventory bigint, remaining_inventory bigint, mode int, sub_type varchar(32), "
                    + "meeting_point varchar(120), meeting_point_address varchar(255), team_size int, "
                    + "min_people int, max_people int, signup_deadline timestamp, "
                    + "gather_lng decimal(12,6), gather_lat decimal(12,6), team_status int, "
                    + "refund_rule varchar(120), refund_supported tinyint, sync_with_theme tinyint, "
                    + "need_audit int, del_flag int, "
                    + "create_by varchar(32), create_time timestamp, update_by varchar(32), update_time timestamp)");
        }
    }

    /** 发布页填的五项必须原样进库并读得回来 —— 这是 2-26 的验收本体。 */
    @Test
    void publishPageFieldsSurviveInsertAndReadBack() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("delete from oms_ticket");

            OmsTicket ticket = publishableTicket();
            ticket.setSaleStartTime(at("2026-09-18 00:00:00"));
            ticket.setSaleEndTime(at("2026-09-25 23:59:59"));
            ticket.setRefundSupported(Boolean.FALSE);
            ticket.setSyncWithTheme(Boolean.TRUE);
            ticket.setMeetingPointAddress("上海市黄浦区人民大道 1 号");

            assertEquals(1, session.getMapper(OmsTicketMapper.class).insertOmsTicket(ticket));
            assertNotNull(ticket.getId(), "useGeneratedKeys 应回填自增主键");

            OmsTicket back = session.getMapper(OmsTicketMapper.class).selectOmsTicketById(ticket.getId());
            assertNotNull(back, "刚插入的票必须读得回来");
            assertEquals("2026-09-18 00:00:00", fmt(back.getSaleStartTime()), "售票开始时间必须落库");
            assertEquals("2026-09-25 23:59:59", fmt(back.getSaleEndTime()), "售票结束时间必须落库");
            assertEquals(Boolean.FALSE, back.getRefundSupported(),
                    "「支持随时退款」必须落库:前端读回用 !== false 判显示,丢了会显示成支持");
            assertEquals(Boolean.TRUE, back.getSyncWithTheme(), "「与主题日期同步」必须落库");
            assertEquals("上海市黄浦区人民大道 1 号", back.getMeetingPointAddress(), "集合点地址必须落库");
        }
    }

    /** 整包重建时这五列是编辑器拥有的列:清空要真清空,改值要真改。 */
    @Test
    void topicRebuildOverwritesAndReallyClearsSaleFields() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("delete from oms_ticket");
            OmsTicket seed = publishableTicket();
            seed.setSaleStartTime(at("2026-09-18 00:00:00"));
            seed.setSaleEndTime(at("2026-09-25 23:59:59"));
            seed.setRefundSupported(Boolean.TRUE);
            seed.setSyncWithTheme(Boolean.TRUE);
            seed.setMeetingPointAddress("旧地址");
            session.getMapper(OmsTicketMapper.class).insertOmsTicket(seed);

            OmsTicket cleared = rebuildPatch(seed.getId(), null);
            assertEquals(1, session.getMapper(OmsTicketMapper.class).updateTopicTicketOnRebuild(cleared));

            OmsTicket afterClear = session.getMapper(OmsTicketMapper.class).selectOmsTicketById(seed.getId());
            assertNull(afterClear.getSaleStartTime(), "发布者清空的售票开始时间必须真清空");
            assertNull(afterClear.getSaleEndTime(), "发布者清空的售票结束时间必须真清空");
            assertNull(afterClear.getRefundSupported(), "发布者取消的随时退款必须真清空");
            assertNull(afterClear.getSyncWithTheme(), "发布者取消的同步开关必须真清空");
            assertNull(afterClear.getMeetingPointAddress(), "发布者清空的集合点地址必须真清空");

            OmsTicket written = rebuildPatch(seed.getId(), false);
            assertEquals(1, session.getMapper(OmsTicketMapper.class).updateTopicTicketOnRebuild(written));

            OmsTicket afterWrite = session.getMapper(OmsTicketMapper.class).selectOmsTicketById(seed.getId());
            assertEquals("2026-10-01 09:00:00", fmt(afterWrite.getSaleStartTime()), "重新填写必须覆盖旧值");
            assertEquals("2026-10-08 18:00:00", fmt(afterWrite.getSaleEndTime()));
            assertEquals(Boolean.FALSE, afterWrite.getRefundSupported(), "布尔 false 必须写成 0 而不是被跳过");
            assertEquals(Boolean.TRUE, afterWrite.getSyncWithTheme());
            assertEquals("上海市静安区南京西路 100 号", afterWrite.getMeetingPointAddress());
        }
    }

    /**
     * 动态 update 的布尔陷阱:{@code <if test="refundSupported != null">} 在 false 时必须仍成立
     * (false 是非 null),否则发布者关掉「随时退款」会被静默保留成旧值;而没传的售票时间不许被抹。
     */
    @Test
    void partialUpdateWritesFalseButLeavesUnsetSaleTimesUntouched() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("delete from oms_ticket");
            OmsTicket seed = publishableTicket();
            seed.setSaleStartTime(at("2026-09-18 00:00:00"));
            seed.setRefundSupported(Boolean.TRUE);
            seed.setMeetingPointAddress("人民广场");
            session.getMapper(OmsTicketMapper.class).insertOmsTicket(seed);

            OmsTicket patch = new OmsTicket();
            patch.setId(seed.getId());
            patch.setName("改过名的票");
            patch.setRefundSupported(Boolean.FALSE);
            assertEquals(1, session.getMapper(OmsTicketMapper.class).updateOmsTicket(patch));

            OmsTicket back = session.getMapper(OmsTicketMapper.class).selectOmsTicketById(seed.getId());
            assertEquals(Boolean.FALSE, back.getRefundSupported(), "false 必须写进库,不能被 <if> 跳过");
            assertEquals("2026-09-18 00:00:00", fmt(back.getSaleStartTime()), "没传的售票开始时间不许动");
            assertEquals("人民广场", back.getMeetingPointAddress(), "没传的集合点地址不许动");
            assertTrue("改过名的票".equals(back.getName()));
        }
    }

    /** 不填这五项的老路径(后台加票等)照旧能插,落 NULL,不能被必填化。 */
    @Test
    void ticketWithoutSaleFieldsStillInsertsAsNull() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("delete from oms_ticket");
            OmsTicket plain = publishableTicket();

            assertEquals(1, session.getMapper(OmsTicketMapper.class).insertOmsTicket(plain));

            OmsTicket back = session.getMapper(OmsTicketMapper.class).selectOmsTicketById(plain.getId());
            assertNull(back.getSaleStartTime());
            assertNull(back.getSaleEndTime());
            assertNull(back.getRefundSupported());
            assertNull(back.getSyncWithTheme());
            assertNull(back.getMeetingPointAddress());
        }
    }

    /** select 清单漏列时回读恒空,mapper 里 selectOmsTicketList 与 byId 共用同一段 —— 这里再钉一遍列表查询。 */
    @Test
    void selectListAlsoCarriesSaleFields() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("delete from oms_ticket");
            OmsTicket seed = publishableTicket();
            seed.setMeetingPointAddress("列表也要带出来");
            session.getMapper(OmsTicketMapper.class).insertOmsTicket(seed);

            OmsTicket query = new OmsTicket();
            query.setOwnerType(1L);
            query.setOwnerId(9L);
            query.setDelFlag(0);
            java.util.List<OmsTicket> rows = session.getMapper(OmsTicketMapper.class).selectOmsTicketList(query);

            assertEquals(1, rows.size());
            assertEquals("列表也要带出来", rows.get(0).getMeetingPointAddress());
        }
    }

    private static OmsTicket publishableTicket() throws Exception
    {
        OmsTicket ticket = new OmsTicket();
        ticket.setMemberId(88L);
        ticket.setOwnerType(1L);
        ticket.setOwnerId(9L);
        ticket.setName("早鸟票");
        ticket.setPrice(new BigDecimal("69.00"));
        ticket.setStartTime(at("2026-10-01 09:00:00"));
        ticket.setEndTime(at("2026-10-01 18:00:00"));
        ticket.setTotalInventory(30L);
        ticket.setRemainingInventory(30L);
        ticket.setMode(1);
        ticket.setMeetingPoint("人民广场");
        ticket.setDelFlag(0);
        ticket.setCreateBy("发布者");
        ticket.setCreateTime(at("2026-09-18 10:00:00"));
        return ticket;
    }

    /** 与 saveTickets 的原地重建补丁同形:总量不变 ⇒ 库存守恒条件恒成立。 */
    private static OmsTicket rebuildPatch(Long id, Boolean refundSupported) throws Exception
    {
        OmsTicket patch = new OmsTicket();
        patch.setId(id);
        patch.setOwnerId(9L);
        patch.setName("早鸟票");
        patch.setPrice(new BigDecimal("69.00"));
        patch.setStartTime(at("2026-10-01 09:00:00"));
        patch.setEndTime(at("2026-10-01 18:00:00"));
        patch.setTotalInventory(30L);
        patch.setRemainingInventory(30L);
        patch.setMode(1);
        patch.setMeetingPoint("人民广场");
        patch.setUpdateBy("发布者");
        patch.setUpdateTime(at("2026-09-19 10:00:00"));
        if (refundSupported != null) {
            patch.setSaleStartTime(at("2026-10-01 09:00:00"));
            patch.setSaleEndTime(at("2026-10-08 18:00:00"));
            patch.setRefundSupported(refundSupported);
            patch.setSyncWithTheme(Boolean.TRUE);
            patch.setMeetingPointAddress("上海市静安区南京西路 100 号");
        }
        return patch;
    }

    private static Date at(String yyyyMMddHHmmss) throws Exception
    {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(yyyyMMddHHmmss);
    }

    private static String fmt(Date date)
    {
        return date == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
    }
}

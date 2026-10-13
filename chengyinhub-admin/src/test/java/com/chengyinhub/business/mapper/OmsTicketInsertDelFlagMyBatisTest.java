package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.service.impl.OmsTicketServiceImpl;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 后台通用新增票务落库时，{@code del_flag} 必须由<b>应用层</b>写成 0，而不是靠库侧默认值兜底。
 *
 * <p><b>为什么非要有这条</b>：R11-C 溯源查明 {@code oms_ticket} 的 {@code CREATE TABLE} 在本仓
 * 与全部 git 历史里都不存在（它是先于 SQL 台账的存量表），因此「生产上 {@code del_flag} 到底有没有
 * {@code DEFAULT 0}」在本地不可验证；而全仓当时<b>没有任何一个测试</b>执行过「对一张真表省略
 * {@code del_flag} 插入」这条路径 —— 唯一会省略它的生产入口是
 * {@link OmsTicketServiceImpl#insertOmsTicket}，而驱动它的测试用的都是 Mockito 假 mapper、零 DDL。
 * 于是「后台建的票可见」这条事实一直押在一个没人回读过的库侧默认上。
 *
 * <p><b>这条测试凭什么不会被库侧默认蒙混过关</b>（这是本文件的承重设计，改 DDL 前先读）：
 * 建表时 {@code del_flag INT NOT NULL} 且<b>刻意不给 DEFAULT</b>。于是这张表**没有能力**替应用层
 * 提供这个值 —— 省略该列的插入会当场报错。{@link #fixtureItselfCannotSupplyDelFlag()} 就是把这条
 * 夹具性质本身钉死的守卫：谁哪天给这个 fixture 补上 {@code DEFAULT 0}，那一条会先红，
 * 提醒他刚刚把 {@link #adminTicketCreationWritesDelFlagFromTheApplicationLayer()} 的判别力清零了。
 */
class OmsTicketInsertDelFlagMyBatisTest
{
    private static final Long ACTIVITY_ID = 4101L;
    private static final Long ACTIVITY_OWNER = 88L;

    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() throws Exception
    {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("OmsTicket", OmsTicket.class);
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:oms_ticket_insert_del_flag;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/OmsTicketMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            // ★ del_flag NOT NULL 且【无 DEFAULT】—— 这张表没有能力替应用层给这个值。
            //   这不是随手写的建表语句，是本文件的判别力来源，别顺手加默认值。
            statement.execute("create table oms_ticket (id bigint auto_increment primary key, member_id bigint, "
                    + "owner_type bigint, owner_id bigint, source_ticket_id bigint, name varchar(80), "
                    + "price decimal(10,2), start_time timestamp, end_time timestamp, "
                    + "total_inventory bigint, remaining_inventory bigint, mode int, sub_type varchar(32), "
                    + "meeting_point varchar(120), team_size int, min_people int, max_people int, "
                    + "signup_deadline timestamp, gather_lng decimal(12,6), gather_lat decimal(12,6), "
                    + "team_status int, refund_rule varchar(120), need_audit int, "
                    + "del_flag int not null, "
                    + "create_by varchar(32), create_time timestamp, update_by varchar(32), update_time timestamp)");
        }
    }

    @AfterEach
    void clearLogin()
    {
        SecurityContextHolder.clearContext();
    }

    /**
     * 夹具自证：这张表<b>不能</b>替应用层提供 del_flag。
     * 它红了只有一个含义 —— 有人给 fixture 补了默认值，兄弟用例的判别力已归零。
     */
    @Test
    void fixtureItselfCannotSupplyDelFlag() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("delete from oms_ticket");

            SQLException error = assertThrows(SQLException.class, () -> statement.execute(
                    "insert into oms_ticket(owner_type, owner_id, name, total_inventory) "
                            + "values(2, " + ACTIVITY_ID + ", '省略了 del_flag 的插入', 10)"));

            assertTrue(error.getMessage().toUpperCase().contains("DEL_FLAG"),
                    "省略 del_flag 必须当场报错，否则本文件全部判别力归零：" + error.getMessage());
            assertEquals(0, count(statement, "select count(*) from oms_ticket"));
        }
    }

    @Test
    void adminTicketCreationWritesDelFlagFromTheApplicationLayer() throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("delete from oms_ticket");
            OmsTicketServiceImpl service = serviceOn(session);
            loginWith("business:ticket:add", "business:activity:edit");

            assertEquals(1, service.insertOmsTicket(adminSubmittedTicket()));

            try (ResultSet rs = statement.executeQuery(
                    "select del_flag, member_id, remaining_inventory from oms_ticket")) {
                assertTrue(rs.next(), "后台新增必须真落一行");
                assertEquals(0, rs.getInt(1),
                        "del_flag 必须由应用层写成 0 —— 这张表没有默认值可兜底，能读到 0 就只能是它写的");
                assertEquals(ACTIVITY_OWNER.longValue(), rs.getLong(2), "归属仍从父活动派生");
                assertEquals(10L, rs.getLong(3), "余席仍按总库存初始化");
            }
        }
    }

    private OmsTicketServiceImpl serviceOn(SqlSession session)
    {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setMemberId(ACTIVITY_OWNER);
        activity.setTopicId(null);              // 独立活动：不触发探店日/自办壳建票闸
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);

        OmsTicketServiceImpl service = new OmsTicketServiceImpl();
        ReflectionTestUtils.setField(service, "omsTicketMapper", session.getMapper(OmsTicketMapper.class));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        return service;
    }

    private static OmsTicket adminSubmittedTicket()
    {
        // 与后台「活动详情 → 添加票务」的白名单 payload 同形：
        // chengyinhub-ui/src/views/business/activity/detail.vue:302-317 不传 delFlag。
        OmsTicket submitted = new OmsTicket();
        submitted.setOwnerType(2L);
        submitted.setOwnerId(ACTIVITY_ID);
        submitted.setName("后台新增的场次");
        submitted.setPrice(new BigDecimal("69.00"));
        submitted.setStartTime(new Date(1_800_000_000_000L));
        submitted.setEndTime(new Date(1_800_003_600_000L));
        submitted.setTotalInventory(10L);
        submitted.setMemberId(999L);            // 伪造归属，服务端必须覆盖
        return submitted;
    }

    private static void loginWith(String... permissions)
    {
        SysUser user = new SysUser();
        user.setUserId(1L);
        LoginUser loginUser = new LoginUser(user, new HashSet<>(Arrays.asList(permissions)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }

    private static long count(Statement statement, String sql) throws Exception
    {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }
}

package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.vo.ClubCustomerVO;
import com.chengyinhub.business.mapper.ClubCrmMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * R9-15:俱乐部核销详情(selectCheckinDetail)在真库上必须能跑 —— 它此前引用
 * {@code a.title},而 cms_activity 的列叫 {@code name},整条请求 500。
 *
 * <p><b>为什么必须是真库。</b>缺陷不在 Java 判空、也不在 resultMap,而在 SQL 里引了
 * 一个不存在的列名。Mockito 单测会把这个 SQL 原样放过(它根本不解析 SQL),
 * 只有 MySQL 能在 prepare 阶段报 1054 Unknown column —— 这里就是那条 1054 的复现。
 *
 * <p>夹具覆盖活动报名(owner_type=2)那一支:主题名为空、走 {@code coalesce(t.name, a.name)},
 * 所以断言最终取到的是活动名称。翻回 {@code a.title} 时本用例立刻报 1054(负控已实测)。
 */
class ClubCrmCheckinDetailMyBatisTest {

    private static final long CLUB_ID = 700L;
    private static final long REGISTRATION_ID = 86L;
    private static final long ACTIVITY_ID = 990001L;
    private static final long MEMBER_ID = 8001L;

    @Test
    void checkinDetailReadsActivityNameThroughTheRealSql() throws Exception {
        SqlSessionFactory factory = factory();
        try (SqlSession session = factory.openSession(true)) {
            createTables(session.getConnection());
            seed(session.getConnection());

            ClubCustomerVO.RawCheckinRow row = session.getMapper(ClubCrmMapper.class)
                    .selectCheckinDetail(CLUB_ID, REGISTRATION_ID);

            assertNotNull(row, "活动报名单必须查得到");
            assertEquals("城市漫游读者见面会", row.getTopicName(),
                    "cms_activity 的标题列是 name —— 引 a.title 时这条整句都跑不起来");
            assertEquals("/activity-cover.png", row.getTopicCover());
            assertEquals("小明", row.getDisplayName());
            assertEquals(86L, row.getRegistrationId());
        }
    }

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("club_crm_checkin"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        try (InputStream in = Resources.getResourceAsStream("mapper/business/ClubCrmMapper.xml")) {
            new XMLMapperBuilder(in, cfg, "mapper/business/ClubCrmMapper.xml", cfg.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    private void createTables(java.sql.Connection connection) throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE cms_registration ("
                    + "id BIGINT PRIMARY KEY, member_id BIGINT, owner_id BIGINT, owner_type INT,"
                    + "parent_order_no VARCHAR(64), ticket_name VARCHAR(128), order_num INT,"
                    + "payable_amount DECIMAL(10,2), payment_time DATETIME, payment_status INT,"
                    + "verification_status INT, verification_time DATETIME, verifier_id BIGINT)");
            st.execute("CREATE TABLE ums_member ("
                    + "id BIGINT PRIMARY KEY, nickname VARCHAR(64), avatar VARCHAR(255),"
                    + "phone VARCHAR(20), del_flag INT DEFAULT 0)");
            st.execute("CREATE TABLE cms_topic ("
                    + "id BIGINT PRIMARY KEY, name VARCHAR(255), img_url VARCHAR(255),"
                    + "start_date DATETIME, club_id BIGINT, del_flag INT DEFAULT 0)");
            st.execute("CREATE TABLE cms_activity ("
                    + "id BIGINT PRIMARY KEY, name VARCHAR(255), img_url VARCHAR(255),"
                    + "start_date DATETIME, club_id BIGINT, del_flag INT DEFAULT 0)");
            st.execute("CREATE TABLE mms_merchant ("
                    + "member_id BIGINT PRIMARY KEY, name VARCHAR(128), del_flag INT DEFAULT 0)");
            st.execute("CREATE TABLE merchant_review ("
                    + "registration_id BIGINT, create_time DATETIME, status VARCHAR(20))");
        }
    }

    private void seed(java.sql.Connection connection) throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute("INSERT INTO cms_activity (id, name, img_url, start_date, club_id, del_flag) "
                    + "VALUES (" + ACTIVITY_ID + ", '城市漫游读者见面会', '/activity-cover.png',"
                    + " '2026-09-20 19:30:00', " + CLUB_ID + ", 0)");
            st.execute("INSERT INTO ums_member (id, nickname, avatar, phone, del_flag) "
                    + "VALUES (" + MEMBER_ID + ", '小明', '/avatar.png', '13800000000', 0)");
            st.execute("INSERT INTO cms_registration (id, member_id, owner_id, owner_type, parent_order_no,"
                    + " ticket_name, order_num, payable_amount, payment_time, payment_status,"
                    + " verification_status, verification_time) "
                    + "VALUES (" + REGISTRATION_ID + ", " + MEMBER_ID + ", " + ACTIVITY_ID + ", 2, 'PO-86',"
                    + " '早鸟票', 1, 99.00, '2026-09-01 10:00:00', 2, 1, '2026-09-20 19:35:00')");
        }
    }
}

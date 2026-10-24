package com.chengyinhub.business.it;

import com.chengyinhub.business.mapper.ClubCrmMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CL-07 / F-W5-02:无报名时待核销为 0。驱动真实 ClubCrmMapper.selectTopicManageStats(H2 MySQL 模式),
 * left join 落空的那一行(r.id 为 null)不能被数成 1 张待核销。
 */
class ClubPendingVerifyZeroSignupV2Test {

    private static final long CLUB = 700L;
    private static final long VIEWER = 7L;
    private static final long EMPTY_TOPIC = 910L;
    private static final long EMPTY_SESSION = 911L;
    private static final long REFUND_ONLY_TOPIC = 920L;
    private static final long REFUND_ONLY_SESSION = 921L;
    private static final long ONE_PENDING_TOPIC = 930L;
    private static final long ONE_PENDING_SESSION = 931L;

    @Test
    void zeroSignupTopicAndSessionShowZeroPendingWhileOneRealPendingStillCountsOne() throws Exception {
        Configuration cfg = new Configuration();
        cfg.setEnvironment(new Environment("test", new JdbcTransactionFactory(),
                new PooledDataSource("org.h2.Driver", "jdbc:h2:mem:club_pending_zero_v2;MODE=MySQL;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1", "sa", "")));
        String resource = "mapper/business/ClubCrmMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        try (SqlSession session = new SqlSessionFactoryBuilder().build(cfg).openSession(true)) {
            try (Statement st = session.getConnection().createStatement()) {
                st.execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, club_id BIGINT, del_flag INT DEFAULT 0, member_id BIGINT)");
                st.execute("CREATE TABLE cms_topic_node (id BIGINT PRIMARY KEY, topic_id BIGINT, status INT)");
                st.execute("CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT, club_id BIGINT,"
                        + " del_flag INT DEFAULT 0, start_date DATETIME NULL, end_date DATETIME NULL, cancel_time DATETIME NULL)");
                st.execute("CREATE TABLE game_session (id BIGINT PRIMARY KEY AUTO_INCREMENT, activity_id BIGINT NOT NULL, status VARCHAR(32) NOT NULL)");
                st.execute("CREATE TABLE cms_registration (id BIGINT PRIMARY KEY, member_id BIGINT, owner_id BIGINT,"
                        + " owner_type INT, payment_status INT, verification_status INT, verifier_id BIGINT, order_num INT)");
                st.execute("CREATE TABLE coop_group_member_verify (id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                        + " topic_id BIGINT, activity_id BIGINT, registration_id BIGINT, verifier_member_id BIGINT)");
                st.execute("CREATE TABLE coop_order (id BIGINT PRIMARY KEY, invite_id BIGINT, topic_id BIGINT,"
                        + " to_id BIGINT, to_type VARCHAR(16), status INT, del_flag INT DEFAULT 0)");
                st.execute("CREATE TABLE coop_invite (id BIGINT PRIMARY KEY, invite_type INT)");

                st.execute("INSERT INTO cms_topic (id, club_id, del_flag, member_id) VALUES"
                        + " (" + EMPTY_TOPIC + ", " + CLUB + ", 0, 1), (" + REFUND_ONLY_TOPIC + ", " + CLUB + ", 0, 1),"
                        + " (" + ONE_PENDING_TOPIC + ", " + CLUB + ", 0, 1)");
                st.execute("INSERT INTO cms_activity (id, topic_id, club_id, del_flag) VALUES"
                        + " (" + EMPTY_SESSION + ", " + EMPTY_TOPIC + ", " + CLUB + ", 0),"
                        + " (" + REFUND_ONLY_SESSION + ", " + REFUND_ONLY_TOPIC + ", " + CLUB + ", 0),"
                        + " (" + ONE_PENDING_SESSION + ", " + ONE_PENDING_TOPIC + ", " + CLUB + ", 0)");
                // 只有退款单(payment_status=4)的场次:left join 条件 payment_status=2 落空,等同 0 报名
                st.execute("INSERT INTO cms_registration VALUES (1, 101, " + REFUND_ONLY_SESSION + ", 2, 4, 0, NULL, 1)");
                // 对照:真有 1 张已付待核
                st.execute("INSERT INTO cms_registration VALUES (2, 102, " + ONE_PENDING_SESSION + ", 2, 2, 0, NULL, 1)");
            }
            ClubCrmMapper mapper = session.getMapper(ClubCrmMapper.class);

            for (Long activityId : new Long[]{null, EMPTY_SESSION}) {
                Map<String, Object> row = mapper.selectTopicManageStats(CLUB, EMPTY_TOPIC, activityId, VIEWER);
                assertEquals(0, num(row, "pendingVerifyCount"), "0 报名(activityId=" + activityId + ")不得显示待核销 1");
                assertEquals(0, num(row, "sessionHeadcount"));
                assertEquals(0, num(row, "verifiedByMeCount"));
            }
            for (Long activityId : new Long[]{null, REFUND_ONLY_SESSION}) {
                assertEquals(0, num(mapper.selectTopicManageStats(CLUB, REFUND_ONLY_TOPIC, activityId, VIEWER),
                        "pendingVerifyCount"), "只有退款单的场次同样是 0 待核销");
            }
            for (Long activityId : new Long[]{null, ONE_PENDING_SESSION}) {
                Map<String, Object> row = mapper.selectTopicManageStats(CLUB, ONE_PENDING_TOPIC, activityId, VIEWER);
                assertEquals(1, num(row, "pendingVerifyCount"), "真实 1 张待核仍是 1");
                assertEquals(1, num(row, "sessionHeadcount"));
            }
        }
    }

    private static int num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).intValue();
    }
}

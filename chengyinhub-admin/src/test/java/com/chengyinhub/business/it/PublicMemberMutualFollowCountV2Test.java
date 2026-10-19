package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.vo.PublicMemberVO;
import com.chengyinhub.business.mapper.PublicMemberMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CL-09 / F-W5-09:公开主页 friend_num 只数「互相关注」的去重、在用好友。
 * 驱动真实 PublicMemberMapper.selectPublicMemberById(H2 MySQL 模式),夹具与 PublicMemberFriendCountMySqlIT 同形,
 * 让没有 MySQL 的环境也能跑到这条口径。
 */
class PublicMemberMutualFollowCountV2Test {

    @Test
    void friendNumCountsOnlyDistinctActiveReciprocalPeers() throws Exception {
        Configuration cfg = new Configuration();
        cfg.setEnvironment(new Environment("test", new JdbcTransactionFactory(),
                new PooledDataSource("org.h2.Driver", "jdbc:h2:mem:public_member_mutual_v2;MODE=MySQL;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1", "sa", "")));
        String resource = "mapper/business/PublicMemberMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        try (SqlSession session = new SqlSessionFactoryBuilder().build(cfg).openSession(true)) {
            try (Statement st = session.getConnection().createStatement()) {
                st.execute("CREATE TABLE ums_member(id BIGINT PRIMARY KEY, nickname VARCHAR(64), avatar VARCHAR(255),"
                        + " introduction VARCHAR(255), case_pics TEXT, case_introducton TEXT, tag_ids VARCHAR(64),"
                        + " role VARCHAR(16), status INT, del_flag INT, create_time DATETIME, invite_user_id BIGINT)");
                st.execute("CREATE TABLE player_growth(member_id BIGINT PRIMARY KEY, level INT)");
                st.execute("CREATE TABLE ums_member_follow(id BIGINT PRIMARY KEY, member_id BIGINT, follow_member_id BIGINT)");
                // 1 主体;2 互关;3 互关但停用;4 单向(1→4);5 互关但已删;6 互关;7 只关注 1(7→1)
                st.execute("INSERT INTO ums_member(id, nickname, role, status, del_flag) VALUES"
                        + " (1,'a','player',1,0),(2,'b','player',1,0),(3,'c','player',0,0),(4,'d','player',1,0),"
                        + " (5,'e','player',1,1),(6,'f','club',1,0),(7,'g','player',1,0)");
                st.execute("INSERT INTO ums_member_follow VALUES"
                        + " (1,1,2),(2,2,1),(3,1,2)," // 互关 + 重复关注行,去重后算 1
                        + " (4,1,3),(5,3,1),"         // 对方停用
                        + " (6,1,4),"                 // 单向
                        + " (7,1,5),(8,5,1),"         // 对方已删
                        + " (9,1,1),"                 // 自己关注自己
                        + " (10,1,6),(11,6,1),"       // 互关
                        + " (12,7,1)");               // 只被关注
            }
            PublicMemberMapper mapper = session.getMapper(PublicMemberMapper.class);
            assertEquals(2, mapper.selectPublicMemberById(1L).getFriendNum(), "去重互关、排除停用/已删/单向/自己");
            assertEquals(1, mapper.selectPublicMemberById(2L).getFriendNum());
            assertEquals(0, mapper.selectPublicMemberById(4L).getFriendNum(), "只被关注、没回关 ⇒ 0");
            assertEquals(0, mapper.selectPublicMemberById(7L).getFriendNum(), "只关注别人、没被回关 ⇒ 0");
            assertNull(mapper.selectPublicMemberById(3L), "停用成员不出公开主页");
            assertNull(mapper.selectPublicMemberById(5L), "已删成员不出公开主页");
        }
    }
}

package com.chengyinhub.business.mapper;

import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.domain.ClubRoleAssignment;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 活动权限发现必须在一次查询内收口委派有效性与活动归属。 */
class ClubEventAccessDiscoveryMyBatisTest {
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:club_event_access_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/ClubRoleAssignmentMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table cms_activity (id bigint primary key, topic_id bigint, club_id bigint, del_flag int)");
            statement.execute("create table club_role_assignment ("
                    + "id bigint primary key, club_id bigint, target_member_id bigint, role_code varchar(32), "
                    + "scope_type varchar(16), scope_id bigint, status varchar(16), expires_at timestamp, "
                    + "assigned_by_member_id bigint, assigned_at timestamp, revoked_by_member_id bigint, "
                    + "revoked_at timestamp, revoke_reason varchar(500), version int, create_by varchar(64), "
                    + "create_time timestamp, update_by varchar(64), update_time timestamp)");
            statement.execute("insert into cms_activity values "
                    + "(55,501,11,0),(56,502,11,0),(57,503,11,0),(58,504,11,1),"
                    + "(59,505,12,0),(60,506,11,0)");
            statement.execute("insert into club_role_assignment "
                    + "(id,club_id,target_member_id,role_code,scope_type,scope_id,status,expires_at) values "
                    + "(1,11,901,'EVENT_CHECKIN','EVENT',55,'ACTIVE',null),"
                    + "(2,11,901,'EVENT_LEAD','EVENT',56,'REVOKED',null),"
                    + "(3,11,901,'EVENT_LEAD','EVENT',57,'ACTIVE','2020-01-01 00:00:00'),"
                    + "(4,11,901,'EVENT_LEAD','EVENT',58,'ACTIVE',null),"
                    + "(5,11,901,'EVENT_LEAD','EVENT',59,'ACTIVE',null),"
                    + "(6,11,902,'EVENT_LEAD','EVENT',60,'ACTIVE',null),"
                    + "(7,11,901,'CLUB_OPERATOR','CLUB',11,'ACTIVE',null),"
                    + "(8,11,901,'EVENT_LEAD','EVENT',60,'ACTIVE',null),"
                    + "(9,12,901,'EVENT_LEAD','EVENT',59,'ACTIVE',null)");
        }
    }

    @Test
    void returnsOnlyActiveUnexpiredAssignmentsForLiveActivitiesOwnedByClub() {
        try (SqlSession session = factory.openSession(true)) {
            List<ClubRoleAssignment> rows = session.getMapper(ClubRoleAssignmentMapper.class)
                    .selectActiveEventAccesses(11L, 901L, new Date());

            assertEquals(2, rows.size());
            assertEquals(55L, rows.get(0).getScopeId());
            assertEquals(501L, rows.get(0).getScopeTopicId());
            assertEquals(ClubRoleCode.EVENT_CHECKIN, rows.get(0).getRoleCode());
            assertEquals(60L, rows.get(1).getScopeId());
            assertEquals(506L, rows.get(1).getScopeTopicId());
            assertEquals(ClubRoleCode.EVENT_LEAD, rows.get(1).getRoleCode());
        }
    }
}

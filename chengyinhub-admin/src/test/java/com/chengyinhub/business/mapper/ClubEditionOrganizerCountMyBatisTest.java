package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
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

/** W10a #26:「已承接」只认有效的 ORGANIZER 俱乐部合作单(与开售解析执行俱乐部同口径)。 */
class ClubEditionOrganizerCountMyBatisTest {
    private static final String XML = "mapper/business/ClubEditionCompensationMapper.xml";

    @Test
    void countsOnlyEffectiveOrganizerOrdersOfThisClub() throws Exception {
        Configuration configuration = new Configuration();
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:club_edition_organizer;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        try (InputStream input = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(input, configuration, XML, configuration.getSqlFragments()).parse();
        }
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(false)) {
            try (Statement st = session.getConnection().createStatement()) {
                st.execute("drop all objects");
                st.execute("create table coop_invite(id bigint primary key, invite_type int)");
                st.execute("create table coop_order(id bigint primary key, invite_id bigint, topic_id bigint, "
                        + "to_id bigint, to_type varchar(16), role_snapshot varchar(32), status int, del_flag int)");
                st.execute("insert into coop_invite values (1,1),(2,2)");
                st.execute("insert into coop_order values "
                        + "(10,1,23,7,'club','ORGANIZER',0,0),"   // 计入
                        + "(11,1,24,7,'club','SUPPORT',0,0),"     // 非执行角色
                        + "(12,1,25,7,'club','ORGANIZER',1,0),"   // 已失效
                        + "(13,1,26,7,'club','ORGANIZER',0,1),"   // 已删除
                        + "(14,2,27,7,'club','ORGANIZER',0,0),"   // 旧 invite_type=2
                        + "(15,1,28,7,'merchant','ORGANIZER',0,0)," // 不是俱乐部主体
                        + "(16,1,29,8,'club','ORGANIZER',0,0)");  // 别的俱乐部
            }
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            assertEquals(1, mapper.countEffectiveOrganizerOrdersForClub(7L));
            assertEquals(0, mapper.countEffectiveOrganizerOrdersForClub(9L));
            session.rollback();
        }
    }
}

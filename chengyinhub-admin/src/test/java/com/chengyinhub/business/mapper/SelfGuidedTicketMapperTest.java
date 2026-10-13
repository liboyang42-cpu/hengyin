package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.vo.PublicTicketVO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.sql.Statement;
import static org.junit.jupiter.api.Assertions.*;

class SelfGuidedTicketMapperTest {
    @Test
    void onlyCopiedSelfHostedTicketsReceiveThePublicSelfGuidedFlag() throws Exception {
        Configuration config = new Configuration();
        config.getTypeAliasRegistry().registerAlias("OmsTicket", OmsTicket.class);
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(),
                new PooledDataSource("org.h2.Driver", "jdbc:h2:mem:self_guided_ticket;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        String resource = "mapper/business/OmsTicketMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
        }
        try (SqlSession session = new SqlSessionFactoryBuilder().build(config).openSession(true);
             Statement s = session.getConnection().createStatement()) {
            s.execute("create table cms_topic(id bigint, member_id bigint, del_flag int)");
            s.execute("create table cms_activity(id bigint, topic_id bigint, member_id bigint, club_id bigint, del_flag int)");
            s.execute("create table oms_ticket(id bigint, member_id bigint, owner_type bigint, owner_id bigint, source_ticket_id bigint, name varchar, price decimal, start_time timestamp, end_time timestamp, sale_start_time timestamp, sale_end_time timestamp, total_inventory bigint, remaining_inventory bigint, mode int, sub_type varchar, meeting_point varchar, meeting_point_address varchar, team_size int, min_people int, max_people int, signup_deadline timestamp, gather_lng decimal, gather_lat decimal, team_status int, refund_rule varchar, refund_supported int, sync_with_theme int, need_audit int, del_flag int, create_by varchar, create_time timestamp, update_by varchar, update_time timestamp)");
            s.execute("insert into cms_topic values(10,7,0)");
            s.execute("insert into cms_activity values(20,10,7,null,0),(21,10,7,5,0),(22,10,8,null,0)");
            s.execute("insert into oms_ticket(id,owner_type,owner_id,source_ticket_id,sub_type) values(1,2,20,90,null),(2,2,21,90,'self'),(3,2,22,90,'self'),(4,2,20,90,'guided'),(5,2,20,null,'self')");
            OmsTicketMapper mapper = session.getMapper(OmsTicketMapper.class);
            OmsTicket self = mapper.selectOmsTicketById(1L);
            assertEquals(Boolean.TRUE, self.getSelfGuided());
            assertEquals(Boolean.TRUE, PublicTicketVO.from(self, null).getSelfGuided());
            for (long id = 2; id <= 5; id++) assertEquals(Boolean.FALSE, mapper.selectOmsTicketById(id).getSelfGuided());
            assertEquals(5, mapper.selectOmsTicketList(new OmsTicket()).size());
        }
    }
}

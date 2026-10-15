package com.chengyinhub.business.mapper;
import com.chengyinhub.business.domain.CmsTopic;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ExplorationAnniversaryMapperTest {
    @Test void actualMapperIncludesCurrentVisibilityAndHistoricalFactWithoutTicketExpiryGates() throws Exception {
        Configuration c=new Configuration();c.getTypeAliasRegistry().registerAlias("CmsTopic",CmsTopic.class);
        for(String name:Arrays.asList("CmsTopicMapper","ExplorationAnniversaryMapper")){String resource="mapper/business/"+name+".xml";try(InputStream in=Resources.getResourceAsStream(resource)){new XMLMapperBuilder(in,c,resource,c.getSqlFragments()).parse();}}
        Map<String,Object> p=new HashMap<>();p.put("owner",7L);p.put("monthDay","02-29");p.put("yearStart","2026-01-01 00:00:00");p.put("beforeAt","2024-02-29 12:00:00.000");p.put("beforeId",99L);p.put("limit",21);
        BoundSql bound=c.getMappedStatement("com.chengyinhub.business.mapper.ExplorationAnniversaryMapper.page").getBoundSql(p);
        String sql=bound.getSql().replaceAll("\\s+"," ").toLowerCase();assertEquals(7L,bound.getAdditionalParameter("viewerMemberId"));
        for(String gate:Arrays.asList("s.member_id=? and s.status=1","cms_topic.status=1","cms_topic.user_status=1","cms_topic.del_flag=0","cms_topic.is_draft=0","cms_topic.is_template=0","active_club.del_flag=0","circle_reviewed_at","viewer_cm.status = 1","viewer_reg.payment_status = 2","s.completed_at < ?","s.id < ?","order by s.completed_at desc,s.id desc limit ?"))assertTrue(sql.contains(gate),gate);
        for(String forbidden:Arrays.asList("config_snapshot_json","state_json","entitlement","purchase_kind","cms_topic.end_date","cms_topic.start_date","imageurl"))assertFalse(sql.contains(forbidden),forbidden);
    }
}

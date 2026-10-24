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
/** Uses the REAL shared visibility fragments, so a missing bind/include fails during parsing. */
class MonthlyRecapMapperTest {
    private BoundSql sql(String method,String kind) throws Exception {
        Configuration config=new Configuration();config.getTypeAliasRegistry().registerAlias("CmsTopic",CmsTopic.class);
        for(String name:Arrays.asList("CmsTopicMapper","MonthlyRecapMapper")) {
            String resource="mapper/business/"+name+".xml";
            try(InputStream in=Resources.getResourceAsStream(resource)){new XMLMapperBuilder(in,config,resource,config.getSqlFragments()).parse();}
        }
        Map<String,Object> args=new HashMap<>();args.put("memberId",7L);args.put("kind",kind);args.put("start","2026-10-01 00:00:00");args.put("end","2026-11-01 00:00:00");args.put("beforeId",99L);args.put("limit",21);
        return config.getMappedStatement("com.chengyinhub.business.mapper.MonthlyRecapMapper."+method).getBoundSql(args);
    }
    @Test void countAndPageUseCurrentViewerGatesAndOnlyCompletedRoutes() throws Exception {
        for(String method:Arrays.asList("count","page")) {
            BoundSql bound=sql(method,"routes");String s=bound.getSql().replaceAll("\\s+"," ").toLowerCase();
            assertEquals(7L,bound.getAdditionalParameter("viewerMemberId"));
            assertTrue(s.contains("s.member_id=? and s.status=1"));assertTrue(s.contains("cms_topic.is_draft=0"));
            assertTrue(s.contains("circle_reviewed_at"));assertTrue(s.contains("viewer_reg.payment_status = 2"));assertTrue(s.contains("viewer_cm.status = 1"));
            assertTrue(s.contains("active_member.status=1 and active_member.del_flag=0"));assertTrue(s.contains("a.topic_id=s.topic_id"));
            assertTrue(s.contains("s.completed_at >= ? and s.completed_at < ?"));assertFalse(s.contains("state_json"));
        }
    }
    @Test void placesRequireMatchingOwnerCompletedSessionAndApprovedPoi() throws Exception {
        String s=sql("page","places").getSql().replaceAll("\\s+"," ").toLowerCase();
        assertTrue(s.contains("s.member_id=f.member_id and s.status=1"));assertTrue(s.contains("f.member_id=?"));assertTrue(s.contains("p.audit_status=1"));
        assertTrue(s.contains("facts.id < ?"));assertTrue(s.contains("order by facts.id desc limit ?"));assertFalse(s.contains("latitude"));assertFalse(s.contains("longitude"));
    }
    @Test void stampsExcludeDeletedAndRiskyContentAndNeverReturnPhotoOrCaption() throws Exception {
        String s=sql("page","stamps").getSql().replaceAll("\\s+"," ").toLowerCase();
        assertTrue(s.contains("s.member_id=? and s.del_flag=0 and s.check_state!=2"));assertFalse(s.contains("pic_url"));assertFalse(s.contains("caption"));
    }
}

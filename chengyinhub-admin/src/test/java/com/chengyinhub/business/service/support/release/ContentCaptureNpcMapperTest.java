package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Existing source mapper SQL on isolated H2 fixtures, not production schema/isolation certification. */
class ContentCaptureNpcMapperTest {
    @Test void enabledMapperFiltersScopeAuditDeletionAndUsesStableOrder() throws Exception {
        Fixture f=new Fixture();
        f.npc(901,2,700,1,1,"0",2);f.npc(902,2,700,1,1,"0",1);f.npc(903,2,700,1,1,"0",1);
        f.npc(904,2,70,1,1,"0",0);f.npc(905,4,700,1,1,"0",0);
        f.npc(906,2,700,0,1,"0",0);f.npc(907,2,700,1,0,"0",0);f.npc(908,2,700,1,1,"2",0);
        try(SqlSession session=f.factory.openSession(true)) {
            List<NpcProfile> result=session.getMapper(NpcProfileMapper.class).selectEnabledByScope(2,700L);
            assertEquals(Arrays.asList(902L,903L,901L),result.stream().map(NpcProfile::getId).collect(Collectors.toList()));
        }
    }
    @Test void realMapperResolvesOwnedMerchantIdAndNeverAdmitsNumericMemberAlias() throws Exception {
        Fixture f=new Fixture();f.merchant(700,70,1,1,0);f.merchant(70,71,1,1,0);
        f.npc(901,2,700,1,1,"0",0);f.npc(900,2,70,1,1,"0",0);f.npc(902,4,1,1,1,"0",0);
        ContentCaptureRows rows=TopicPublicationValidationContextTest.clean();rows.nodes.get(0).setMerchantMemberId(70L);
        f.capture(rows);assertEquals(new HashSet<>(Arrays.asList(901L,902L)),ContentCaptureNpcEvidence.availableIds(rows));
        assertEquals(700L,rows.npcMerchants.get(70L).getId());
        String json=new ContentCaptureBuilder().build(rows).serverOnlyJson();assertTrue(json.contains("npc_profile:901"));assertFalse(json.contains("npc_profile:900"));
    }
    @Test void realMapperAmbiguityIsRejectedRatherThanArbitraryMerchantSelection() throws Exception {
        Fixture f=new Fixture();f.merchant(700,70,1,1,0);f.merchant(701,70,1,1,0);
        ContentCaptureRows rows=TopicPublicationValidationContextTest.clean();rows.nodes.get(0).setMerchantMemberId(70L);
        assertThrows(IllegalArgumentException.class,()->f.capture(rows));
    }
    @Test void realMapperUnavailableMerchantProducesNoNpcAuthority() throws Exception {
        Fixture f=new Fixture();f.merchant(700,70,1,2,0);f.npc(901,2,700,1,1,"0",0);
        ContentCaptureRows rows=TopicPublicationValidationContextTest.clean();rows.nodes.get(0).setMerchantMemberId(70L);f.capture(rows);
        assertTrue(ContentCaptureNpcEvidence.availableIds(rows).isEmpty());
        assertTrue(new ContentCaptureBuilder().build(rows).serverOnlyJson().contains("\"merchantAvailability\":\"UNAVAILABLE\""));
    }
    private static class Fixture {
        final DataSource source; final JdbcTemplate jdbc; final SqlSessionFactory factory;
        Fixture() throws Exception {
            source=new DriverManagerDataSource("jdbc:h2:mem:npc-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(source);
            jdbc.execute("create table npc_profile(id bigint primary key,scope_type int,scope_id bigint,name varchar(32),enabled int,audit_status int,del_flag varchar(1),sort int)");
            // Explicit simplified H2 projection fixture, generated from the existing mapper's SELECT list.
            String xml=resource("mapper/business/MmsMerchantMapper.xml");String fragment=xml.substring(xml.indexOf("<sql id=\"selectMmsMerchantVo\">"));
            String columns=fragment.substring(fragment.indexOf("select ")+7,fragment.indexOf(" from mms_merchant"));
            StringBuilder ddl=new StringBuilder("create table mms_merchant(");boolean first=true;
            for(String column:columns.split(",")) {if(!first)ddl.append(',');first=false;column=column.trim();ddl.append(column).append(" ");
                if(Arrays.asList("id","member_id","status","account_status","del_flag").contains(column))ddl.append("bigint");
                else if(column.endsWith("_time")&&(column.equals("create_time")||column.equals("update_time")))ddl.append("timestamp");
                else ddl.append("varchar(2048)");}
            jdbc.execute(ddl.append(", primary key(id))").toString());
            Configuration config=new Configuration(new Environment("npc-h2",new SpringManagedTransactionFactory(),source));
            config.getTypeAliasRegistry().registerAlias("MmsMerchant",MmsMerchant.class);
            for(String path:Arrays.asList("mapper/business/MmsMerchantMapper.xml","mapper/business/NpcProfileMapper.xml"))
                try(InputStream stream=getClass().getClassLoader().getResourceAsStream(path)){new XMLMapperBuilder(stream,config,path,config.getSqlFragments()).parse();}
            factory=new SqlSessionFactoryBuilder().build(config);
        }
        void npc(long id,int scope,long source,int enabled,int audit,String deleted,int sort){jdbc.update("insert into npc_profile values(?,?,?,?,?,?,?,?)",id,scope,source,"Character",enabled,audit,deleted,sort);}
        void merchant(long id,long member,int status,int account,int deleted){jdbc.update("insert into mms_merchant(id,member_id,status,account_status,del_flag) values(?,?,?,?,?)",id,member,status,account,deleted);}
        void capture(ContentCaptureRows rows){TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.execute(status->{ContentCaptureNpcEvidence.capture(new SqlSessionTemplate(factory),rows);return null;});}
        String resource(String path)throws Exception {try(InputStream in=getClass().getClassLoader().getResourceAsStream(path)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
    }
}

package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.sql.Connection;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real production provenance SQL and real body SQL in one session, isolated readSources only.
 * H2 is NOT MySQL READ ONLY/RR/concurrency acceptance; preview coordinator has separate behavior tests.
 * Other source statements are minimal SQL fixtures, not production CMS mapper integration.
 */
class ContentCaptureProvenanceSessionTest {
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private ConsistentContentCapture capture;
    private Probe probe;
    private ContentCaptureRows rows;

    @BeforeEach void setup() throws Exception {
        source=new DriverManagerDataSource("jdbc:h2:mem:capture_provenance_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(source);
        jdbc.execute("create table cms_member_template(id bigint primary key,w18_provenance_kind varchar(32),question_answer varchar(128))");
        jdbc.execute("create table w18_cms_template_provenance(template_id bigint primary key)");
        jdbc.update("insert into cms_member_template values (101,null,'private fixture body')");
        Configuration config=new Configuration(new Environment("same-session",new SpringManagedTransactionFactory(),source));
        config.setMapUnderscoreToCamelCase(true);
        String resource="mapper/business/LegacyCmsProvenanceMapper.xml";
        try(InputStream input=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input); new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();
        }
        statement(config,CmsTopicNodeMapper.class,"selectCmsTopicNodeList",CmsTopicNode.class,
                "select 1 as id,10 as topic_id,101 as template_id,1 as status,1 as sort_id");
        statement(config,CmsMemberTemplateMapper.class,"selectCmsMemberTemplateByIds",CmsMemberTemplate.class,
                "select id,question_answer from cms_member_template where id=101");
        statement(config,CmsTopicChapterMapper.class,"selectCmsTopicChapterList",CmsTopicChapter.class,"select 1 where 1=0");
        statement(config,ThemeStateKeyMapper.class,"selectByTopicId",ThemeStateKey.class,"select 1 where 1=0");
        statement(config,CmsRegistrationMerchantMapper.class,"selectCmsRegistrationMerchantList",CmsRegistrationMerchant.class,"select 1 where 1=0");
        statement(config,SmsCouponMapper.class,"selectSmsCouponById",SmsCoupon.class,"select 1 where 1=0");
        statement(config,TopicSaleSnapshotMapper.class,"selectSnapshotByTopicId",TopicSaleSnapshot.class,"select 1 where 1=0");
        statement(config,NpcProfileMapper.class,"selectEnabledByScope",NpcProfile.class,"select 1 where 1=0");
        statement(config,MmsMerchantMapper.class,"selectMmsMerchantList",MmsMerchant.class,"select 1 where 1=0");
        probe=new Probe();config.addInterceptor(probe);
        capture=new ConsistentContentCapture(new SqlSessionFactoryBuilder().build(config));
        rows=new ContentCaptureRows();rows.topic=new CmsTopic();rows.topic.setId(10L);rows.topic.setConfigVersion(1L);
    }
    @AfterEach void cleanup() { if(jdbc!=null) jdbc.execute("SHUTDOWN"); }
    private void read() {
        // Deliberately does not invoke preview's MySQL SET TRANSACTION READ ONLY on H2.
        new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{
            ReflectionTestUtils.invokeMethod(capture,"readSources",rows);return null;
        });
    }
    @Test void legacyGuardAndBodyUseSameConnectionTransactionAndSqlSession() {
        read();
        assertEquals("private fixture body",rows.templates.get(101L).getQuestionAnswer());
        assertNotNull(probe.guardConnection);assertSame(probe.guardConnection,probe.bodyConnection);
        assertSame(probe.guardExecutor,probe.bodyExecutor);
        assertTrue(probe.guardBeforeBody);
    }
    @Test void anyNonNullMarkerBlocksBeforeBody() {
        jdbc.update("update cms_member_template set w18_provenance_kind='unknown-marker' where id=101");
        assertThrows(ServiceException.class,this::read);
        assertNull(probe.bodyConnection);assertTrue(rows.templates.isEmpty());
    }
    @Test void nullMarkerWithSidecarStillBlocksBeforeBody() {
        jdbc.update("insert into w18_cms_template_provenance values (101)");
        assertThrows(ServiceException.class,this::read);
        assertNull(probe.bodyConnection);assertTrue(rows.templates.isEmpty());
    }
    @Test void absentCanonicalRowBlocksBeforeBody() {
        jdbc.update("delete from cms_member_template where id=101");
        assertThrows(ServiceException.class,this::read);
        assertNull(probe.bodyConnection);assertTrue(rows.templates.isEmpty());
    }
    @Test void missingSchemaFailsWithoutBodyFallback() {
        jdbc.execute("drop table w18_cms_template_provenance");
        assertThrows(RuntimeException.class,this::read);
        assertNull(probe.bodyConnection);assertTrue(rows.templates.isEmpty());
    }
    private static void statement(Configuration c,Class<?> mapper,String method,Class<?> type,String sql) {
        if(!c.hasMapper(mapper))c.addMapper(mapper);
        String id=mapper.getName()+"."+method;
        ResultMap result=new ResultMap.Builder(c,id+".row",type,Collections.emptyList()).build();
        c.addMappedStatement(new MappedStatement.Builder(c,id,new StaticSqlSource(c,sql),SqlCommandType.SELECT)
                .resultMaps(Collections.singletonList(result)).build());
    }
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    static class Probe implements Interceptor {
        Connection guardConnection,bodyConnection;Object guardExecutor,bodyExecutor;boolean guardBeforeBody;
        public Object intercept(Invocation call)throws Throwable {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            Executor executor=(Executor)call.getTarget();String id=((MappedStatement)call.getArgs()[0]).getId();
            if(id.endsWith(".selectLegacyBlockState")){guardConnection=executor.getTransaction().getConnection();guardExecutor=executor;}
            if(id.endsWith(".selectCmsMemberTemplateByIds")) {
                guardBeforeBody=guardConnection!=null;bodyConnection=executor.getTransaction().getConnection();bodyExecutor=executor;
            }
            return call.proceed();
        }
        public Object plugin(Object target){return Plugin.wrap(target,this);}
        public void setProperties(Properties ignored){}
    }
}

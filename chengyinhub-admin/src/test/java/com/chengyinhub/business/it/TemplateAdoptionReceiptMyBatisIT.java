package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.business.domain.vo.TemplateLibraryVO;
import com.chengyinhub.business.domain.dto.TemplateAdoptionCommand;
import com.chengyinhub.business.mapper.TemplateAdoptionReceiptMapper;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.business.service.support.TemplateAdoptionService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real MySQL + actual Mapper + actual receipt service/transactions. No availability skips.
 * Draft delegate inserts a fixture in the same transaction; it does not certify all existing draft validation. */
class TemplateAdoptionReceiptMyBatisIT {
    @Test void concurrencyLostAckConflictSourceChangeAndRollback() throws Exception {
        var ds = new DriverManagerDataSource(MybatisItDatabase.url("template_adoption_receipt"), MybatisItDatabase.user(), MybatisItDatabase.password());
        var jdbc = new JdbcTemplate(ds);
        try (var connection = ds.getConnection()) {
            ItSchema.applyMigrations(connection, "sql/migration_20261027_template_adoption_receipt.sql");
        }
        createFixtureTable(jdbc,"CmsTemplateLibraryMapper","CmsTemplateLibraryResult","cms_template_library");
        createFixtureTable(jdbc,"CmsMemberTemplateMapper","CmsMemberTemplateResult","cms_member_template");
        jdbc.update("delete from template_adoption_receipt"); jdbc.update("delete from cms_member_template"); jdbc.update("delete from cms_template_library");
        jdbc.update("insert into cms_template_library(id,title,question_answer,status,publish_status,del_flag) values(7,'original','answer',1,1,0)");
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(ds);
        factory.setTypeAliases(CmsTemplateLibrary.class, TemplateLibraryVO.class, CmsMemberTemplate.class, MemberTemplateVO.class);
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsTemplateLibraryMapper.xml"), new ClassPathResource("mapper/business/CmsMemberTemplateMapper.xml"), new ClassPathResource("mapper/business/TemplateAdoptionReceiptMapper.xml"));
        var session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        var mapper = session.getMapper(TemplateAdoptionReceiptMapper.class);
        var drafts = mock(ITemplatePublishService.class); AtomicLong serial = new AtomicLong(9007199254740992L); AtomicBoolean fail = new AtomicBoolean();
        when(drafts.saveOrUpdateDraft(any(), any(), any())).thenAnswer(call -> {
            long id = serial.incrementAndGet(); jdbc.update("insert into cms_member_template(id,original_template_id,member_id,title,question_answer) values(?,7,101,'original','answer')", id);
            if (fail.get()) throw new IllegalStateException("injected after draft insert"); return id;
        });
        var service = new TemplateAdoptionService(mapper, drafts, new DataSourceTransactionManager(ds));
        String version = (String) service.source(101L,101L,"7").get("sourceVersion");
        TemplateAdoptionCommand command = command("adopt_concurrent_001",version);
        ExecutorService pool = Executors.newFixedThreadPool(4); CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<Map<String,Object>>> futures = new ArrayList<>();
            for (int i=0;i<4;i++) futures.add(pool.submit(()->{gate.await();return service.adopt(101L,101L,command,"test");}));
            gate.countDown(); for (var future:futures) assertEquals("9007199254740993",future.get(15,TimeUnit.SECONDS).get("draftId"));
        } finally { pool.shutdownNow(); }
        assertEquals(1,jdbc.queryForObject("select count(*) from cms_member_template",Integer.class));
        assertEquals("COMPLETED",service.receipt(101L,101L,"",command.getRequestId()).get("status"));
        // Lost ACK + source removed: completion still replays, without reading/recreating source.
        jdbc.update("update cms_template_library set publish_status=0 where id=7");
        assertEquals("9007199254740993",service.adopt(101L,101L,command,"test").get("draftId"));
        var completed = service.receipt(101L,101L,"",command.getRequestId());
        assertTrue(service.matchesDraft(completed,mapper.lockDraft(9007199254740993L)));
        jdbc.update("update cms_member_template set question_answer='edited' where id=9007199254740993");
        assertFalse(service.matchesDraft(completed,mapper.lockDraft(9007199254740993L)), "Do not silently bind a later edited draft");
        verify(drafts,times(1)).saveOrUpdateDraft(any(),any(),any());
        assertThrows(ServiceException.class,()->service.adopt(101L,101L,command(command.getRequestId(),"b".repeat(64)),"test"));
        assertThrows(ServiceException.class,()->service.receipt(101L,102L,"",command.getRequestId()));
        assertEquals("UNKNOWN",service.receipt(102L,102L,"",command.getRequestId()).get("status"));
        assertEquals("REJECTED",service.adopt(101L,101L,command("adopt_source_changed",version),"test").get("status"));
        jdbc.update("update cms_template_library set publish_status=1 where id=7");
        fail.set(true); TemplateAdoptionCommand rollback = command("adopt_rollback_001",version);
        assertThrows(IllegalStateException.class,()->service.adopt(101L,101L,rollback,"test"));
        assertEquals(1,jdbc.queryForObject("select count(*) from cms_member_template",Integer.class));
        assertEquals("PENDING",service.receipt(101L,101L,"",rollback.getRequestId()).get("status"));
        fail.set(false); assertEquals("COMPLETED",service.adopt(101L,101L,rollback,"test").get("status"));
        assertEquals(2,jdbc.queryForObject("select count(*) from cms_member_template",Integer.class));
        jdbc.update("insert into cms_member_template(id,member_id,title,interaction_type,status,del_flag) values(800,999,'canonical','A',1,0)");
        jdbc.update("update cms_template_library set member_template_id=800,member_id=999 where id=7");
        String canonicalVersion=(String)service.source(101L,101L,"7").get("sourceVersion");
        jdbc.update("update cms_member_template set interaction_type='B' where id=800");
        assertEquals("REJECTED",service.adopt(101L,101L,command("adopt_canonical_changed",canonicalVersion),"test").get("status"));
        String lockedVersion=(String)service.source(101L,101L,"7").get("sourceVersion");
        CountDownLatch creating=new CountDownLatch(1),release=new CountDownLatch(1),editing=new CountDownLatch(1);
        when(drafts.saveOrUpdateDraft(any(),any(),any())).thenAnswer(call->{
            creating.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));long id=serial.incrementAndGet();
            jdbc.update("insert into cms_member_template(id,member_id,original_template_id,title) values(?,101,7,'copy')",id);return id;
        });
        ExecutorService race=Executors.newFixedThreadPool(2);
        try {
            Future<?> adopting=race.submit(()->service.adopt(101L,101L,command("adopt_lock_canonical",lockedVersion),"test"));
            assertTrue(creating.await(5,TimeUnit.SECONDS));
            Future<?> writer=race.submit(()->{editing.countDown();jdbc.update("update cms_member_template set interaction_type='C' where id=800");});
            assertTrue(editing.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->writer.get(200,TimeUnit.MILLISECONDS));
            release.countDown();adopting.get(5,TimeUnit.SECONDS);writer.get(5,TimeUnit.SECONDS);
        } finally {release.countDown();race.shutdownNow();}
    }
    private void createFixtureTable(JdbcTemplate jdbc,String mapper,String resultMap,String table) throws Exception {
        String xml=new ClassPathResource("mapper/business/"+mapper+".xml").getContentAsString(StandardCharsets.UTF_8);
        var section=java.util.regex.Pattern.compile("<resultMap[^>]*id=\""+resultMap+"\"[^>]*>([\\s\\S]*?)</resultMap>").matcher(xml);
        assertTrue(section.find());
        var columns=java.util.regex.Pattern.compile("column=\"([^\"]+)\"").matcher(section.group(1));
        Set<String> ddl=new LinkedHashSet<>();
        while(columns.find()){String column=columns.group(1);ddl.add(column+(column.equals("id")?" bigint primary key":" longtext null"));}
        jdbc.execute("create table if not exists "+table+"("+String.join(",",ddl)+") engine=InnoDB");
    }
    private TemplateAdoptionCommand command(String key,String version) {
        var c=new TemplateAdoptionCommand();c.setRequestId(key);c.setOriginalTemplateId("7");c.setSourceVersion(version);c.setScope("");return c;
    }
}

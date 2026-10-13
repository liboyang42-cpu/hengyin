package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.support.ConfigDraftSource;
import com.chengyinhub.common.constant.ProductType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.io.*;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 授权 fixture 库内独占随机表，真实 Mapper/事务；非 HTTP 或真实身份验收。 */
@EnabledIfEnvironmentVariable(named="CHENGYIN_TEST_MYSQL_DATABASE", matches=".+")
class ConfigNewDraftMySqlIT {
    private static PooledDataSource db;
    private static SqlSessionTemplate sql;
    private static JdbcTemplate jdbc;
    private static final String PREFIX="w2_config_"+UUID.randomUUID().toString().replace("-", "").substring(0,8)+"_";
    private static final Map<String,String> TABLES=new LinkedHashMap<>();
    private static final List<String> CREATED=new ArrayList<>();
    private CmsTopicServiceImpl target;
    private ICmsTopicService service;
    private CmsTopicMapper topics;
    private CmsTopicChapterMapper chapters;
    private CmsTopicNodeMapper nodes;
    private CmsMemberTemplateMapper members;
    private Long canonicalId;
    private ICmsTemplateLibraryService publicLibrary;
    private final String source="{\"schemaVersion\":1,\"capacity\":6,\"configSource\":{\"schemaVersion\":1,\"route\":{\"id\":7,\"updatedAt\":\"v1\",\"name\":\"原路线\",\"nodes\":[{\"id\":10,\"playTemplateId\":77}]}}}";

    @BeforeAll static void database() throws Exception {
        String database=System.getenv("CHENGYIN_TEST_MYSQL_DATABASE");
        assertTrue(database.toLowerCase(Locale.ROOT).contains("w2") && !database.equals("chengyinhubdb"));
        String url="jdbc:mysql://"+System.getenv("CHENGYIN_TEST_MYSQL_HOST")+":"+System.getenv("CHENGYIN_TEST_MYSQL_PORT")+"/"+database+"?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
        db=new PooledDataSource("com.mysql.cj.jdbc.Driver",url,System.getenv("CHENGYIN_TEST_MYSQL_USER"),System.getenv("CHENGYIN_TEST_MYSQL_PASSWORD"));
        jdbc=new JdbcTemplate(db);
        for(String table:Arrays.asList("cms_topic","cms_topic_chapter","cms_topic_node","cms_member_template","merchant_chapter_offer")) TABLES.put(table,PREFIX+table);
        try(Connection c=db.getConnection()) {
            assertEquals(database,c.getCatalog()); assertTrue(c.getMetaData().getDatabaseProductVersion().startsWith("8.0"));
            for(String resource:Arrays.asList("it-schema/cms-topic-it-schema.sql","it-schema/journey-full-flow-it-schema.sql")) {
                List<String> statements=new ArrayList<>();
                org.springframework.jdbc.datasource.init.ScriptUtils.splitSqlScript(read(resource), ";", statements);
                for(Map.Entry<String,String> entry:TABLES.entrySet()) {
                    for(String ddl:statements) if(ddl.matches("(?is)CREATE TABLE `"+entry.getKey()+"`\\s*\\(.*")) {
                        jdbc.execute(ddl.replace("`"+entry.getKey()+"`","`"+entry.getValue()+"`")); CREATED.add(entry.getValue());
                    }
                }
            }
        }
        jdbc.execute("ALTER TABLE `"+TABLES.get("merchant_chapter_offer")+"` ADD withdraw_reason VARCHAR(500) DEFAULT NULL");
        Configuration cfg=new Configuration(); cfg.setEnvironment(new Environment("config",new SpringManagedTransactionFactory(),db));
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for(String mapper:Arrays.asList("CmsTopic","CmsTopicChapter","CmsTopicNode","CmsMemberTemplate")) {
            String resource="mapper/business/"+mapper+"Mapper.xml", xml=read(resource);
            for(Map.Entry<String,String> entry:TABLES.entrySet()) xml=xml.replaceAll("\\b"+entry.getKey()+"\\b",entry.getValue());
            try(InputStream in=new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) { new XMLMapperBuilder(in,cfg,resource,cfg.getSqlFragments()).parse(); }
        }
        sql=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));
    }
    private static String read(String path) throws Exception {
        try(InputStream in=ConfigNewDraftMySqlIT.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in,path); ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] bytes=new byte[4096]; int length;
            while((length=in.read(bytes))>=0)out.write(bytes,0,length); return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    @AfterAll static void close() {
        if(jdbc!=null)for(String table:CREATED)jdbc.execute("DROP TABLE `"+table+"`");
        if(db!=null)db.forceCloseAll();
    }
    @BeforeEach void services() throws Exception {
        for(String table:CREATED)jdbc.update("DELETE FROM `"+table+"`");
        topics=sql.getMapper(CmsTopicMapper.class); chapters=sql.getMapper(CmsTopicChapterMapper.class);
        nodes=sql.getMapper(CmsTopicNodeMapper.class); members=sql.getMapper(CmsMemberTemplateMapper.class);
        CmsMemberTemplate canonical=new CmsMemberTemplate(); canonical.setMemberId(7L); canonical.setTitle("公共玩法");
        canonical.setStatus(1L); canonical.setPublishStatus(1L); canonical.setDelFlag(0); canonical.setDraftStatus(1);
        canonical.setValidationMethod(1); canonical.setQuestionAnswer("旧答案"); members.insertCmsMemberTemplate(canonical); canonicalId=canonical.getId();
        CmsTemplateLibrary projection=new CmsTemplateLibrary(); projection.setId(77L); projection.setMemberTemplateId(canonicalId);
        projection.setStatus(1L); projection.setPublishStatus(1L); projection.setDelFlag(0);
        publicLibrary=mock(ICmsTemplateLibraryService.class); when(publicLibrary.selectCmsTemplateLibraryById(77L)).thenReturn(projection);
        CmsMemberTemplateServiceImpl memberService=new CmsMemberTemplateServiceImpl(); ReflectionTestUtils.setField(memberService,"cmsMemberTemplateMapper",members);
        TemplatePublishServiceImpl templateService=new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(templateService,"memberTemplateService",memberService);
        ReflectionTestUtils.setField(templateService,"templateLibraryService",publicLibrary);
        ReflectionTestUtils.setField(templateService,"capabilityGuard",mock(ICapabilityGuard.class));
        target=new CmsTopicServiceImpl();
        for(Field f:CmsTopicServiceImpl.class.getDeclaredFields()) {
            if(!f.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class))continue;
            f.setAccessible(true); if(f.get(target)==null)f.set(target,mock(f.getType()));
        }
        ReflectionTestUtils.setField(target,"cmsTopicMapper",topics); ReflectionTestUtils.setField(target,"cmsTopicChapterMapper",chapters);
        ReflectionTestUtils.setField(target,"cmsTopicNodeMapper",nodes); ReflectionTestUtils.setField(target,"cmsMemberTemplateMapper",members);
        ReflectionTestUtils.setField(target,"configTemplatePublishService",templateService);
        ProxyFactory proxy=new ProxyFactory(target); proxy.setInterfaces(ICmsTopicService.class);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db),new AnnotationTransactionAttributeSource()));
        service=(ICmsTopicService)proxy.getProxy();
    }
    private TopicCreateDTO draft() {
        TopicCreateDTO dto=new TopicCreateDTO(); dto.setName("独立路线"); dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setPublishMode("pro"); dto.setSaveDraft(1); dto.setThemeRuntimeConfig(source);
        dto.setTickets(Collections.emptyList()); dto.setCollaboratorIds(Collections.emptyList());
        ChapterDTO chapter=new ChapterDTO(); chapter.setName("独立章"); List<NodeDTO> list=new ArrayList<>();
        for(int i=0;i<2;i++){NodeDTO node=new NodeDTO();node.setName("站"+i);node.setNodeTime(0);node.setSourceLibraryTemplateId(77L);node.setClientNodeKey("key"+i);list.add(node);}
        chapter.setNodes(list);dto.setChapters(Collections.singletonList(chapter));return dto;
    }
    @Test void createReadReopenAndPublishedSnapshotRemainIndependentOfUpdatedPublicSource() throws Exception {
        Long id=service.createTopicV2(draft(),42L,"fixture");
        CmsTopic reopened=service.selectCmsTopicById(id); assertEquals(42L,reopened.getMemberId()); assertEquals(1,topics.selectTopicIsDraft(id));
        CmsTopicChapter query=new CmsTopicChapter();query.setTopicId(id);List<CmsTopicChapter> writtenChapters=chapters.selectCmsTopicChapterList(query);
        assertEquals(1,writtenChapters.size()); CmsTopicNode nodeQuery=new CmsTopicNode();nodeQuery.setTopicId(id);
        List<CmsTopicNode> writtenNodes=nodes.selectCmsTopicNodeList(nodeQuery);assertEquals(2,writtenNodes.size());
        Long copiedId=writtenNodes.get(0).getTemplateId(); assertNotEquals(canonicalId,copiedId);assertNotEquals(77L,copiedId);
        assertEquals(copiedId,writtenNodes.get(1).getTemplateId()); assertNotEquals(writtenNodes.get(0).getId(),writtenNodes.get(1).getId());
        assertEquals(writtenChapters.get(0).getId(),writtenNodes.get(0).getChapterId());
        assertEquals(42L,members.selectCmsMemberTemplateById(copiedId).getMemberId());
        assertEquals(copiedId.longValue(),new ObjectMapper().readTree(reopened.getThemeRuntimeConfigJson()).path("configSource").path("templateCopies").path("77").asLong());
        CmsMemberTemplate changed=new CmsMemberTemplate();changed.setId(canonicalId);changed.setQuestionAnswer("新答案");members.updateCmsMemberTemplate(changed);
        assertEquals("旧答案",members.selectCmsMemberTemplateById(copiedId).getQuestionAnswer());
        assertEquals(reopened.getThemeRuntimeConfigJson(),service.selectCmsTopicById(id).getThemeRuntimeConfigJson());
        clearInvocations(publicLibrary);
        TopicUpdateDTO update=new TopicUpdateDTO();update.setId(id);update.setName(reopened.getName());
        update.setProductType(ProductType.FREE_EXPLORE);update.setPublishMode("pro");update.setSaveDraft(1);
        update.setConfigVersion(reopened.getConfigVersion());update.setRouteMode("LINEAR");
        update.setTickets(Collections.emptyList());update.setCollaboratorIds(Collections.emptyList());
        ChapterDTO chapter=new ChapterDTO();chapter.setId(writtenChapters.get(0).getId());chapter.setName("独立章");
        List<NodeDTO> copiedNodes=new ArrayList<>();
        for(CmsTopicNode original:writtenNodes){NodeDTO node=new NodeDTO();node.setId(original.getId());node.setName(original.getName());node.setNodeTime(0);node.setTemplateId(copiedId);copiedNodes.add(node);}
        chapter.setNodes(copiedNodes);update.setChapters(Collections.singletonList(chapter));
        // 未传运行配置的旧客户端也不能抹掉来源；重建只重映射本人草稿节点。
        service.updateTopicByOwnerV2(update,42L,"fixture");
        assertEquals(reopened.getThemeRuntimeConfigJson(),service.selectCmsTopicById(id).getThemeRuntimeConfigJson());
        assertEquals(reopened.getConfigVersion()+1,service.selectCmsTopicById(id).getConfigVersion());
        List<CmsTopicNode> rebuilt=nodes.selectCmsTopicNodeList(nodeQuery);
        assertNotEquals(writtenNodes.get(0).getId(),rebuilt.get(0).getId());assertEquals(copiedId,rebuilt.get(0).getTemplateId());
        verifyNoInteractions(publicLibrary);
        assertEquals(1,topics.updateTopicDraftState(id,0)); assertEquals(1,topics.publishRouteSnapshot(id,null,new Date()));
        String published=topics.selectCmsTopicById(id).getPublishedThemeRuntimeConfigJson();
        assertEquals(reopened.getThemeRuntimeConfigJson(),published);
        assertThrows(IllegalArgumentException.class,()->ConfigDraftSource.assertSourceUnchanged(published,published.replace("原路线","更新路线")));
        CmsTopic working=new CmsTopic();working.setId(id);working.setThemeRuntimeConfigJson(published.replace("原路线","待编辑版本"));topics.updateCmsTopic(working);
        assertEquals(published,topics.selectCmsTopicById(id).getPublishedThemeRuntimeConfigJson());
    }
    @Test void failedTopicSaveRollsBackPrivateCopyChapterAndFirstStationInSameTransaction() {
        CmsTopicNodeMapper failing=mock(CmsTopicNodeMapper.class,org.mockito.AdditionalAnswers.delegatesTo(nodes));
        doThrow(new IllegalStateException("injected station failure")).when(failing).insertCmsTopicNode(argThat(n->"站1".equals(n.getName())));
        ReflectionTestUtils.setField(target,"cmsTopicNodeMapper",failing);
        assertThrows(RuntimeException.class,()->service.createTopicV2(draft(),42L,"fixture"));
        for(String table:Arrays.asList("cms_topic","cms_topic_chapter","cms_topic_node")) assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM `"+TABLES.get(table)+"`",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM `"+TABLES.get("cms_member_template")+"`",Integer.class));
    }
}

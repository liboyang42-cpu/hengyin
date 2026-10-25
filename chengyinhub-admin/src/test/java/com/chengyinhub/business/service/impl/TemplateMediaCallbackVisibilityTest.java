package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.WxContentCallbackClaim;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.domain.vo.TemplateLibraryVO;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.redis.RedisCache;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CL-39：模板封面/勋章图(BIZ_TYPE_TEMPLATE=13)的异步机审结论必须落地。
 *
 * <p>ApiTemplateController.submitTemplateMediaCheck 在模板发布后对封面/勋章图 submitMediaCheck(13, memberTemplateId)。
 * 回调链：WxContentSecurityServiceImpl.handleMediaCheckResult → inbox.process →
 * WxContentCallbackMutationService.apply → lockBusinessTarget(13) → isCurrentSubmittedMedia → processMediaCheckResult。
 * 公开读路径：/api/template/list 与 /homeData(selectCmsTemplateLibraryList + del_flag=0,status=1,publish_status=1)、
 * /api/template/info(Java 侧同三条件)、/groupByCategory(selectTemplatesByCategoryIds)。
 * 这里用真 MyBatis XML 跑在 H2 上，回调后按这四条读路径的同一谓词复查。
 */
class TemplateMediaCallbackVisibilityTest {

    private static final long MEMBER_TEMPLATE_ID = 5L;
    private static final long PROJECTION_ID = 50L;
    private static final String COVER = "https://cdn.example/cover.jpg";
    private static final String MEDAL = "https://cdn.example/medal.png";

    private JdbcTemplate jdbc;
    private SqlSession session;
    private CmsMemberTemplateMapper memberTemplateMapper;
    private WxContentCallbackInboxMapper inboxMapper;
    private WxContentSecurityServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:template_media_callback_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        jdbc = new JdbcTemplate(ds);
        seed();
        session = factory(ds).openSession(true);

        memberTemplateMapper = mock(CmsMemberTemplateMapper.class);
        when(memberTemplateMapper.selectCmsMemberTemplateById(MEMBER_TEMPLATE_ID)).thenReturn(memberTemplate(COVER, MEDAL));
        inboxMapper = mock(WxContentCallbackInboxMapper.class);
        when(inboxMapper.lockBusinessTarget(IWxContentSecurityService.BIZ_TYPE_TEMPLATE, MEMBER_TEMPLATE_ID))
                .thenReturn(MEMBER_TEMPLATE_ID);
        when(inboxMapper.markDone(anyString(), anyString(), anyLong(), anyInt())).thenReturn(1);
        when(inboxMapper.markIgnoredStale(anyString(), anyString(), anyLong(), anyInt())).thenReturn(1);

        service = new WxContentSecurityServiceImpl();
        ReflectionTestUtils.setField(service, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(service, "cmsMemberTemplateMapper", memberTemplateMapper);
        ReflectionTestUtils.setField(service, "cmsTemplateLibraryMapper", session.getMapper(CmsTemplateLibraryMapper.class));
        ReflectionTestUtils.setField(service, "auditTaskMapper", session.getMapper(AuditTaskMapper.class));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.chengyinhub.business.service.IAuditTaskService> auditTasks =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(auditTasks.getObject()).thenReturn(mock(com.chengyinhub.business.service.IAuditTaskService.class));
        ReflectionTestUtils.setField(service, "auditTaskServiceProvider", auditTasks);
    }

    @AfterEach
    void tearDown() {
        session.close();
    }

    @Test
    void templateCallbackLocksTheMemberTemplateRowForUpdate() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/business/WxContentCallbackInboxMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("bizType", IWxContentSecurityService.BIZ_TYPE_TEMPLATE);
        params.put("bizId", MEMBER_TEMPLATE_ID);
        String sql = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.WxContentCallbackInboxMapper.lockBusinessTarget")
                .getBoundSql(params).getSql().trim();
        assertEquals("select id from cms_member_template where id = ? for update", sql);
        assertEquals(MEMBER_TEMPLATE_ID, jdbc.queryForObject(sql, Long.class, MEMBER_TEMPLATE_ID),
                "the lock resolves to the member template row, so the mutation service no longer throws");
    }

    @Test
    void riskyVerdictUnlistsTheTemplateFromListInfoHomeDataAndGroupByCategory() {
        assertEquals(List.of("approved"), publicTitles());

        applyCallback("trace-risky", hashOf(MEDAL), true);

        verify(inboxMapper).markDone(anyString(), anyString(), anyLong(), eq(1));
        assertEquals(List.of(), publicTitles(), "the risky template leaves every public read path");
        assertEquals("2|0|模板图片违规已自动下架", projectionState());
        assertEquals(4, jdbc.queryForObject("select status from audit_task where id = 1", Integer.class),
                "an open market review is cancelled so approval cannot put the image back");
        assertEquals(0, jdbc.queryForObject("select status from audit_task where id = 2", Integer.class),
                "other templates' review tasks are untouched");

        // 重复回调 = 已观测到目标态,幂等成功。
        applyCallback("trace-risky-again", hashOf(COVER), true);
        assertEquals("2|0|模板图片违规已自动下架", projectionState());
    }

    @Test
    void staleVerdictOnAReplacedTemplateImageChangesNothing() {
        when(memberTemplateMapper.selectCmsMemberTemplateById(MEMBER_TEMPLATE_ID))
                .thenReturn(memberTemplate("https://cdn.example/new-cover.jpg", MEDAL));

        applyCallback("trace-stale", hashOf(COVER), true);

        verify(inboxMapper).markIgnoredStale(anyString(), anyString(), anyLong(), eq(1));
        verify(inboxMapper, never()).markDone(anyString(), anyString(), anyLong(), anyInt());
        assertEquals(List.of("approved"), publicTitles());
        assertEquals("1|1|", projectionState());
        assertEquals(0, jdbc.queryForObject("select status from audit_task where id = 1", Integer.class));
    }

    @Test
    void passVerdictKeepsTheTemplateListed() {
        applyCallback("trace-pass", hashOf(COVER), false);

        verify(inboxMapper).markDone(anyString(), anyString(), anyLong(), eq(0));
        assertEquals(List.of("approved"), publicTitles());
        assertEquals("1|1|", projectionState());
    }

    /** 真实回调链：service 交给 inbox 的 Mutation 在 mutation 服务(锁目标 → 是否当前媒体 → 落地)里执行。 */
    private void applyCallback(String traceId, String mediaHash, boolean risky) {
        IWxContentCallbackInboxService inbox = mock(IWxContentCallbackInboxService.class);
        AtomicReference<IWxContentCallbackInboxService.Mutation> captured = new AtomicReference<>();
        when(inbox.isRegistered(traceId)).thenReturn(true);
        when(inbox.process(eq(traceId), eq(risky), any(IWxContentCallbackInboxService.Mutation.class)))
                .thenAnswer(invocation -> {
                    captured.set(invocation.getArgument(2, IWxContentCallbackInboxService.Mutation.class));
                    return true;
                });
        ReflectionTestUtils.setField(service, "callbackInboxService", inbox);
        assertTrue(service.handleMediaCheckResult(traceId, risky));

        WxContentCallbackInbox row = new WxContentCallbackInbox();
        row.setBizType(IWxContentSecurityService.BIZ_TYPE_TEMPLATE);
        row.setBizId(MEMBER_TEMPLATE_ID);
        row.setMediaHash(mediaHash);
        when(inboxMapper.selectOwnedForUpdate(anyString(), anyString(), anyLong())).thenReturn(row);
        WxContentCallbackMutationService mutationService = new WxContentCallbackMutationService();
        ReflectionTestUtils.setField(mutationService, "mapper", inboxMapper);
        assertTrue(mutationService.apply(WxContentCallbackClaim.acquired("hash", "owner", 1L), risky, captured.get()));
    }

    /** /list、/homeData、/info、/groupByCategory 四条公开读路径的并集(都必须看不到才算下架)。 */
    private List<String> publicTitles() {
        CmsTemplateLibraryMapper mapper = session.getMapper(CmsTemplateLibraryMapper.class);
        CmsTemplateLibrary gallery = new CmsTemplateLibrary();
        gallery.setDelFlag(0);
        gallery.setStatus(1L);
        gallery.setPublishStatus(1L);
        List<String> listAndHome = mapper.selectCmsTemplateLibraryList(gallery).stream()
                .map(CmsTemplateLibrary::getTitle).collect(Collectors.toList());
        List<String> grouped = mapper.selectTemplatesByCategoryIds(List.of(1L)).stream()
                .map(TemplateLibraryVO::getTitle).collect(Collectors.toList());
        CmsTemplateLibrary info = mapper.selectCmsTemplateLibraryById(PROJECTION_ID);
        boolean infoVisible = info != null && Integer.valueOf(0).equals(info.getDelFlag())
                && Long.valueOf(1L).equals(info.getStatus()) && Long.valueOf(1L).equals(info.getPublishStatus());
        assertEquals(listAndHome, grouped, "/list and /groupByCategory agree");
        assertEquals(!listAndHome.isEmpty(), infoVisible, "/list and /info agree");
        return listAndHome;
    }

    private String projectionState() {
        return jdbc.queryForObject("select concat(status,'|',publish_status,'|',coalesce(reason,'')) "
                + "from cms_template_library where id = " + PROJECTION_ID, String.class);
    }

    private static CmsMemberTemplate memberTemplate(String cover, String medal) {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(MEMBER_TEMPLATE_ID);
        template.setImgUrl(cover);
        template.setMedalImg(medal);
        return template;
    }

    private static String hashOf(String url) {
        return WxContentCallbackInboxServiceImpl.sha256Hex(url);
    }

    private void seed() {
        jdbc.execute("create table cms_member_template(id bigint primary key, member_id bigint, interaction_type varchar(32))");
        jdbc.update("insert into cms_member_template(id,member_id) values (" + MEMBER_TEMPLATE_ID + ",11)");
        jdbc.execute("create table cms_template_library("
                + "id bigint auto_increment primary key,"
                + "member_id bigint, member_template_id bigint, category_id bigint,"
                + "title varchar(255), description varchar(1000), img_url varchar(1000),"
                + "players varchar(64), usage_location varchar(255), required_materials varchar(1000),"
                + "duration bigint, difficulty varchar(64), is_sync int,"
                + "rule_instructions text, merchant_guide text, reward_rules_json text,"
                + "validation_method int, sensor_type varchar(64), sensor_config text,"
                + "question_name varchar(255), question_answer text, feedback_text text, coupon_id bigint,"
                + "story_text text, story_img varchar(1000), story_json text, hint1 text, hint2 text,"
                + "answer_reveal text, photo_require_desc text, photo_review int,"
                + "audio_url varchar(1000), audio_duration bigint,"
                + "question_a text, question_b text, question_c text, question_d text, correct_answer varchar(255),"
                + "question_img varchar(1000), question_audio varchar(1000),"
                + "question_option_media_json text, preference_json text, advanced_config_json text,"
                + "activity_categoryids varchar(255), status bigint, publish_status bigint, reason varchar(255),"
                + "del_flag int, rating decimal(10,2), use_num int, pack_type tinyint,"
                + "create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp)");
        jdbc.execute("create table sys_category(id bigint primary key,category_name varchar(64),icon varchar(255),del_flag int,status int)");
        jdbc.update("insert into sys_category values (1,'cat','',0,1)");
        jdbc.update("insert into cms_template_library(id,member_id,member_template_id,title,category_id,img_url,"
                + "status,publish_status,del_flag,use_num,create_time) values (?,11,?,'approved',1,?,1,1,0,0,now())",
                PROJECTION_ID, MEMBER_TEMPLATE_ID, COVER);
        jdbc.execute("create table audit_task(id bigint primary key, biz_type varchar(32), biz_id bigint,"
                + " status int, version int, update_time timestamp, source_type varchar(40))");
        // 1 = 本模板投影在途的广场复审单;2 = 别的模板投影的审核单。
        jdbc.update("insert into audit_task(id,biz_type,biz_id,status,version,update_time,source_type) values (1,'TEMPLATE_LIBRARY',?,0,0,now(),'template_market')", PROJECTION_ID);
        jdbc.update("insert into audit_task(id,biz_type,biz_id,status,version,update_time,source_type) values (2,'TEMPLATE_LIBRARY',99,0,0,now(),'template_market')");
    }

    private SqlSessionFactory factory(DriverManagerDataSource dataSource) throws Exception {
        Configuration configuration = new Configuration(new Environment(
                "template-media-callback", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain.vo");
        configuration.addMapper(CmsTemplateLibraryMapper.class);
        configuration.addMapper(AuditTaskMapper.class);
        for (String resource : new String[] {
                "mapper/business/CmsTemplateLibraryMapper.xml", "mapper/business/AuditTaskMapper.xml" }) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (in == null) throw new IllegalStateException("missing " + resource);
                new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}

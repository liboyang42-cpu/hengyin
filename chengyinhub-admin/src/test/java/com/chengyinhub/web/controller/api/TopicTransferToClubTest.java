package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 移交给俱乐部承接:复制一份新草稿,归属仍发起人本人,挂承接俱乐部 club_id,并下架原主题。
 * 安全点:①归属校验(IDOR)仅创建者本人可移交;②承接俱乐部合法性(须主理人或已通过合作邀约),防移交绕过 coop 门禁假挂他人俱乐部。
 */
class TopicTransferToClubTest {

    private ICmsTopicService cmsTopicService;
    private IClubService clubService;
    private IClubLeaderService clubLeaderService;
    private ICoopInviteService coopInviteService;

    @BeforeEach
    void setUp() {
        cmsTopicService = mock(ICmsTopicService.class);
        clubService = mock(IClubService.class);
        clubLeaderService = mock(IClubLeaderService.class);
        coopInviteService = mock(ICoopInviteService.class);
    }

    private ApiTopicController spyAs(long uid) {
        ApiTopicController c = org.mockito.Mockito.spy(new ApiTopicController());
        org.mockito.Mockito.doReturn(uid).when(c).getAppUserId();
        ReflectionTestUtils.setField(c, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(c, "clubService", clubService);
        ReflectionTestUtils.setField(c, "clubLeaderService", clubLeaderService);
        ReflectionTestUtils.setField(c, "coopInviteService", coopInviteService);
        return c;
    }

    @Test
    void owner_startsAtomicTransfer() {
        ApiTopicController owner = spyAs(9L);
        CmsTopic t = new CmsTopic(); t.setId(100L); t.setMemberId(9L); // 主题归属发起人 9
        when(cmsTopicService.selectCmsTopicById(100L)).thenReturn(t);
        // 承接俱乐部为本人主理(isLeaderOfOwnClub)→ 通过合法性校验
        Club club200 = new Club(); club200.setId(200L); club200.setMemberId(9L);
        when(clubService.selectClubById(200L)).thenReturn(club200);
        ClubLeader activeLeader = new ClubLeader(); activeLeader.setStatus(1);
        when(clubLeaderService.selectClubLeaderByMemberId(9L)).thenReturn(activeLeader);
        when(com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(cmsTopicService).transferToClub(100L, 9L, 200L)).thenReturn(555L);

        AjaxResult r = owner.transferToClub("100", "200", null);

        assertEquals(200, ((Integer) r.get("code")).intValue(), "本人移交应成功");
        org.mockito.Mockito.verify(cmsTopicService).transferToClub(100L, 9L, 200L);
        java.util.Map<?, ?> data = (java.util.Map<?, ?>) r.get("data");
        assertEquals(555L, data.get("newTopicId"));
    }

    @Test
    void transfer_persistsClubAndOfflinesOriginal() throws Exception {
        DriverManagerDataSource dataSource = dataSource("topic_transfer_success");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists cms_topic");
        jdbc.execute("create table cms_topic (id bigint primary key, user_status int, club_id bigint, lifecycle int, del_flag int, update_time timestamp)");
        jdbc.update("insert into cms_topic (id, user_status, lifecycle) values (100, 1, 1)");

        CmsTopicServiceImpl target = topicService(dataSource);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.Map<?, ?> params = invocation.getArgument(1);
            assertEquals(9L, params.get("memberId"), "复制新草稿归属仍发起人本人");
            assertEquals(2, params.get("copyType"), "须复制章节+节点但不复制票");
            jdbc.update("insert into cms_topic (id, user_status, lifecycle) values (555, 0, 0)");
            return 555L;
        }).when(target).copyCmsTopic(org.mockito.ArgumentMatchers.eq(100L), any());

        ICmsTopicService service = transactionalProxy(target, dataSource);
        assertEquals(555L, com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(service).transferToClub(100L, 9L, 200L));
        assertEquals(200L, jdbc.queryForObject("select club_id from cms_topic where id = 555", Long.class));
        assertEquals(0, jdbc.queryForObject("select user_status from cms_topic where id = 100", Integer.class));
    }

    @Test
    void offlineFailure_rollsBackEntireTransfer() throws Exception {
        DriverManagerDataSource dataSource = dataSource("topic_transfer_rollback");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists cms_topic");
        jdbc.execute("create table cms_topic (id bigint primary key, user_status int, club_id bigint, lifecycle int, del_flag int, update_time timestamp,"
                + " constraint fail_original_offline check (id <> 100 or user_status = 1))");
        jdbc.update("insert into cms_topic (id, user_status, lifecycle) values (100, 1, 1)");

        CmsTopicServiceImpl target = topicService(dataSource);
        org.mockito.Mockito.doAnswer(invocation -> {
            jdbc.update("insert into cms_topic (id, user_status, lifecycle) values (555, 0, 0)");
            return 555L;
        }).when(target).copyCmsTopic(org.mockito.ArgumentMatchers.eq(100L), any());
        ICmsTopicService service = transactionalProxy(target, dataSource);

        assertThrows(RuntimeException.class, () -> com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(service).transferToClub(100L, 9L, 200L));
        assertEquals(1, jdbc.queryForObject("select count(*) from cms_topic", Integer.class),
                "下架失败时复制出的新主题必须回滚");
        assertEquals(1, jdbc.queryForObject("select user_status from cms_topic where id = 100", Integer.class),
                "下架失败时原主题应保持在架");
    }

    private CmsTopicServiceImpl topicService(DriverManagerDataSource dataSource) throws Exception {
        CmsTopicServiceImpl target = org.mockito.Mockito.spy(new CmsTopicServiceImpl());
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topicMapper(dataSource));
        return target;
    }

    private DriverManagerDataSource dataSource(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    private ICmsTopicService transactionalProxy(CmsTopicServiceImpl target, DriverManagerDataSource dataSource) {
        TransactionInterceptor interceptor = new TransactionInterceptor(
                new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(ICmsTopicService.class);
        proxyFactory.addAdvice(interceptor);
        return (ICmsTopicService) proxyFactory.getProxy();
    }

    private CmsTopicMapper topicMapper(DriverManagerDataSource dataSource) throws Exception {
        Environment environment = new Environment("test", new SpringManagedTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        configuration.addMapper(CmsTopicMapper.class);
        String resource = "mapper/business/CmsTopicMapper.xml";
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("找不到 MyBatis XML: " + resource);
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        return new SqlSessionTemplate(factory).getMapper(CmsTopicMapper.class);
    }

    @Test
    void nonOwner_rejected_noCopy() {
        ApiTopicController stranger = spyAs(7L); // 非归属人(主题归 9)
        CmsTopic t = new CmsTopic(); t.setId(100L); t.setMemberId(9L);
        when(cmsTopicService.selectCmsTopicById(100L)).thenReturn(t);

        AjaxResult r = stranger.transferToClub("100", "200", null);

        assertEquals(500, ((Integer) r.get("code")).intValue(), "越权移交他人主题应被拒");
        org.mockito.Mockito.verify(cmsTopicService, org.mockito.Mockito.never())
                .transferToClub(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void owner_butClubNotPartner_rejected_noCopy() {
        ApiTopicController owner = spyAs(9L);
        CmsTopic t = new CmsTopic(); t.setId(100L); t.setMemberId(9L); // 本人主题
        when(cmsTopicService.selectCmsTopicById(100L)).thenReturn(t);
        Club club200 = new Club(); club200.setId(200L); club200.setMemberId(777L); // 非本人主理
        when(clubService.selectClubById(200L)).thenReturn(club200);
        when(clubLeaderService.selectClubLeaderByMemberId(9L)).thenReturn(null);
        when(coopInviteService.selectCoopInviteList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new java.util.ArrayList<>()); // 无已通过合作邀约

        AjaxResult r = owner.transferToClub("100", "200", null);

        assertEquals(500, ((Integer) r.get("code")).intValue(), "无合作关系的俱乐部→拒(防绕过 coop 门禁)");
        org.mockito.Mockito.verify(cmsTopicService, org.mockito.Mockito.never())
                .transferToClub(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong());
    }
}

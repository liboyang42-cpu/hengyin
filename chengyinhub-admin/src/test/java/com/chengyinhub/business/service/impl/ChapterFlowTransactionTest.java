package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.domain.dto.TopicUpdateDTO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsCollaboratorsMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IXpBudgetService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.business.service.support.TopicBundleReviewService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.utils.DistanceCalculatorUtils;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A20：真实 Spring 事务代理 + H2，证明删旧节点后 blocks 写失败会恢复旧数据。 */
class ChapterFlowTransactionTest
{
    @Test
    void blocksWriteFailureRollsBackDeletedAndInsertedFlowRows()
    {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:chapter_flow_rollback;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createFlowTables(jdbc);
        jdbc.update("insert into cms_topic_chapter (id, topic_id, description, blocks, sort_id, required) values (10, 71, '旧正文', '[{\"type\":\"text\",\"content\":\"旧正文\"}]', 1, 1)");
        jdbc.update("insert into cms_topic_node (id, topic_id, chapter_id, name, sort_id, status) values (20, 71, 10, '旧节点', 9, 1)");

        CmsTopicChapterMapper chapterMapper = chapterMapper(jdbc, true);
        CmsTopicNodeMapper nodeMapper = nodeMapper(jdbc);
        CmsTopicServiceImpl target = service(chapterMapper, nodeMapper);
        ICmsTopicService proxy = transactionalProxy(target, dataSource);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(proxy).updateTopicByOwnerV2(validUpdate(), 19L, "tester"));

        assertTrue(failure.getMessage().contains("注入 blocks 写失败"), failure.getMessage());
        assertEquals(1, jdbc.queryForObject("select count(*) from cms_topic_chapter where id = 10", Integer.class),
                "章节删除必须回滚");
        assertEquals("旧正文", jdbc.queryForObject(
                "select description from cms_topic_chapter where id = 10", String.class),
                "旧章节正文必须完整可用");
        assertEquals(1, jdbc.queryForObject("select count(*) from cms_topic_node where id = 20", Integer.class),
                "节点删除必须回滚");
        assertEquals(Long.valueOf(9L), jdbc.queryForObject(
                "select sort_id from cms_topic_node where id = 20", Long.class),
                "旧节点顺序必须恢复");
        assertEquals(0, jdbc.queryForObject("select count(*) from cms_topic_chapter where id <> 10", Integer.class),
                "失败事务不得留下新章节");
        assertEquals(0, jdbc.queryForObject("select count(*) from cms_topic_node where id <> 20", Integer.class),
                "失败事务不得留下新节点");
    }

    @Test
    void auditTaskFailureRollsBackTheCompletedV2Rebuild()
    {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:topic_bundle_submit_rollback;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createFlowTables(jdbc);
        jdbc.update("insert into cms_topic_chapter values (10, 71, '旧正文', '[{\"type\":\"text\",\"content\":\"旧正文\"}]', 1, 1)");
        jdbc.update("insert into cms_topic_node (id, topic_id, chapter_id, name, sort_id, status) values (20, 71, 10, '旧节点', 9, 1)");

        CmsTopicServiceImpl target = service(chapterMapper(jdbc, false), nodeMapper(jdbc));
        CmsTopicMapper topicMapper = (CmsTopicMapper) ReflectionTestUtils.getField(target, "cmsTopicMapper");
        when(topicMapper.updateTopicDraftState(71L, 0)).thenReturn(1);
        TopicBundleReviewService bundles = mock(TopicBundleReviewService.class);
        TopicBundleReviewService.Submission submission =
                new TopicBundleReviewService.Submission("{\"schemaVersion\":2}", Collections.singletonList(100L));
        when(bundles.freezeForSubmission(71L, 19L)).thenReturn(submission);
        IAuditTaskService audit = mock(IAuditTaskService.class);
        when(audit.submitOrRefreshTopicBundle(71L, 19L, submission.getSnapshotJson()))
                .thenThrow(new IllegalStateException("注入审核任务失败"));
        set(target, "topicBundleReviewService", bundles);
        set(target, "auditTaskService", audit);
        TopicUpdateDTO dto = validUpdate();
        dto.setSaveDraft(0);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> transactionalProxy(target, dataSource).updateTopicByOwnerV2(dto, 19L, "tester"));

        assertTrue(failure.getMessage().contains("注入审核任务失败"));
        assertEquals(1, jdbc.queryForObject("select count(*) from cms_topic_chapter where id = 10", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from cms_topic_node where id = 20", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from cms_topic_chapter where id <> 10", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from cms_topic_node where id <> 20", Integer.class));
    }

    private CmsTopicChapterMapper chapterMapper(JdbcTemplate jdbc, boolean failBlocks)
    {
        CmsTopicChapterMapper mapper = mock(CmsTopicChapterMapper.class);
        when(mapper.selectCmsTopicChapterList(any())).thenAnswer(invocation -> jdbc.query(
                "select id, topic_id, description, blocks, sort_id, required from cms_topic_chapter where topic_id = 71",
                (rs, rowNum) -> {
                    CmsTopicChapter row = new CmsTopicChapter();
                    row.setId(rs.getLong("id"));
                    row.setTopicId(rs.getLong("topic_id"));
                    row.setDescription(rs.getString("description"));
                    row.setBlocksJson(rs.getString("blocks"));
                    row.setSortId(rs.getLong("sort_id"));
                    row.setRequired(rs.getInt("required"));
                    return row;
                }));
        doAnswer(invocation -> jdbc.update("delete from cms_topic_chapter where id = ?",
                new Object[]{invocation.getArgument(0)})).when(mapper).deleteCmsTopicChapterById(any());
        AtomicLong ids = new AtomicLong(100L);
        doAnswer(invocation -> {
            CmsTopicChapter chapter = invocation.getArgument(0);
            chapter.setId(ids.incrementAndGet());
            return jdbc.update("insert into cms_topic_chapter (id, topic_id, description, blocks, sort_id, required) values (?, ?, ?, ?, ?, ?)",
                    chapter.getId(), chapter.getTopicId(), chapter.getDescription(), chapter.getBlocksJson(),
                    chapter.getSortId(), chapter.getRequired());
        }).when(mapper).insertCmsTopicChapter(any());
        doAnswer(invocation -> {
            if (failBlocks) throw new IllegalStateException("注入 blocks 写失败");
            CmsTopicChapter chapter = invocation.getArgument(0);
            return jdbc.update("update cms_topic_chapter set blocks = ? where id = ?",
                    chapter.getBlocksJson(), chapter.getId());
        }).when(mapper).updateCmsTopicChapter(any());
        return mapper;
    }

    private CmsTopicNodeMapper nodeMapper(JdbcTemplate jdbc)
    {
        CmsTopicNodeMapper mapper = mock(CmsTopicNodeMapper.class);
        when(mapper.selectCmsTopicNodeList(any())).thenAnswer(invocation -> jdbc.query(
                "select id, topic_id, chapter_id, name, sort_id, status from cms_topic_node where topic_id = 71",
                (rs, rowNum) -> {
                    CmsTopicNode row = new CmsTopicNode();
                    row.setId(rs.getLong("id"));
                    row.setTopicId(rs.getLong("topic_id"));
                    row.setChapterId(rs.getLong("chapter_id"));
                    row.setName(rs.getString("name"));
                    row.setSortId(rs.getLong("sort_id"));
                    row.setStatus(rs.getLong("status"));
                    return row;
                }));
        doAnswer(invocation -> jdbc.update("delete from cms_topic_node where id = ?",
                new Object[]{invocation.getArgument(0)})).when(mapper).deleteCmsTopicNodeById(any());
        AtomicLong ids = new AtomicLong(200L);
        doAnswer(invocation -> {
            CmsTopicNode node = invocation.getArgument(0);
            node.setId(ids.incrementAndGet());
            return jdbc.update("insert into cms_topic_node (id, topic_id, chapter_id, name, sort_id, status) values (?, ?, ?, ?, ?, ?)",
                    node.getId(), node.getTopicId(), node.getChapterId(), node.getName(), node.getSortId(), node.getStatus());
        }).when(mapper).insertCmsTopicNode(any());
        return mapper;
    }

    private CmsTopicServiceImpl service(CmsTopicChapterMapper chapterMapper, CmsTopicNodeMapper nodeMapper)
    {
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsTopic existing = new CmsTopic();
        existing.setId(71L);
        existing.setMemberId(19L);
        existing.setStatus(0);
        existing.setLifecycle(0);
        existing.setDelFlag(0);
        existing.setMerchantStatus(1);
        when(topicMapper.selectCmsTopicById(71L)).thenReturn(existing);
        when(topicMapper.selectCmsTopicByIdForUpdate(71L)).thenReturn(existing);
        when(topicMapper.selectTopicIsDraft(71L)).thenReturn(1);
        when(topicMapper.updateCmsTopic(any())).thenReturn(1);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());

        // 本用例的 dto 带 openMerchantPool=1,会走到招商资格闸 resolveMerchantPoolFlag ⇒
        // 给一个有效主理人档让它放行;闸本身的行为由 CmsTopicMerchantPoolRoleGuardTest 覆盖。
        IClubLeaderService clubLeaderService = mock(IClubLeaderService.class);
        ClubLeader activeLeader = new ClubLeader();
        activeLeader.setStatus(1);
        when(clubLeaderService.selectClubLeaderByMemberId(any())).thenReturn(activeLeader);
        set(service, "clubLeaderService", clubLeaderService);

        set(service, "cmsTopicMapper", topicMapper);
        set(service, "cmsTopicChapterMapper", chapterMapper);
        set(service, "cmsTopicNodeMapper", nodeMapper);
        set(service, "omsTicketMapper", mock(OmsTicketMapper.class));
        set(service, "cmsCollaboratorsMapper", mock(CmsCollaboratorsMapper.class));
        set(service, "cmsActivityMapper", mock(CmsActivityMapper.class));
        set(service, "cmsRegistrationMapper", registrations);
        set(service, "couponRewardAuthorizationService", mock(ICouponRewardAuthorizationService.class));
        set(service, "clubService", mock(IClubService.class));
        set(service, "xpBudgetService", mock(IXpBudgetService.class));
        set(service, "auditTaskService", mock(IAuditTaskService.class));
        set(service, "umsCreativeSquareService", mock(IUmsCreativeSquareService.class));
        set(service, "commercialTopicPolicy", new com.chengyinhub.business.service.support.CommercialTopicPolicy(
                (OmsTicketMapper) ReflectionTestUtils.getField(service, "omsTicketMapper"), chapterMapper,
                mock(com.chengyinhub.business.mapper.MerchantChapterOfferMapper.class), topicMapper));
        set(service, "omsTicketService", mock(IOmsTicketService.class));
        set(service, "distanceCalculatorUtils", mock(DistanceCalculatorUtils.class));
        set(service, "sysCategoryService", mock(ISysCategoryService.class));
        set(service, "cmsMemberTemplateMapper", mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class));
        // Actual media schema/reference admission; only reviewed persistence is a fixture.
        com.chengyinhub.business.mapper.AppMediaAssetMapper assets = mock(com.chengyinhub.business.mapper.AppMediaAssetMapper.class);
        for (long mediaId : new long[] {901L, 902L, 903L}) when(assets.select(mediaId)).thenReturn(java.util.Map.of(
                "id", mediaId, "ownerType", "APP", "memberId", 19L, "reviewState", "APPROVED"));
        com.chengyinhub.business.service.support.AppMediaReferenceGuard references =
                new com.chengyinhub.business.service.support.AppMediaReferenceGuard();
        ReflectionTestUtils.setField(references, "mapper", assets);
        com.chengyinhub.business.service.support.AppMediaPublicationGuard media =
                new com.chengyinhub.business.service.support.AppMediaPublicationGuard();
        ReflectionTestUtils.setField(media, "references", references);
        ReflectionTestUtils.setField(media, "topics", topicMapper);
        ReflectionTestUtils.setField(media, "chapters", chapterMapper);
        ReflectionTestUtils.setField(media, "nodes", nodeMapper);
        ReflectionTestUtils.setField(media, "templates", (com.chengyinhub.business.mapper.CmsMemberTemplateMapper) com.chengyinhub.business.service.support.TopicPublicationTestWiring.get(service, "cmsMemberTemplateMapper"));
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", media);
        set(service, "themeStateKeyService", new com.chengyinhub.business.service.support.ThemeStateKeyService(
                mock(com.chengyinhub.business.mapper.ThemeStateKeyMapper.class)));
        return service;
    }

    private ICmsTopicService transactionalProxy(CmsTopicServiceImpl target, DriverManagerDataSource dataSource)
    {
        TransactionInterceptor interceptor = new TransactionInterceptor(
                new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(target));
        factory.setInterfaces(ICmsTopicService.class);
        factory.addAdvice(interceptor);
        return (ICmsTopicService) factory.getProxy();
    }

    private TopicUpdateDTO validUpdate()
    {
        NodeDTO node = new NodeDTO();
        node.setName("新节点");
        node.setNodeTime(5);
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setType("node");
        block.setNodeIndex(0);
        ChapterDTO chapter = new ChapterDTO();
        chapter.setName("新章节");
        chapter.setSchemaVersion(1);
        chapter.setRequired(1);
        chapter.setNodes(Collections.singletonList(node));
        chapter.setBlocks(Collections.singletonList(block));
        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(71L);
        dto.setName("事务主题");
        dto.setProductType(ProductType.ORIENTEERING);
        dto.setOpenMerchantPool(1);
        dto.setChapters(Collections.singletonList(chapter));
        dto.setTickets(new ArrayList<com.chengyinhub.business.domain.dto.ActivityTicketRequest>());
        dto.setCollaboratorIds(new ArrayList<Long>());
        return dto;
    }

    // 两套隔离事务夹具共用同一份建表定义，新增列在此维护。
    private void createFlowTables(JdbcTemplate jdbc)
    {
        jdbc.execute("create table cms_topic_chapter (id bigint primary key, topic_id bigint, description varchar(500), blocks clob, sort_id bigint, required int)");
        jdbc.execute("create table cms_topic_node (id bigint primary key, topic_id bigint, chapter_id bigint, name varchar(100), sort_id bigint, status bigint)");
    }

    private void set(CmsTopicServiceImpl service, String field, Object value)
    {
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, field, value);
    }
}

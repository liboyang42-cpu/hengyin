package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsTopicNode;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商家节点可见性闸 —— 真跑 SQL,不是断言字符串。
 *
 * 为什么要闸在 mapper:全仓有十来处读 cms_topic_node,逐个加闸必然漏一个,
 * 而漏一个就等于商家提交的**未审内容直接进了别人的付费主题**。
 * 所以判据是「没有显式声明管理上下文的查询,一律只看得到已过审的商家节点」。
 *
 * ⚠️ 最容易写反的一条:条件必须是「主办方节点 或 已过审」。
 * 写成 `node_audit_status = 1` 会把 merchant_member_id 为 NULL 的**存量主办方节点全部隐藏**,
 * 整条路线在玩家侧空掉 —— 下面 hostNodes_alwaysVisible 专门钉这个。
 */
class ChapterMerchantNodeVisibilityMyBatisTest {

    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CmsTopicNode", CmsTopicNode.class);
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:chapter_node_vis;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/CmsTopicNodeMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
             Statement st = session.getConnection().createStatement()) {
            st.execute("drop table if exists merchant_chapter_offer");
            st.execute("create table merchant_chapter_offer (id bigint primary key, chapter_id bigint, " +
                    "merchant_id bigint, status int, withdraw_reason varchar(200))");
            st.execute("drop table if exists cms_topic_node");
            st.execute("create table cms_topic_node (" +
                    "id bigint primary key auto_increment, topic_id bigint, chapter_id bigint, name varchar(80), " +
                    "description varchar(255), address varchar(255), longitude varchar(40), latitude varchar(40), " +
                    "img_url varchar(500), node_time varchar(40), template_id bigint, " +
                    "merchant_member_id bigint, node_audit_status tinyint, node_audit_reason varchar(200), " +
                    "node_audit_member_id bigint, node_audit_time timestamp, " +
                    "checkin_code varchar(64), business_time varchar(64), status bigint, sort_id bigint, " +
                    "xp_value int, hook_text varchar(255), card_hook_long varchar(255), fragment_text varchar(255), " +
                    "unlock_after_node_id bigint, " +
                    "create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp)");
            // 1 = 主办方存量节点(两列均 NULL);2/3/4 = 商家节点的三种审核态
            st.execute("insert into cms_topic_node(id, topic_id, chapter_id, name, status) values (1, 100, 9, '主办方点位', 1)");
            st.execute("insert into cms_topic_node(id, topic_id, chapter_id, name, status, merchant_member_id, " +
                    "node_audit_status, node_audit_reason, node_audit_member_id, node_audit_time) " +
                    "values (2, 100, 9, '商家-已过审', 1, 88, 1, '上一家已过审', 8802, " +
                    "timestamp '2026-08-17 12:00:00')");
            st.execute("insert into cms_topic_node(id, topic_id, chapter_id, name, status, merchant_member_id, node_audit_status) " +
                    "values (3, 100, 9, '商家-待审', 1, 88, 0)");
            st.execute("insert into cms_topic_node(id, topic_id, chapter_id, name, status, merchant_member_id, node_audit_status) " +
                    "values (4, 100, 9, '商家-已驳回', 1, 99, 2)");
        }
    }

    private List<String> namesOf(CmsTopicNode probe) {
        try (SqlSession session = factory.openSession(true)) {
            List<CmsTopicNode> list = session.getMapper(CmsTopicNodeMapper.class)
                    .selectCmsTopicNodeList(probe);
            return list.stream().map(CmsTopicNode::getName).sorted().collect(Collectors.toList());
        }
    }

    /** 玩家/公开侧:只看得到主办方节点 + 已过审的商家节点。 */
    @Test
    @Timeout(10)
    void playerSide_seesOnlyApprovedMerchantNodes() {
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(100L);
        List<String> names = namesOf(probe);
        assertEquals(List.of("主办方点位", "商家-已过审"), names);
        assertTrue(!names.contains("商家-待审"), "★未审内容绝不能进玩家侧 —— 那是别人的付费主题");
        assertTrue(!names.contains("商家-已驳回"), "驳回的更不能出");
    }

    /** 5-3 注入①：已过审节点退回待审后，下一次玩家读取必须立即不可见。 */
    @Test
    @Timeout(10)
    void approvedNodeResetToPendingDisappearsFromPlayerRead() {
        try (SqlSession session = factory.openSession(false)) {
            CmsTopicNode reset = new CmsTopicNode();
            reset.setId(2L);
            reset.setMerchantMemberId(99L);
            reset.setNodeAuditStatus(0);
            reset.setClearNodeAuditTrace(true);
            assertEquals(1, session.getMapper(CmsTopicNodeMapper.class).updateCmsTopicNode(reset));

            CmsTopicNode playerProbe = new CmsTopicNode();
            playerProbe.setTopicId(100L);
            List<String> playerNames = session.getMapper(CmsTopicNodeMapper.class)
                    .selectCmsTopicNodeList(playerProbe).stream()
                    .map(CmsTopicNode::getName).collect(Collectors.toList());
            assertTrue(!playerNames.contains("商家-已过审"),
                    "node_audit_status 改回 0 后玩家读取不得继续看到该店");

            CmsTopicNode auditProbe = new CmsTopicNode();
            auditProbe.setTopicId(100L);
            auditProbe.setNodeAuditStatus(0);
            List<CmsTopicNode> auditRows = session.getMapper(CmsTopicNodeMapper.class)
                    .selectCmsTopicNodeList(auditProbe);
            assertTrue(auditRows.stream().anyMatch(row -> "商家-已过审".equals(row.getName())),
                    "同一行仍在审核台，证明玩家不可见来自可见性闸而非数据被删");
            CmsTopicNode rebound = auditRows.stream()
                    .filter(row -> Long.valueOf(2L).equals(row.getId()))
                    .findFirst().orElseThrow(AssertionError::new);
            assertEquals(Long.valueOf(99L), rebound.getMerchantMemberId());
            assertNull(rebound.getNodeAuditReason(), "真实 MyBatis UPDATE 必须清掉上一家审核理由");
            assertNull(rebound.getNodeAuditMemberId(), "真实 MyBatis UPDATE 必须清掉上一家审核人");
            assertNull(rebound.getNodeAuditTime(), "真实 MyBatis UPDATE 必须清掉上一家审核时间");
            session.rollback();
        }
    }

    /**
     * ★最容易写反的一条:主办方存量节点两列都是 NULL,
     * 闸若写成 `node_audit_status = 1` 会把它们一起隐藏,整条路线空掉。
     */
    @Test
    @Timeout(10)
    void hostNodes_alwaysVisible() {
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(100L);
        assertTrue(namesOf(probe).contains("主办方点位"),
                "存量主办方节点必须照常可见 —— 隐藏它们等于整条路线在玩家侧消失");
    }

    /** 商家看自己的:显式带 merchantMemberId ⇒ 管理上下文,待审也要能看到(否则改完看不见自己的稿)。 */
    @Test
    @Timeout(10)
    void merchantSelfScope_seesOwnPending() {
        CmsTopicNode probe = new CmsTopicNode();
        probe.setMerchantMemberId(88L);
        List<String> names = namesOf(probe);
        assertTrue(names.contains("商家-待审"));
        assertTrue(names.contains("商家-已过审"));
        assertTrue(!names.contains("商家-已驳回"), "别人的节点不该出现在我的列表里");
    }

    /** 审核台:显式带 nodeAuditStatus ⇒ 管理上下文,能按状态查到待审。 */
    @Test
    @Timeout(10)
    void auditQueueScope_seesPending() {
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(100L);
        probe.setNodeAuditStatus(0);
        assertEquals(List.of("商家-待审"), namesOf(probe));
    }

    /**
     * ★负控:闸真的在起作用吗?
     * 造一个「本该被挡住」的查询,确认它确实拿不到未审节点 —— 若哪天条件被改松,这条会红。
     */
    @Test
    @Timeout(10)
    void negativeControl_pendingNodeIsReachableOnlyViaManagementScope() {
        CmsTopicNode player = new CmsTopicNode();
        player.setChapterId(9L);
        assertTrue(!namesOf(player).contains("商家-待审"), "章节维度查询同样受闸");

        // 同一行数据,换成管理上下文就该查得到 —— 证明「查不到」不是因为数据不存在
        CmsTopicNode admin = new CmsTopicNode();
        admin.setChapterId(9L);
        admin.setNodeAuditStatus(0);
        assertTrue(namesOf(admin).contains("商家-待审"), "数据本身在,闸只是挡住了玩家侧");
    }
    @Test
    @Timeout(10)
    void withdrawnStoreIsHiddenFromPlayerButRetainedForManagement() throws Exception {
        try (SqlSession session = factory.openSession(false);
             Statement st = session.getConnection().createStatement()) {
            st.execute("insert into merchant_chapter_offer values (10, 9, 88, 0, '停止营业')");
            CmsTopicNode publicProbe = new CmsTopicNode();
            publicProbe.setTopicId(100L);
            List<CmsTopicNode> publicNodes = session.getMapper(CmsTopicNodeMapper.class)
                    .selectCmsTopicNodeList(publicProbe);
            assertEquals(List.of("主办方点位"), publicNodes.stream()
                    .map(CmsTopicNode::getName).collect(Collectors.toList()));
            CmsTopicNode ownProbe = new CmsTopicNode();
            ownProbe.setMerchantMemberId(88L);
            assertEquals(2, session.getMapper(CmsTopicNodeMapper.class)
                    .selectCmsTopicNodeList(ownProbe).size());
            CmsTopicNode scanProbe = new CmsTopicNode();
            scanProbe.setChapterId(9L);
            scanProbe.setName("商家-已过审");
            assertTrue(session.getMapper(CmsTopicNodeMapper.class)
                    .selectCmsTopicNodeList(scanProbe).isEmpty());
            st.execute("update merchant_chapter_offer set status = 1, withdraw_reason = null where id = 10");
            session.clearCache();
            assertEquals(2, session.getMapper(CmsTopicNodeMapper.class)
                    .selectCmsTopicNodeList(publicProbe).size());
            session.rollback();
        }
    }

}

package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.enums.PublicActivityScope;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公开活动可见性四道闸的负控 —— 真 MyBatis + 真 MySQL，跑的是仓库里那份 CmsActivityMapper.xml。
 *
 * <p><b>为什么必须是真库而不是 Mockito。</b>要证的东西全在 SQL 里:{@code cancel_time is null} 写没写死、
 * {@code end_date &gt; now()} 有没有真的生效。mock 掉 mapper 就等于把被测对象换成了自己写的假货 ——
 * 那正是这次线上事故的形状:5112 个单测全绿,SQL 里的闸却没开。
 *
 * <p><b>这道闸在防什么(2026-08-27 实测的真实缺口)。</b>
 * {@code selectPublicCmsActivityList} 名字里带 public,但「不出社群活动」「只出未结束的」
 * 「排除已取消」三道都曾是<b>可选</b> {@code <if>};四个调用方拼出了四套不同的闸,
 * 其中主题详情页两道全没开 —— 生产 8 条 3 月就结束的活动被当作「可参加场次」下发。
 *
 * <p><b>怎么当负控用。</b>把 {@link PublicActivityScope} 里任意一维翻反、或把 XML 里
 * {@code and cancel_time is null} 删掉,下面对应的断言必须变红。绿是廉价的,能变红才是价值。
 */
class PublicActivityScopeGateMyBatisTest {

    /** fixture 行 id,名字即含义,断言里直接按 id 比对。 */
    private static final long LIVE_PUBLIC = 1L;
    private static final long ENDED_PUBLIC = 2L;
    private static final long LIVE_CLUB = 3L;
    private static final long CANCELLED_PUBLIC = 4L;
    private static final long ENDED_CLUB = 5L;

    @Test
    void eachScopeOpensExactlyTheGatesItDeclares() throws Exception {
        SqlSessionFactory f = factory();
        try (SqlSession probe = f.openSession()) {
            assertTrue(probe.getConnection().isValid(2), "真实 MySQL fixture 必须可达");
        }

        try (SqlSession s = f.openSession(true)) {
            Statement st = s.getConnection().createStatement();
            recreateTables(st);
            seed(st);

            CmsActivityMapper mapper = s.getMapper(CmsActivityMapper.class);
            CmsActivityServiceImpl service = new CmsActivityServiceImpl();
            ReflectionTestUtils.setField(service, "cmsActivityMapper", mapper);

            // ---- 硬编码闸:已取消的活动,任何 scope 都不该出 ----
            for (PublicActivityScope scope : PublicActivityScope.values()) {
                assertFalse(ids(service.selectPublicCmsActivityList(new CmsActivity(), scope))
                                .contains(CANCELLED_PUBLIC),
                        scope + " 不该出已取消的活动 —— cancel_time is null 写死在 SQL 里，没有例外");
            }

            // ---- PUBLIC_SHELF:首页/搜索/地图/即将上线。不出社群 + 只出未结束 ----
            List<Long> shelf = ids(service.selectPublicCmsActivityList(
                    new CmsActivity(), PublicActivityScope.PUBLIC_SHELF));
            assertEquals(List.of(LIVE_PUBLIC), shelf,
                    "公域货架只留「未结束的公域活动」这一种；曾经 is_my=2 分支漏了 end_date 闸");

            // ---- TOPIC_SHELF:主题详情页可参加场次。出社群,但仍只出未结束 ----
            List<Long> topicShelf = ids(service.selectPublicCmsActivityList(
                    new CmsActivity(), PublicActivityScope.TOPIC_SHELF));
            assertTrue(topicShelf.contains(LIVE_CLUB),
                    "主题页要出社群活动 —— 俱乐部承接的场次正是要在主题下被看到");
            assertFalse(topicShelf.contains(ENDED_PUBLIC) || topicShelf.contains(ENDED_CLUB),
                    "★ 本次事故点:已结束的场次不叫「可参加」，主题页必须滤掉");

            // ---- MEMBER_FAVOURITES:收藏列表。出社群 + 保留已结束 ----
            List<Long> favourites = ids(service.selectPublicCmsActivityList(
                    new CmsActivity(), PublicActivityScope.MEMBER_FAVOURITES));
            assertTrue(favourites.containsAll(List.of(LIVE_PUBLIC, ENDED_PUBLIC, LIVE_CLUB, ENDED_CLUB)),
                    "收藏列表是唯一保留已结束活动的公开面，社群活动也要出");

            // ---- fail-closed:忘了传 scope 必须炸,不能静默退化成「什么都不过滤」----
            assertThrows(IllegalArgumentException.class,
                    () -> service.selectPublicCmsActivityList(new CmsActivity(), null),
                    "scope 缺失必须响亮失败 —— 静默不过滤正是这次要杀掉的形态");
        }
    }

    @Test
    void ownerListRetainsCancellationForLifecycleDisplay() throws Exception {
        try (SqlSession session = factory().openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            recreateTables(statement);
            seed(statement);
            CmsActivity query = new CmsActivity();
            query.setMemberId(7L);
            CmsActivity cancelled = session.getMapper(CmsActivityMapper.class).selectCmsActivityList(query)
                    .stream().filter(row -> row.getId() == CANCELLED_PUBLIC).findFirst().orElseThrow();
            assertEquals("cancelled", com.chengyinhub.business.util.ActivityLifecycle.state(cancelled));
        }
    }

    private List<Long> ids(List<CmsActivity> rows) {
        return rows.stream().map(CmsActivity::getId).sorted().collect(Collectors.toList());
    }

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CmsActivity", CmsActivity.class);
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("public_activity_scope"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        try (InputStream in = Resources.getResourceAsStream("mapper/business/CmsActivityMapper.xml")) {
            new XMLMapperBuilder(in, cfg, "mapper/business/CmsActivityMapper.xml", cfg.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    private void recreateTables(Statement st) throws Exception {
        st.execute("DROP TABLE IF EXISTS cms_activity");
        st.execute("CREATE TABLE cms_activity ("
                + "id BIGINT PRIMARY KEY, member_id BIGINT, name VARCHAR(100), description TEXT, img_url VARCHAR(255),"
                + "img_arr TEXT, category_ids VARCHAR(100), address_name VARCHAR(100), longitude VARCHAR(30), latitude VARCHAR(30),"
                + "address VARCHAR(255), status BIGINT, publish_status BIGINT, cancel_time DATETIME, cancel_reason VARCHAR(255),"
                + "reason VARCHAR(255), del_flag INT, start_date DATETIME, end_date DATETIME, template_id BIGINT,"
                + "topic_id BIGINT, club_id BIGINT, check_status INT, is_settlement INT, create_by VARCHAR(64),"
                + "create_time DATETIME, update_by VARCHAR(64), update_time DATETIME)");
        // 公开查询会 join 这两张表判「俱乐部还在不在」,建出来才不会因缺表而假红。
        st.execute("DROP TABLE IF EXISTS club");
        st.execute("CREATE TABLE club (id BIGINT PRIMARY KEY, del_flag INT)");
        st.execute("DROP TABLE IF EXISTS cms_topic");
        st.execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, club_id BIGINT, product_type INT, del_flag INT,"
                + " status INT, user_status INT, team_mode INT, team_max_members INT)");
        st.execute("INSERT INTO club (id, del_flag) VALUES (700, 0)");
    }

    private void seed(Statement st) throws Exception {
        // 五行覆盖三个维度的组合:未结束/已结束 × 公域/社群 × 正常/已取消。
        // 全部 status=1 publish_status=1 del_flag=0 —— 那三道本来就写死,这里不重复验。
        st.execute("INSERT INTO cms_activity (id,member_id,name,status,publish_status,del_flag,"
                + "cancel_time,end_date,club_id,create_time) VALUES "
                + "(" + LIVE_PUBLIC + ",7,'未结束·公域',1,1,0,NULL,DATE_ADD(NOW(), INTERVAL 30 DAY),NULL,NOW()),"
                + "(" + ENDED_PUBLIC + ",7,'已结束·公域',1,1,0,NULL,DATE_SUB(NOW(), INTERVAL 30 DAY),NULL,NOW()),"
                + "(" + LIVE_CLUB + ",7,'未结束·社群',1,1,0,NULL,DATE_ADD(NOW(), INTERVAL 30 DAY),700,NOW()),"
                + "(" + CANCELLED_PUBLIC + ",7,'已取消·公域',1,1,0,NOW(),DATE_ADD(NOW(), INTERVAL 30 DAY),NULL,NOW()),"
                + "(" + ENDED_CLUB + ",7,'已结束·社群',1,1,0,NULL,DATE_SUB(NOW(), INTERVAL 30 DAY),700,NOW())");
    }
}

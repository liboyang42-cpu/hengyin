package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsTopicMapper;
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

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * D7:{@code insertCmsTopic} 漏列 —— service 设了值、mapper 不写这一列,建题接口照旧返回「主题发布成功」。
 *
 * <p><b>为什么必须是真库 IT 而不是 mock 单测</b>:这一族缺陷的判据是「值有没有进库」。
 * mock 掉 mapper 的单测只能证明 service 调了 {@code insertCmsTopic(topic)} 并且 topic 上带着值 ——
 * 而 D7 恰恰发生在这一步之后。**接口说「成功」不等于值进去了**,只有把行读回来才算数。
 *
 * <p>本轮实测(2026-08-18,基线 2025d2022)漏的两列都在
 * {@code CmsTopicServiceImpl:2467-2470} 同一个 {@code if} 里:
 * <ul>
 *   <li>{@code recruit_deadline} ⇒ {@code RecruitmentWindowPolicy.assertOpen} 抛
 *       「招商写入已关闭：recruitDeadline 未配置」⇒ 商家永远填不了实际供给 ⇒ offer 0 行 ⇒
 *       poster-code 恒红、购票容量恒 0。<b>通过 API 建的每个探店日,招商从一开始就是关死的。</b></li>
 *   <li>{@code lifecycle} ⇒ 落库是库默认的 0(DRAFT)而不是 1(RECRUITING)⇒
 *       {@code selectRecruitExpiredTopics} 的 {@code and lifecycle = 1} 永不命中 ⇒ 双死线② 从不触发;
 *       而 {@code confirmPrice} 的第一行是 {@code lifecycle != 2 → throw} ⇒
 *       挂在它上面的四道钱闸(地板价 / 费率快照 / 每必选章 SUPPLY≥1 / 计酬成本上限)
 *       <b>从来没有被走到过</b> —— 这正是 {@code CmsTopicServiceImpl:2450-2458} 那段注释担心的事,
 *       它担心的是「不传 recruitDeadline 的人」,实际连传了的人也一样。</li>
 * </ul>
 *
 * <p>跑法见 {@code ci/backend-test.sh} 的 MYBATIS_ITS 清单(本套已登记;
 * 不登记 = 类名以 IT 结尾、surefire 默认 include 收不到 = 永远不会跑的假绿)。
 */
class TopicInsertColumnCoverageMyBatisIT {

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("topic_insert_coverage"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        try (InputStream in = Resources.getResourceAsStream("mapper/business/CmsTopicMapper.xml")) {
            new XMLMapperBuilder(in, cfg, "mapper/business/CmsTopicMapper.xml", cfg.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    private void applyTopicSchema(java.sql.Connection connection) throws Exception {
        ItSchema.apply(connection,"it-schema/cms-topic-it-schema.sql");
        ItSchema.applyMigrations(connection,"sql/migration_20261029_topic_cancellation_receipt.sql");
    }

    private SqlSessionFactory connectedFactory() {
        try {
            SqlSessionFactory f = factory();
            try (SqlSession probe = f.openSession()) {
                probe.getConnection().isValid(2);
            }
            return f;
        } catch (Throwable t) {
            // 缺库一律红,绝不 skip —— skip 在构建里算绿,而「永远不会红的测试」比没有测试更危险。
            throw new IllegalStateException(
                    "IT 前置不满足(库不可达 / Mapper XML / schema 配置有误),本套必须真跑不得跳过:" + t.getMessage(), t);
        }
    }

    private static Date at(String yyyyMMddHHmmss) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(yyyyMMddHHmmss);
    }

    private static String fmt(Date d) {
        return d == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    /** 建一个「探店日」形状的主题:自由探索(product_type=2)+ 招募截止 + lifecycle=1,与 createTopicInternal 落盘时同形。 */
    private static CmsTopic exploreDayTopic(Date recruitDeadline) {
        CmsTopic t = new CmsTopic();
        t.setMemberId(9001L);
        t.setProductType(2);            // FREE_EXPLORE = 探店日
        t.setName("D7 探店日");
        t.setStatus(0);
        t.setUserStatus(0);
        t.setMerchantStatus(1);
        t.setDelFlag(0);
        t.setCreateBy("d7-it");
        t.setCreateTime(new Date());
        // ⚠️ 这两行对应 CmsTopicServiceImpl:2467-2470 —— service 就是这么设的
        t.setLifecycle(1);
        t.setRecruitDeadline(recruitDeadline);
        return t;
    }

    @Test
    void insertCmsTopic_persistsRecruitDeadline_notJustReturnsOk() throws Exception {
        SqlSessionFactory f = connectedFactory();
        try (SqlSession s = f.openSession(true)) {
            applyTopicSchema(s.getConnection());
            CmsTopicMapper m = s.getMapper(CmsTopicMapper.class);

            Date deadline = at("2026-09-06 23:59:59");
            CmsTopic t = exploreDayTopic(deadline);
            m.insertCmsTopic(t);
            assertNotNull(t.getId(), "useGeneratedKeys 应回填自增主键");

            // ★ 判据在这一行:从库里读回来,不是看 insert 返回了几行
            CmsTopic back = m.selectCmsTopicById(t.getId());
            assertNotNull(back, "刚插入的主题必须读得回来");
            assertEquals("2026-09-06 23:59:59", fmt(back.getRecruitDeadline()),
                    "recruit_deadline 必须落库:它为 NULL ⇒ RecruitmentWindowPolicy 抛「招商写入已关闭："
                            + "recruitDeadline 未配置」⇒ 探店日的招商从建出来那一刻就是关死的,而建题接口不报任何错");
        }
    }

    @Test
    void insertCmsTopic_persistsLifecycle_notTheDbDefault() throws Exception {
        SqlSessionFactory f = connectedFactory();
        try (SqlSession s = f.openSession(true)) {
            applyTopicSchema(s.getConnection());
            CmsTopicMapper m = s.getMapper(CmsTopicMapper.class);

            CmsTopic t = exploreDayTopic(at("2026-09-06 23:59:59"));
            m.insertCmsTopic(t);

            CmsTopic back = m.selectCmsTopicById(t.getId());
            assertEquals(Integer.valueOf(1), back.getLifecycle(),
                    "lifecycle 必须落 1(RECRUITING):落成库默认的 0 ⇒ selectRecruitExpiredTopics 的 "
                            + "`and lifecycle = 1` 永不命中(双死线②不触发),confirmPrice 的 `lifecycle != 2 → throw` "
                            + "也永远走不到 ⇒ 挂在它上面的四道钱闸从来没生效过");
        }
    }

    /**
     * 守住 {@code <if test="x != null">} 的另一半:没设的字段不许被写成非空默认值,
     * 否则复制主题(copyTopicBasicInfo 不带 lifecycle/recruitDeadline)会被这次修改带偏。
     */
    @Test
    void unsetColumns_stillFallBackToDbDefaults() throws Exception {
        SqlSessionFactory f = connectedFactory();
        try (SqlSession s = f.openSession(true)) {
            applyTopicSchema(s.getConnection());
            CmsTopicMapper m = s.getMapper(CmsTopicMapper.class);

            CmsTopic copyLike = new CmsTopic();
            copyLike.setMemberId(9001L);
            copyLike.setName("复制出来的草稿");
            copyLike.setDelFlag(0);
            copyLike.setCreateBy("d7-it");
            copyLike.setCreateTime(new Date());
            // lifecycle / recruitDeadline 一个都不设 —— 与 copyTopicBasicInfo 同形
            m.insertCmsTopic(copyLike);

            CmsTopic back = m.selectCmsTopicById(copyLike.getId());
            assertEquals(Integer.valueOf(0), back.getLifecycle(), "没设 lifecycle 的副本必须落库默认 0(DRAFT)");
            assertEquals(null, back.getRecruitDeadline(), "没设 recruitDeadline 的副本必须落 NULL");
        }
    }

    // ---------- R2-B10:journey_rules_json 的 insert/update 落列 ----------

    /**
     * service 层 mock 单测只能证明「调了 updateCmsTopic 且对象上带着值」——
     * 值有没有进库,只有把行读回来才算数(mapper 漏列时接口照样不报错)。
     */
    @Test
    void insertCmsTopic_persistsJourneyRulesJson_andUpdateWithoutItKeepsValue() throws Exception {
        SqlSessionFactory f = connectedFactory();
        try (SqlSession s = f.openSession(true)) {
            applyTopicSchema(s.getConnection());
            CmsTopicMapper m = s.getMapper(CmsTopicMapper.class);
            com.fasterxml.jackson.databind.ObjectMapper json =
                    new com.fasterxml.jackson.databind.ObjectMapper();

            CmsTopic t = exploreDayTopic(at("2026-09-06 23:59:59"));
            t.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,\"modCap\":6}");
            m.insertCmsTopic(t);

            CmsTopic back = m.selectCmsTopicById(t.getId());
            assertNotNull(back.getJourneyRulesJson(),
                    "journey_rules_json 必须落库:insert 丢列 = 保存说成功,玩家侧永远拿不到规则");
            assertEquals(6, json.readTree(back.getJourneyRulesJson()).path("modCap").asInt());

            // 老编辑端整包保存不带 journeyRules ⇒ 动态 update 必须跳过这一列,原值不动
            CmsTopic patch = new CmsTopic();
            patch.setId(t.getId());
            patch.setName("老编辑端整包保存");
            m.updateCmsTopic(patch);

            String after = m.selectCmsTopicById(t.getId()).getJourneyRulesJson();
            assertNotNull(after, "不带 journeyRules 的更新把规则抹成 NULL = 老编辑端每保存一次静默清空");
            assertEquals(6, json.readTree(after).path("modCap").asInt());
        }
    }

    /** R8-STORY:journey_story_json 的 insert/update 落列(mock 单测看不见列有没有进 SQL)。 */
    @Test
    void insertCmsTopic_persistsJourneyStoryJson_andUpdateWithoutItKeepsValue() throws Exception {
        SqlSessionFactory f = connectedFactory();
        try (SqlSession s = f.openSession(true)) {
            applyTopicSchema(s.getConnection());
            CmsTopicMapper m = s.getMapper(CmsTopicMapper.class);
            com.fasterxml.jackson.databind.ObjectMapper json =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String story = "{\"schemaVersion\":1,\"stages\":[{\"chapterId\":11,\"end\":{}}]}";

            CmsTopic t = exploreDayTopic(at("2026-09-06 23:59:59"));
            t.setJourneyStoryJson(story);
            m.insertCmsTopic(t);

            CmsTopic back = m.selectCmsTopicById(t.getId());
            assertNotNull(back.getJourneyStoryJson(),
                    "journey_story_json 必须落库:insert 丢列 = 保存说成功,玩家侧永远拿不到剧情");
            assertEquals(11, json.readTree(back.getJourneyStoryJson()).path("stages").get(0)
                    .path("chapterId").asInt());

            // 老编辑端整包保存不带 journeyStory ⇒ 动态 update 必须跳过这一列,原值不动
            CmsTopic patch = new CmsTopic();
            patch.setId(t.getId());
            patch.setName("老编辑端整包保存");
            m.updateCmsTopic(patch);

            String after = m.selectCmsTopicById(t.getId()).getJourneyStoryJson();
            assertNotNull(after, "不带 journeyStory 的更新把剧情抹成 NULL = 老编辑端每保存一次静默清空");
            assertEquals(11, json.readTree(after).path("stages").get(0).path("chapterId").asInt());
        }
    }

    // ---------- D6:上下架 CAS 的 SQL 谓词(mock 单测看不见 where 子句) ----------

    /** CAS 命中:库中态 == expected ⇒ 翻转成功。 */
    @Test
    void casUserStatus_flipsWhenExpectationMatches() throws Exception {
        SqlSessionFactory f = connectedFactory();
        try (SqlSession s = f.openSession(true)) {
            applyTopicSchema(s.getConnection());
            CmsTopicMapper m = s.getMapper(CmsTopicMapper.class);
            CmsTopic t = exploreDayTopic(at("2026-09-06 23:59:59"));
            t.setUserStatus(1);
            m.insertCmsTopic(t);

            assertEquals(1, m.casUserStatus(t.getId(), 1, 0, new Date()), "期望态相符必须写成功");
            assertEquals(Integer.valueOf(0), m.selectCmsTopicById(t.getId()).getUserStatus());
        }
    }

    /**
     * ★ CAS 的全部价值在这一条:期望态与库中不符 ⇒ <b>0 行、值一动不动</b>。
     *
     * <p>这就是「内容安全回调已自动下架、运营那一屏还停在已上架」的竞态:
     * 无条件 update 会把违规主题重新推上架,CAS 让它写不进去。
     */
    @Test
    void casUserStatus_writesNothingWhenExpectationIsStale() throws Exception {
        SqlSessionFactory f = connectedFactory();
        try (SqlSession s = f.openSession(true)) {
            applyTopicSchema(s.getConnection());
            CmsTopicMapper m = s.getMapper(CmsTopicMapper.class);
            CmsTopic t = exploreDayTopic(at("2026-09-06 23:59:59"));
            t.setUserStatus(0);   // 已被内容安全回调下架
            m.insertCmsTopic(t);

            // 陈旧请求以为它还是「已上架」
            assertEquals(0, m.casUserStatus(t.getId(), 1, 1, new Date()), "期望态不符必须 0 行");
            assertEquals(Integer.valueOf(0), m.selectCmsTopicById(t.getId()).getUserStatus(),
                    "值必须一动不动 —— 被陈旧点击重新推上架就是内容安全事故");
        }
    }

    /**
     * 存量脏数据 user_status = NULL:DDL 没有 NOT NULL,直接 `user_status = #{expected}` 会
     * 恒不匹配(SQL 里 NULL = 0 为 UNKNOWN)⇒ 这类主题的上下架按钮会**静默失效**。
     * COALESCE 把它按「未上架」算,与 Java 侧 normalizeUserStatus 同口径。
     */
    @Test
    void casUserStatus_treatsNullUserStatusAsOffline() throws Exception {
        SqlSessionFactory f = connectedFactory();
        try (SqlSession s = f.openSession(true)) {
            applyTopicSchema(s.getConnection());
            CmsTopicMapper m = s.getMapper(CmsTopicMapper.class);
            CmsTopic t = exploreDayTopic(at("2026-09-06 23:59:59"));
            t.setStatus(1);
            m.insertCmsTopic(t);
            s.getConnection().createStatement()
                    .execute("UPDATE cms_topic SET user_status = NULL WHERE id = " + t.getId());

            assertEquals(1, m.casUserStatus(t.getId(), 0, 1, new Date()),
                    "NULL 必须能被 expected=0 匹配上,否则存量脏数据的上下架按钮永远点不动");
            assertEquals(Integer.valueOf(1), m.selectCmsTopicById(t.getId()).getUserStatus());
        }
    }

    /** 已软删的主题不许被上下架(与 transitionLifecycle 同口径带 del_flag = 0)。 */
    @Test
    void casUserStatus_refusesSoftDeletedTopic() throws Exception {
        SqlSessionFactory f = connectedFactory();
        try (SqlSession s = f.openSession(true)) {
            applyTopicSchema(s.getConnection());
            CmsTopicMapper m = s.getMapper(CmsTopicMapper.class);
            CmsTopic t = exploreDayTopic(at("2026-09-06 23:59:59"));
            t.setUserStatus(0);
            t.setDelFlag(2);
            m.insertCmsTopic(t);

            assertEquals(0, m.casUserStatus(t.getId(), 0, 1, new Date()), "软删主题不许被推上架");
        }
    }

    @Test
    void casOnlineRequiresPublishedNonDraftButPendingAuditMayBePublic() throws Exception {
        try (SqlSession s = connectedFactory().openSession(true)) {
            applyTopicSchema(s.getConnection());
            CmsTopicMapper mapper = s.getMapper(CmsTopicMapper.class);
            for (int[] state : new int[][] {{2, 0}, {0, 0}, {1, 1}, {1, 0}}) {
                CmsTopic topic = exploreDayTopic(at("2026-09-06 23:59:59"));
                topic.setStatus(state[0]);
                topic.setCheckStatus(0);
                mapper.insertCmsTopic(topic);
                s.getConnection().createStatement().execute("UPDATE cms_topic SET is_draft="
                        + state[1] + " WHERE id=" + topic.getId());
                boolean publicTopic = state[0] == 1 && state[1] == 0;
                assertEquals(publicTopic ? 1 : 0, mapper.casUserStatus(topic.getId(), 0, 1, new Date()));
                assertEquals(Integer.valueOf(publicTopic ? 1 : 0),
                        mapper.selectCmsTopicById(topic.getId()).getUserStatus());
                // 停售不受审核/草稿状态约束。
                CmsTopic offline = new CmsTopic();
                offline.setId(topic.getId());
                offline.setUserStatus(1);
                mapper.updateCmsTopic(offline);
                assertEquals(1, mapper.casUserStatus(topic.getId(), 1, 0, new Date()));
            }
        }
    }

    // ---------- 反向漏列:publish_mode 是 insert 有、update 没有 ----------

    /**
     * 「改了发布模式，保存后没变」—— 而且后果不是显示问题，是**先审后发被绕过**。
     *
     * <p>链路:小程序发布器每次保存都上送 {@code publishMode}(index.js:4737 恒送
     * {@code aiSimple ? 'ai_simple' : 'pro'}),service 也照收
     * ({@code CmsTopicServiceImpl:2710} 在 rebuildTopicContent 里 {@code patch.setPublishMode(...)}),
     * 但 {@code <update id="updateCmsTopic">} <b>没有这一列</b> ⇒ 库里的值纹丝不动。
     *
     * <p>而 {@code publishDraft}({@code CmsTopicServiceImpl:2289})读的是**库里那一列**:
     * simple ⇒ {@code status=1, userStatus=1} 免审直发；pro ⇒ {@code status=0} 进审核。
     * 于是「先用简易版存了草稿、后来改成专业版再发布」的主题，会被当成简易版
     * <b>免审直发上架</b> —— M0-6 先审后发这道内容闸被静默跳过。
     */
    @Test
    void updateCmsTopic_persistsPublishMode_soReviewGateIsNotBypassed() throws Exception {
        SqlSessionFactory f = connectedFactory();
        try (SqlSession s = f.openSession(true)) {
            applyTopicSchema(s.getConnection());
            CmsTopicMapper m = s.getMapper(CmsTopicMapper.class);
            CmsTopic t = exploreDayTopic(at("2026-09-06 23:59:59"));
            t.setPublishMode("ai_simple");
            m.insertCmsTopic(t);
            assertEquals("ai_simple", m.selectCmsTopicById(t.getId()).getPublishMode(), "前置:建题时确实落了简易版");

            CmsTopic patch = new CmsTopic();
            patch.setId(t.getId());
            patch.setPublishMode("pro");       // 用户在编辑器里切到专业版并保存
            // 真实 update 路径同时还会写一堆别的字段;这里带上 name 是为了让断言精确落在
            // publish_mode 上 —— 只带 publish_mode 会让 <trim prefix="SET"> 整个空掉、
            // SQL 直接语法错,那是钝红(只证明"SQL 坏了"),证不出"这一列没被更新"。
            patch.setName("改成专业版");
            m.updateCmsTopic(patch);

            assertEquals("改成专业版", m.selectCmsTopicById(t.getId()).getName(),
                    "前置:这次 update 确实生效了(否则下面那条断言测的是别的东西)");

            assertEquals("pro", m.selectCmsTopicById(t.getId()).getPublishMode(),
                    "发布模式必须能改:库里仍是 ai_simple 的话,publishDraft 会把这个本应进审核的主题免审直发上架");
        }
    }
}

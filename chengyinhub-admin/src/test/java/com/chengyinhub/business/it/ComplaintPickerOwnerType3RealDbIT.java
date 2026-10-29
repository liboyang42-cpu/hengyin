package com.chengyinhub.business.it;

import com.chengyinhub.web.controller.api.RegistrationQueryTestFixtures;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopComplaint;
import com.chengyinhub.business.domain.vo.ComplainableTopicVO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopComplaintService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.web.controller.api.ApiCoopController;
import com.chengyinhub.web.controller.api.ApiRegistrationController;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 「投诉与建议」的可投诉对象:选择源与受理口必须给同一个答案。跑在真库真 SQL 上。
 *
 * <p><b>来历</b>:开工计划「四、待验证」第 1 项 —— complaint 页用 {@code owner_type:3} 拉可投诉对象,
 * 该值语义未实测,「修了后端玩家仍可能选不到对象」。2026-08-18 真库实测的结论:
 * {@code owner_type=3} 的语义**本身是对的**(= 全部类型,见 test ①,该条已从待验证划掉),
 * 但选择源在它下游被两件事砍空:
 * <ol>
 *   <li>末端过了 {@code ApiRegistrationController.filterEndedOverWeek}(HIDE_AFTER_END_DAYS=7)
 *       ⇒ 结束超过 7 天的主题选不到,而受理口没有任何时间窗;</li>
 *   <li>活动单({@code owner_type=2})既不挂 {@code cmsTopic}、{@code registration.topic_id} 又恒 null
 *       ⇒ ③ 自由探索玩家(买活动票、活动 topic_id 指向主题)整条选不到。</li>
 * </ol>
 *
 * <p><b>修法</b>(2026-08-18):选择源改成 {@code /api/coop/complaint/topics},
 * 与受理口 {@code /api/coop/complaint/report} 共读
 * {@code ICmsRegistrationService.selectComplainableTopics} 这一份判据;
 * 主题由服务端按 {@code (ownerType, ownerId)} 反查,反查不出就 fail-closed 不给选。
 * <b>当时没有动 filterEndedOverWeek 本身。</b>2026-09-16 截图冒烟又发现它把
 * {@code myRegistrationList}(订单账本)砍成空列表 ⇒ 现只保留在 {@code myJoined}(进行中 Tab),
 * 见 test ⑥。
 *
 * <p><b>为什么必须真库</b>:{@code selectCmsRegistrationList} 的 where 是动态 SQL、
 * 活动→主题的溯源是真表 join,mock 掉就等于把要验的东西换成了我自己的假设。
 *
 * <p><b>怎么跑</b>:与其余 MyBatis 真库 IT 同一套约定 —— 连 {@code chengyin_mybatis_it}
 * ({@code ituser/itpass}),表由 {@code ItSchema.apply} 从
 * {@code it-schema/complaint-it-schema.sql}(生产 DDL 快照)先 DROP 再 CREATE,跑完即弃。
 * 它已列进 {@code ci/backend-test.sh} 的 {@code MYBATIS_ITS} 被点名跑,
 * 由 {@code ci/assert_it_ran.py} 回读 skipped=0 坐实「真跑了」。
 * <b>刻意不设 assumeTrue / EnabledIfEnvironmentVariable</b>:库连不上就该红,
 * skip 算绿等于这套白跑(2026-08-14 血案:9 套从来没被执行过,其中 6 条真跑起来是红的)。
 *
 * <p>本机跑法(端口/账号可用环境变量覆盖):
 * <pre>
 *   mvn -pl chengyinhub-admin -am test -Dtest=ComplaintPickerOwnerType3RealDbIT
 * </pre>
 */
class ComplaintPickerOwnerType3RealDbIT {

    private static final long MEMBER = 900001L;
    private static final long TOPIC_RECENT = 900101L;       // 2 天前结束
    private static final long TOPIC_OLD = 900102L;          // 30 天前结束
    private static final long TOPIC_VIA_ACTIVITY = 900103L; // ③ 自由探索主题,靠 cms_activity.topic_id 挂过来
    private static final long ACTIVITY_STANDALONE = 900201L; // 独立活动:topic_id 为空 = 平台自办
    private static final long ACTIVITY_OF_TOPIC = 900202L;   // 指向 TOPIC_VIA_ACTIVITY 的活动

    // 默认值与其余 MyBatis 真库 IT 完全一致(CI 的 backend job 起的那个空 MySQL8);
    // 本机换端口/账号时用环境变量覆盖,不改代码。
    // ⚠️ 时区参数与家族其余类不同(这里是 Asia/Shanghai,别处是 GMT+8),本轮只改库与账号,不动它 ——
    // 时区会改变 DATETIME 的解释,属于断言语义,不在隔离这一轮的范围内。
    private static String url() {
        String host = System.getenv().getOrDefault("CHENGYIN_MYBATIS_IT_HOST", MybatisItDatabase.host());
        String port = System.getenv().getOrDefault("CHENGYIN_MYBATIS_IT_PORT", MybatisItDatabase.port());
        // 一类一库:先让 MybatisItDatabase 把库建出来(首次调用即 DROP+CREATE),再按本类的时区参数拼 URL。
        String db = MybatisItDatabase.databaseNameAfterCreate("complaint_picker");
        return "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CmsRegistration", CmsRegistration.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsActivity", CmsActivity.class);
        PooledDataSource ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", url(),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("complaint-it", new JdbcTransactionFactory(), ds));
        for (String r : new String[]{
                "mapper/business/CmsRegistrationMapper.xml",
                "mapper/business/CmsTopicMapper.xml",
                "mapper/business/CmsActivityMapper.xml"}) {
            try (InputStream in = Resources.getResourceAsStream(r)) {
                new XMLMapperBuilder(in, cfg, r, cfg.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    /** 真库造最小可复现数据:一个玩家 + 三个主题 + 两个活动 + 五张已支付报名单。 */
    private void seed(SqlSession s) throws Exception {
        // 表由测试自己决定形状:先 DROP 再按生产 DDL 建,绝不沿用库里恰好剩下的旧表。
        ItSchema.apply(s.getConnection(), "it-schema/complaint-it-schema.sql");
        ItSchema.applyMigrations(s.getConnection(), "sql/migration_20260930_registration_first_successful_scan.sql");
        try (Statement st = s.getConnection().createStatement()) {
            st.execute("delete from cms_registration where member_id = " + MEMBER);
            st.execute("delete from cms_topic where id in ("
                    + TOPIC_RECENT + "," + TOPIC_OLD + "," + TOPIC_VIA_ACTIVITY + ")");
            st.execute("delete from cms_activity where id in ("
                    + ACTIVITY_STANDALONE + "," + ACTIVITY_OF_TOPIC + ")");
            st.execute("insert into cms_topic (id,name,end_date) values ("
                    + TOPIC_RECENT + ",'刚结束2天的主题', DATE_SUB(NOW(), INTERVAL 2 DAY))");
            st.execute("insert into cms_topic (id,name,end_date) values ("
                    + TOPIC_OLD + ",'结束30天的主题', DATE_SUB(NOW(), INTERVAL 30 DAY))");
            st.execute("insert into cms_topic (id,name,end_date) values ("
                    + TOPIC_VIA_ACTIVITY + ",'③自由探索主题', DATE_SUB(NOW(), INTERVAL 2 DAY))");
            // 独立活动:topic_id 为空 = 平台自办,没有可投诉的主办方 ⇒ fail-closed 不给选
            st.execute("insert into cms_activity (id,name,end_date) values ("
                    + ACTIVITY_STANDALONE + ",'独立活动', DATE_SUB(NOW(), INTERVAL 2 DAY))");
            // ③ 自由探索:玩家买的是**活动票**,活动 topic_id 指向主题,
            // ApiPlayProgressController:204 顺着它把玩家送进该主题去玩 ⇒ 他确实参与了这个主题。
            st.execute("insert into cms_activity (id,name,topic_id,end_date) values ("
                    + ACTIVITY_OF_TOPIC + ",'③活动票'," + TOPIC_VIA_ACTIVITY
                    + ", DATE_SUB(NOW(), INTERVAL 2 DAY))");
            st.execute(reg(900301L, "T900301", 1, TOPIC_RECENT, String.valueOf(TOPIC_RECENT)));
            st.execute(reg(900302L, "T900302", 1, TOPIC_OLD, String.valueOf(TOPIC_OLD)));
            st.execute(reg(900303L, "A900303", 2, ACTIVITY_STANDALONE, "NULL"));
            st.execute(reg(900304L, "T900304", 1, TOPIC_RECENT, "NULL"));
            st.execute(reg(900305L, "A900305", 2, ACTIVITY_OF_TOPIC, "NULL"));
        }
    }

    private static String reg(long id, String no, int ownerType, long ownerId, String topicId) {
        return "insert into cms_registration (id,registration_no,owner_type,owner_id,topic_id,ticket_id,"
                + "ticket_price,member_id,original_amount,total_amount,payable_amount,pay_expire_time,"
                + "payment_status,registration_status) values ("
                + id + ",'" + no + "'," + ownerType + "," + ownerId + "," + topicId + ",0,100.00,"
                + MEMBER + ",100.00,100.00,100.00,NOW(),2,2)";
    }

    /** 真 service 实现 + 真 mapper:只有依赖装配是手工的,SQL 与判据都是产品码本体。 */
    private ICmsRegistrationService realRegistrationService(SqlSession s) {
        CmsRegistrationServiceImpl impl = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(impl, "cmsRegistrationMapper", s.getMapper(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(impl, "cmsTopicMapper", s.getMapper(CmsTopicMapper.class));
        ReflectionTestUtils.setField(impl, "cmsActivityMapper", s.getMapper(CmsActivityMapper.class));
        return impl;
    }

    /** 投诉页的选择源 + 受理口,都挂在真 service 上。 */
    private ApiCoopController coopController(SqlSession s, ICoopComplaintService complaintService) {
        ApiCoopController c = spy(new ApiCoopController());
        ReflectionTestUtils.setField(c, "cmsRegistrationService", realRegistrationService(s));
        ReflectionTestUtils.setField(c, "coopComplaintService", complaintService);
        doReturn(MEMBER).when(c).getAppUserId();
        return c;
    }

    private static ICoopComplaintService emptyComplaintService() {
        ICoopComplaintService svc = mock(ICoopComplaintService.class);
        when(svc.listByTopic(any())).thenReturn(new ArrayList<>());
        when(svc.create(any())).thenReturn(1L);
        return svc;
    }

    @SuppressWarnings("unchecked")
    private static List<ComplainableTopicVO> picker(AjaxResult res) {
        assertEquals(200, res.get(AjaxResult.CODE_TAG), "选择源应返回 200:" + res.get(AjaxResult.MSG_TAG));
        return (List<ComplainableTopicVO>) res.get(AjaxResult.DATA_TAG);
    }

    private static List<Long> topicIds(List<ComplainableTopicVO> rows) {
        return rows.stream().map(ComplainableTopicVO::getTopicId).collect(Collectors.toList());
    }

    // ---------- ① owner_type=3 的语义本身(待验证项的原问题,已实测通过,留着防回归) ----------

    @SuppressWarnings("unchecked")
    @Test
    void ownerType3_returnsAllOwnerTypes_andAttachesTopic() throws Exception {
        try (SqlSession s = factory().openSession(true)) {
            seed(s);
            AjaxResult res = RegistrationQueryTestFixtures.wire(listController(s)).myRegistrationList("3", "0", null);
            assertEquals(200, res.get(AjaxResult.CODE_TAG));
            List<CmsRegistration> rows =
                    (List<CmsRegistration>) ((TableDataInfo) res.get(AjaxResult.DATA_TAG)).getRows();
            assertTrue(rows.stream().anyMatch(r -> r.getOwnerType() == 1L), "owner_type=3 应含主题单");
            assertTrue(rows.stream().anyMatch(r -> r.getOwnerType() == 2L), "owner_type=3 应含活动单");
            CmsRegistration topicReg = rows.stream()
                    .filter(r -> r.getId() == 900301L).findFirst().orElse(null);
            assertNotNull(topicReg, "刚结束 2 天的主题单应在列表里");
            assertNotNull(topicReg.getCmsTopic(), "主题单应挂上 cmsTopic");
        }
    }

    // ---------- ② 缺陷一:结束 >7 天的主题必须选得到 ----------

    @Test
    void endedOverSevenDaysTopic_isSelectable() throws Exception {
        try (SqlSession s = factory().openSession(true)) {
            seed(s);
            List<Long> ids = topicIds(picker(coopController(s, emptyComplaintService()).complaintTopics()));
            assertTrue(ids.contains(TOPIC_OLD),
                    "结束 30 天的主题必须能被投诉(受理口本来就没有时间窗;投诉是事后行为)。实得=" + ids);
        }
    }

    // ---------- ③ 缺陷二:③ 自由探索玩家必须选得到他真正玩的主题 ----------

    @Test
    void exploreParticipantViaActivityTicket_canSelectTheTopic() throws Exception {
        try (SqlSession s = factory().openSession(true)) {
            seed(s);
            List<Long> ids = topicIds(picker(coopController(s, emptyComplaintService()).complaintTopics()));
            assertTrue(ids.contains(TOPIC_VIA_ACTIVITY),
                    "买活动票的 ③ 自由探索玩家必须能选到他真正参与的主题(服务端顺 cms_activity.topic_id 反查)。实得=" + ids);
        }
    }

    // ---------- ④ 选择源与受理口必须给同一个答案(防两边再漂开) ----------

    @Test
    void everyPickerOptionIsAcceptedByTheGate() throws Exception {
        try (SqlSession s = factory().openSession(true)) {
            seed(s);
            ApiCoopController c = coopController(s, emptyComplaintService());
            List<ComplainableTopicVO> options = picker(c.complaintTopics());
            assertTrue(options.size() >= 3, "至少应有三个可投诉主题,实得 " + topicIds(options));
            for (ComplainableTopicVO o : options) {
                CoopComplaint body = new CoopComplaint();
                body.setTopicId(o.getTopicId());
                body.setReason("真库 IT:选择源给的每一项,受理口都必须收");
                AjaxResult res = c.complaintReport(body);
                assertEquals(200, res.get(AjaxResult.CODE_TAG),
                        "选择源给了主题 " + o.getTopicId() + ",受理口却拒了:" + res.get(AjaxResult.MSG_TAG));
            }
        }
    }

    // ---------- ⑤ fail-closed:反查不出主题的单不给选,也不给投 ----------

    @Test
    void standaloneActivityWithoutTopic_isNeitherSelectableNorAcceptable() throws Exception {
        try (SqlSession s = factory().openSession(true)) {
            seed(s);
            ApiCoopController c = coopController(s, emptyComplaintService());
            List<Long> ids = topicIds(picker(c.complaintTopics()));
            assertFalse(ids.contains(ACTIVITY_STANDALONE),
                    "独立活动(topic_id 为空 = 平台自办)不该被当成可投诉主题,更不该把 activityId 当 topicId 兜底。实得=" + ids);

            // 非参与者/瞎猜的 topicId 一律拒 —— 投诉一旦受理即冻结该主题结算放款,这里是资损防线。
            CoopComplaint body = new CoopComplaint();
            body.setTopicId(ACTIVITY_STANDALONE);
            body.setReason("负控:不该被受理");
            AjaxResult res = c.complaintReport(body);
            assertEquals(500, res.get(AjaxResult.CODE_TAG), "受理口必须拒绝反查不出主题的目标");
        }
    }

    // ---------- ⑥ 订单账本与「我参与中」的分工(2026-09-16 改口径) ----------

    /**
     * 2026-09-16 截图冒烟:真实商家号有已支付订单,「我的订单」(myRegistrationList)却整屏空 ——
     * 根因是该接口末端套了只该服务「进行中」视觉收纳的 filterEndedOverWeek。
     * 现口径:订单账本保留全部单(客户端「已完成/已退款」Tab 靠它);
     * 7 天收纳规则只留在 myJoined(my-joined 的单元 wiring 在 ApiRegistrationHideEndedTest 里钉住)。
     */
    @SuppressWarnings("unchecked")
    @Test
    void orderLedgerKeepsEndedOverSevenDaysRegistration() throws Exception {
        try (SqlSession s = factory().openSession(true)) {
            seed(s);
            AjaxResult res = RegistrationQueryTestFixtures.wire(listController(s)).myRegistrationList("3", "0", null);
            List<CmsRegistration> rows =
                    (List<CmsRegistration>) ((TableDataInfo) res.get(AjaxResult.DATA_TAG)).getRows();
            assertTrue(rows.stream().anyMatch(r -> r.getId() == 900302L),
                    "结束 30 天的已支付单必须留在「我的订单」账本里,否则「已完成/已退款」Tab 恒空。"
                            + "实得 id=" + rows.stream().map(CmsRegistration::getId).collect(Collectors.toList()));
        }
    }

    /** 「我参与的」列表那条老链路,只在 ① / ⑥ 用 —— 投诉选择源已经不走它了。 */
    private ApiRegistrationController listController(SqlSession s) {
        CmsTopicMapper topicMapper = s.getMapper(CmsTopicMapper.class);
        CmsActivityMapper activityMapper = s.getMapper(CmsActivityMapper.class);
        CmsRegistrationMapper regMapper = s.getMapper(CmsRegistrationMapper.class);

        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        when(registrationService.selectCmsRegistrationList(any()))
                .thenAnswer(inv -> regMapper.selectCmsRegistrationList(inv.getArgument(0)));
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicById(any()))
                .thenAnswer(inv -> topicMapper.selectCmsTopicById(inv.getArgument(0)));
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        when(activityService.selectCmsActivityById(any()))
                .thenAnswer(inv -> activityMapper.selectCmsActivityById(inv.getArgument(0)));
        IRefundService refundService = mock(IRefundService.class);
        when(refundService.selectRefundList(any())).thenReturn(new ArrayList<>());

        ApiRegistrationController c = spy(new ApiRegistrationController());
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(c), "registrationService", registrationService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(c), "topicService", topicService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(c), "activityService", activityService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(c), "refundService", refundService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(c), "omsTicketService", mock(IOmsTicketService.class));
        doReturn(MEMBER).when(c).getAppUserId();
        return c;
    }
}

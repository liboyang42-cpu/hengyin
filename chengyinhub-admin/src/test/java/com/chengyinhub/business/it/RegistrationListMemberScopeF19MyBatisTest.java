package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.web.controller.api.ApiRegistrationController;
import com.chengyinhub.web.service.registration.RegistrationQueryApplication;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * F-19(backend-app-r3 Wave 5 之后报告):会员 9004 在 cms_registration 有 14 行,
 * 但 {@code POST /api/registration/list} 回 total 0、{@code /api/registration/my-joined} 回 []。
 *
 * <p>排查结论(见 qa/REGLIST-R3.md):e8c893b73..HEAD 没有改动这两条读链路(controller → RegistrationQueryApplication
 * → CmsRegistrationServiceImpl → CmsRegistrationMapper.xml selectCmsRegistrationList),会员只由 token 的
 * {@code getAppUserId()} 决定。本测试用真实 mapper XML(H2 MySQL 模式)+ 真实 controller/查询应用,钉住:
 * <ol>
 *   <li>9004 的 token 读到自己全部 14 行(list total 14),my-joined 读到自己 registration_status=2 的行;</li>
 *   <li>别人(9009)的行永不出现(归属收口不放松);</li>
 *   <li>同一库里,换成另一会员的 token(如 {@code e2e-code-*} 登录新建的 990008)就是 total 0 / [] ——
 *       即 F-19 的现象只能来自「调用者身份不是 9004」或「部署的 jar/库不是本分支」,不是查询本身。</li>
 * </ol>
 * 任何人给这两条链路加 owner/tenant/status 过滤、或把 memberId 换成别的字段来源,导致 9004 读不到自己的单,本测试即红。
 */
class RegistrationListMemberScopeF19MyBatisTest {

    private static final long MEMBER = 9004L;
    private static final long OTHER = 9009L;
    private static final long FRESH_E2E_LOGIN = 990008L;

    private SqlSession session;
    private CmsRegistrationServiceImpl registrationService;

    @BeforeEach
    void setUp() throws Exception {
        Configuration config = new Configuration();
        config.getTypeAliasRegistry().registerAlias("CmsRegistration", CmsRegistration.class);
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(),
                new PooledDataSource("org.h2.Driver", "jdbc:h2:mem:f19_registration_list;MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa", "")));
        String resource = "mapper/business/CmsRegistrationMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(config).openSession(true);
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("drop table if exists cms_registration");
            statement.execute("create table cms_registration (id bigint primary key, registration_no varchar(64), "
                    + "parent_order_no varchar(64), request_id varchar(64), request_fingerprint varchar(64), "
                    + "owner_type bigint, owner_id bigint, activity_title varchar(128), ticket_id bigint, "
                    + "ticket_name varchar(128), ticket_price decimal(10,2), member_id bigint, real_name varchar(128), "
                    + "phone varchar(128), phone_hash varchar(128), email varchar(128), address varchar(255), "
                    + "address_id bigint, participate_date varchar(32), order_num int, original_amount decimal(10,2), "
                    + "discount_amount decimal(10,2), total_amount decimal(10,2), point_used bigint, "
                    + "point_deduct_amount decimal(10,2), point_deduct_rate decimal(10,4), max_point_deduct bigint, "
                    + "payable_amount decimal(10,2), payment_status int, payment_type int, payment_time timestamp, "
                    + "transaction_id varchar(64), point_payment_amount decimal(10,2), "
                    + "wechat_payment_amount decimal(10,2), fee_platform_rate decimal(10,4), registration_status int, "
                    + "pay_expire_time timestamp, topic_id bigint, purchase_kind int, refund_policy_code varchar(32), "
                    + "refund_policy_version int, refund_deadline timestamp, expires_at timestamp, "
                    + "create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp, "
                    + "verification_code varchar(64), qrcode_url varchar(255), verification_status int, "
                    + "verification_time timestamp, first_successful_scan_at timestamp, verifier_id bigint, "
                    + "points_returned int)");
            // 9004:14 行 = 主题 4 行 + 活动 6 行已报名(registration_status=2),2 行待支付(1),2 行已取消/退款(3)。
            long id = 1;
            for (int i = 0; i < 4; i++) insert(statement, id++, MEMBER, 1, 2, 2);
            for (int i = 0; i < 6; i++) insert(statement, id++, MEMBER, 2, 2, 2);
            for (int i = 0; i < 2; i++) insert(statement, id++, MEMBER, 2, 1, 0);
            for (int i = 0; i < 2; i++) insert(statement, id++, MEMBER, 2, 3, 4);
            // 别的会员:不得出现在 9004 的结果里。
            for (int i = 0; i < 3; i++) insert(statement, id++, OTHER, 2, 2, 2);
            insert(statement, id++, OTHER, 1, 2, 2);
        }
        registrationService = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(registrationService, "cmsRegistrationMapper",
                session.getMapper(CmsRegistrationMapper.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("drop table if exists cms_registration");
        }
        session.close();
    }

    private static void insert(Statement statement, long id, long memberId, int ownerType,
                               int registrationStatus, int paymentStatus) throws Exception {
        statement.execute("insert into cms_registration (id, registration_no, owner_type, owner_id, member_id, "
                + "registration_status, payment_status, verification_status, payable_amount, create_by, create_time) "
                + "values (" + id + ", 'F19-" + id + "', " + ownerType + ", " + (990000 + ownerType) + ", "
                + memberId + ", " + registrationStatus + ", " + paymentStatus + ", 0, 0, '" + memberId + "', "
                + "timestamp '2026-11-15 10:00:00' + " + id + " * interval '1' minute)");
    }

    private ApiRegistrationController controllerAs(long memberId) {
        ApiRegistrationController controller = spy(new ApiRegistrationController());
        RegistrationQueryApplication query = new RegistrationQueryApplication();
        ReflectionTestUtils.setField(controller, "registrationQueryApplication", query);
        IRefundService refundService = mock(IRefundService.class);
        when(refundService.selectRefundList(any())).thenReturn(new ArrayList<>());
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        when(ticketService.selectOmsTicketList(any())).thenReturn(new ArrayList<>());
        ReflectionTestUtils.setField(query, "registrationService", registrationService);
        ReflectionTestUtils.setField(query, "topicService", mock(ICmsTopicService.class));
        ReflectionTestUtils.setField(query, "activityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(query, "refundService", refundService);
        ReflectionTestUtils.setField(query, "omsTicketService", ticketService);
        doReturn(memberId).when(controller).getAppUserId();
        return controller;
    }

    private static TableDataInfo table(AjaxResult result) {
        assertEquals(200, result.get(AjaxResult.CODE_TAG), String.valueOf(result.get(AjaxResult.MSG_TAG)));
        return (TableDataInfo) result.get(AjaxResult.DATA_TAG);
    }

    @SuppressWarnings("unchecked")
    private static List<CmsRegistration> rows(AjaxResult result) {
        assertEquals(200, result.get(AjaxResult.CODE_TAG), String.valueOf(result.get(AjaxResult.MSG_TAG)));
        return (List<CmsRegistration>) result.get(AjaxResult.DATA_TAG);
    }

    private static void assertAllOwnedBy(long memberId, List<?> rows) {
        for (Object row : rows) {
            assertEquals(Long.valueOf(memberId), ((CmsRegistration) row).getMemberId(), "只能看到本人的报名");
        }
    }

    @Test
    void f19_listAllOrders_returnsAllFourteenOfTheMembersOwnRows() {
        TableDataInfo all = table(controllerAs(MEMBER).myRegistrationList("3", null, null));
        assertEquals(14L, all.getTotal(), "9004 的 token 必须读到自己的 14 行(F-19 报的是 total 0)");
        assertEquals(14, all.getRows().size());
        assertAllOwnedBy(MEMBER, all.getRows());
    }

    @Test
    void f19_listByType_andStatusTabs_stayScopedToTheMember() {
        ApiRegistrationController controller = controllerAs(MEMBER);
        TableDataInfo topics = table(controller.myRegistrationList("1", null, null));
        assertEquals(4L, topics.getTotal());
        assertAllOwnedBy(MEMBER, topics.getRows());
        TableDataInfo wallet = table(controller.myRegistrationList("2", null, null));
        assertEquals(6L, wallet.getTotal(), "活动票夹只列已报名(registration_status=2)的本人订单");
        assertAllOwnedBy(MEMBER, wallet.getRows());
        assertEquals(2L, table(controller.myRegistrationList("3", "1", null)).getTotal(), "待支付 Tab");
        assertEquals(2L, table(controller.myRegistrationList("3", "4", null)).getTotal(), "已取消 Tab");
    }

    @Test
    void f19_myJoined_returnsTheMembersSignedUpRows() {
        List<CmsRegistration> joined = rows(controllerAs(MEMBER).myJoined());
        assertEquals(10, joined.size(), "my-joined = 本人 registration_status=2 的主题 4 + 活动 6(F-19 报的是 [])");
        assertAllOwnedBy(MEMBER, joined);
    }

    @Test
    void f19_anotherIdentity_seesNothingOfThe9004Rows() {
        // 同一库:token 若属于别的会员(例如 e2e-code-* 登录新建的会员),就是 F-19 的 total 0 / []。
        assertEquals(0L, table(controllerAs(FRESH_E2E_LOGIN).myRegistrationList("3", null, null)).getTotal());
        assertTrue(rows(controllerAs(FRESH_E2E_LOGIN).myJoined()).isEmpty());
        TableDataInfo other = table(controllerAs(OTHER).myRegistrationList("3", null, null));
        assertEquals(4L, other.getTotal());
        assertAllOwnedBy(OTHER, other.getRows());
    }
}
